package com.jaac.avoqado_tpv.features.ordering.data.mappers

import com.jaac.avoqado_tpv.features.ordering.data.dto.OrderDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.OrderItemDetailDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.ProductModifierDto
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import java.math.BigDecimal
import java.time.Instant

/**
 * Map OrderDto to Order domain model.
 */
fun OrderDto.toOrder(): Order {
    return Order(
        id = id,
        orderNumber = orderNumber,
        venueId = venueId,
        tableId = tableId,
        tableName = tableName,
        covers = covers ?: 0,
        waiterId = waiterId,
        waiterName = waiterName,
        customerName = customerName,  // ✅ Guest name
        customerPhone = customerPhone,  // ✅ Guest phone
        specialRequests = specialRequests,  // ✅ Dietary restrictions/allergies
        status = status.toOrderStatus(),
        kitchenStatus = kitchenStatus?.toKitchenStatus() ?: KitchenStatus.PENDING,  // ✅ FALLBACK
        paymentStatus = paymentStatus.toPaymentStatus(),
        orderType = orderType.toOrderType(),
        items = items.map { it.toOrderItem() },
        subtotal = BigDecimal(subtotal.toString()),
        discountAmount = BigDecimal((discountAmount ?: 0.0).toString()),  // ✅ Map discountAmount with fallback
        tax = BigDecimal(taxAmount.toString()),
        total = BigDecimal(total.toString()),
        paidAmount = BigDecimal((paidAmount ?: 0.0).toString()),  // ⭐ Partial payment tracking
        remainingBalance = BigDecimal((remainingBalance ?: total).toString()),  // ⭐ Fallback to total if not set
        notes = notes,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
        version = version,
        lastSplitType = lastSplitType?.toSplitType(),  // ⭐ Split type restriction (nullable for graceful degradation)
        paidItemIds = paidItemIds ?: emptyList(),  // ⭐ Items already paid (for SplitByProduct screen)
        discounts = orderDiscounts?.map { it.toOrderDiscount() } ?: emptyList(),  // 🎟️ Applied discounts
        orderCustomers = orderCustomers?.map { it.toOrderCustomer() } ?: emptyList()  // 💳 Customers linked to order (for pay-later)
    )
}

/**
 * Map OrderItemDetailDto to OrderItem domain model.
 */
fun OrderItemDetailDto.toOrderItem(): OrderItem {
    return OrderItem(
        id = id,
        orderId = orderId,
        productId = productId ?: "",  // ✅ Handle nullable productId
        productName = product?.name ?: productName ?: "(producto sin nombre)",
        productSku = product?.sku ?: productSku.orEmpty(),
        quantity = quantity,
        unitPrice = BigDecimal(unitPrice.toString()),
        totalPrice = BigDecimal(totalPrice.toString()),
        modifiers = modifiers?.map { it.toProductModifier() } ?: emptyList(),
        notes = notes,
        kitchenStatus = kitchenStatus?.toKitchenStatus() ?: KitchenStatus.PENDING,  // ✅ FALLBACK: Backend doesn't have this field
        createdAt = Instant.parse(createdAt),
        sentToKitchenAt = sentToKitchenAt?.let { Instant.parse(it) }
    )
}

/**
 * Map ProductModifierDto to ProductModifier domain model.
 */
fun ProductModifierDto.toProductModifier(): ProductModifier {
    return ProductModifier(
        id = id,
        name = name ?: "Modificador desconocido",  // ← Fallback para modifiers eliminados/sin nombre
        priceAdjustment = BigDecimal(priceAdjustment.toString()),
        type = (type ?: "MULTIPLE_CHOICE").toModifierType(),
        required = required ?: false,
        displayOrder = displayOrder ?: 0
    )
}

/**
 * Convert string to ModifierType enum.
 */
fun String.toModifierType(): com.jaac.avoqado_tpv.features.ordering.domain.ModifierType {
    return try {
        com.jaac.avoqado_tpv.features.ordering.domain.ModifierType.valueOf(this.uppercase())
    } catch (e: IllegalArgumentException) {
        com.jaac.avoqado_tpv.features.ordering.domain.ModifierType.MULTIPLE_CHOICE  // Default fallback
    }
}

/**
 * Convert string to OrderStatus enum.
 */
fun String.toOrderStatus(): OrderStatus {
    return try {
        OrderStatus.valueOf(this.uppercase())
    } catch (e: IllegalArgumentException) {
        OrderStatus.OPEN  // Default fallback
    }
}

/**
 * Convert string to KitchenStatus enum.
 */
fun String.toKitchenStatus(): KitchenStatus {
    return try {
        KitchenStatus.valueOf(this.uppercase())
    } catch (e: IllegalArgumentException) {
        KitchenStatus.PENDING  // Default fallback
    }
}

/**
 * Convert string to PaymentStatus enum.
 */
fun String.toPaymentStatus(): PaymentStatus {
    return try {
        PaymentStatus.valueOf(this.uppercase())
    } catch (e: IllegalArgumentException) {
        PaymentStatus.PENDING  // Default fallback
    }
}

/**
 * Convert string to OrderType enum.
 */
fun String.toOrderType(): OrderType {
    return try {
        OrderType.valueOf(this.uppercase())
    } catch (e: IllegalArgumentException) {
        OrderType.DINE_IN  // Default fallback
    }
}

/**
 * Convert string to SplitType enum.
 * Returns null if invalid (graceful degradation - shows all options)
 */
fun String.toSplitType(): SplitType? {
    return try {
        SplitType.valueOf(this.uppercase())
    } catch (e: IllegalArgumentException) {
        null  // Return null to allow all split options (graceful degradation)
    }
}
