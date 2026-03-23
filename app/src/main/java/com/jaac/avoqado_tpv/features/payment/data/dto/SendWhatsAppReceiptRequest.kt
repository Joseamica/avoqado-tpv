package com.jaac.avoqado_tpv.features.payment.data.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO para request de envío de recibo por WhatsApp.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/payments/{paymentId}/send-whatsapp
 *
 * **Backend Response:** SendReceiptResponse (reused)
 *
 * @param recipientPhone Phone number with country code (e.g., "525512345678")
 */
data class SendWhatsAppReceiptRequest(
    @SerializedName("recipientPhone")
    val recipientPhone: String
)
