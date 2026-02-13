package com.jaac.avoqado_tpv.features.payments.domain.models

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Payment Domain Model
 *
 * Represents a single payment transaction in the system.
 * Used for payment history display in Payments feature.
 *
 * Design Pattern: Following ReportsScreen architecture (1GB RAM optimized).
 *
 * @property id Payment unique identifier
 * @property orderId Associated order ID (nullable for fast payments)
 * @property orderNumber Order number for display
 * @property venueId Venue identifier (tenant isolation)
 * @property amount Payment amount (excluding tip)
 * @property tipAmount Tip amount (separate field for transparency)
 * @property totalAmount Total amount (amount + tipAmount)
 * @property method Payment method (CASH, CARD, VOUCHER, OTHER)
 * @property processedBy Staff member who processed the payment
 * @property createdAt Payment timestamp
 * @property status Payment status (COMPLETED, FAILED, PENDING)
 * @property tableName Table name/number (nullable for fast payments)
 */
data class Payment(
    val id: String,
    val orderId: String?,
    val orderNumber: String?,
    val venueId: String,
    val amount: BigDecimal,
    val tipAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val method: PaymentMethod,
    val processedBy: StaffSummary?,
    val createdAt: Instant,
    val status: PaymentStatus,
    val tableName: String?,

    // ⭐ MULTI-MERCHANT REFUND SUPPORT (2025-01-XX)
    // These fields are CRITICAL for processing refunds to the correct merchant
    val merchantAccountId: String? = null, // Backend MerchantAccount ID (null for cash payments)
    val blumonSerialNumber: String? = null, // Terminal serial used for payment (for SDK switch)

    // 🔐 REFUND STATUS TRACKING
    val refundedAmount: BigDecimal? = null, // Amount already refunded (null = not refunded)
    val isFullyRefunded: Boolean = false, // True if totalAmount == refundedAmount

    // 🎫 BLUMON TRANSACTION REFERENCE (for audit/tracking)
    // This is the original transaction reference from Blumon SDK SaleData
    // NOTE: This is NOT the operationID for CancelIcc! Use blumonOperationNumber instead
    // Example: "195978383755"
    val referenceNumber: String? = null,

    // 💳 BLUMON OPERATION NUMBER (CRITICAL for CancelIcc refunds!)
    // This is the small integer from Blumon webhook (processorData.blumonOperationNumber)
    // Required as operationID for CancelIccUseCase when processing refunds
    // Example: 75656
    // NOTE: referenceNumber (12-digit) is too large for Blumon's Integer API
    val blumonOperationNumber: Int? = null,

    // 💸 REFUND TRANSACTION FLAG
    // True if this transaction is a refund (money returned to customer)
    // Detected by negative totalAmount or explicit flag from backend
    val isRefundTransaction: Boolean = false,
) {
    /**
     * Check if this payment is a refund transaction
     * A refund has negative totalAmount OR explicit isRefundTransaction flag
     */
    val isRefund: Boolean
        get() = isRefundTransaction || totalAmount < BigDecimal.ZERO
    /**
     * Format payment amount for display
     * Example: "$125.50"
     */
    fun formatAmount(): String {
        return "$${amount.setScale(2, RoundingMode.HALF_UP)}"
    }

    /**
     * Format total amount for display
     * Example: "$150.00"
     */
    fun formatTotalAmount(): String {
        return "$${totalAmount.setScale(2, RoundingMode.HALF_UP)}"
    }

    /**
     * Format tip amount for display
     * Example: "$24.50"
     */
    fun formatTipAmount(): String {
        return "$${tipAmount.setScale(2, RoundingMode.HALF_UP)}"
    }

    /**
     * Format timestamp for display
     * Example: "15 Ene 2025, 14:30"
     */
    fun formatTimestamp(zoneId: ZoneId = ZoneId.of("America/Mexico_City")): String {
        val formatter = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm", Locale("es", "MX"))
            .withZone(zoneId)
        return formatter.format(createdAt)
    }

    /**
     * Format date only (for grouping)
     * Example: "15 Ene 2025"
     */
    fun formatDate(zoneId: ZoneId = ZoneId.of("America/Mexico_City")): String {
        val formatter = DateTimeFormatter
            .ofPattern("dd MMM yyyy", Locale("es", "MX"))
            .withZone(zoneId)
        return formatter.format(createdAt)
    }

    /**
     * Get display label for payment method
     */
    fun getMethodLabel(): String {
        return method.label
    }

    /**
     * Get display label for payment source
     * Example: "Mesa 5" or "Pago rápido"
     */
    fun getSourceLabel(): String {
        return if (tableName != null) {
            "Mesa $tableName"
        } else {
            "Pago rápido"
        }
    }

    /**
     * Check if this payment can be refunded
     *
     * A payment is refundable if:
     * - Status is COMPLETED (not FAILED or PENDING)
     * - Not already fully refunded
     * - Method is not CASH (cash refunds handled differently - see Square pattern)
     * - Has merchant account info (required for multi-merchant routing)
     * - Has Blumon operation number (required as operationID for CancelIcc)
     *
     * NOTE: blumonOperationNumber comes from Blumon webhook (processorData.blumonOperationNumber)
     * It's the small integer (e.g., 75656) that Blumon's API accepts.
     * referenceNumber (12-digit SDK value) is TOO LARGE for Blumon's Integer API.
     */
    fun isRefundable(): Boolean {
        return status == PaymentStatus.COMPLETED &&
                !isFullyRefunded &&
                method != PaymentMethod.CASH &&
                merchantAccountId != null &&
                blumonOperationNumber != null // Required for CancelIcc operationID
    }

    /**
     * Get the remaining amount that can be refunded
     * For partial refund support
     */
    fun getRefundableAmount(): BigDecimal {
        return if (refundedAmount != null) {
            totalAmount - refundedAmount
        } else {
            totalAmount
        }
    }

    /**
     * Format refundable amount for display
     * Example: "$125.50"
     */
    fun formatRefundableAmount(): String {
        return "$${getRefundableAmount().setScale(2, RoundingMode.HALF_UP)}"
    }

    /**
     * Get reason why payment cannot be refunded (for UI display)
     */
    fun getNonRefundableReason(): String? {
        return when {
            status != PaymentStatus.COMPLETED -> "El pago no está completado"
            isFullyRefunded -> "Este pago ya fue reembolsado"
            method == PaymentMethod.CASH -> "Los pagos en efectivo no pueden reembolsarse con tarjeta"
            merchantAccountId == null -> "Información de comercio no disponible"
            blumonOperationNumber == null -> "Número de operación Blumon no disponible (webhook pendiente)"
            else -> null
        }
    }
}

/**
 * Payment Method Enum
 *
 * Matches backend PaymentMethod enum.
 * Handles various backend formats: DEBIT, CREDIT, CARD_PRESENT, etc.
 */
enum class PaymentMethod(val label: String) {
    CASH("Efectivo"),
    CARD("Tarjeta"),
    VOUCHER("Vale"),
    OTHER("Otro");

    companion object {
        /**
         * Parse payment method from backend string.
         *
         * Handles various formats:
         * - CASH, cash → CASH
         * - CARD, DEBIT, CREDIT, DEBIT_CARD, CREDIT_CARD, CARD_PRESENT → CARD
         * - VOUCHER, vale → VOUCHER
         * - Anything else → OTHER
         */
        fun fromString(value: String): PaymentMethod {
            val normalized = value.uppercase().trim()

            return when {
                // Cash variations
                normalized == "CASH" || normalized == "EFECTIVO" -> CASH

                // Card variations (covers DEBIT, CREDIT, CARD_PRESENT, etc.)
                normalized == "CARD" ||
                normalized == "DEBIT" ||
                normalized == "CREDIT" ||
                normalized == "DEBIT_CARD" ||
                normalized == "CREDIT_CARD" ||
                normalized == "CARD_PRESENT" ||
                normalized == "CARD_NOT_PRESENT" ||
                normalized == "TARJETA" ||
                normalized.contains("CARD") ||
                normalized.contains("DEBIT") ||
                normalized.contains("CREDIT") -> CARD

                // Voucher variations
                normalized == "VOUCHER" || normalized == "VALE" -> VOUCHER

                // Fallback
                else -> OTHER
            }
        }
    }
}

/**
 * Payment Status Enum
 *
 * Matches backend payment status values.
 */
enum class PaymentStatus {
    COMPLETED,
    FAILED,
    PENDING;

    companion object {
        fun fromString(value: String): PaymentStatus {
            return values().firstOrNull { it.name == value.uppercase() } ?: PENDING
        }
    }
}

/**
 * Staff Summary
 *
 * Lightweight staff representation for payment attribution.
 */
data class StaffSummary(
    val id: String,
    val firstName: String,
    val lastName: String
) {
    fun getFullName(): String {
        return "$firstName $lastName"
    }
}

/**
 * Paginated Payment Response
 *
 * Response wrapper for paginated payment list.
 *
 * @property payments List of payments in current page
 * @property total Total count of payments (for pagination UI)
 * @property page Current page number
 * @property pageSize Number of items per page
 * @property hasMore Whether there are more pages to load
 */
data class PaginatedPayments(
    val payments: List<Payment>,
    val total: Int,
    val page: Int,
    val pageSize: Int
) {
    val hasMore: Boolean
        get() = (page * pageSize) < total
}
