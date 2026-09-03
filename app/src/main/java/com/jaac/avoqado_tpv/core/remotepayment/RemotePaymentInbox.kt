package com.jaac.avoqado_tpv.core.remotepayment

import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RemotePaymentReceiveDecision {
    data class Deliver(val request: RemotePaymentRequest) : RemotePaymentReceiveDecision
    data object AckOnly : RemotePaymentReceiveDecision
    data class ReplayResult(val finalResultJson: String) : RemotePaymentReceiveDecision
    data class Reject(val reason: String) : RemotePaymentReceiveDecision
}

/** Persistir primero; ACK/navegación después. */
@Singleton
class RemotePaymentInbox @Inject constructor(
    private val dao: RemotePaymentRequestDao,
) {
    suspend fun receive(event: SocketEvent.TerminalPaymentRequest): RemotePaymentReceiveDecision {
        if (event.requestId.isBlank() || event.venueId.isBlank() || event.amountCents <= 0L || event.tipCents < 0L) {
            return RemotePaymentReceiveDecision.Reject("Solicitud remota inválida")
        }
        if (event.rating != null && event.rating !in 1..5) {
            return RemotePaymentReceiveDecision.Reject("Calificación remota inválida")
        }

        return try {
            val incoming = RemotePaymentRequestEntity.from(event)
            if (dao.insert(incoming) != -1L) {
                RemotePaymentReceiveDecision.Deliver(incoming.toRemoteRequest())
            } else {
                val existing = dao.getById(event.requestId)
                    ?: return RemotePaymentReceiveDecision.Reject("No se pudo releer la solicitud persistida")
                if (!existing.sameMoneyContract(event)) {
                    Timber.e("🛑 [RemotePaymentInbox] requestId repetido con contrato distinto: ${event.requestId}")
                    return RemotePaymentReceiveDecision.Reject("requestId ya existe con otro importe o contexto")
                }
                when (existing.status) {
                    RemotePaymentRequestEntity.STATUS_RECEIVED -> RemotePaymentReceiveDecision.Deliver(existing.toRemoteRequest())
                    RemotePaymentRequestEntity.STATUS_PROCESSING -> RemotePaymentReceiveDecision.AckOnly
                    RemotePaymentRequestEntity.STATUS_RESOLVED -> existing.finalResultJson
                        ?.let(RemotePaymentReceiveDecision::ReplayResult)
                        ?: RemotePaymentReceiveDecision.Reject("Solicitud resuelta sin resultado durable")
                    else -> RemotePaymentReceiveDecision.Reject("Estado local de solicitud desconocido")
                }
            }
        } catch (error: Exception) {
            Timber.e(error, "❌ [RemotePaymentInbox] No se pudo persistir ${event.requestId}; no se confirma al servidor")
            RemotePaymentReceiveDecision.Reject("No se pudo guardar la solicitud en la terminal")
        }
    }

    suspend fun markProcessing(requestId: String): Boolean =
        dao.markProcessing(requestId, System.currentTimeMillis()) == 1

    suspend fun markResolved(requestId: String, finalResultJson: String): Boolean =
        dao.markResolved(requestId, finalResultJson, System.currentTimeMillis()) == 1
}
