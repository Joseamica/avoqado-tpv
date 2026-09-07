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
