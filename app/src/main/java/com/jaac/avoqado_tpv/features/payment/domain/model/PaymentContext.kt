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
    abstract val shiftId: String? // 🆕 CRITICAL: Shift ID for cash reconciliation (Square/Toast pattern, null = no shift validation bypass)
    abstract val amount: BigDecimal
    abstract val tip: BigDecimal
    abstract val rating: Int? // 🆕 Optional rating from user (1-5 stars, null if skipped)

    // ⭐ PROVIDER-AGNOSTIC MERCHANT TRACKING (2025-01-11)
    // ✅ NULLABLE: null for cash payments (proper reconciliation separation)
    abstract val merchantAccountId: String?  // 🆕 PRIMARY: Merchant account ID (null = cash payment, no processor)
    abstract val blumonSerialNumber: String // ⚠️ LEGACY: Blumon-specific serial (deprecated, kept for fallback)

    // ⭐ Device Serial Number for Terminal attribution (2026-01-08)
    // Links payment to the Terminal that processed it (for device-based reporting)
    // This is the Terminal.serialNumber (e.g., "AVQD-2841548417"), NOT blumonSerialNumber
    abstract val deviceSerialNumber: String?

    /**
     * Fast Payment: Pago directo sin orden existente.
     *
     * El backend creará automáticamente una orden virtual con:
     * - orderNumber: últimos 8 dígitos del timestamp
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
        override val shiftId: String? = null, // 🆕 CRITICAL: Shift ID for reconciliation (required for production, null for testing)
        override val amount: BigDecimal,
        override val tip: BigDecimal = BigDecimal.ZERO,
        override val rating: Int? = null, // 🆕 Optional rating (1-5 stars, null if skipped)
        override val merchantAccountId: String?, // ✅ NULLABLE: null for cash (no processor, proper reconciliation)
        override val blumonSerialNumber: String = "", // ⚠️ LEGACY: Blumon serial (deprecated)
        override val deviceSerialNumber: String? = null, // ⭐ Terminal attribution (2026-01-08)
        // 📸 PRE-PAYMENT VERIFICATION (2025-01-14)
        // Order reference generated ONCE when entering VerifyingPrePayment state
        // Ensures Firebase photos match the order number created in backend
        val orderReference: String? = null, // e.g., "87654321"
        // Firebase Storage URLs of verification photos (uploaded before payment)
        val verificationPhotos: List<String> = emptyList(),
        // Scanned barcodes from verification screen
        val verificationBarcodes: List<String> = emptyList(),
        // 💸 Blumon Operation Number (2025-12-16) - For refunds without webhook
        // This comes from response.operation in SaleIccResponse
        val blumonOperationNumber: Int? = null,
        // 📸 NON-BLOCKING PROOF-OF-SALE (2026-03-10)
        val isPortabilidad: Boolean = false,
        val serialNumbers: List<String> = emptyList(), // ICCID(s) from scanned SIMs
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
        override val shiftId: String? = null, // 🆕 CRITICAL: Shift ID for reconciliation (required for production, null for testing)
        val orderId: String,
        override val amount: BigDecimal,
        override val tip: BigDecimal = BigDecimal.ZERO,
        override val rating: Int? = null, // 🆕 Optional rating (1-5 stars, null if skipped)
        override val merchantAccountId: String?, // ✅ NULLABLE: null for cash (no processor, proper reconciliation)
        override val blumonSerialNumber: String = "", // ⚠️ LEGACY: Blumon serial (deprecated)
        override val deviceSerialNumber: String? = null, // ⭐ Terminal attribution (2026-01-08)
        // ⭐ SPLIT PAYMENT FIELDS
        val splitType: SplitType = SplitType.FULLPAYMENT,
        val paidProductIds: List<String> = emptyList(), // Product IDs for PERPRODUCT mode
        val equalPartsPartySize: Int? = null, // Total people for EQUALPARTS mode
        val equalPartsPayedFor: Int? = null, // How many parts being paid now
        // 💸 Blumon Operation Number (2025-12-16) - For refunds without webhook
        // This comes from response.operation in SaleIccResponse
        val blumonOperationNumber: Int? = null,
        // 📸 NON-BLOCKING PROOF-OF-SALE (2026-03-10)
        val isPortabilidad: Boolean = false,
        val serialNumbers: List<String> = emptyList(),
    ) : PaymentContext()

    /**
     * Refund Payment: Devolución de un pago existente.
     *
     * Procesa un reembolso al cliente por un pago previamente completado.
     * Usa TransType.REFUND con el SDK de Blumon.
     *
     * **Flujo Square Pattern:**
     * 1. Usuario navega a historial de pagos (PaymentsScreen)
     * 2. Selecciona un pago → ve detalles (PaymentDetailBottomSheet)
     * 3. Toca "Reembolso" → confirma monto/razón (RefundConfirmationScreen)
     * 4. SDK procesa con TransType.REFUND → Dinero regresa al cliente
     *
     * **Multi-Merchant Critical:**
     * ⚠️ El reembolso DEBE usar el mismo merchantAccountId del pago original.
     * MultiMerchantSDKManager debe cambiar al merchant correcto ANTES de procesar.
     *
     * **Endpoint backend:**
     * POST /tpv/venues/{venueId}/refunds
     *
     * @param venueId ID del venue actual
     * @param staffId ID del staff que procesa el reembolso
     * @param shiftId ID del turno actual (para reconciliación)
     * @param amount Monto a reembolsar (puede ser menor que original para parcial)
     * @param originalPaymentId ID del pago original siendo reembolsado
     * @param originalOrderId ID de la orden original (si aplica)
     * @param originalTotalAmount Monto total del pago original (para validación)
     * @param refundReason Razón del reembolso
     * @param merchantAccountId MUST match original payment (multi-merchant routing)
     * @param blumonSerialNumber Terminal serial for SDK initialization
     * @param originalOperationNumber CRITICAL: Blumon operation number for CancelIcc (from webhook)
     */
    data class RefundPayment(
        override val venueId: String,
        override val staffId: String,
        override val shiftId: String? = null,
        override val amount: BigDecimal, // Monto a reembolsar (puede ser parcial)
        override val tip: BigDecimal = BigDecimal.ZERO, // Tip to refund (usually 0 for partial, original tip for full)
        override val rating: Int? = null, // Not used for refunds
        override val merchantAccountId: String?, // ⚠️ CRITICAL: MUST match original payment!
        override val blumonSerialNumber: String, // ⚠️ REQUIRED: For SDK merchant switch
        override val deviceSerialNumber: String? = null, // ⭐ Terminal attribution (2026-01-08)

        // Refund-specific fields
        val originalPaymentId: String, // Payment being refunded
        val originalOrderId: String?, // Order (if order payment, null for fast payment)
        val originalTotalAmount: BigDecimal, // Original payment total (for validation)
        val refundReason: RefundReason, // Why the refund is being processed
        val isPartialRefund: Boolean = false, // True if refunding less than original amount

        // 🎫 CRITICAL: Blumon Operation Number (2025-12-XX)
        // This is passed as operationID to CancelIccUseCase
        // Without this, Blumon cannot identify which transaction to cancel/refund
        //
        // ⚠️ IMPORTANT: This is the OPERATION NUMBER (small int like 75656), NOT referenceNumber!
        // referenceNumber (12-digit SDK value like "195978383755") exceeds Integer.MAX_VALUE
        // operationNumber comes from Blumon webhook (processorData.blumonOperationNumber)
        val originalOperationNumber: Int, // e.g., 75656 (fits in Integer)
    ) : PaymentContext() {

        /**
         * Validate refund amount doesn't exceed original
         */
        fun isValidRefundAmount(): Boolean {
            return amount <= originalTotalAmount && amount > BigDecimal.ZERO
        }

        /**
         * Calculate remaining refundable amount
         * (Used for partial refunds - in future, track already refunded amounts)
         */
        fun getRemainingRefundable(): BigDecimal {
            return originalTotalAmount - amount
        }
    }
}

/**
 * Reasons for processing a refund
 *
 * Following PCI compliance and payment processor requirements,
 * refunds should have documented reasons.
 */
enum class RefundReason(val displayName: String, val description: String) {
    /**
     * Customer requested the refund
     * Most common reason - customer changed mind, dissatisfied, etc.
     */
    CUSTOMER_REQUEST("Solicitud del cliente", "El cliente solicitó el reembolso"),

    /**
     * Duplicate charge was made
     * Technical issue - customer was charged twice for same transaction
     */
    DUPLICATE_CHARGE("Cargo duplicado", "Se cobró dos veces por error"),

    /**
     * Product or service was defective
     * Quality issue - item damaged, wrong order, service not provided
     */
    PRODUCT_ISSUE("Problema con producto/servicio", "Producto defectuoso o servicio no cumplido"),

    /**
     * Incorrect amount was charged
     * Pricing error - wrong price, wrong items, calculation error
     */
    INCORRECT_AMOUNT("Monto incorrecto", "Se cobró un monto erróneo"),

    /**
     * Fraud or unauthorized transaction
     * Security issue - requires additional verification
     */
    FRAUD_SUSPECTED("Fraude sospechado", "Transacción no autorizada o fraudulenta"),

    /**
     * Other reason not listed
     * Catch-all - should include notes for audit trail
     */
    OTHER("Otro motivo", "Otra razón no especificada");

    companion object {
        fun fromString(value: String): RefundReason {
            return values().find { it.name.equals(value, ignoreCase = true) } ?: OTHER
        }
    }
}
