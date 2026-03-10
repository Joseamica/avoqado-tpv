package com.jaac.avoqado_tpv.features.ordering.data.api

import com.jaac.avoqado_tpv.features.ordering.data.dto.AddItemsRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApiResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyDiscountRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.CompItemsRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateOrderRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.OrderDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.UpdateGuestRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.VoidItemsRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit service interface for order management endpoints.
 *
 * **Base URL:** https://api.avoqado.io/api/v1/ (production)
 *              https://humane-immortal-pika.ngrok-free.app/api/v1/ (development)
 *
 * **Authentication:** All requests require Bearer token in header.
 * ```
 * Authorization: Bearer {access_token}
 * ```
 */
interface OrderApiService {
    /**
     * Get all open orders for a venue.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/orders
     *
     * **Backend Behavior:**
     * 1. Fetches orders with paymentStatus IN ['PENDING', 'PARTIAL']
     * 2. Includes items, payments, createdBy, servedBy, table info
     * 3. Returns computed tableName field ("Mesa X")
     * 4. Ordered by createdAt DESC (most recent first)
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "data": [
     *     {
     *       "id": "cmi1yg8mw00ad9kti6j7jy7f8",
     *       "orderNumber": "ORD-001234",
     *       "tableName": "Mesa 5",
     *       "items": [...],
     *       "total": 174.00,
     *       "paymentStatus": "PENDING"
     *     }
     *   ]
     * }
     * ```
     *
     * **Error Responses:**
     * - 401: Unauthorized (token missing or expired)
     * - 500: Internal server error
     *
     * @param venueId ID of the venue (for tenant isolation)
     * @param includePayLater If true, include pay-later orders (orders with customers)
     * @param onlyPayLater If true, ONLY return pay-later orders
     * @return Response with list of open orders
     */
    @GET("tpv/venues/{venueId}/orders")
    suspend fun getOrders(
        @Path("venueId") venueId: String,
        @Query("includePayLater") includePayLater: Boolean? = null,
        @Query("onlyPayLater") onlyPayLater: Boolean? = null
    ): Response<ApiResponse<List<OrderDto>>>

    /**
     * Get order by ID with full details including items.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/orders/{orderId}
     *
     * **Backend Behavior:**
     * 1. Fetches order with all items and modifiers
     * 2. Includes product details for each item
     * 3. Includes table information if applicable
     * 4. Returns full order state (status, payment status, kitchen status)
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "id": "order_123",
     *   "orderNumber": "ORD-1234567890",
     *   "venueId": "venue_xxx",
     *   "tableId": "table_1",
     *   "items": [
     *     {
     *       "id": "item_1",
     *       "productId": "prod_1",
     *       "productName": "Pizza Margherita",
     *       "quantity": 2,
     *       "unitPrice": 180.00,
     *       "totalPrice": 360.00,
     *       "notes": null
     *     }
     *   ],
     *   "subtotal": 360.00,
     *   "taxAmount": 0.00,
     *   "total": 360.00,
     *   "status": "OPEN",
     *   "paymentStatus": "PENDING",
     *   "kitchenStatus": "PREPARING"
     * }
     * ```
     *
     * **Error Responses:**
     * - 401: Unauthorized (token missing or expired)
     * - 403: Forbidden (order belongs to different venue)
     * - 404: Order not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue (for tenant isolation)
     * @param orderId ID of the order to fetch
     * @return Response with order data or HTTP error
     */
    @GET("tpv/venues/{venueId}/orders/{orderId}")
    suspend fun getOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String
    ): Response<ApiResponse<OrderDto>>

    /**
     * Create a new order.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders
     *
     * **Backend Behavior:**
     * 1. Generates unique CUID for orderId
     * 2. Generates sequential order number (ORD-XXXXX)
     * 3. Initializes order with OPEN status
     * 4. Returns created order with empty items array
     *
     * **Success Response (201):**
     * ```json
     * {
     *   "success": true,
     *   "data": {
     *     "id": "cmi1yg8mw00ad9kti6j7jy7f8",
     *     "orderNumber": "ORD-0001234",
     *     "venueId": "venue_xxx",
     *     "tableId": null,
     *     "covers": 1,
     *     "waiterId": "staff_xxx",
     *     "status": "OPEN",
     *     "paymentStatus": "PENDING",
     *     "kitchenStatus": "PENDING",
     *     "orderType": "TAKEOUT",
     *     "items": [],
     *     "subtotal": 0.0,
     *     "taxAmount": 0.0,
     *     "total": 0.0
     *   }
     * }
     * ```
     *
     * **Error Responses:**
     * - 401: Unauthorized (token missing or expired)
     * - 400: Invalid request (missing required fields)
     * - 500: Internal server error
     *
     * @param venueId ID of the venue (for tenant isolation)
     * @param request Order creation parameters
     * @return Response with created order or HTTP error
     */
    @POST("tpv/venues/{venueId}/orders")
    suspend fun createOrder(
        @Path("venueId") venueId: String,
        @Body request: CreateOrderRequest
    ): Response<ApiResponse<OrderDto>>

    /**
     * Add items to existing order.
     *
     * **Endpoint:** PATCH /tpv/venues/{venueId}/orders/{orderId}/items
     *
     * **Backend Behavior:**
     * 1. Validates order exists and belongs to venue (tenant isolation)
     * 2. Checks version field for optimistic concurrency control
     * 3. Validates all products exist and belong to venue
     * 4. Creates new order items
     * 5. Recalculates order totals (subtotal, tax, total)
     * 6. Increments version field
     * 7. Emits Socket.IO event for real-time updates
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "data": {
     *     "id": "cmi3k125i00079kp0",
     *     "orderNumber": "ORD-1234567890",
     *     "items": [
     *       {
     *         "id": "item_1",
     *         "productId": "prod_1",
     *         "productName": "Hamburguesa",
     *         "quantity": 2,
     *         "unitPrice": 89.00,
     *         "total": 178.00
     *       }
     *     ],
     *     "subtotal": 178.00,
     *     "tax": 28.48,
     *     "total": 206.48,
     *     "version": 2
     *   }
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Invalid request (missing fields, invalid product IDs)
     * - 404: Order not found
     * - 409: Version conflict (order was modified by another request)
     * - 500: Internal server error
     *
     * @param venueId ID of the venue (for tenant isolation)
     * @param orderId ID of the order to add items to
     * @param request Request body with items array and version number
     * @return Response with updated order data or HTTP error
     */
    @PATCH("tpv/venues/{venueId}/orders/{orderId}/items")
    suspend fun addItemsToOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: AddItemsRequest
    ): Response<ApiResponse<OrderDto>>

    /**
     * Remove item from order.
     *
     * **Endpoint:** DELETE /tpv/venues/{venueId}/orders/{orderId}/items/{itemId}
     *
     * **Backend Behavior:**
     * 1. Validates order exists and belongs to venue
     * 2. Checks version field for optimistic concurrency control
     * 3. Deletes the order item (cascades to modifiers)
     * 4. Recalculates order totals
     * 5. Increments version field
     * 6. Emits Socket.IO event for real-time updates
     *
     * **Success Response (200):**
     * Returns updated order with item removed and recalculated totals.
     *
     * **Error Responses:**
     * - 400: Order already paid
     * - 404: Order or item not found
     * - 409: Version conflict
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param itemId ID of the item to remove
     * @param version Current order version for optimistic locking
     * @return Response with updated order or HTTP error
     */
    @DELETE("tpv/venues/{venueId}/orders/{orderId}/items/{itemId}")
    suspend fun removeOrderItem(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Path("itemId") itemId: String,
        @Query("version") version: Int
    ): Response<ApiResponse<OrderDto>>

    /**
     * Update guest information on order.
     *
     * **Endpoint:** PATCH /tpv/venues/{venueId}/orders/{orderId}/guest
     *
     * **Backend Behavior:**
     * 1. Validates order exists and belongs to venue
     * 2. Updates covers, customerName, customerPhone, specialRequests
     * 3. Emits Socket.IO event for real-time updates
     *
     * **Use Cases:**
     * - DINE_IN: Update covers (party size), customer name, special requests (allergies, dietary restrictions)
     * - TAKEOUT: Update customer name and phone for order pickup/delivery
     *
     * **Success Response (200):**
     * Returns updated order with guest information.
     *
     * **Error Responses:**
     * - 404: Order not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param request Guest information to update
     * @return Response with updated order or HTTP error
     */
    @PATCH("tpv/venues/{venueId}/orders/{orderId}/guest")
    suspend fun updateGuest(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: UpdateGuestRequest
    ): Response<ApiResponse<OrderDto>>

    /**
     * Comp items or entire order.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/comp
     *
     * **Backend Behavior:**
     * 1. Validates order not already paid
     * 2. Calculates comp amount (specific items or entire order)
     * 3. Increases discountAmount on order
     * 4. Creates OrderAction audit trail record
     * 5. Emits Socket.IO event for real-time updates
     *
     * **Use Cases:**
     * - Service recovery (food quality issues, long wait times)
     * - Manager discretion (special occasions, loyal customers)
     * - Compensation for mistakes
     *
     * **Success Response (200):**
     * Returns updated order with increased discount amount.
     *
     * **Error Responses:**
     * - 400: Order already paid
     * - 404: Order not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param request Comp details (itemIds, reason, staffId)
     * @return Response with updated order or HTTP error
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/comp")
    suspend fun compItems(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: CompItemsRequest
    ): Response<ApiResponse<OrderDto>>

    /**
     * Void specific items from order.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/void
     *
     * **Backend Behavior:**
     * 1. Validates order not already paid
     * 2. Checks version field for optimistic concurrency control
     * 3. Deletes specified items
     * 4. Recalculates order totals
     * 5. Creates OrderAction audit trail record
     * 6. Increments version field
     * 7. Emits Socket.IO event for real-time updates
     *
     * **Use Cases:**
     * - Customer changed mind
     * - Item entered incorrectly
     * - Kitchen cannot fulfill (out of stock)
     *
     * **Success Response (200):**
     * Returns updated order with items removed and recalculated totals.
     *
     * **Error Responses:**
     * - 400: Order already paid
     * - 404: Order not found
     * - 409: Version conflict
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param request Void details (itemIds, reason, staffId, expectedVersion)
     * @return Response with updated order or HTTP error
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/void")
    suspend fun voidItems(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: VoidItemsRequest
    ): Response<ApiResponse<OrderDto>>

    /**
     * Apply discount to order or specific items.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/discount
     *
     * **Backend Behavior:**
     * 1. Validates discount value (percentage 1-100, fixed amount > 0)
     * 2. Checks version field for optimistic concurrency control
     * 3. Calculates discount amount (item-level or order-level)
     * 4. Updates order discountAmount
     * 5. Creates OrderAction audit trail record
     * 6. Increments version field
     * 7. Emits Socket.IO event for real-time updates
     *
     * **Discount Types:**
     * - PERCENTAGE: Value between 1-100 (e.g., 20 = 20% off)
     * - FIXED_AMOUNT: Dollar amount (e.g., 10 = $10 off)
     *
     * **Success Response (200):**
     * Returns updated order with discount applied.
     *
     * **Error Responses:**
     * - 400: Invalid discount value or version conflict
     * - 404: Order not found
     * - 409: Version conflict
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param request Discount details (type, value, reason, staffId, expectedVersion)
     * @return Response with updated order or HTTP error
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/discount")
    suspend fun applyDiscount(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: ApplyDiscountRequest
    ): Response<ApiResponse<OrderDto>>
}
