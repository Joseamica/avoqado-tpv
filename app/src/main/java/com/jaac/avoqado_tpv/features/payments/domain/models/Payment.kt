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
    val tableName: String?
) {
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
    fun formatTimestamp(zoneId: ZoneId = ZoneId.systemDefault()): String {
        val formatter = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm", Locale("es", "MX"))
            .withZone(zoneId)
        return formatter.format(createdAt)
    }

    /**
     * Format date only (for grouping)
     * Example: "15 Ene 2025"
     */
    fun formatDate(zoneId: ZoneId = ZoneId.systemDefault()): String {
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
}

/**
 * Payment Method Enum
 *
 * Matches backend PaymentMethod enum.
 */
enum class PaymentMethod(val label: String) {
    CASH("Efectivo"),
    CARD("Tarjeta"),
    VOUCHER("Vale"),
    OTHER("Otro");

    companion object {
        fun fromString(value: String): PaymentMethod {
            return values().firstOrNull { it.name == value.uppercase() } ?: OTHER
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
