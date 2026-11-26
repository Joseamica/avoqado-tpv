package com.avoqado.cobropropio.motor

import com.avoqado.cobropropio.blumon.ClaseDelReverso
import com.avoqado.cobropropio.blumon.Clasificador
import com.avoqado.cobropropio.blumon.CuerpoCongelado
import com.avoqado.cobropropio.libreta.Arbitraje
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.EstadoDelReverso
import com.avoqado.cobropropio.libreta.ResultadoParaReportar
import com.avoqado.cobropropio.red.Respuesta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

private const val TX_008 = "TX_008"

/**
 * B, D, E y F de la tabla de decisiones: arbitraje, vueltas del reverso con su techo y el plazo. Tras el arbitraje durable las
 * vueltas empiezan enseguida: el reverso no le pide permiso al SERVIDOR (bloque S, D-2). Un reverso que Blumon confirma es
 * `NoSeCobro` en ese instante; el reporte al SERVIDOR queda en la libreta (D-3). El techo es un esfuerzo de puntualidad
 * (parada 2): la corrección sale de la clasificación de cada respuesta, también la que llega tarde.
 */
internal suspend fun MotorDeCobro.caminoDelReverso(r: MotorDeCobro.Registro, salida: MotorDeCobro.Salida, t0: Long) {
    val id = r.id
    val tope = t0 + relojes.techoDelReverso
    // Si el reverso ni se puede armar, o ya no hay tiempo, nunca sale un byte: no se compromete (dejaría una intención huérfana).
    val armable = runCatching { salida.cuerpo.conMotivoDeReverso() }.isSuccess
    if (!armable || ahora() >= tope) return alPlazo(r)
    when (val a = enLibreta { libreta.comprometerAReversar(id) }) {
        Arbitraje.Comprometido -> Unit
        // Un `YaResuelta` con la fila todavía ENVIADO + COMPROMETIDO no es una resolución: ya estaba comprometida.
        is Arbitraje.YaResuelta ->
            if (a.fila.estado != EstadoDelIntento.ENVIADO || a.fila.reverso != EstadoDelReverso.COMPROMETIDO) return emitirFila(r)
        else -> return emitirFila(r)
    }

    // D. Cada vuelta: plazo ⇒ reclamo durable ⇒ plazo ⇒ POST ⇒ clasificación, y su cierre en una transacción. El cuerpo es
    // siempre el congelado; sólo el token cambia si una vuelta contestó 401/403 y se pudo renovar a tiempo.
    var congelado = salida.congelado
    // Si la venta misma contestó 401/403, ya la primera vuelta pide token nuevo; si no, sale con el congelado.
    var renovarToken = r.ventaConTokenMalo
    while (ahora() < tope) {
        if (renovarToken) {
            renovarToken = false
            tokenNuevo(congelado.token, tope - ahora())?.let { congelado = CuerpoCongelado(congelado.cuerpo, it) }
            if (ahora() >= tope) break
        }
        if (!enLibreta { libreta.reclamarReverso(id) }) break
        val (clase, tokenMalo) = if (ahora() >= tope) ClaseDelReverso.NoEnviado to false else unaVuelta(congelado, salida.esperado, tope)
        if (tokenMalo) {
            tokenVencido()
            renovarToken = true
        }
        when (clase) {
            is ClaseDelReverso.Confirmado -> {
                enLibreta { libreta.cerrarVuelta(id, clase, ResultadoParaReportar.CONFIRMADO, EstadoDelIntento.NO_SE_COBRO) }
                // El reporte es una obligación durable de la libreta: aquí sólo se adelanta un intento; `recuperar()` lo repite.
                fondo.launch { intentar { cola.entregar(id) } }
                emitirFila(r)
                return
            }
            is ClaseDelReverso.Rechazado -> {
                enLibreta { libreta.cerrarVuelta(id, clase, ResultadoParaReportar.RECHAZADO, EstadoDelIntento.NO_SE) }
                return emitirFila(r)
            }
            is ClaseDelReverso.Reintentar -> {
                enLibreta { libreta.cerrarVuelta(id, clase, null, null) }
                if (ahora() + relojes.pausaEntreReversos >= tope) break
                delay(relojes.pausaEntreReversos)
            }
            ClaseDelReverso.NoEnviado -> {
                enLibreta { libreta.cerrarVuelta(id, clase, null, null) }
                break
            }
        }
    }
    alPlazo(r)
}

/**
 * Una excepción del puerto pudo pasar con bytes escritos: es un «sin respuesta» (pudo entrar), nunca un «no salió». La vuelta
 * se clasifica igual que siempre; el segundo valor dice si contestó 401/403 (el token congelado ya no sirve).
 *
 * R3-2: lo que llegó nunca lo tira el reloj. El transporte deja la respuesta YA CLASIFICADA en el [buzon] en cuanto la lee,
 * antes de reanudar esta corrutina; si el reloj vence después, se devuelve lo del buzón (un `REVERSADA` leído confirma).
 */
private suspend fun MotorDeCobro.unaVuelta(
    congelado: CuerpoCongelado,
    esperado: Clasificador.Esperado,
    tope: Long,
): Pair<ClaseDelReverso, Boolean> {
    fun clasificar(r: Respuesta): Pair<ClaseDelReverso, Boolean> {
        if (Clasificador.pideLlavesNuevas(r)) pedirLlavesNuevas() // T8 arreglos 1 (D): sólo una bandera
        return Clasificador.reverso(r, esperado, catalogo, relojDePared) to (r is Respuesta.Http && r.codigo in MotorDeCobro.TOKEN_VENCIDO)
    }
    val buzon = AtomicReference<Pair<ClaseDelReverso, Boolean>?>(null)
    val respuesta = withTimeoutOrNull(relojes.esperaReverso) {
        intentar {
            blumon.reversar(congelado, relojes.esperaReverso.milliseconds, tope) { buzon.compareAndSet(null, clasificar(it)) }
        } ?: Respuesta.SinRespuesta("EXCEPCION")
    }
    return buzon.get() ?: clasificar(respuesta ?: Respuesta.SinRespuesta("TIEMPO"))
}

/**
 * F. Al vencer el plazo, o si ya no había tiempo (o forma) para reversar: cierra como `NO_SE` lo que siga en duda y publica lo
 * que diga la fila (si una aprobación ganó la escritura justo antes, manda ella).
 */
private suspend fun MotorDeCobro.alPlazo(r: MotorDeCobro.Registro) {
    val id = r.id
    enLibreta {
        val f = libreta.buscar(id)
        when {
            f == null -> Unit
            f.reverso == EstadoDelReverso.COMPROMETIDO -> libreta.cerrarSinVuelta(id)
            f.reverso == EstadoDelReverso.NO_CONCLUYENTE && f.pudoEntrar ->
                libreta.cerrarVuelta(id, ClaseDelReverso.NoEnviado, ResultadoParaReportar.PUDO_ENTRAR, EstadoDelIntento.NO_SE)
            f.reverso == EstadoDelReverso.NO_CONCLUYENTE && f.ultimoCodigoReverso == TX_008 ->
                libreta.cerrarVuelta(id, ClaseDelReverso.NoEnviado, ResultadoParaReportar.RECHAZADO, EstadoDelIntento.NO_SE)
            else -> ponerNoSe(id) // ningún reverso salió
        }
        Unit
    }
    emitirFila(r)
}
