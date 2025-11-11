package com.jaac.avoqado_tpv.features.payment.domain.model

import java.math.BigDecimal

/**
 * Contexto unificado de pago que funciona para fast payment y order payment.
 *
 * Esta sealed class encapsula AMBOS escenarios de pago en la app:
 * 1. Fast Payment: Pago directo sin orden (como Square Quick Sale)
 * 2. Order Payment: Pago para una orden existente (como Toast full service)
 *
 * **Ventajas:**
 * - Type-safe: Compiler garantiza exhaustive when
 * - Single interface: PaymentViewModel no distingue entre tipos
 * - Extensible: Fácil agregar nuevos tipos (split payment, refund, etc.)
 *
 * **Uso actual:**
 * Solo FastPayment está implementado. OrderPayment está listo para cuando
 * se implemente la feature de "crear orden".
 */
sealed class PaymentContext {
    abstract val venueId: String
    abstract val staffId: String
    abstract val amount: BigDecimal
    abstract val tip: BigDecimal
    abstract val rating: Int? // 🆕 Optional rating from user (1-5 stars, null if skipped)

    // ⭐ PROVIDER-AGNOSTIC MERCHANT TRACKING (2025-01-11)
    // ✅ NULLABLE: null for cash payments (proper reconciliation separation)
    abstract val merchantAccountId: String?  // 🆕 PRIMARY: Merchant account ID (null = cash payment, no processor)
    abstract val blumonSerialNumber: String // ⚠️ LEGACY: Blumon-specific serial (deprecated, kept for fallback)

    /**
     * Fast Payment: Pago directo sin orden existente.
     *
     * El backend creará automáticamente una orden virtual con:
     * - orderNumber: "FAST-{timestamp}"
     * - No items
     * - status: CONFIRMED + PAID
     *
     * **Cuándo usar:**
     * - Usuario solo quiere cobrar un monto (ej. $50.00)
     * - No hay orden previa creada
     * - Flujo rápido similar a Square Terminal Quick Sale
     *
     * **Endpoint backend:**
     * POST /tpv/venues/{venueId}/fast
     *
     * @param venueId ID del venue actual (de AuthContext)
     * @param staffId ID del staff que procesa el pago (de AuthContext)
     * @param amount Monto total del pago en pesos (ej. 50.00)
     * @param tip Propina opcional en pesos (default: 0.00)
     */
    data class FastPayment(
        override val venueId: String,
        override val staffId: String,
        override val amount: BigDecimal,
        override val tip: BigDecimal = BigDecimal.ZERO,
        override val rating: Int? = null, // 🆕 Optional rating (1-5 stars, null if skipped)
        override val merchantAccountId: String?, // ✅ NULLABLE: null for cash (no processor, proper reconciliation)
        override val blumonSerialNumber: String = "", // ⚠️ LEGACY: Blumon serial (deprecated)
    ) : PaymentContext()

    /**
     * Order Payment: Pago para una orden existente.
     *
     * La orden ya tiene:
     * - Items (productos)
     * - Subtotal, tax, total calculados
     * - orderNumber generado
     *
     * El pago se vincula a esa orden y marca como PAID.
     *
     * **Cuándo usar:**
     * - Usuario creó orden con productos
     * - Orden ya existe en backend con status PENDING
     * - Flujo full service similar a Toast POS
     *
     * **Endpoint backend:**
     * POST /tpv/venues/{venueId}/orders/{orderId}
     *
     * **NOTA:** Esta feature NO está implementada todavía.
     * El código está listo para cuando se implemente.
     *
     * @param venueId ID del venue actual
     * @param staffId ID del staff que procesa el pago
     * @param orderId ID de la orden existente (REQUERIDO)
     * @param amount Monto total del pago (debe coincidir con order.total)
     * @param tip Propina opcional
     */
    data class OrderPayment(
        override val venueId: String,
        override val staffId: String,
        val orderId: String,
        override val amount: BigDecimal,
        override val tip: BigDecimal = BigDecimal.ZERO,
        override val rating: Int? = null, // 🆕 Optional rating (1-5 stars, null if skipped)
        override val merchantAccountId: String?, // ✅ NULLABLE: null for cash (no processor, proper reconciliation)
        override val blumonSerialNumber: String = "", // ⚠️ LEGACY: Blumon serial (deprecated)
    ) : PaymentContext()
}
