package com.jaac.avoqado_tpv.features.payment.data.dto

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/**
 * DTO para response del backend después de procesar un reembolso.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/refunds
 *
 * **Estructura del response:**
 * ```json
 * {
 *   "success": true,
 *   "data": {
 *     "id": "clxxx...",
 *     "originalPaymentId": "clyyy...",
 *     "amount": 50.00,
 *     "status": "COMPLETED",
 *     "authorizationNumber": "502511",
 *     "referenceNumber": "000000188231",
 *     "digitalReceipt": {
 *       "id": "clzzz...",
 *       "accessKey": "secure_key_123",
 *       "receiptUrl": "https://api.avoqado.io/api/v1/public/receipt/secure_key_123"
 *     }
 *   }
 * }
 * ```
 *
 * **Uso:**
 * Este DTO se mapea a domain model RefundReceipt en el repository.
 */
data class RefundResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: RefundData,
)

/**
 * Datos del refund creado en el backend.
 */
data class RefundData(
    @SerializedName("id")
    val id: String, // Refund record ID

    @SerializedName("originalPaymentId")
    val originalPaymentId: String, // Payment that was refunded

    @SerializedName("amount")
    val amount: BigDecimal, // Refund amount in pesos (backend returns decimal)

    @SerializedName("status")
    val status: String, // "COMPLETED", "FAILED", "PENDING"

    @SerializedName("authorizationNumber")
    val authorizationNumber: String?,

    @SerializedName("referenceNumber")
    val referenceNumber: String?,

    @SerializedName("digitalReceipt")
    val digitalReceipt: DigitalReceiptData?, // Refund receipt (may be null if backend doesn't generate)
)
