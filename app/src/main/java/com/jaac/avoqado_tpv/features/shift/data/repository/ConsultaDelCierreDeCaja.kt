package com.jaac.avoqado_tpv.features.shift.data.repository

import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.remotepayment.BandejaServerRecovery
import com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerServerRecovery
import com.jaac.avoqado_tpv.features.shift.domain.LocalShiftObligation
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔒 D3 («ninguna duda de cobro sin salida», QA 2-nov): al intentar cerrar el turno, primero se PREGUNTA.
 *
 * Tras declarar «no se cobró» en el POS, el intento seguía en duda hasta una pasada del worker que nadie dispara (23 min medidos en
 * la PAX): la pasada respeta el espaciado de E3 (10 min × 2^consultas). Caja decía «Consúltala» y no había dónde. Aquí se pregunta
 * con lo mismo que «Consultar»: [LedgerServerRecovery.recoverOne] sin estampar, que no mira el espaciado ni gasta el turno, y
 * [BandejaServerRecovery.conciliar] para las solicitudes sin intento (lo mismo que hace el worker). No hay otra consulta, ni otro
 * espaciado, ni otra regla: el dinero, el veto y la declaración de una persona los siguen decidiendo los CAS de siempre.
 */
@Singleton
class ConsultaDelCierreDeCaja @Inject constructor(
    private val recuperacion: LedgerServerRecovery,
    private val bandeja: BandejaServerRecovery,
    private val socket: dagger.Lazy<SocketManager>,
) {
    /**
     * Pregunta por lo que aparta este cierre, con tope de [TOPE_MS] en total. Sin red no se espera: cada consulta falla al instante,
     * y el WiFi sin salida lo corta el tope. Cortada a media pasada no se pierde nada: lo que se alcanzó a escribir queda y la consulta
     * siguiente pregunta otra vez, sin gastar turnos.
     *
     * @return true si Avoqado no contestó (sin red, o sin respuesta a tiempo): es lo que el mensaje del cierre tiene que decir.
     */
    suspend fun preguntar(venueId: String, pendientes: List<LocalShiftObligation>): Boolean {
        var contesto = false
        var sinRespuesta = false
        val termino = withTimeoutOrNull(TOPE_MS) {
            for (intento in pendientes) {
                if (intento.owner != OBLIGACION_DE_INTENTO) continue
                val lectura = recuperacion.recoverOne(venueId, intento.ownerId, estampar = false)
                if (lectura.servidorContesto) contesto = true
                if (lectura.sinRespuesta) sinRespuesta = true
                // Como el trigger y el worker: un `success` durable de bandeja recién escrito se le emite al servidor.
                lectura.bandejaResueltaJson?.let(socket.get()::emitDurableTerminalPaymentResult)
            }
            if (pendientes.any { it.owner == OBLIGACION_REMOTA }) {
                val conciliacion = bandeja.conciliar(venueId)
                if (conciliacion.consultadas > 0) contesto = true
                if (conciliacion.sinRespuesta > 0) sinRespuesta = true
            }
            true
        } ?: false
        return !contesto && (sinRespuesta || !termino)
    }

    companion object {
        /** «Pocos segundos»: el cajero está frente a un cargando y S6 contesta en cientos de ms. */
        const val TOPE_MS = 5_000L
        const val OBLIGACION_DE_INTENTO = "ATTEMPT"
        const val OBLIGACION_REMOTA = "REMOTE"
    }
}
