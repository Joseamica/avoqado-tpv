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

    // 🔴 NULLABLE A PROPÓSITO — el backend SÍ manda `digitalReceipt: null` y no es un caso raro:
    //  (a) rama idempotente: si el pago existente no tiene fila en `DigitalReceipt`,
    //      `mapDigitalReceiptResponse()` devuelve null (`if (!receipt) return null`);
    //  (b) rama de cobro fresco: `generateDigitalReceipt()` está en try/catch y si falla
    //      responde `digitalReceipt: null` sin fallar el pago.
    // Gson NO respeta la no-nulabilidad de Kotlin: declarado como no-nulo, un `null` del JSON
    // entra igual y revienta con NPE al primer acceso. Eso fue exactamente el bucle infinito de
    // Testarudo Café (2,781 reintentos del MISMO pago del 23-jun en 6.3 h): la NPE se clasificaba
    // como error transitorio → 5 reintentos internos × 10 del worker = 50 requests por ciclo,
    // para siempre. `RefundResponse.digitalReceipt` ya era nullable por esta misma razón; a
    // pagos nunca se le portó. El cobro SIEMPRE quedó registrado — lo único que faltaba era el QR.
    @SerializedName("digitalReceipt")
    val digitalReceipt: DigitalReceiptData?,

    // Checkpoint 2 (Codex P1-2): el estado del Payment y `processorData.reconciliation` viajan en el mismo 2xx. Nulos si el
    // servidor no los manda (versión anterior). Gson: un objeto ausente es null, nunca revienta.
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("processorData")
    val processorData: com.google.gson.JsonObject? = null,
    /**
     * N0b: la COLUMNA `Payment.terminalPaymentRequestId`, que el servidor sólo escribe al LIGAR la solicitud con este Payment
     * (`closeRowFromPaymentTx`). Con `status = COMPLETED` es la prueba durable de que este Payment cerró esa solicitud; un
     * COMPLETED sin ella quedó registrado pero NO ligado (sin identidad de terminal, asociación inválida) y no acredita ganador.
     */
    @SerializedName("terminalPaymentRequestId")
    val terminalPaymentRequestId: String? = null,
) {
    /** `processorData.reconciliation.kind` si viene como cadena; null en cualquier otra forma. */
    val reconciliationKind: String?
        get() = runCatching {
            processorData?.getAsJsonObject("reconciliation")?.get("kind")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()

    /** `processorData.registradoVia` (`webhook` sólo si el servidor lo dice); ausente ⇒ `terminal`. */
    val registradoVia: String
        get() = runCatching {
            processorData?.get("registradoVia")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()?.takeIf { it == "webhook" } ?: "terminal"

    /** `processorData.reconciliation.winnerPaymentId` si viene como cadena. */
    val winnerPaymentId: String?
        get() = runCatching {
            processorData?.getAsJsonObject("reconciliation")?.get("winnerPaymentId")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
}

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
