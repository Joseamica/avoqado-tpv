package com.avoqado.cobropropio.servidor

import com.avoqado.cobropropio.blumon.Clasificador
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.ResultadoParaReportar
import kotlin.time.Duration

/** La respuesta del SERVIDOR a `…/reverso/resultado` (CON §2.2). */
internal sealed interface AcuseDelResultado {
    data class Guardado(val estado: EstadoDelReversoEnServidor, val eventLogId: String, val paymentId: String?) : AcuseDelResultado {
        override fun toString() = "Guardado(estado=$estado)"
    }

    /** 409 `REVERSAL_RESULT_CONFLICT`: el SERVIDOR ya tiene otro desenlace. */
    data class Conflicto(val estadoGuardado: EstadoDelReversoEnServidor) : AcuseDelResultado

    /** 404, con o sin `code`. */
    data object NoEncontrado : AcuseDelResultado

    /** 400, 403, 409 `ATTEMPT_NOT_BLUMON` y cualquier otro 4xx definitivo: no se reintenta. */
    data class Final(val http: Int, val code: String?) : AcuseDelResultado {
        override fun toString() = "Final(http=$http)"
    }

    /** 401 (token vencido, ya renovado), sin JWT, 503, 5xx, 408, 429, sin red, o un 200 sin forma: la cola lo vuelve a mandar. */
    data object Reintentar : AcuseDelResultado
}

/** El cuerpo de `/resultado`. `reversadoEn` es la hora OBSERVADA guardada (ISO 8601 con zona), nunca la hora de la cola. */
internal data class ResultadoDelReverso(
    val descripcion: String?,
    val codigo: String?,
    val operationNumber: Long?,
    val reversadoEn: String,
) {
    companion object {
        /**
         * Lo que la fila tiene que reportar: `CONFIRMADO` ⇒ «REVERSADA»; `RECHAZADO` ⇒ su código; `PUDO_ENTRAR` ⇒
         * `SIN_RESPUESTA`, que el SERVIDOR clasifica no concluyente y deja la lápida `reversing` (r3 R3-08). Un `RECHAZADO` sin
         * código también va como `SIN_RESPUESTA`: nunca se afirma un rechazo sin su código. null si no hay nada que reportar.
         */
        fun deLaFila(f: IntentoEntity): ResultadoDelReverso? {
            val hora = f.horaObservadaIso ?: return null
            return when (f.resultadoParaReportar ?: return null) {
                ResultadoParaReportar.CONFIRMADO -> ResultadoDelReverso(REVERSADA, null, null, hora)
                ResultadoParaReportar.RECHAZADO -> ResultadoDelReverso(null, f.codigoParaReportar ?: Clasificador.SIN_RESPUESTA, null, hora)
                ResultadoParaReportar.PUDO_ENTRAR -> ResultadoDelReverso(null, Clasificador.SIN_RESPUESTA, null, hora)
            }
        }

        private const val REVERSADA = "REVERSADA"
    }
}

/**
 * El JWT de ESTA terminal para el SERVIDOR (v5 §2.5). Lo implementa la app en la fase 3 con el refresco de `TokenAuthenticator`.
 * Nunca el de otra terminal ni de otra sucursal: si fallara, el SERVIDOR contesta 404 y eso nunca da un desenlace falso.
 */
public interface CredencialDelServidor {
    /** El JWT vigente, sin red. `null` = no hay sesión. */
    public fun vigente(): String?

    /** Un JWT nuevo de la MISMA terminal, dado el que el SERVIDOR rechazó; `null` si no se pudo. Se acota con [espera]. */
    public suspend fun renovar(rechazado: String, espera: Duration): String?
}

/**
 * El contrato del reverso con el SERVIDOR: sólo se le reporta el resultado; el reverso nunca le pide permiso (bloque S, D-2).
 * La identidad (la terminal) sale del JWT de [CredencialDelServidor].
 */
internal interface ReversoDelServidor {
    suspend fun reportarResultado(venueId: String, id: AttemptId, r: ResultadoDelReverso): AcuseDelResultado
}
