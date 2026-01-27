package com.jaac.avoqado_tpv.features.ordering.domain

import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import java.math.BigDecimal
import java.time.Instant

/**
 * Order domain model
 *
 * Represents a restaurant order with items, payment, and status tracking.
 * Used across all UI states (collapsed panel, peek panel, expanded panel).
 *
 * Version field used for optimistic concurrency control (multi-terminal sync).
 */
data class Order(
    val id: String,
    val orderNumber: String,
    val venueId: String,
    val tableId: String?,
    val tableName: String?,
    val covers: Int,
    val waiterId: String?,
    val waiterName: String?,
    val customerName: String? = null,  // Guest name (TAKEOUT/DINE_IN)
    val customerPhone: String? = null,  // Guest phone (TAKEOUT)
    val specialRequests: String? = null,  // Allergies, dietary restrictions
    val status: OrderStatus,
    val kitchenStatus: KitchenStatus,
    val paymentStatus: PaymentStatus,
    val orderType: OrderType,
    val items: List<OrderItem>,
    val subtotal: BigDecimal,
    val discountAmount: BigDecimal = BigDecimal.ZERO,  // Total discounts (comps, discounts)
    val tax: BigDecimal,
    val total: BigDecimal,
    val paidAmount: BigDecimal = BigDecimal.ZERO,  // ⭐ Amount already paid (partial payment tracking)
    val remainingBalance: BigDecimal = BigDecimal.ZERO,  // ⭐ Amount left to pay (total - paidAmount)
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int,  // Optimistic concurrency control
    val merchantAccountId: String? = null,  // Last merchant used (informational; no lock enforcement)
    val merchantAccountName: String? = null,  // Display name for informational/debugging
    val lastSplitType: SplitType? = null,  // ⭐ Split type of last payment (restricts future split options)
    val paidItemIds: List<String> = emptyList(),  // ⭐ Items already paid (for SplitByProduct screen)
    val discounts: List<OrderDiscount> = emptyList(),  // 🎟️ Applied discounts on this order
    val orderCustomers: List<OrderCustomer> = emptyList()  // 💳 Customers linked to order (for pay-later tracking)
) {
    /**
     * Convenience property: Number of items in order
     * Used in collapsed panel: "Mesa 5 • 3 items • $150.00"
     */
    val itemCount: Int
        get() = items.sumOf { it.quantity }

    /**
     * Convenience property: Is order empty?
     * Used for empty state UI
     */
    val isEmpty: Boolean
        get() = items.isEmpty()

    /**
     * Convenience property: Can items be added?
     * Only allow adding items if order is OPEN or IN_PROGRESS
     */
    val canAddItems: Boolean
        get() = status == OrderStatus.OPEN || status == OrderStatus.IN_PROGRESS

    /**
     * Convenience property: Can order be sent to kitchen?
     * Only if has items and kitchen status is PENDING
     */
    val canSendToKitchen: Boolean
        get() = items.isNotEmpty() && kitchenStatus == KitchenStatus.PENDING

    /**
     * Convenience property: Can process payment?
     * Allow payments if has items, payment status is PENDING or PARTIAL, AND remainingBalance > 0
     * ✅ FIX: Added remainingBalance check to prevent paying with amount 0 during sync
     */
    val canProcessPayment: Boolean
        get() = items.isNotEmpty() &&
                paymentStatus in listOf(PaymentStatus.PENDING, PaymentStatus.PARTIAL) &&
                remainingBalance > BigDecimal.ZERO

    /**
     * Convenience property: Has remaining balance to pay?
     * True if there's still money to collect (partial payment scenario)
     */
    val hasRemainingBalance: Boolean
        get() = remainingBalance > BigDecimal.ZERO

    /**
     * Convenience property: Items that haven't been sent to kitchen yet.
     * Used for incremental kitchen ticket printing (only print new items).
     */
    val pendingKitchenItems: List<OrderItem>
        get() = items.filter { it.isPendingKitchen }

    /**
     * Convenience property: Are there any items pending to send to kitchen?
     * Used to enable/disable "Send to Kitchen" button.
     */
    val hasPendingKitchenItems: Boolean
        get() = pendingKitchenItems.isNotEmpty()

    /**
     * Convenience property: Is this a pay-later order?
     * Pay-later orders have:
     * - At least one customer linked (identified customer for tracking)
     * - PENDING/PARTIAL payment status
     * - Remaining balance > 0 (actual debt)
     * - Order not cancelled
     *
     * Used to filter orders in OrderListScreen and identify deferred payments.
     * Matches dashboard query in reports.dashboard.service.ts
     */
    val isPayLater: Boolean
        get() = orderCustomers.isNotEmpty() &&
                (paymentStatus == PaymentStatus.PENDING || paymentStatus == PaymentStatus.PARTIAL) &&
                remainingBalance > BigDecimal.ZERO &&
                status != OrderStatus.CANCELLED
}

/**
 * Order item (product + quantity + modifications)
 */
data class OrderItem(
    val id: String,
    val orderId: String,
    val productId: String,
    val productName: String,
    val productSku: String?,
    val quantity: Int,
    val unitPrice: BigDecimal,  // Base price + modifiers price (per unit)
    val totalPrice: BigDecimal,  // unitPrice * quantity
    val modifiers: List<ProductModifier> = emptyList(),  // Selected modifiers
    val notes: String?,  // "Sin aceitunas", "Punto medio"
    val kitchenStatus: KitchenStatus,
    val createdAt: Instant,
    val sentToKitchenAt: Instant?
) {
    /**
     * Convenience property: Display price per unit
     * Used in item card: "$180.00 c/u"
     */
    val formattedUnitPrice: String
        get() = String.format("$%.2f c/u", unitPrice)

    /**
     * Convenience property: Display total
     * Used in item card: "$360.00"
     */
    val formattedTotalPrice: String
        get() = String.format("$%.2f", totalPrice)

    /**
     * Convenience property: Formatted modifiers for display
     * Example: "Extra queso +$20, Sin cebolla"
     */
    val formattedModifiers: String
        get() = modifiers.joinToString(", ") { modifier ->
            if (modifier.priceAdjustment > BigDecimal.ZERO) {
                "${modifier.name} ${modifier.formattedPrice}"
            } else {
                modifier.name
            }
        }

    /**
     * Convenience property: Was this item already sent to kitchen?
     * Used for visual indicators and incremental printing.
     */
    val wasSentToKitchen: Boolean
        get() = sentToKitchenAt != null

    /**
     * Convenience property: Is this item pending kitchen send?
     * True = needs to be printed on next "Send to Kitchen"
     */
    val isPendingKitchen: Boolean
        get() = sentToKitchenAt == null
}

/**
 * Order status (overall lifecycle)
 */
enum class OrderStatus {
    DRAFT,       // Created but not confirmed (not used in MVP)
    OPEN,        // Active order, accepting items
    IN_PROGRESS, // Items being prepared
    READY,       // Ready to serve
    SERVED,      // Served to table
    COMPLETED,   // Paid and closed
    CANCELLED;   // Cancelled order

    val displayName: String
        get() = when (this) {
            DRAFT -> "Borrador"
            OPEN -> "Abierta"
            IN_PROGRESS -> "En Progreso"
            READY -> "Lista"
            SERVED -> "Servida"
            COMPLETED -> "Completada"
            CANCELLED -> "Cancelada"
        }

    val colorHex: String
        get() = when (this) {
            DRAFT -> "#9E9E9E"      // Gray
            OPEN -> "#FFC107"       // Yellow
            IN_PROGRESS -> "#2196F3" // Blue
            READY -> "#4CAF50"      // Green
            SERVED -> "#00BCD4"     // Cyan
            COMPLETED -> "#757575"  // Dark gray
            CANCELLED -> "#F44336"  // Red
        }
}

/**
 * Kitchen status (preparation lifecycle)
 */
enum class KitchenStatus {
    PENDING,   // Not sent to kitchen yet
    PREPARING, // Kitchen preparing items
    READY,     // Items ready to serve
    SERVED;    // Items served to table

    val displayName: String
        get() = when (this) {
            PENDING -> "Pendiente"
            PREPARING -> "En Cocina"
            READY -> "Lista"
            SERVED -> "Servida"
        }

    val emoji: String
        get() = when (this) {
            PENDING -> "🟡"
            PREPARING -> "🟢"
            READY -> "✅"
            SERVED -> "🍽️"
        }
}

/**
 * Payment status
 */
enum class PaymentStatus {
    PENDING,        // No payment processed
    PARTIAL,        // Partially paid (split payments)
    PAID,           // Fully paid
    REFUNDED;       // Refunded

    val displayName: String
        get() = when (this) {
            PENDING -> "Pendiente"
            PARTIAL -> "Pago Parcial"
            PAID -> "Pagada"
            REFUNDED -> "Reembolsada"
        }
}

/**
 * Order type (service model)
 */
enum class OrderType {
    DINE_IN,    // Table service
    TAKEOUT,    // Para llevar
    DELIVERY,   // Delivery (not in MVP)
    PICKUP;     // Pickup (not in MVP)

    val displayName: String
        get() = when (this) {
            DINE_IN -> "Mesa"
            TAKEOUT -> "Para Llevar"
            DELIVERY -> "Domicilio"
            PICKUP -> "Recoger"
        }

    val emoji: String
        get() = when (this) {
            DINE_IN -> "🪑"
            TAKEOUT -> "🥡"
            DELIVERY -> "🚗"
            PICKUP -> "🚶"
        }
}
