package com.jaac.avoqado_tpv.features.ordering.domain.usecases

import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case: Get order by ID
 *
 * Fetches order details from backend.
 * Used when:
 * - Navigating from Floor Plan → Menu screen (existing order)
 * - Refreshing order after Socket.IO event
 * - Recovering from version conflict (optimistic concurrency)
 *
 * Single Responsibility: Retrieve order with proper error handling and logging.
 *
 * @see OrderRepository.getOrder
 */
class GetOrderUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /**
     * Execute use case
     *
     * @param venueId Tenant isolation (CRITICAL for security)
     * @param orderId Order unique identifier
     * @return Result with Order or error
     *
     * Error handling:
     * - 404: Order not found → User-friendly message
     * - 403: Access denied → Security log
     * - 500: Server error → Generic error message
     * - Network: Connection error → User-friendly message
     */
    suspend operator fun invoke(
        venueId: String,
        orderId: String
    ): Result<Order> {
        Timber.d("📋 [GetOrderUseCase] Fetching order: $orderId (venue: $venueId)")

        return try {
            val result = orderRepository.getOrder(venueId, orderId)

            result.onSuccess { order ->
                Timber.i("✅ [GetOrderUseCase] Order fetched successfully: ${order.orderNumber}")
                Timber.d("   Items: ${order.items.size}, Total: ${order.total}, Version: ${order.version}")
            }.onFailure { error ->
                Timber.e(error, "❌ [GetOrderUseCase] Failed to fetch order: $orderId")
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "❌ [GetOrderUseCase] Unexpected error fetching order: $orderId")
            Result.failure(e)
        }
    }
}

/**
 * Use case: Get order by table ID
 *
 * Finds the currently OPEN order for a specific table.
 * Returns null if no open order exists (table is free).
 *
 * Used when:
 * - User taps table in Floor Plan → Check if order exists
 *   - If exists → Load existing order in Menu screen
 *   - If null → Create new order
 *
 * @see OrderRepository.getOrderByTable
 */
class GetOrderByTableUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /**
     * Execute use case
     *
     * @param venueId Tenant isolation
     * @param tableId Table unique identifier
     * @return Result with Order or null
     */
    suspend operator fun invoke(
        venueId: String,
        tableId: String
    ): Result<Order?> {
        Timber.d("🪑 [GetOrderByTableUseCase] Fetching order for table: $tableId (venue: $venueId)")

        return try {
            val result = orderRepository.getOrderByTable(venueId, tableId)

            result.onSuccess { order ->
                if (order != null) {
                    Timber.i("✅ [GetOrderByTableUseCase] Found existing order: ${order.orderNumber}")
                } else {
                    Timber.i("✅ [GetOrderByTableUseCase] No open order for table: $tableId")
                }
            }.onFailure { error ->
                Timber.e(error, "❌ [GetOrderByTableUseCase] Failed to fetch order for table: $tableId")
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "❌ [GetOrderByTableUseCase] Unexpected error fetching order for table: $tableId")
            Result.failure(e)
        }
    }
}

/**
 * Use case: Get all orders for venue
 *
 * Used in Órdenes tab to display list of orders.
 * Can filter by status (OPEN, IN_PROGRESS, COMPLETED).
 *
 * @see OrderRepository.getOrders
 */
class GetOrdersUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /**
     * Execute use case
     *
     * @param venueId Tenant isolation
     * @param status Optional filter by status
     * @return Result with list of orders
     */
    suspend operator fun invoke(
        venueId: String,
        status: com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus? = null
    ): Result<List<Order>> {
        val statusFilter = status?.displayName ?: "All"
        Timber.d("📋 [GetOrdersUseCase] Fetching orders for venue: $venueId (filter: $statusFilter)")

        return try {
            val result = orderRepository.getOrders(venueId, status)

            result.onSuccess { orders ->
                Timber.i("✅ [GetOrdersUseCase] Fetched ${orders.size} orders")
            }.onFailure { error ->
                Timber.e(error, "❌ [GetOrdersUseCase] Failed to fetch orders")
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "❌ [GetOrdersUseCase] Unexpected error fetching orders")
            Result.failure(e)
        }
    }
}
