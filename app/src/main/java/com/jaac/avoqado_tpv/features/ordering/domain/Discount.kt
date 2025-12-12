package com.jaac.avoqado_tpv.features.ordering.domain

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.Instant

/**
 * Discount domain model
 *
 * Represents a predefined discount available for application.
 * Venue-specific and can have conditions (time, amount, day).
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
@Immutable
data class Discount(
    val id: String,
    val name: String,
    val type: DiscountType,
    val value: BigDecimal,
    val scope: DiscountScope,
    val conditions: DiscountConditions?,
    val active: Boolean,
    val requiresAuthorization: Boolean
) {
    /**
     * Formatted value display
     * Example: "10%" for percentage, "$50.00" for fixed
     */
    val formattedValue: String
        get() = when (type) {
            DiscountType.PERCENTAGE -> "${value.toInt()}%"
            DiscountType.FIXED -> String.format("$%.2f", value)
        }

    /**
     * Display badge for discount type
     */
    val typeBadge: String
        get() = when (type) {
            DiscountType.PERCENTAGE -> "%"
            DiscountType.FIXED -> "$"
        }

    /**
     * Icon/emoji for discount
     */
    val emoji: String
        get() = when {
            name.contains("Happy Hour", ignoreCase = true) -> "🍻"
            name.contains("Empleado", ignoreCase = true) ||
            name.contains("Employee", ignoreCase = true) -> "👔"
            name.contains("VIP", ignoreCase = true) -> "⭐"
            name.contains("Cumpleaños", ignoreCase = true) ||
            name.contains("Birthday", ignoreCase = true) -> "🎂"
            name.contains("Lealtad", ignoreCase = true) ||
            name.contains("Loyalty", ignoreCase = true) -> "💎"
            requiresAuthorization -> "🔐"
            else -> "🏷️"
        }

    /**
     * Calculate discount amount for a given subtotal
     */
    fun calculateAmount(subtotal: BigDecimal): BigDecimal {
        return when (type) {
            DiscountType.PERCENTAGE -> subtotal.multiply(value).divide(BigDecimal(100))
            DiscountType.FIXED -> value.min(subtotal)  // Can't exceed subtotal
        }
    }

    /**
     * Check if discount can be applied based on conditions
     */
    fun canApply(orderTotal: BigDecimal): Boolean {
        if (!active) return false

        val conditions = this.conditions ?: return true

        // Check min order amount
        conditions.minOrderAmount?.let {
            if (orderTotal < it) return false
        }

        // Check max order amount
        conditions.maxOrderAmount?.let {
            if (orderTotal > it) return false
        }

        // TODO: Add day/time validation when needed

        return true
    }
}

/**
 * Discount Type enum
 */
enum class DiscountType {
    PERCENTAGE,  // % off (e.g., 10%)
    FIXED;       // Fixed amount (e.g., $50)

    val displayName: String
        get() = when (this) {
            PERCENTAGE -> "Porcentaje"
            FIXED -> "Monto Fijo"
        }

    companion object {
        fun fromString(value: String): DiscountType {
            return when (value.uppercase()) {
                "PERCENTAGE" -> PERCENTAGE
                "FIXED" -> FIXED
                else -> PERCENTAGE
            }
        }
    }
}

/**
 * Discount Scope enum
 */
enum class DiscountScope {
    ORDER,     // Applies to entire order
    ITEM,      // Applies to specific items
    CATEGORY;  // Applies to category

    val displayName: String
        get() = when (this) {
            ORDER -> "Toda la Orden"
            ITEM -> "Items Específicos"
            CATEGORY -> "Categoría"
        }

    companion object {
        fun fromString(value: String): DiscountScope {
            return when (value.uppercase()) {
                "ORDER" -> ORDER
                "ITEM" -> ITEM
                "CATEGORY" -> CATEGORY
                else -> ORDER
            }
        }
    }
}

/**
 * Discount Conditions
 *
 * Time and amount restrictions for discount application.
 */
@Immutable
data class DiscountConditions(
    val minOrderAmount: BigDecimal?,
    val maxOrderAmount: BigDecimal?,
    val validDays: List<DayOfWeek>?,
    val validHoursStart: String?,  // "14:00"
    val validHoursEnd: String?     // "18:00"
) {
    /**
     * Formatted conditions for display
     */
    val summary: String
        get() {
            val parts = mutableListOf<String>()

            minOrderAmount?.let { parts.add("Mín: $${it.toInt()}") }
            maxOrderAmount?.let { parts.add("Máx: $${it.toInt()}") }

            if (validHoursStart != null && validHoursEnd != null) {
                parts.add("$validHoursStart - $validHoursEnd")
            }

            validDays?.let { days ->
                if (days.isNotEmpty()) {
                    parts.add(days.joinToString(", ") { it.shortName })
                }
            }

            return parts.joinToString(" • ").ifBlank { "Sin restricciones" }
        }
}

/**
 * Day of Week enum
 */
enum class DayOfWeek {
    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;

    val displayName: String
        get() = when (this) {
            MONDAY -> "Lunes"
            TUESDAY -> "Martes"
            WEDNESDAY -> "Miércoles"
            THURSDAY -> "Jueves"
            FRIDAY -> "Viernes"
            SATURDAY -> "Sábado"
            SUNDAY -> "Domingo"
        }

    val shortName: String
        get() = when (this) {
            MONDAY -> "Lun"
            TUESDAY -> "Mar"
            WEDNESDAY -> "Mié"
            THURSDAY -> "Jue"
            FRIDAY -> "Vie"
            SATURDAY -> "Sáb"
            SUNDAY -> "Dom"
        }
}

/**
 * Coupon Code domain model
 *
 * Represents a promotional code that can be redeemed.
 */
@Immutable
data class CouponCode(
    val id: String,
    val code: String,
    val name: String,
    val type: DiscountType,
    val value: BigDecimal,
    val usesRemaining: Int?,
    val validFrom: Instant?,
    val validUntil: Instant?,
    val isValid: Boolean,
    val invalidReason: String?,
    val estimatedSavings: BigDecimal? = null
) {
    /**
     * Formatted value display
     */
    val formattedValue: String
        get() = when (type) {
            DiscountType.PERCENTAGE -> "${value.toInt()}%"
            DiscountType.FIXED -> String.format("$%.2f", value)
        }

    /**
     * Formatted estimated savings display
     */
    val formattedEstimatedSavings: String
        get() = estimatedSavings?.let { String.format("-$%.2f", it) } ?: ""

    /**
     * Display status for coupon
     */
    val statusText: String
        get() = when {
            !isValid -> invalidReason ?: "Inválido"
            usesRemaining == 0 -> "Agotado"
            usesRemaining != null -> "$usesRemaining usos restantes"
            else -> "Válido"
        }

    /**
     * Status color
     */
    val statusColor: String
        get() = when {
            !isValid -> "#EF4444"  // Red
            usesRemaining == 0 -> "#F59E0B"  // Amber
            else -> "#10B981"  // Green
        }
}

/**
 * Applied Order Discount
 *
 * Represents a discount that has been applied to an order.
 */
@Immutable
data class OrderDiscount(
    val id: String,
    val orderId: String,
    val discountId: String?,
    val couponCodeId: String?,
    val name: String,
    val type: DiscountType,
    val value: BigDecimal,
    val amount: BigDecimal,  // Calculated amount in currency
    val reason: String?,
    val appliedBy: String,
    val appliedAt: Instant
) {
    /**
     * Formatted applied amount
     */
    val formattedAmount: String
        get() = String.format("-$%.2f", amount)

    /**
     * Is this a manual/manager discount?
     */
    val isManual: Boolean
        get() = discountId == null && couponCodeId == null
}

/**
 * Discount operation state
 */
sealed class DiscountState {
    data object Idle : DiscountState()
    data object Loading : DiscountState()
    data class Success(
        val availableDiscounts: List<Discount>,
        val appliedDiscounts: List<OrderDiscount> = emptyList()
    ) : DiscountState()
    data class CouponValidated(val coupon: CouponCode) : DiscountState()
    data class Applied(val discount: OrderDiscount) : DiscountState()
    data class Error(val message: String) : DiscountState()
}
