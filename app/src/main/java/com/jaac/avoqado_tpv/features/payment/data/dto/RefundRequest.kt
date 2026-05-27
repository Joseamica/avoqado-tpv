package com.jaac.avoqado_tpv.features.payment.data.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO para request de refund al backend.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/refunds
 *
 * **Backend Response:** RefundResponse
 *
 * **CRITICAL - Multi-Merchant Routing:**
 * The refund MUST be processed by the same merchant that processed the original payment.
 * - merchantAccountId: Identifies which MerchantAccount to credit back
 * - blumonSerialNumber: Required for SDK initialization (merchant switch)
 *
 * **IMPORTANTE:**
 * - amount debe estar en CENTAVOS (Int), no pesos (BigDecimal)
 * - Backend convierte: 5000 cents → $50.00 MXN
 * - Ejemplo: $30.50 → 3050 cents
 *
 * @param venueId ID del venue (requerido en body además del path)
 * @param originalPaymentId ID del pago original siendo reembolsado
 * @param originalOrderId ID de la orden original (nullable para fast payments)
 * @param amount Monto a reembolsar en centavos (ej. 5000 = $50.00)
 * @param reason Razón del reembolso (para auditoría)
 * @param staffId ID del staff que procesa el reembolso
 * @param shiftId ID del turno activo (para reconciliación)
 * @param merchantAccountId ID del merchant account (MUST match original payment)
 * @param blumonSerialNumber Serial del terminal Blumon (para SDK switch)
 * @param authorizationNumber Código de autorización de Blumon para el refund
 * @param referenceNumber Referencia de transacción de Blumon para el refund
 * @param maskedPan Número de tarjeta enmascarado (del pago original o refund)
 * @param cardBrand Marca de tarjeta ("VISA", "MASTERCARD", etc.)
 * @param entryMode Método de entrada ("CHIP", "CONTACTLESS", "SWIPE")
 */
data class RefundRequest(
    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("originalPaymentId")
    val originalPaymentId: String,

    @SerializedName("originalOrderId")
    val originalOrderId: String?,

    @SerializedName("amount")
    val amount: Int, // In cents (5000 = $50.00)

    @SerializedName("reason")
    val reason: String, // RefundReason.name (e.g., "CUSTOMER_REQUEST")

    @SerializedName("staffId")
    val staffId: String,

    @SerializedName("shiftId")
    val shiftId: String?,

    // ⭐ MULTI-MERCHANT CRITICAL (2025-01-XX)
    // These fields MUST match the original payment for proper routing
    @SerializedName("merchantAccountId")
    val merchantAccountId: String?, // FK to MerchantAccount - MUST match original payment

    @SerializedName("blumonSerialNumber")
    val blumonSerialNumber: String, // Terminal serial for SDK merchant switch

    // Blumon SDK response data (from TransType.REFUND)
    @SerializedName("authorizationNumber")
    val authorizationNumber: String,

    @SerializedName("referenceNumber")
    val referenceNumber: String,

    // Card details (may differ from original if card was re-inserted)
    @SerializedName("maskedPan")
    val maskedPan: String?,

    @SerializedName("cardBrand")
    val cardBrand: String?,

    @SerializedName("entryMode")
    val entryMode: String?,

    // 🛡️ Idempotency key for refund attempt deduplication (2026-04-22)
    @SerializedName("idempotencyKey")
    val idempotencyKey: String? = null,

    // Metadata
    @SerializedName("isPartialRefund")
    val isPartialRefund: Boolean = false,

    @SerializedName("currency")
    val currency: String = "MXN",

    /**
     * Optional explicit tip portion of the refund, in cents.
     *
     * Semantics (backend side, NOT Blumon SDK):
     *   - `null` → backend default: split proportional to original sale/tip ratio.
     *   - `0`    → refund categorized 100% as sale (staff tip ledger untouched).
     *   - `= amount` → refund categorized as tip-only.
     *
     * **IMPORTANT:** This does NOT change what Blumon returns to the cardholder.
     * Blumon always refunds the FULL requested `amount` on the card. This field
     * only controls how we book the refund internally across Payment.amount /
     * Payment.tipAmount and Shift.totalSales / Shift.totalTips.
     */
    @SerializedName("tipRefundCents")
    val tipRefundCents: Int? = null,

    /**
     * Payment processor that handled the original transaction. Sent so the
     * backend persists it on the refund row's `processor` field for
     * downstream reconciliation. Backwards compatible: null = backend
     * defaults to `'blumon'`, matching legacy behavior of pre-2.31 TPVs.
     *
     * Accepted values: `"blumon"` (PAX) | `"angelpay"` (Nexgo).
     */
    @SerializedName("processor")
    val processor: String? = null,
)
