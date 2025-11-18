package com.jaac.avoqado_tpv.features.ordering.domain

/**
 * Repository interface for Order operations
 *
 * Defines contract for order CRUD operations.
 * Implementation in data layer (OrderRepositoryImpl.kt) handles:
 * - REST API calls
 * - DTO ↔ Domain mapping
 * - Error handling
 * - Optimistic concurrency control
 *
 * @see com.jaac.avoqado_tpv.features.ordering.data.repository.OrderRepositoryImpl
 */
interface OrderRepository {

    /**
     * Get order by ID
     *
     * @param venueId Tenant isolation (CRITICAL for security)
     * @param orderId Order unique identifier
     * @return Result with Order or error
     *
     * Backend: GET /tpv/venues/{venueId}/orders/{orderId}
     *
     * Error cases:
     * - 404: Order not found
     * - 403: Order belongs to different venue (security)
     * - 500: Server error
     */
    suspend fun getOrder(
        venueId: String,
        orderId: String
    ): Result<Order>

    /**
     * Get order by table ID
     *
     * Finds the currently OPEN order for a specific table.
     * Used when navigating from Floor Plan → Menu screen.
     *
     * @param venueId Tenant isolation
     * @param tableId Table unique identifier
     * @return Result with Order or null if no open order exists
     *
     * Backend: GET /tpv/venues/{venueId}/orders?tableId={tableId}&status=OPEN
     */
    suspend fun getOrderByTable(
        venueId: String,
        tableId: String
    ): Result<Order?>

    /**
     * Get all orders for venue
     *
     * Used in Órdenes tab to show list of all orders.
     *
     * @param venueId Tenant isolation
     * @param status Optional filter by status (OPEN, IN_PROGRESS, COMPLETED)
     * @return Result with list of orders
     *
     * Backend: GET /tpv/venues/{venueId}/orders?status={status}
     */
    suspend fun getOrders(
        venueId: String,
        status: OrderStatus? = null
    ): Result<List<Order>>

    /**
     * Create new order
     *
     * Used when starting new order from Floor Plan (free table).
     *
     * @param venueId Tenant isolation
     * @param tableId Table where order is placed
     * @param covers Number of people (optional)
     * @param waiterId Waiter who created order
     * @param orderType DINE_IN, TAKEOUT, etc.
     * @return Result with created Order
     *
     * Backend: POST /tpv/venues/{venueId}/orders
     */
    suspend fun createOrder(
        venueId: String,
        tableId: String?,
        covers: Int,
        waiterId: String?,
        orderType: OrderType
    ): Result<Order>

    /**
     * Add items to existing order
     *
     * CRITICAL: Uses optimistic concurrency control with version field.
     * If version mismatch (another terminal modified order), returns 409 Conflict.
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param items List of items to add (productId, quantity, notes)
     * @param currentVersion Current version from UI state (for concurrency check)
     * @return Result with updated Order (new version incremented)
     *
     * Backend: PATCH /tpv/venues/{venueId}/orders/{orderId}/items
     *
     * Request body:
     * {
     *   "items": [
     *     { "productId": "prod_1", "quantity": 2, "notes": "Sin aceitunas" }
     *   ],
     *   "version": 3
     * }
     *
     * Response:
     * {
     *   "order": { ... },
     *   "version": 4  // Incremented
     * }
     *
     * Error cases:
     * - 409 Conflict: Version mismatch (auto-refresh + retry in ViewModel)
     * - 404: Order not found
     * - 400: Invalid product IDs
     */
    suspend fun addItemsToOrder(
        venueId: String,
        orderId: String,
        items: List<AddOrderItemRequest>,
        currentVersion: Int
    ): Result<Order>

    /**
     * Remove item from order
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param orderItemId Item to remove
     * @param currentVersion Current version for concurrency check
     * @return Result with updated Order
     *
     * Backend: DELETE /tpv/venues/{venueId}/orders/{orderId}/items/{orderItemId}?version={version}
     */
    suspend fun removeOrderItem(
        venueId: String,
        orderId: String,
        orderItemId: String,
        currentVersion: Int
    ): Result<Order>

    /**
     * Update order item quantity
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param orderItemId Item to update
     * @param newQuantity New quantity (must be > 0)
     * @param currentVersion Current version for concurrency check
     * @return Result with updated Order
     *
     * Backend: PATCH /tpv/venues/{venueId}/orders/{orderId}/items/{orderItemId}
     */
    suspend fun updateOrderItemQuantity(
        venueId: String,
        orderId: String,
        orderItemId: String,
        newQuantity: Int,
        currentVersion: Int
    ): Result<Order>

    /**
     * Send order to kitchen
     *
     * Changes kitchen status from PENDING → PREPARING.
     * Emits Socket.IO event for kitchen display terminals.
     *
     * @param venueId Tenant isolation
     * @param orderId Order to send
     * @param currentVersion Current version for concurrency check
     * @return Result with updated Order
     *
     * Backend: PATCH /tpv/venues/{venueId}/orders/{orderId}/kitchen-status
     *
     * Request body:
     * {
     *   "kitchenStatus": "PREPARING",
     *   "version": 5
     * }
     *
     * Socket.IO event emitted:
     * {
     *   "event": "order_kitchen_status_changed",
     *   "orderId": "order_123",
     *   "kitchenStatus": "PREPARING"
     * }
     */
    suspend fun sendToKitchen(
        venueId: String,
        orderId: String,
        currentVersion: Int
    ): Result<Order>

    /**
     * Update order status
     *
     * @param venueId Tenant isolation
     * @param orderId Order to update
     * @param newStatus New status
     * @param currentVersion Current version for concurrency check
     * @return Result with updated Order
     *
     * Backend: PATCH /tpv/venues/{venueId}/orders/{orderId}/status
     */
    suspend fun updateOrderStatus(
        venueId: String,
        orderId: String,
        newStatus: OrderStatus,
        currentVersion: Int
    ): Result<Order>
}

/**
 * Request to add item to order
 *
 * Used in addItemsToOrder() to specify what products to add.
 */
data class AddOrderItemRequest(
    val productId: String,
    val quantity: Int,
    val notes: String? = null
) {
    init {
        require(quantity > 0) { "Quantity must be greater than 0" }
        require(productId.isNotBlank()) { "Product ID cannot be blank" }
    }
}
