package com.jaac.avoqado_tpv.features.payment.data.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO para response de envío de recibo por email.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/payments/{paymentId}/send-receipt
 *
 * @param success True si el email fue enviado exitosamente
 * @param receiptId ID del recibo digital generado (nullable si ya existía)
 * @param message Mensaje descriptivo del resultado
 */
data class SendReceiptResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("receiptId")
    val receiptId: String? = null,

    @SerializedName("message")
    val message: String? = null
)
