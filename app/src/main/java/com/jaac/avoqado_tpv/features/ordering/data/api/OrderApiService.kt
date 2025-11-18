package com.jaac.avoqado_tpv.features.ordering.data.api

import com.jaac.avoqado_tpv.features.ordering.data.dto.AddItemsRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApiResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateOrderRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.OrderDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

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
}
