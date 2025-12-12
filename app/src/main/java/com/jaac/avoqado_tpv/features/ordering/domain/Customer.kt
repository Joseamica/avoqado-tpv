package com.jaac.avoqado_tpv.features.ordering.domain

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Customer domain model
 *
 * Represents a customer record for TPV operations (checkout, loyalty).
 * Optimized for fast lookup and minimal data transfer.
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
@Immutable
data class Customer(
    val id: String,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?,
    val loyaltyPoints: Int,
    val totalVisits: Int,
    val totalSpent: BigDecimal,
    val customerGroup: CustomerGroup?
) {
    /**
     * Display name for customer
     * Formats as "FirstName LastName" or "Phone" if no name
     */
    val displayName: String
        get() {
            val fullName = listOfNotNull(firstName, lastName).joinToString(" ")
            return fullName.ifBlank { phone ?: email ?: "Cliente" }
        }

    /**
     * Short display name for compact UI (chips, badges)
     * Returns first name only, or "Cliente" if unavailable
     */
    val shortName: String
        get() = firstName ?: phone?.takeLast(4)?.let { "...$it" } ?: "Cliente"

    /**
     * Formatted loyalty points display
     * Example: "1,250 pts"
     */
    val formattedLoyaltyPoints: String
        get() = String.format("%,d pts", loyaltyPoints)

    /**
     * Formatted total spent display
     * Example: "$1,234.56"
     */
    val formattedTotalSpent: String
        get() = String.format("$%,.2f", totalSpent)

    /**
     * Is this a frequent customer? (10+ visits)
     */
    val isFrequent: Boolean
        get() = totalVisits >= 10

    /**
     * Is this a VIP customer? (based on group or spend threshold)
     */
    val isVip: Boolean
        get() = customerGroup?.name?.contains("VIP", ignoreCase = true) == true ||
                totalSpent >= BigDecimal(10000)
}

/**
 * Customer Group domain model
 *
 * Represents customer segments (VIP, Employee, Regular, etc.)
 * Used for targeted discounts and service differentiation.
 */
@Immutable
data class CustomerGroup(
    val id: String,
    val name: String,
    val color: String?
) {
    /**
     * Get badge color or default
     */
    val badgeColor: String
        get() = color ?: "#6366F1"  // Default indigo

    /**
     * Display emoji based on group name
     */
    val emoji: String
        get() = when {
            name.contains("VIP", ignoreCase = true) -> "⭐"
            name.contains("Employee", ignoreCase = true) ||
            name.contains("Empleado", ignoreCase = true) -> "👔"
            name.contains("Regular", ignoreCase = true) -> "👤"
            name.contains("New", ignoreCase = true) ||
            name.contains("Nuevo", ignoreCase = true) -> "🆕"
            else -> "👥"
        }
}

/**
 * Customer lookup result for search operations
 */
sealed class CustomerSearchState {
    data object Idle : CustomerSearchState()
    data object Loading : CustomerSearchState()
    data class Success(val customers: List<Customer>) : CustomerSearchState()
    data class Error(val message: String) : CustomerSearchState()
}

/**
 * Order-Customer association domain model
 *
 * Represents the junction between an order and a customer (many-to-many).
 * Allows multiple customers per order for visit tracking and loyalty.
 * First customer added becomes primary and receives loyalty points.
 */
@Immutable
data class OrderCustomer(
    val id: String,
    val orderId: String,
    val customerId: String,
    val customer: Customer,
    val isPrimary: Boolean,
    val addedAt: LocalDateTime
)
