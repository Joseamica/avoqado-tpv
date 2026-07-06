package com.jaac.avoqado_tpv.features.payment.data.dto

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/**
 * DTO para response del backend después de crear un pago.
 *
 * **Endpoints que retornan este DTO:**
 * - POST /tpv/venues/{venueId}/fast
 * - POST /tpv/venues/{venueId}/orders/{orderId}
 *
 * **Estructura del response:**
 * ```json
 * {
 *   "success": true,
 *   "data": {
 *     "id": "clxxx...",
 *     "amount": 50.00,
 *     "tipAmount": 5.00,
 *     "authorizationNumber": "502511",
 *     "referenceNumber": "000000188231",
 *     "digitalReceipt": {
 *       "id": "clyyy...",
 *       "accessKey": "secure_key_123",
 *       "receiptUrl": "https://api.avoqado.io/api/v1/public/receipt/secure_key_123"
 *     }
 *   }
 * }
 * ```
 *
 * **Uso:**
 * Este DTO se mapea a domain model PaymentReceipt en el repository.
 */
data class PaymentResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: PaymentData,
)

/**
 * Datos del payment creado en el backend.
 */
data class PaymentData(
    @SerializedName("id")
    val id: String,

    @SerializedName("amount")
    val amount: BigDecimal,

    @SerializedName("tipAmount")
    val tipAmount: BigDecimal,

    @SerializedName("authorizationNumber")
    val authorizationNumber: String?,

    @SerializedName("referenceNumber")
    val referenceNumber: String?,

    @SerializedName("digitalReceipt")
    val digitalReceipt: DigitalReceiptData,
)

/**
 * Datos del recibo digital generado automáticamente.
 *
 * El backend genera este recibo con un access key único
 * que permite acceso público sin autenticación.
 */
data class DigitalReceiptData(
    @SerializedName("id")
    val id: String,

    @SerializedName("accessKey")
    val accessKey: String,

    @SerializedName("receiptUrl")
    val receiptUrl: String,

    @SerializedName("autofacturaAvailable")
    val autofacturaAvailable: Boolean = false,
)
