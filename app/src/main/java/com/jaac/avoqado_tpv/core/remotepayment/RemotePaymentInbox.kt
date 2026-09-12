package com.jaac.avoqado_tpv.core.remotepayment

import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RemotePaymentReceiveDecision {
    data class Deliver(val request: RemotePaymentRequest) : RemotePaymentReceiveDecision
    data object AckOnly : RemotePaymentReceiveDecision
    data class ReplayResult(val finalResultJson: String) : RemotePaymentReceiveDecision
    data class Reject(val reason: String) : RemotePaymentReceiveDecision
}

enum class RemotePaymentCancelDisposition { ACTIVE, ACCEPTED, ALREADY_RESOLVED }

/**
 * Respuesta a la SONDA del servidor (`terminal:payment_probe`). Sale de lo DURABLE y nunca entrega:
 *   RESOLVED           ⇒ hay resultado final guardado; se reproduce tal cual (con su `outcomeEvidence`).
 *   ACTIVE             ⇒ la solicitud está reclamada (PROCESSING) o alguien la reclamó en carrera: se conserva.
 *   RECEIVED_CANCELLED ⇒ estaba RECIBIDA y nadie la reclamó: se cancela durablemente ANTES de cualquier
 *                        autorización (evidencia `PRE_AUTHORIZATION`) y se reproduce ese resultado.
 *   NOT_FOUND          ⇒ esta bandeja nunca la persistió (y persistir precede al ACK). Deja una LÁPIDA durable
 *                        (`STATUS_NOT_FOUND_ANSWERED`): la misma solicitud, si llega después, se rechaza y no se ejecuta.
 */
enum class RemotePaymentProbeDisposition { RESOLVED, ACTIVE, RECEIVED_CANCELLED, NOT_FOUND }

data class RemotePaymentProbeAnswer(
    val disposition: RemotePaymentProbeDisposition,
    val finalResultJson: String? = null,
)

data class RemotePaymentCancelDecision(
    val disposition: RemotePaymentCancelDisposition,
    val finalResultJson: String? = null,
)

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
                if (existing.status == RemotePaymentRequestEntity.STATUS_NOT_FOUND_ANSWERED) {
                    // Codex 11-sep: la declaración «no recibida» es DURABLE. Esta solicitud llega DESPUÉS de haberle
                    // dicho al servidor que nunca se recibió (entrega retrasada, replay sin procedencia): no se ejecuta.
                    Timber.e("🪦 [RemotePaymentInbox] ${event.requestId} llegó DESPUÉS de contestar NOT_FOUND a la sonda: se rechaza sin ejecutar")
                    return RemotePaymentReceiveDecision.Reject("La terminal ya declaró al servidor que nunca recibió esta solicitud; no se ejecuta")
                }
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

    fun observePendingObligationCount(venueId: String) = dao.observePendingObligationCount(venueId)

    suspend fun markProcessingForVenue(requestId: String, venueId: String): Boolean =
        dao.markProcessingForVenue(requestId, venueId, System.currentTimeMillis()) == 1

    suspend fun markProcessing(requestId: String): Boolean =
        dao.markProcessing(requestId, System.currentTimeMillis()) == 1

    suspend fun markResolved(requestId: String, finalResultJson: String): Boolean {
        val status = runCatching { JSONObject(finalResultJson).optString("status") }.getOrNull()
        // A timeout says nothing about authorization. Keep the durable request open.
        if (status !in setOf("success", "failed", "cancelled")) return false
        return dao.markResolved(requestId, finalResultJson, System.currentTimeMillis()) == 1
    }

    /**
     * No UI snapshot: this CAS and markProcessing are the only admission decision.
     *
     * C.5 (11-sep): una solicitud YA reclamada (PROCESSING) también se puede cancelar, pero sólo por el CAS
     * de [RemotePaymentRequestDao.cancelarTrasReclamar] — en una transacción con la libreta y dejando la
     * CERCA puesta. [propiedadEnEsteProceso] = este proceso reclamó el `requestId` (lo sabe el coordinador):
     * es lo único que permite aceptar un cancel cuando todavía no hay ningún intento en la libreta.
     */
    suspend fun cancel(requestId: String, propiedadEnEsteProceso: Boolean = false): RemotePaymentCancelDecision {
        val cancelled = JSONObject().put("requestId", requestId).put("status", "cancelled")
            .put("outcomeEvidence", "PRE_AUTHORIZATION")
            .put("errorMessage", "Cancelado por el POS antes de iniciar el cobro").toString()
        if (dao.resolveReceived(requestId, cancelled, System.currentTimeMillis()) == 1) {
            return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACCEPTED, cancelled)
        }
        val existing = dao.getById(requestId)
        if (existing?.status == RemotePaymentRequestEntity.STATUS_RESOLVED && existing.finalResultJson != null) {
            return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ALREADY_RESOLVED, existing.finalResultJson)
        }
        if (existing?.status == RemotePaymentRequestEntity.STATUS_PROCESSING) {
            val trasReclamar = JSONObject().put("requestId", requestId).put("status", "cancelled")
                .put("outcomeEvidence", "PRE_AUTHORIZATION")
                .put("errorMessage", "Cancelado por el POS; la terminal no inició ni iniciará otro intento").toString()
            val aceptado = runCatching {
                dao.cancelarTrasReclamar(requestId, trasReclamar, System.currentTimeMillis(), propiedadEnEsteProceso)
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Sin escritura durable no hay aceptación: el servidor conserva la reserva.
                if (error !is RemotePaymentRequestDao.CancelNoAceptado) {
                    Timber.e(error, "❌ [RemotePaymentInbox] CAS del cancel tras reclamar falló para $requestId")
                }
                false
            }
            if (aceptado) {
                Timber.i("🛑 [RemotePaymentInbox] Cancel ACEPTADO tras reclamar: $requestId queda cercada")
                return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACCEPTED, trasReclamar)
            }
            // Una carrera pudo resolverla entre la lectura y el CAS: se contesta por lo que quedó.
            val despues = dao.getById(requestId)
            if (despues?.status == RemotePaymentRequestEntity.STATUS_RESOLVED && despues.finalResultJson != null) {
                return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ALREADY_RESOLVED, despues.finalResultJson)
            }
        }
        // Claimed with execution in flight, not found or tombstoned: no evidence that cancelling is safe.
        return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACTIVE)
    }

    /**
     * SONDA. Nunca marca PROCESSING, nunca entrega. Dos escrituras posibles, las dos durables y las dos sin
     * fabricar evidencia: cancelar una solicitud RECIBIDA que nadie reclamó (el mismo CAS que `cancel`, así que un
     * reclamo concurrente gana y la respuesta baja a ACTIVE), y dejar una LÁPIDA cuando la bandeja no la tiene.
     * El `venueId` es el del evento de la sonda (el servidor sólo sonda filas del venue de esta terminal): es el
     * tenant de la lápida. Sin venue no hay lápida posible, y sin lápida escrita no se contesta NOT_FOUND: se lanza
     * y el servidor conserva la reserva (una respuesta que no consta no es una respuesta).
     */
    suspend fun probe(requestId: String, venueId: String): RemotePaymentProbeAnswer {
        require(venueId.isNotBlank()) { "La sonda no puede dejar lápida sin venue" }
        val existing = dao.getById(requestId) ?: return answerAbsent(requestId, venueId)
        return answerFor(existing)
    }

    /**
     * Codex 11-sep: «una declaración “no recibido” impide DURABLEMENTE ejecutar ese intento». La lápida es lo que
     * convierte NOT_FOUND en una promesa: si la misma solicitud llega después (entrega retrasada, replay de un servidor
     * sin procedencia), `receive` la rechaza en vez de ejecutarla. Una lápida que no se pudo escribir propaga la
     * excepción: sin escritura durable no hay NOT_FOUND.
     */
    private suspend fun answerAbsent(requestId: String, venueId: String): RemotePaymentProbeAnswer {
        if (dao.insert(RemotePaymentRequestEntity.tombstone(requestId, venueId)) != -1L) {
            Timber.w("🪦 [RemotePaymentInbox] NOT_FOUND con lápida: $requestId no se ejecutará aunque llegue después")
            return RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.NOT_FOUND)
        }
        // Carrera: la solicitud se persistió entre la lectura y la lápida. Se contesta por lo que hay; nunca NOT_FOUND.
        val raced = dao.getById(requestId) ?: return RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
        return answerFor(raced)
    }

    private suspend fun answerFor(existing: RemotePaymentRequestEntity): RemotePaymentProbeAnswer = when (existing.status) {
        RemotePaymentRequestEntity.STATUS_RESOLVED -> existing.finalResultJson
            ?.let { RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.RESOLVED, it) }
            ?: RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
        RemotePaymentRequestEntity.STATUS_PROCESSING -> RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
        RemotePaymentRequestEntity.STATUS_RECEIVED -> {
            val cancelled = JSONObject().put("requestId", existing.requestId).put("status", "cancelled")
                .put("outcomeEvidence", "PRE_AUTHORIZATION")
                .put("errorMessage", "Cancelado por conciliación: la terminal nunca inició este cobro").toString()
            if (dao.resolveReceived(existing.requestId, cancelled, System.currentTimeMillis()) == 1) {
                RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.RECEIVED_CANCELLED, cancelled)
            } else {
                RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
            }
        }
        // La lápida contesta lo mismo cada vez: esta bandeja nunca la persistió como solicitud y no la ejecutará.
        RemotePaymentRequestEntity.STATUS_NOT_FOUND_ANSWERED -> RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.NOT_FOUND)
        else -> RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
    }

    /** A full navigation queue may reject ONLY a command that has never been claimed. */
    suspend fun rejectUnclaimed(requestId: String): String? {
        val result = JSONObject().put("requestId", requestId).put("status", "failed")
            .put("outcomeEvidence", "PRE_AUTHORIZATION")
            .put("errorMessage", "La terminal no pudo abrir otra solicitud de cobro").toString()
        return result.takeIf { dao.resolveReceived(requestId, it, System.currentTimeMillis()) == 1 }
    }

    /**
     * Return the actual durable winner so duplicate callbacks replay rather than disappear.
     *
     * 🛑 H.3 + evidencia POR INTENTO (11-sep): un desenlace NEGATIVO (failed/cancelled) no se escribe con la
     * evidencia que trae la pantalla, sino con la que acredita la LIBRETA del último intento de la solicitud
     * ([RemotePaymentRequestDao.resolverDesenlaceNegativo]). Si la libreta no acredita ningún «no se cobró»
     * (intento incierto o en curso), no se escribe nada y la solicitud se conserva sin desenlace: el servidor
     * la deja sin resolver en vez de certificar un «no» que no consta.
     */
    suspend fun persistResult(requestId: String, resultJson: String): String? {
        val status = runCatching { JSONObject(resultJson).optString("status") }.getOrNull()
        if (status == "failed" || status == "cancelled") {
            val escrito = runCatching {
                dao.resolverDesenlaceNegativo(requestId, resultJson, System.currentTimeMillis())
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (error !is RemotePaymentRequestDao.CancelNoAceptado) {
                    Timber.e(error, "❌ [RemotePaymentInbox] No se pudo escribir el desenlace negativo de $requestId")
                }
                null
            }
            if (escrito != null) return escrito
            val existing = dao.getById(requestId) ?: return null
            if (existing.status == RemotePaymentRequestEntity.STATUS_PROCESSING) {
                Timber.w("⚠️ [RemotePaymentInbox] $requestId: la libreta no acredita «no se cobró» — no se escribe desenlace negativo")
                return null
            }
            // Ya resuelta: se reproduce el ganador durable (nunca se degrada un éxito).
            if (existing.status != RemotePaymentRequestEntity.STATUS_RESOLVED) return null
            return existing.finalResultJson
        }
        if (markResolved(requestId, resultJson)) return resultJson
        val existing = dao.getById(requestId) ?: return null
        val previous = existing.finalResultJson ?: return null
        if (existing.status != RemotePaymentRequestEntity.STATUS_RESOLVED) return null
        // A real approval outranks historical cancellation/failed results, including old APK
        // false cancellations. Never allow cancellation or failure to overwrite approval.
        if (JSONObject(resultJson).optString("status") == "success" &&
            JSONObject(previous).optString("status") != "success"
        ) {
            if (dao.replaceResolvedResult(requestId, previous, resultJson, System.currentTimeMillis()) == 1) return resultJson
            return dao.getById(requestId)?.finalResultJson
        }
        return previous
    }

}
