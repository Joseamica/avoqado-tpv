package com.jaac.avoqado_tpv.core.data.local.mappers

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import java.math.BigDecimal
import java.time.Instant

/**
 * DraftOrderMappers - Conversion functions between Room entities and Domain models
 *
 * Provides extension functions to convert:
 * - DraftOrderEntity ↔ Order
 * - DraftOrderItemEntity ↔ OrderItem
 *
 * ## JSON Serialization
 * Uses Gson for ProductModifier list serialization.
 * Modifiers are stored as JSON string in Room DB.
 *
 * ## BigDecimal Handling
 * Room doesn't natively support BigDecimal, so we store as String.
 * Conversion: `BigDecimal("123.45")` ↔ `"123.45"`
 *
 * ## Instant (Timestamp) Handling
 * Room stores timestamps as Long (Unix milliseconds).
 * Conversion: `Instant.now()` ↔ `System.currentTimeMillis()`
 *
 * ## Usage
 * ```kotlin
 * // Entity → Domain
 * val order: Order = draftOrderEntity.toDomain(items)
 *
 * // Domain → Entity
 * val entity: DraftOrderEntity = order.toEntity(syncStatus = "PENDING", isServerCreated = false)
 * ```
 */

// ============================================================================
// Order: Entity → Domain
// ============================================================================

/**
 * Convert DraftOrderEntity to Domain Order.
 *
 * Combines order header (DraftOrderEntity) with items (List<DraftOrderItemEntity>).
 * Deserializes all string/enum fields to proper domain types.
 *
 * @param items List of order items (from DraftOrderItemDao.getItemsByOrder)
 * @return Domain Order model
 */
fun DraftOrderEntity.toDomain(items: List<DraftOrderItemEntity>): Order {
    return Order(
        id = id,
        orderNumber = orderNumber,
        venueId = venueId,
        tableId = tableId,
        tableName = tableName,
        covers = covers,
        waiterId = waiterId,
        waiterName = waiterName,
        customerName = customerName,
        customerPhone = customerPhone,
        specialRequests = specialRequests,
        status = OrderStatus.valueOf(status),
        kitchenStatus = KitchenStatus.valueOf(kitchenStatus),
        paymentStatus = PaymentStatus.valueOf(paymentStatus),
        orderType = OrderType.valueOf(orderType),
        items = items.map { it.toDomain() },
        subtotal = BigDecimal(subtotal),
        discountAmount = BigDecimal(discountAmount),
        tax = BigDecimal(tax),
        total = BigDecimal(total),
        notes = notes,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        version = version,
        merchantAccountId = merchantAccountId,
        merchantAccountName = merchantAccountName
    )
}

// ============================================================================
// Order: Domain → Entity
// ============================================================================

/**
 * Convert Domain Order to DraftOrderEntity.
 *
 * Serializes domain types to strings/primitives for Room DB storage.
 * DOES NOT include items (items stored separately in draft_order_items table).
 *
 * @param syncStatus Current sync status (SYNCED, PENDING, SYNCING, CONFLICT)
 * @param isServerCreated Whether order exists on backend (false = local-only)
 * @return DraftOrderEntity for Room DB
 */
fun Order.toEntity(
    syncStatus: String,
    isServerCreated: Boolean
): DraftOrderEntity {
    return DraftOrderEntity(
        id = id,
        venueId = venueId,
        orderNumber = orderNumber,
        tableId = tableId,
        tableName = tableName,
        covers = covers,
        waiterId = waiterId,
        waiterName = waiterName,
        customerName = customerName,
        customerPhone = customerPhone,
        specialRequests = specialRequests,
        status = status.name,
        kitchenStatus = kitchenStatus.name,
        paymentStatus = paymentStatus.name,
        orderType = orderType.name,
        subtotal = subtotal.toString(),
        discountAmount = discountAmount.toString(),
        tax = tax.toString(),
        total = total.toString(),
        notes = notes,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        version = version,
        syncStatus = syncStatus,
        isServerCreated = isServerCreated,
        lastSyncAt = System.currentTimeMillis(),
        conflictData = null,
        merchantAccountId = merchantAccountId,
        merchantAccountName = merchantAccountName
    )
}

// ============================================================================
// OrderItem: Entity → Domain
// ============================================================================

/**
 * Convert DraftOrderItemEntity to Domain OrderItem.
 *
 * Deserializes JSON modifiers string to List<ProductModifier>.
 * Handles nullable sentToKitchenAt timestamp.
 *
 * @return Domain OrderItem model
 */
fun DraftOrderItemEntity.toDomain(): OrderItem {
    val gson = Gson()
    val modifierListType = object : TypeToken<List<ProductModifier>>() {}.type

    return OrderItem(
        id = id,
        orderId = orderId,
        productId = productId,
        productName = productName,
        productSku = productSku,
        quantity = quantity,
        unitPrice = BigDecimal(unitPrice),
        totalPrice = BigDecimal(totalPrice),
        modifiers = if (modifiers.isNotBlank()) {
            gson.fromJson(modifiers, modifierListType)
        } else {
            emptyList()
        },
        notes = notes,
        kitchenStatus = KitchenStatus.valueOf(kitchenStatus),
        createdAt = Instant.ofEpochMilli(createdAt),
        sentToKitchenAt = sentToKitchenAt?.let { Instant.ofEpochMilli(it) }
    )
}

// ============================================================================
// OrderItem: Domain → Entity
// ============================================================================

/**
 * Convert Domain OrderItem to DraftOrderItemEntity.
 *
 * Serializes ProductModifier list to JSON string.
 * Handles nullable sentToKitchenAt timestamp.
 *
 * @param syncStatus Current sync status (SYNCED, PENDING, SYNCING, DELETED)
 * @param isServerCreated Whether item exists on backend (false = local-only)
 * @return DraftOrderItemEntity for Room DB
 */
fun OrderItem.toEntity(
    syncStatus: String,
    isServerCreated: Boolean
): DraftOrderItemEntity {
    val gson = Gson()

    return DraftOrderItemEntity(
        id = id,
        orderId = orderId,
        productId = productId,
        productName = productName,
        productSku = productSku,
        quantity = quantity,
        unitPrice = unitPrice.toString(),
        totalPrice = totalPrice.toString(),
        modifiers = if (modifiers.isNotEmpty()) {
            gson.toJson(modifiers)
        } else {
            "[]"
        },
        notes = notes,
        kitchenStatus = kitchenStatus.name,
        createdAt = createdAt.toEpochMilli(),
        sentToKitchenAt = sentToKitchenAt?.toEpochMilli(),
        syncStatus = syncStatus,
        isServerCreated = isServerCreated
    )
}

// ============================================================================
// Batch Conversions
// ============================================================================

/**
 * Convert list of DraftOrderItemEntity to Domain OrderItem list.
 *
 * @return List<OrderItem>
 */
fun List<DraftOrderItemEntity>.toDomain(): List<OrderItem> {
    return map { it.toDomain() }
}

/**
 * Convert list of Domain OrderItem to DraftOrderItemEntity list.
 *
 * @param syncStatus Sync status to apply to all items
 * @param isServerCreated Whether items exist on backend
 * @return List<DraftOrderItemEntity>
 */
fun List<OrderItem>.toEntities(
    syncStatus: String,
    isServerCreated: Boolean
): List<DraftOrderItemEntity> {
    return map { it.toEntity(syncStatus, isServerCreated) }
}
