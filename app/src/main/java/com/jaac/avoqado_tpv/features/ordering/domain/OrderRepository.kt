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
     * @param skipCaching If true, skip caching order to local DB (used by OrderSyncCoordinator)
     * @param source Order source: "TPV" for regular, "KIOSK" for self-service.
     *               KIOSK orders are excluded from pay-later lists when abandoned.
     * @return Result with created Order
     *
     * Backend: POST /tpv/venues/{venueId}/orders
     */
    suspend fun createOrder(
        venueId: String,
        tableId: String?,
        covers: Int,
        waiterId: String?,
        orderType: OrderType,
        skipCaching: Boolean = false,
        source: String = "TPV",
        externalId: String? = null
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
        currentVersion: Int,
        skipCaching: Boolean = false
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

    /**
     * Update guest information on order
     *
     * Updates covers (party size), customer name, phone, and special requests.
     * Used for both DINE_IN (covers, allergies) and TAKEOUT (contact info).
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param covers Number of people (nullable - only update if provided)
     * @param customerName Customer name (nullable)
     * @param customerPhone Customer phone (nullable)
     * @param specialRequests Special dietary requirements or requests (nullable)
     * @return Result with updated Order
     *
     * Backend: PATCH /tpv/venues/{venueId}/orders/{orderId}/guest
     *
     * Request body:
     * {
     *   "covers": 4,
     *   "customerName": "John Doe",
     *   "customerPhone": "555-1234",
     *   "specialRequests": "No onions, extra sauce"
     * }
     *
     * Use cases:
     * - DINE_IN: Update party size when guests arrive
     * - TAKEOUT: Add customer contact for order pickup
     * - Both: Record allergies or special requests
     */
    suspend fun updateGuest(
        venueId: String,
        orderId: String,
        covers: Int? = null,
        customerName: String? = null,
        customerPhone: String? = null,
        specialRequests: String? = null,
        customerId: String? = null
    ): Result<Order>

    /**
     * Comp items or entire order
     *
     * Complimentary (free) items or entire order for service recovery,
     * manager discretion, or compensation.
     * Creates audit trail record (OrderAction).
     *
     * @param venueId Tenant isolation
     * @param orderId Order to comp
     * @param itemIds Items to comp (empty list = comp entire order)
     * @param reason Comp reason (required for audit trail)
     * @param staffId Staff member authorizing comp
     * @param notes Additional notes (optional)
     * @return Result with updated Order (discountAmount increased)
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/comp
     *
     * Request body:
     * {
     *   "itemIds": ["item_1", "item_2"],  // Empty array = comp entire order
     *   "reason": "Service recovery - food quality issue",
     *   "staffId": "staff_123",
     *   "notes": "Customer complained about cold food"
     * }
     *
     * Use cases:
     * - Service recovery (food quality, long wait times)
     * - Manager discretion (special occasions, loyal customers)
     * - Compensation for mistakes
     *
     * Error cases:
     * - 400: Order already paid
     * - 404: Order not found
     */
    suspend fun compItems(
        venueId: String,
        orderId: String,
        itemIds: List<String>,
        reason: String,
        staffId: String,
        notes: String? = null
    ): Result<Order>

    /**
     * Void specific items from order
     *
     * Remove items with audit trail (different from removeItem - this logs reason).
     * Used when customer changes mind, item entered incorrectly, or kitchen cannot fulfill.
     * Uses optimistic concurrency control.
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param itemIds Items to void
     * @param reason Void reason (required for audit trail)
     * @param staffId Staff member authorizing void
     * @param currentVersion Current version for concurrency check
     * @return Result with updated Order (items removed, totals recalculated)
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/void
     *
     * Request body:
     * {
     *   "itemIds": ["item_1"],
     *   "reason": "Customer changed mind",
     *   "staffId": "staff_123",
     *   "expectedVersion": 2
     * }
     *
     * Use cases:
     * - Customer changed mind
     * - Item entered incorrectly
     * - Kitchen cannot fulfill (out of stock)
     *
     * Error cases:
     * - 400: Order already paid
     * - 409: Version conflict (order was modified)
     * - 404: Order not found
     */
    suspend fun voidItems(
        venueId: String,
        orderId: String,
        itemIds: List<String>,
        reason: String,
        staffId: String,
        currentVersion: Int
    ): Result<Order>

    /**
     * Apply discount to order or specific items
     *
     * Supports percentage (1-100) or fixed amount discounts.
     * Uses optimistic concurrency control.
     * Creates audit trail record (OrderAction).
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param type Discount type ("PERCENTAGE" or "FIXED_AMOUNT")
     * @param value Discount value (1-100 for percentage, dollar amount for fixed)
     * @param staffId Staff member authorizing discount
     * @param reason Discount reason (optional but recommended for audit)
     * @param itemIds Items to discount (null = order-level discount)
     * @param currentVersion Current version for concurrency check
     * @return Result with updated Order (discountAmount updated)
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/discount
     *
     * Request body (20% order-level):
     * {
     *   "type": "PERCENTAGE",
     *   "value": 20,
     *   "reason": "Happy hour discount",
     *   "staffId": "staff_123",
     *   "itemIds": null,
     *   "expectedVersion": 2
     * }
     *
     * Request body ($10 fixed discount):
     * {
     *   "type": "FIXED_AMOUNT",
     *   "value": 10,
     *   "reason": "Loyalty discount",
     *   "staffId": "staff_123",
     *   "expectedVersion": 2
     * }
     *
     * Error cases:
     * - 400: Invalid discount value
     * - 409: Version conflict
     * - 404: Order not found
     */
    suspend fun applyDiscount(
        venueId: String,
        orderId: String,
        type: String,
        value: Double,
        staffId: String,
        reason: String? = null,
        itemIds: List<String>? = null,
        currentVersion: Int
    ): Result<Order>

    // ========================================================================
    // ORDER-CUSTOMER OPERATIONS (Multi-Customer per Order)
    // ========================================================================

    /**
     * Get all customers associated with an order
     *
     * Returns list of OrderCustomer records with full Customer details.
     * First customer added is marked as isPrimary=true (receives loyalty points).
     *
     * @param venueId Tenant isolation
     * @param orderId Order to query
     * @return Result with list of OrderCustomer records
     *
     * Backend: GET /tpv/venues/{venueId}/orders/{orderId}/customers
     */
    suspend fun getOrderCustomers(
        venueId: String,
        orderId: String
    ): Result<List<OrderCustomer>>

    /**
     * Add an existing customer to an order
     *
     * Immediately associates customer with order (no extra "save" step).
     * First customer added becomes isPrimary=true.
     * Duplicate customers cannot be added to same order.
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param customerId Customer to add
     * @return Result with updated list of OrderCustomer records
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/customers
     *
     * Use cases:
     * - Click on customer search result → immediate add
     * - Multiple customers per order for visit tracking
     */
    suspend fun addCustomerToOrder(
        venueId: String,
        orderId: String,
        customerId: String
    ): Result<List<OrderCustomer>>

    /**
     * Remove a customer from an order
     *
     * Removes association but does NOT delete the Customer record.
     * If removed customer was isPrimary, promotes next oldest to primary.
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param customerId Customer to remove
     * @return Result with updated list of OrderCustomer records
     *
     * Backend: DELETE /tpv/venues/{venueId}/orders/{orderId}/customers/{customerId}
     *
     * Use cases:
     * - Click (X) on customer chip in GuestTab
     * - Correct wrong customer selection
     */
    suspend fun removeCustomerFromOrder(
        venueId: String,
        orderId: String,
        customerId: String
    ): Result<List<OrderCustomer>>

    /**
     * Create a new customer and add to order in one operation
     *
     * For quick inline customer creation during checkout.
     * At least one of firstName, phone, or email is required.
     *
     * @param venueId Tenant isolation
     * @param orderId Order to modify
     * @param firstName Customer first name (optional)
     * @param phone Customer phone (optional)
     * @param email Customer email (optional)
     * @return Result with updated list of OrderCustomer records
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/customers/create
     *
     * Use cases:
     * - Inline customer creation in GuestTab
     * - Quick registration for loyalty program
     */
    suspend fun createAndAddCustomerToOrder(
        venueId: String,
        orderId: String,
        firstName: String?,
        phone: String?,
        email: String?
    ): Result<List<OrderCustomer>>
}

/**
 * Request to add item to order
 *
 * Used in addItemsToOrder() to specify what products to add.
 */
data class AddOrderItemRequest(
    val productId: String,
    val quantity: Int,
    val notes: String? = null,
    val modifierIds: List<String>? = null,
    val externalId: String? = null
) {
    init {
        require(quantity > 0) { "Quantity must be greater than 0" }
        require(productId.isNotBlank()) { "Product ID cannot be blank" }
    }
}
