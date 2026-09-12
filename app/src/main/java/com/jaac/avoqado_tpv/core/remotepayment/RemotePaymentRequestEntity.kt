package com.jaac.avoqado_tpv.core.remotepayment

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent

/** Inbox durable del contrato POS → TPV. No es un Payment ni alimenta reportes. */
@Entity(
    tableName = "remote_payment_requests",
    indices = [Index(value = ["venue_id", "status"]), Index(value = ["created_at"])],
)
data class RemotePaymentRequestEntity(
    @PrimaryKey @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "venue_id") val venueId: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "tip_cents") val tipCents: Long,
    val rating: Int?,
    @ColumnInfo(name = "skip_review") val skipReview: Boolean,
    @ColumnInfo(name = "order_id") val orderId: String?,
    @ColumnInfo(name = "processed_by_staff_id") val processedByStaffId: String?,
    @ColumnInfo(name = "sender_device_name") val senderDeviceName: String?,
    @ColumnInfo(name = "source_timestamp") val sourceTimestamp: String,
    val status: String,
    @ColumnInfo(name = "final_result_json") val finalResultJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * C.5 (11-sep): el POS canceló DESPUÉS de que esta terminal reclamara la solicitud y la cancelación
     * se ACEPTÓ (CAS en una transacción con la libreta). Es CERCA: [PaymentAttemptDao.reserveTerminal]
     * ya no admite ningún intento nuevo de esta solicitud. Room v34.
     */
    @ColumnInfo(name = "cancel_accepted_at") val cancelAcceptedAt: Long? = null,
    /**
     * C.5: arrancó EFECTIVO o CRIPTO para esta solicitud (CAS antes de registrar). Desde aquí un cancel
     * remoto contesta ACTIVE y la terminal ya no certifica un «no se cobró». Room v34.
     */
    @ColumnInfo(name = "execution_started_at") val executionStartedAt: Long? = null,
    /**
     * H.3: ya quedó escrito el desenlace FINAL de esta solicitud (el que se emite al servidor). También
     * es CERCA: una solicitud tiene a lo más UN final emitido, y desde ese momento la terminal ya no la
     * ejecuta. Room v34.
     */
    @ColumnInfo(name = "final_emitted_at") val finalEmittedAt: Long? = null,
) {
    fun sameMoneyContract(event: SocketEvent.TerminalPaymentRequest): Boolean =
        venueId == event.venueId &&
            amountCents == event.amountCents &&
            tipCents == event.tipCents &&
            rating == event.rating &&
            skipReview == event.skipReview &&
            orderId == event.orderId &&
            processedByStaffId == event.processedByStaffId

    fun toRemoteRequest(): RemotePaymentRequest = RemotePaymentRequest(
        amountCents = amountCents,
        tipCents = tipCents,
        rating = rating,
        skipReview = skipReview,
        orderId = orderId,
        processedByStaffId = processedByStaffId,
        source = PaymentSource.SOCKET,
        socketRequestId = requestId,
    )

    companion object {
        const val STATUS_RECEIVED = "RECEIVED"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_RESOLVED = "RESOLVED"
        /**
         * LÁPIDA: esta bandeja contestó NOT_FOUND a la sonda del servidor. No es una solicitud entregable (no lleva
         * contrato de dinero) y NUNCA se ejecuta: si la solicitud con ese `requestId` llega después, `receive` la rechaza.
         */
        const val STATUS_NOT_FOUND_ANSWERED = "NOT_FOUND_ANSWERED"

        fun tombstone(requestId: String, venueId: String, now: Long = System.currentTimeMillis()) = RemotePaymentRequestEntity(
            requestId = requestId,
            venueId = venueId,
            amountCents = 0L,
            tipCents = 0L,
            rating = null,
            skipReview = false,
            orderId = null,
            processedByStaffId = null,
            senderDeviceName = null,
            sourceTimestamp = java.time.Instant.ofEpochMilli(now).toString(),
            status = STATUS_NOT_FOUND_ANSWERED,
            finalResultJson = null,
            createdAt = now,
            updatedAt = now,
        )

        fun from(
            event: SocketEvent.TerminalPaymentRequest,
            status: String = STATUS_RECEIVED,
            finalResultJson: String? = null,
            now: Long = System.currentTimeMillis(),
        ) = RemotePaymentRequestEntity(
            requestId = event.requestId,
            venueId = event.venueId,
            amountCents = event.amountCents,
            tipCents = event.tipCents,
            rating = event.rating,
            skipReview = event.skipReview,
            orderId = event.orderId,
            processedByStaffId = event.processedByStaffId,
            senderDeviceName = event.senderDeviceName,
            sourceTimestamp = event.timestamp,
            status = status,
            finalResultJson = finalResultJson,
            createdAt = now,
            updatedAt = now,
        )
    }
}
