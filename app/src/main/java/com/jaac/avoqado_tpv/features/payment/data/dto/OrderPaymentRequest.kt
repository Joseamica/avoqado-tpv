package com.jaac.avoqado_tpv.features.payment.data.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO para request de order payment al backend.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}
 *
 * **Backend Response:** PaymentResponse
 *
 * Este DTO es similar a FastPaymentRequest pero incluye campos
 * específicos para pagos de órdenes existentes:
 * - venueId (requerido en body)
 * - paidProductsId (lista de productos pagados)
 *
 * **NOTA:** Este DTO NO se usa todavía (no hay create order implementado).
 * Está listo para cuando se implemente la feature.
 *
 * @param venueId ID del venue (requerido en body además del path)
 * @param amount Monto total en centavos
 * @param tip Propina en centavos
 * @param status Estado del pago ("COMPLETED", "PENDING", "FAILED")
 * @param method Método de pago ("CASH", "CREDIT_CARD", "DEBIT_CARD")
 * @param source Origen del pago ("AVOQADO_TPV")
 * @param splitType Tipo de split ("FULLPAYMENT", "PERPRODUCT", etc.)
 * @param staffId ID del staff que procesa el pago
 * @param paidProductsId IDs de productos incluidos en este pago (para split payments)
 * @param authorizationNumber Código de autorización de Blumon
 * @param referenceNumber Referencia de transacción de Blumon
 * @param maskedPan Número de tarjeta enmascarado
 * @param cardBrand Marca de tarjeta
 * @param entryMode Método de entrada
 * @param currency Moneda del pago
 * @param isInternational true si es tarjeta internacional
 * @param reviewRating Calificación opcional
 */
data class OrderPaymentRequest(
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

    @SerializedName("paidProductsId")
    val paidProductsId: List<String> = emptyList(),

    // Datos de Blumon SDK
    @SerializedName("authorizationNumber")
    val authorizationNumber: String?,

    @SerializedName("referenceNumber")
    val referenceNumber: String?,

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

    // ⭐ Provider-agnostic merchant tracking (2025-01-17)
    // ✅ RECONCILIATION: null for cash, CUID for card payments
    @SerializedName("merchantAccountId")
    val merchantAccountId: String? = null,
)
