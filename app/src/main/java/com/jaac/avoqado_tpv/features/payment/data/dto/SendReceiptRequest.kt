package com.jaac.avoqado_tpv.features.payment.data.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO para request de envío de recibo por email.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/payments/{paymentId}/send-receipt
 *
 * **Backend Response:** SendReceiptResponse
 *
 * @param recipientEmail Email del cliente donde enviar el recibo
 */
data class SendReceiptRequest(
    @SerializedName("recipientEmail")
    val recipientEmail: String
)
