package com.jaac.avoqado_tpv.features.payment.data.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO para request de fast payment al backend.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/fast
 *
 * **Backend Response:** PaymentResponse
 *
 * Este DTO mapea exactamente al schema esperado por el backend
 * (ver avoqado-server/src/services/tpv/payment.tpv.service.ts:669-707)
 *
 * **IMPORTANTE:**
 * - amount y tip deben estar en CENTAVOS (Int), no pesos (BigDecimal)
 * - Backend convierte: 5000 cents → $50.00 MXN
 * - Ejemplo: $30.50 → 3050 cents
 * - venueId debe estar en el body (además de la URL path) para validación
 *
 * @param venueId ID del venue (requerido en body además del path)
 * @param amount Monto total en centavos (ej. 5000 = $50.00)
 * @param tip Propina en centavos (ej. 500 = $5.00)
 * @param status Estado del pago ("COMPLETED", "PENDING", "FAILED")
 * @param method Método de pago ("CASH", "CREDIT_CARD", "DEBIT_CARD", "DIGITAL_WALLET")
 * @param source Origen del pago ("AVOQADO_TPV" para esta app)
 * @param splitType Tipo de split ("FULLPAYMENT", "PERPRODUCT", "EQUALPARTS", "CUSTOMAMOUNT")
 * @param staffId ID del staff que procesa el pago
 * @param authorizationNumber Código de autorización de Blumon (ej. "502511")
 * @param referenceNumber Referencia de transacción de Blumon (ej. "000000188231")
 * @param maskedPan Número de tarjeta enmascarado (ej. "411111******1111")
 * @param cardBrand Marca de tarjeta ("VISA", "MASTERCARD", "AMEX", etc.)
 * @param entryMode Método de entrada ("CHIP", "CONTACTLESS", "SWIPE")
 * @param currency Moneda del pago (default: "MXN")
 * @param isInternational true si es tarjeta internacional
 * @param reviewRating Calificación opcional ("EXCELLENT", "GOOD", "POOR")
 */
data class FastPaymentRequest(
    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("amount")
    val amount: Int,

    @SerializedName("tip")
    val tip: Int,

    @SerializedName("status")
    val status: String,

    @SerializedName("method")
    val method: String,

    @SerializedName("source")
    val source: String,

    @SerializedName("splitType")
    val splitType: String,

    @SerializedName("staffId")
    val staffId: String,

    // Datos de Blumon SDK
    @SerializedName("authorizationNumber")
    val authorizationNumber: String?,

    @SerializedName("referenceNumber")
    val referenceNumber: String?,

    // ⭐ PROVIDER-AGNOSTIC MERCHANT TRACKING (2025-01-10)
    @SerializedName("merchantAccountId")
    val merchantAccountId: String?, // 🆕 PRIMARY: Merchant account ID (FK to MerchantAccount)

    @SerializedName("blumonSerialNumber")
    val blumonSerialNumber: String?, // ⚠️ LEGACY: Blumon serial (deprecated, kept for fallback)

    @SerializedName("maskedPan")
    val maskedPan: String?,

    @SerializedName("cardBrand")
    val cardBrand: String?,

    @SerializedName("entryMode")
    val entryMode: String?,

    @SerializedName("currency")
    val currency: String = "MXN",

    @SerializedName("isInternational")
    val isInternational: Boolean = false,

    @SerializedName("reviewRating")
    val reviewRating: String? = null,
)
