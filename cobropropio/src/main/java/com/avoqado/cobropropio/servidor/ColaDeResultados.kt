package com.avoqado.cobropropio.servidor

import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.EstadoDelReporte
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.libreta.ResultadoParaReportar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lo que pasó con una entrega. `acuse` = la respuesta del SERVIDOR, o null si la fila no tenía nada que reportar (y entonces
 * no se llamó al SERVIDOR). `fila` = la fila tal como quedó (la del acuse aplicado, o la de ahora si el CAS no aplicó); null si
 * el id no existe.
 */
internal data class ResultadoDeEntrega(val acuse: AcuseDelResultado?, val fila: IntentoEntity?)

/**
 * La cola durable de `/resultado`: la obligación vive en la libreta (`reporte = PENDIENTE`), y cada acuse se aplica con el CAS
 * de [Libreta.aplicarAcuse] en una transacción por id (r3 R3-09). La cola no tiene reloj: `reversadoEn` sale de la fila.
 * La app sólo la construye para dársela al motor; entregar es del motor (`MotorDeCobro.recuperar`), r2 familia A.
 */
internal class ColaDeResultados(
    private val libreta: Libreta,
    private val servidor: ReversoDelServidor,
    /** Donde corren las llamadas de la libreta. Sólo las pruebas lo cambian, para que el tiempo virtual las espere. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Un intento de entrega de ESE id (r4 R4-13). */
    internal suspend fun entregar(id: AttemptId): ResultadoDeEntrega {
        val f = enLaLibreta { libreta.buscar(id) } ?: return ResultadoDeEntrega(null, null)
        val acusado = f.resultadoParaReportar
        val r = ResultadoDelReverso.deLaFila(f)
        if (f.reporte != EstadoDelReporte.PENDIENTE || acusado == null || r == null) return ResultadoDeEntrega(null, f)

        val acuse = servidor.reportarResultado(f.venueId, id, r)
        val aplicada = enLaLibreta {
            fun aplicar(enServidor: EstadoDelReversoEnServidor?, reporte: EstadoDelReporte, estado: EstadoDelIntento? = null) =
                libreta.aplicarAcuse(id, acusado, enServidor, reporte, estado)
            when (acuse) {
                is AcuseDelResultado.Guardado -> when (acuse.estado) {
                    EstadoDelReversoEnServidor.REVERSED -> aplicar(acuse.estado, EstadoDelReporte.ENTREGADO, EstadoDelIntento.NO_SE_COBRO)
                    EstadoDelReversoEnServidor.REVERSE_FAILED -> aplicar(acuse.estado, EstadoDelReporte.ENTREGADO)
                    EstadoDelReversoEnServidor.CONTRADICTION -> aplicar(acuse.estado, EstadoDelReporte.ENTREGADO, EstadoDelIntento.NO_SE)
                    // Sin desenlace: es el acuse del PUDO_ENTRAR. Para un CONFIRMADO o un RECHAZADO no cuadra ⇒ lo ve una persona.
                    EstadoDelReversoEnServidor.REVERSING, EstadoDelReversoEnServidor.REVERSE_UNCONFIRMED -> aplicar(
                        acuse.estado,
                        if (acusado == ResultadoParaReportar.PUDO_ENTRAR) EstadoDelReporte.ENTREGADO else EstadoDelReporte.CONCILIAR,
                    )
                }
                is AcuseDelResultado.Conflicto -> aplicar(acuse.estadoGuardado, EstadoDelReporte.ENTREGADO, EstadoDelIntento.NO_SE)
                // r5/r6 R4-08: con vínculo efectivo un 404/400/401/403 puede ser sesión o sucursal cambiada ⇒ CONCILIAR, nunca FINAL.
                AcuseDelResultado.NoEncontrado, is AcuseDelResultado.Final ->
                    aplicar(null, if (vinculoEfectivo(f)) EstadoDelReporte.CONCILIAR else EstadoDelReporte.FINAL_SIN_ENTREGAR)
                AcuseDelResultado.Reintentar -> null
            } ?: libreta.buscar(id)
        }
        return ResultadoDeEntrega(acuse, aplicada)
    }

    /** Una vuelta por todas las obligaciones pendientes, en orden (r3 R3-09). */
    internal suspend fun entregarPendientes(): Map<AttemptId, AcuseDelResultado> {
        val ids = enLaLibreta { libreta.pendientesDeReporte() }.map { AttemptId(it.attemptId) }
        return buildMap {
            for (id in ids) {
                // I3: una fila no detiene a las demás; la que lanzó se queda PENDIENTE para la siguiente vuelta.
                val acuse = try {
                    entregar(id).acuse
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                acuse?.let { put(id, it) }
            }
        }
    }

    // V = el origen congelado al anotar. `vinculoAcreditadoPorPermiso` ya no tiene escritor (bloque S); una fila vieja puede traerlo en true.
    private fun vinculoEfectivo(f: IntentoEntity) = f.origen == Origen.VINCULADO_AL_POS || f.vinculoAcreditadoPorPermiso

    /** Las llamadas de la libreta bloquean: nunca en el hilo de quien llama. */
    private suspend fun <T> enLaLibreta(bloque: () -> T): T = withContext(io) { bloque() }
}
