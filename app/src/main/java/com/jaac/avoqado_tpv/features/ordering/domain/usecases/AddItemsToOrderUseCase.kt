package com.jaac.avoqado_tpv.features.ordering.domain.usecases

import com.jaac.avoqado_tpv.features.ordering.domain.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case: Add items to order
 *
 * Adds one or more products to an existing order with optimistic concurrency control.
 *
 * Critical Features:
 * - Validates product IDs exist (via MockProducts for now)
 * - Sends current version for backend concurrency check
 * - Returns updated order with incremented version
 * - ViewModel handles 409 Conflict with auto-refresh + retry
 *
 * Used when:
 * - User taps product in Menu screen
 * - User confirms quantity in QuantitySelector
 * - Bulk add multiple items
 *
 * Flow:
 * 1. User taps "Pizza" in product grid
 * 2. QuantitySelector appears: "Cantidad: 2"
 * 3. User confirms
 * 4. ViewModel calls: addItemsToOrder(listOf(AddOrderItemRequest("prod_pizza", 2, null)))
 * 5. Use case validates productId exists
 * 6. Repository sends PATCH request with currentVersion
 * 7. Backend validates version, adds items, increments version
 * 8. Returns updated order
 * 9. If 409 Conflict → ViewModel auto-refreshes + retries
 *
 * @see OrderRepository.addItemsToOrder
 * @see com.jaac.avoqado_tpv.features.ordering.domain.Product (MockProducts validation)
 */
class AddItemsToOrderUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /**
     * Execute use case
     *
     * @param venueId Tenant isolation (CRITICAL for security)
     * @param orderId Order to modify
     * @param items List of items to add (productId, quantity, notes)
     * @param currentVersion Current version from UI state (for concurrency check)
     * @return Result with updated Order (version incremented) or error
     *
     * Error cases:
     * - 409 Conflict: Version mismatch → ViewModel auto-refreshes
     * - 404: Order not found → User-friendly error
     * - 400: Invalid product IDs → Validation error
     * - 500: Server error → Generic error
     */
    suspend operator fun invoke(
        venueId: String,
        orderId: String,
        items: List<AddOrderItemRequest>,
        currentVersion: Int
    ): Result<Order> {
        Timber.d("➕ [AddItemsToOrderUseCase] Adding ${items.size} items to order: $orderId (version: $currentVersion)")
        items.forEach { item ->
            Timber.d("   - Product: ${item.productId}, Qty: ${item.quantity}, Notes: ${item.notes ?: "none"}")
        }

        // Validate input
        if (items.isEmpty()) {
            val error = IllegalArgumentException("Cannot add empty item list")
            Timber.e(error, "❌ [AddItemsToOrderUseCase] Validation failed: Empty item list")
            return Result.failure(error)
        }

        // Validate all product IDs exist (using MockProducts for now)
        val invalidProducts = validateProductIds(items)
        if (invalidProducts.isNotEmpty()) {
            val error = IllegalArgumentException("Invalid product IDs: ${invalidProducts.joinToString()}")
            Timber.e(error, "❌ [AddItemsToOrderUseCase] Validation failed: Invalid products")
            return Result.failure(error)
        }

        return try {
            val result = orderRepository.addItemsToOrder(venueId, orderId, items, currentVersion)

            result.onSuccess { order ->
                Timber.i("✅ [AddItemsToOrderUseCase] Items added successfully")
                Timber.d("   New version: ${order.version}, Total items: ${order.items.size}, Total: ${order.total}")
            }.onFailure { error ->
                Timber.e(error, "❌ [AddItemsToOrderUseCase] Failed to add items")
                // Note: 409 Conflict is handled by ViewModel with auto-refresh + retry
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "❌ [AddItemsToOrderUseCase] Unexpected error adding items")
            Result.failure(e)
        }
    }

    /**
     * Validate that all product IDs exist in MockProducts
     *
     * TODO: Replace with ProductRepository.getProduct() in Phase 8
     *
     * @param items Items to validate
     * @return List of invalid product IDs (empty if all valid)
     */
    private fun validateProductIds(items: List<AddOrderItemRequest>): List<String> {
        return items.mapNotNull { item ->
            val productExists = com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
                .getProductById(item.productId) != null

            if (!productExists) {
                Timber.w("⚠️ [AddItemsToOrderUseCase] Product not found: ${item.productId}")
                item.productId
            } else {
                null
            }
        }
    }
}

/**
 * Use case: Remove item from order
 *
 * Removes a specific order item.
 * Used when user taps "Eliminar" button in OrderItemCard.
 *
 * @see OrderRepository.removeOrderItem
 */
class RemoveOrderItemUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /**
     * Execute use case
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param orderItemId Item to remove
     * @param currentVersion Current version for concurrency check
     * @return Result with updated Order
     */
    suspend operator fun invoke(
        venueId: String,
        orderId: String,
        orderItemId: String,
        currentVersion: Int
    ): Result<Order> {
        Timber.d("➖ [RemoveOrderItemUseCase] Removing item: $orderItemId from order: $orderId (version: $currentVersion)")

        return try {
            val result = orderRepository.removeOrderItem(venueId, orderId, orderItemId, currentVersion)

            result.onSuccess { order ->
                Timber.i("✅ [RemoveOrderItemUseCase] Item removed successfully")
                Timber.d("   New version: ${order.version}, Remaining items: ${order.items.size}")
            }.onFailure { error ->
                Timber.e(error, "❌ [RemoveOrderItemUseCase] Failed to remove item")
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "❌ [RemoveOrderItemUseCase] Unexpected error removing item")
            Result.failure(e)
        }
    }
}

/**
 * Use case: Update order item quantity
 *
 * Changes quantity of existing order item.
 * Used when user taps +/- buttons in expanded panel.
 *
 * @see OrderRepository.updateOrderItemQuantity
 */
class UpdateOrderItemQuantityUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /**
     * Execute use case
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param orderItemId Item to update
     * @param newQuantity New quantity (must be > 0)
     * @param currentVersion Current version for concurrency check
     * @return Result with updated Order
     */
    suspend operator fun invoke(
        venueId: String,
        orderId: String,
        orderItemId: String,
        newQuantity: Int,
        currentVersion: Int
    ): Result<Order> {
        Timber.d("🔄 [UpdateOrderItemQuantityUseCase] Updating quantity: $orderItemId → $newQuantity (version: $currentVersion)")

        // Validate quantity
        if (newQuantity <= 0) {
            val error = IllegalArgumentException("Quantity must be greater than 0")
            Timber.e(error, "❌ [UpdateOrderItemQuantityUseCase] Invalid quantity: $newQuantity")
            return Result.failure(error)
        }

        return try {
            val result = orderRepository.updateOrderItemQuantity(venueId, orderId, orderItemId, newQuantity, currentVersion)

            result.onSuccess { order ->
                Timber.i("✅ [UpdateOrderItemQuantityUseCase] Quantity updated successfully")
                Timber.d("   New version: ${order.version}")
            }.onFailure { error ->
                Timber.e(error, "❌ [UpdateOrderItemQuantityUseCase] Failed to update quantity")
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "❌ [UpdateOrderItemQuantityUseCase] Unexpected error updating quantity")
            Result.failure(e)
        }
    }
}

/**
 * Use case: Send order to kitchen
 *
 * Changes kitchen status from PENDING → PREPARING.
 * Emits Socket.IO event for kitchen display.
 *
 * Used when user taps "Enviar a Cocina" button.
 *
 * @see OrderRepository.sendToKitchen
 */
class SendToKitchenUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /**
     * Execute use case
     *
     * @param venueId Tenant isolation
     * @param orderId Order to send
     * @param currentVersion Current version for concurrency check
     * @return Result with updated Order
     */
    suspend operator fun invoke(
        venueId: String,
        orderId: String,
        currentVersion: Int
    ): Result<Order> {
        Timber.d("🍳 [SendToKitchenUseCase] Sending order to kitchen: $orderId (version: $currentVersion)")

        return try {
            val result = orderRepository.sendToKitchen(venueId, orderId, currentVersion)

            result.onSuccess { order ->
                Timber.i("✅ [SendToKitchenUseCase] Order sent to kitchen successfully")
                Timber.d("   Kitchen status: ${order.kitchenStatus}")
            }.onFailure { error ->
                Timber.e(error, "❌ [SendToKitchenUseCase] Failed to send to kitchen")
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "❌ [SendToKitchenUseCase] Unexpected error sending to kitchen")
            Result.failure(e)
        }
    }
}
