package com.jaac.avoqado_tpv.features.ordering.data.api

import com.jaac.avoqado_tpv.features.ordering.data.dto.ApiResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.AssignTableRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.AssignTableResponseData
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateTableRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.TableDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.UpdateTablePositionRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.UpdateTableRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.UpdatedTableDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit service interface for table management endpoints.
 *
 * **Base URL:** https://api.avoqado.io/api/v1/ (production)
 *              https://humane-immortal-pika.ngrok-free.app/api/v1/ (development)
 *
 * **Authentication:** All requests require Bearer token in header.
 * ```
 * Authorization: Bearer {access_token}
 * ```
 *
 * **Available Endpoints:**
 * 1. GET /tpv/venues/{venueId}/tables - Get all tables with status
 * 2. POST /tpv/venues/{venueId}/tables/{tableId}/assign - Assign table and create/get order
 *
 * **Real-time Updates:**
 * Tables subscribe to Socket.IO events for real-time status changes:
 * - Event: "table_status_change"
 * - Payload: { tableId, tableNumber, status, orderId, orderNumber, covers, waiter }
 *
 * **Rate Limiting:**
 * - Production: 1000 requests/hour
 * - Development: 10,000 requests/hour
 *
 * **Error Codes:**
 * - 401: Token invalid or expired → Renew token
 * - 403: No permissions → Show error to user
 * - 404: Venue or Table not found → Verify IDs
 * - 429: Rate limit exceeded → Retry after 60s
 * - 500: Internal server error → Retry with backoff
 */
interface TableApiService {
    /**
     * Get all tables with their current status and active orders.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/tables
     *
     * **Backend Behavior:**
     * 1. Fetches all tables for the venue
     * 2. Includes current order info for occupied tables
     * 3. Returns table layout data (positionX, positionY, shape, rotation)
     * 4. Sorted by table number ascending
     *
     * **Success Response (200):**
     * ```json
     * [
     *   {
     *     "id": "table_1",
     *     "number": "1",
     *     "capacity": 4,
     *     "positionX": 100.0,
     *     "positionY": 200.0,
     *     "shape": "ROUND",
     *     "rotation": 0,
     *     "status": "OCCUPIED",
     *     "currentOrder": {
     *       "id": "order_123",
     *       "orderNumber": "ORD-1234567890",
     *       "covers": 2,
     *       "total": 125.50,
     *       "itemCount": 5,
     *       "items": [...],
     *       "waiter": { "id": "staff_1", "name": "Juan Pérez" },
     *       "createdAt": "2025-01-15T10:30:00Z"
     *     }
     *   },
     *   {
     *     "id": "table_2",
     *     "number": "2",
     *     "capacity": 2,
     *     "positionX": 300.0,
     *     "positionY": 200.0,
     *     "shape": "SQUARE",
     *     "rotation": 0,
     *     "status": "AVAILABLE",
     *     "currentOrder": null
     *   }
     * ]
     * ```
     *
     * **Error Responses:**
     * - 401: Unauthorized (token missing or expired)
     * - 403: Forbidden (no permissions)
     * - 404: Venue not found
     * - 429: Too many requests
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @return Response with list of tables or HTTP error
     */
    @GET("tpv/venues/{venueId}/tables")
    suspend fun getTables(
        @Path("venueId") venueId: String
    ): Response<ApiResponse<List<TableDto>>>

    /**
     * Assign a table to start a new order or return existing order.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/tables/assign
     *
     * **Backend Behavior:**
     * 1. If table has existing order → Returns that order (isNewOrder: false)
     * 2. If table is free → Creates new order and marks table OCCUPIED (isNewOrder: true)
     * 3. Validates staff exists and belongs to venue
     * 4. Emits Socket.IO event "table_status_change" for real-time updates
     *
     * **Request Body:** AssignTableRequest
     * ```json
     * {
     *   "tableId": "table_xxx",
     *   "staffId": "staff_xxx",
     *   "covers": 2
     * }
     * ```
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "order": {
     *     "id": "order_123",
     *     "venueId": "venue_xxx",
     *     "tableId": "table_1",
     *     "orderNumber": "ORD-1234567890",
     *     "covers": 2,
     *     "status": "PENDING",
     *     "paymentStatus": "PENDING",
     *     "kitchenStatus": "PENDING",
     *     "subtotal": 0,
     *     "discountAmount": 0,
     *     "taxAmount": 0,
     *     "total": 0,
     *     "servedById": "staff_xxx",
     *     "createdAt": "2025-01-15T10:30:00Z",
     *     "updatedAt": "2025-01-15T10:30:00Z"
     *   },
     *   "isNewOrder": true
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Invalid request data (e.g., covers < 1)
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Venue, Table, or Staff not found
     * - 429: Too many requests
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param request Assignment data (tableId, staffId, covers)
     * @return Response with order data or HTTP error
     */
    @POST("tpv/venues/{venueId}/tables/assign")
    suspend fun assignTable(
        @Path("venueId") venueId: String,
        @Body request: AssignTableRequest
    ): Response<ApiResponse<AssignTableResponseData>>

    /**
     * Create a new table.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/tables
     *
     * **Backend Behavior:**
     * 1. Validates venue exists
     * 2. Validates table number is unique
     * 3. Validates area exists if provided
     * 4. Creates table with default position (center) if not provided
     * 5. Returns created table
     *
     * **Request Body:** CreateTableRequest
     * ```json
     * {
     *   "number": "10",
     *   "capacity": 4,
     *   "shape": "ROUND",
     *   "rotation": 0,
     *   "positionX": 0.5,
     *   "positionY": 0.5,
     *   "areaId": "area_123"
     * }
     * ```
     *
     * **Success Response (201):**
     * ```json
     * {
     *   "success": true,
     *   "data": {
     *     "id": "table_new",
     *     "number": "10",
     *     "capacity": 4,
     *     "shape": "ROUND",
     *     "rotation": 0,
     *     "status": "AVAILABLE",
     *     "areaId": "area_123",
     *     ...
     *   },
     *   "message": "Table created successfully"
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Invalid data (duplicate number, invalid shape, capacity < 1)
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Venue or Area not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param request Table creation data
     * @return Response with created table or HTTP error
     */
    @POST("tpv/venues/{venueId}/tables")
    suspend fun createTable(
        @Path("venueId") venueId: String,
        @Body request: CreateTableRequest
    ): Response<ApiResponse<TableDto>>

    /**
     * Update table position on floor plan.
     *
     * **Endpoint:** PUT /tpv/venues/{venueId}/tables/{tableId}/position
     *
     * **Backend Behavior:**
     * 1. Validates table exists and belongs to venue
     * 2. Validates coordinates are in 0-1 range
     * 3. Updates positionX and positionY in database
     * 4. Returns updated table with new coordinates
     *
     * **Request Body:** UpdateTablePositionRequest
     * ```json
     * {
     *   "positionX": 0.35,
     *   "positionY": 0.62
     * }
     * ```
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "data": {
     *     "id": "table_1",
     *     "number": "1",
     *     "positionX": 0.35,
     *     "positionY": 0.62
     *   },
     *   "message": "Table position updated successfully"
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Invalid coordinates (not in 0-1 range)
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Venue or Table not found
     * - 429: Too many requests
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param tableId ID of the table to update
     * @param request Position update data (positionX, positionY)
     * @return Response with updated table data or HTTP error
     */
    @PUT("tpv/venues/{venueId}/tables/{tableId}/position")
    suspend fun updateTablePosition(
        @Path("venueId") venueId: String,
        @Path("tableId") tableId: String,
        @Body request: UpdateTablePositionRequest
    ): Response<ApiResponse<UpdatedTableDto>>

    /**
     * Update table properties (number, capacity, shape, rotation, areaId).
     *
     * **Endpoint:** PUT /tpv/venues/{venueId}/tables/{tableId}
     *
     * **Backend Behavior:**
     * 1. Validates table exists and belongs to venue
     * 2. Validates area exists if provided
     * 3. Validates table number is unique
     * 4. Updates only provided fields
     * 5. Returns full table data with current order info
     *
     * **Request Body:** UpdateTableRequest (all fields optional)
     * ```json
     * {
     *   "number": "5",
     *   "capacity": 6,
     *   "shape": "RECTANGLE",
     *   "rotation": 90,
     *   "areaId": "area_123"
     * }
     * ```
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "data": {
     *     "id": "table_1",
     *     "number": "5",
     *     "capacity": 6,
     *     "shape": "RECTANGLE",
     *     "rotation": 90,
     *     "status": "AVAILABLE",
     *     "areaId": "area_123",
     *     ...
     *   },
     *   "message": "Table updated successfully"
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Invalid data (duplicate number, invalid shape, etc.)
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Table, Venue, or Area not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param tableId ID of the table to update
     * @param request Update data (only include fields to update)
     * @return Response with updated table data or HTTP error
     */
    @PUT("tpv/venues/{venueId}/tables/{tableId}")
    suspend fun updateTable(
        @Path("venueId") venueId: String,
        @Path("tableId") tableId: String,
        @Body request: UpdateTableRequest
    ): Response<ApiResponse<TableDto>>

    /**
     * Delete a table (soft delete).
     *
     * **Endpoint:** DELETE /tpv/venues/{venueId}/tables/{tableId}
     *
     * **Backend Behavior:**
     * 1. Validates table exists and belongs to venue
     * 2. Checks table doesn't have active unpaid order
     * 3. Soft deletes by setting active: false
     * 4. Table is hidden from GET requests but remains in database
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "message": "Table deleted successfully"
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Table has active unpaid order
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Table or Venue not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param tableId ID of the table to delete
     * @return Response indicating success or HTTP error
     */
    @DELETE("tpv/venues/{venueId}/tables/{tableId}")
    suspend fun deleteTable(
        @Path("venueId") venueId: String,
        @Path("tableId") tableId: String
    ): Response<ApiResponse<Unit>>

    /**
     * Clear table after payment completion.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/tables/{tableId}/clear
     *
     * **Backend Behavior:**
     * 1. Validates table exists and belongs to venue
     * 2. Verifies current order is paid (PaymentStatus.PAID)
     * 3. Sets table status to AVAILABLE
     * 4. Removes currentOrderId link
     * 5. Emits Socket.IO event "table_status_change"
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "message": "Table cleared successfully"
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Table has unpaid order
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Table or Venue not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param tableId ID of the table to clear
     * @return Response indicating success or HTTP error
     */
    @POST("tpv/venues/{venueId}/tables/{tableId}/clear")
    suspend fun clearTable(
        @Path("venueId") venueId: String,
        @Path("tableId") tableId: String
    ): Response<ApiResponse<Unit>>
}
