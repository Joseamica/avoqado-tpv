package com.jaac.avoqado_tpv.core.data.network

import retrofit2.Response
import retrofit2.http.*

/**
 * Avoqado API Service
 *
 * REST API endpoints for TPV operations
 *
 * **Authentication:**
 * - JWT token automatically added by AuthInterceptor
 * - venueId automatically added by TenantInterceptor
 *
 * **Base URL:**
 * - PROD: https://api.avoqado.io/api/v1/
 * - DEV: https://humane-immortal-pika.ngrok-free.app/api/v1/
 *
 * **Response Handling:**
 * Use Result<T> wrapper in repositories:
 * ```kotlin
 * suspend fun getOrders(): Result<List<Order>> {
 *     return try {
 *         val response = apiService.getOrders(venueId)
 *         if (response.isSuccessful && response.body() != null) {
 *             Result.Success(response.body()!!)
 *         } else {
 *             Result.Error(ApiException.HttpError(response.code(), response.message()))
 *         }
 *     } catch (e: Exception) {
 *         Result.Error(ApiException.NetworkError(e))
 *     }
 * }
 * ```
 */
interface ApiService {

    // ========== Terminal Activation (Public Endpoint) ==========

    /**
     * Activate terminal with activation code
     *
     * POST /tpv/activate
     *
     * **PUBLIC ENDPOINT** - No authentication required
     * - TenantInterceptor skips X-Venue-Id header for this endpoint
     * - AuthInterceptor skips Authorization header (no token yet)
     *
     * Flow:
     * 1. Device generates serial number (AVQD-{androidId})
     * 2. User enters 6-character activation code from dashboard
     * 3. Backend validates code and returns venueId
     * 4. App stores venueId permanently in SecureStorage
     *
     * @param request Activation request with serialNumber and activationCode
     * @return Activation response with venueId, terminalId, and venue info
     */
    @POST("tpv/activate")
    suspend fun activateTerminal(
        @Body request: com.jaac.avoqado_tpv.core.data.network.dto.ActivateTerminalRequest
    ): Response<com.jaac.avoqado_tpv.core.data.network.dto.ActivationResponse>

    /**
     * Check terminal activation status
     *
     * GET /tpv/terminals/{serialNumber}/activation-status
     *
     * **PUBLIC ENDPOINT** - No authentication required
     * - Used by SplashScreen to verify backend activation before routing
     * - Prevents routing to LoginScreen when terminal is not activated
     * - Returns RETIRED status to force logout of stolen devices
     *
     * Flow:
     * 1. SplashScreen calls this BEFORE checking local venueId
     * 2. Backend checks if activatedAt !== null
     * 3. If not activated → route to ActivationScreen
     * 4. If RETIRED → clear local data and route to ActivationScreen
     * 5. If activated → proceed to login check
     *
     * @param serialNumber Device serial number (e.g., AVQD-2841548417)
     * @param environment Blumon environment ("PROD" or "SAND") - differentiates same serial in different environments
     * @return Activation status with venueId if activated
     */
    @GET("tpv/terminals/{serialNumber}/activation-status")
    suspend fun checkActivationStatus(
        @Path("serialNumber") serialNumber: String,
        @Query("environment") environment: String
    ): Response<com.jaac.avoqado_tpv.core.data.network.dto.ActivationStatusResponse>

    // ========== Health Monitoring (Public Endpoint) ==========

    /**
     * Send heartbeat to server
     *
     * POST /tpv/heartbeat
     *
     * **PUBLIC ENDPOINT** - No authentication required
     * - Allows terminals to report health even when auth fails
     * - Returns server status for synchronization
     *
     * Flow:
     * 1. Worker collects device health metrics every 30s
     * 2. Sends heartbeat with terminal ID, status, system info
     * 3. Backend updates Terminal.lastHeartbeat and status
     * 4. Returns server's view of terminal status for sync
     *
     * @param request Heartbeat data with health metrics
     * @return Heartbeat response with server status
     */
    @POST("tpv/heartbeat")
    suspend fun sendHeartbeat(
        @Body request: com.jaac.avoqado_tpv.core.data.network.dto.HeartbeatRequestDto
    ): Response<com.jaac.avoqado_tpv.core.data.network.dto.HeartbeatResponseDto>

    // ========== Terminal Configuration (Public Endpoint) ==========

    /**
     * Get terminal configuration with assigned merchant accounts
     *
     * GET /tpv/terminals/{serialNumber}/config
     *
     * **PUBLIC ENDPOINT** - No authentication required
     * - Called on app startup before login
     * - Fetches terminal info + assigned merchant accounts
     * - Enables dynamic multi-merchant support
     *
     * Flow:
     * 1. App startup → reads device serial number
     * 2. Calls this endpoint to fetch config
     * 3. Backend returns terminal + merchant accounts
     * 4. App stores in TerminalConfig and MerchantRepository
     * 5. User can switch between merchants in payment screen
     *
     * **Response Example:**
     * ```json
     * {
     *   "success": true,
     *   "data": {
     *     "terminal": {
     *       "id": "term_xxxxx",
     *       "serialNumber": "2841548417",
     *       "brand": "PAX",
     *       "model": "A910S",
     *       "status": "ACTIVE",
     *       "venueId": "venue_xxxxx"
     *     },
     *     "merchantAccounts": [
     *       {
     *         "id": "ma_xxxxx",
     *         "displayName": "Main Account",
     *         "serialNumber": "2841548417",
     *         "posId": "376",
     *         "environment": "SANDBOX"
     *       }
     *     ]
     *   }
     * }
     * ```
     *
     * @param serialNumber Terminal serial number (e.g., "2841548417")
     * @return Terminal config response with terminal info and merchant accounts
     */
    @GET("tpv/terminals/{serialNumber}/config")
    suspend fun getTerminalConfig(
        @Path("serialNumber") serialNumber: String
    ): Response<com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigResponse>

    // ========== Authentication ==========

    /**
     * Login with PIN
     *
     * POST /tpv/venues/{venueId}/auth
     *
     * **Rate Limited:** 10 attempts per 15 minutes per IP
     *
     * Flow:
     * 1. User enters 4-6 digit PIN
     * 2. Backend validates PIN with bcrypt comparison
     * 3. Returns JWT tokens + staff data + venue context
     * 4. App saves tokens to SecureStorage
     * 5. Starts heartbeat worker
     *
     * @param venueId Venue identifier (from activation)
     * @param request PIN login request
     * @return Auth response with tokens, staff, and venue data
     */
    @POST("tpv/venues/{venueId}/auth")
    suspend fun loginWithPin(
        @Path("venueId") venueId: String,
        @Body request: com.jaac.avoqado_tpv.features.authentication.data.dto.PinLoginRequestDto
    ): Response<com.jaac.avoqado_tpv.features.authentication.data.dto.AuthResponseDto>

    /**
     * Refresh access token
     *
     * POST /tpv/venues/{venueId}/auth/refresh
     *
     * Exchanges refresh token for new access token without requiring PIN re-entry.
     * Used to extend session duration from 24 hours to 7-30 days (configurable).
     *
     * Flow:
     * 1. Check if access token is expired (AuthInterceptor detects 401)
     * 2. Call refresh endpoint with refresh token
     * 3. Receive new access token + refresh token
     * 4. Save new tokens to SecureStorage
     * 5. Retry original request with new access token
     *
     * @param venueId Venue identifier
     * @param request Refresh token request
     * @return New access token with updated expiration
     */
    @POST("tpv/venues/{venueId}/auth/refresh")
    suspend fun refreshToken(
        @Path("venueId") venueId: String,
        @Body request: com.jaac.avoqado_tpv.features.authentication.data.dto.RefreshTokenRequestDto
    ): Response<com.jaac.avoqado_tpv.features.authentication.data.dto.RefreshTokenResponseDto>

    /**
     * Logout (invalidate token)
     *
     * POST /tpv/venues/{venueId}/auth/logout
     */
    @POST("tpv/venues/{venueId}/auth/logout")
    suspend fun logout(
        @Path("venueId") venueId: String
    ): Response<Unit>

    /**
     * Get current user info
     *
     * GET /tpv/venues/{venueId}/auth/me
     */
    @GET("tpv/venues/{venueId}/auth/me")
    suspend fun getCurrentUser(
        @Path("venueId") venueId: String
    ): Response<StaffMember>

    // ========== Orders ==========

    /**
     * Get all orders for venue
     *
     * GET /tpv/venues/{venueId}/orders
     *
     * @param venueId Venue identifier
     * @param status Optional status filter (OPEN, CLOSED, CANCELLED)
     * @param tableId Optional table filter
     */
    @GET("tpv/venues/{venueId}/orders")
    suspend fun getOrders(
        @Path("venueId") venueId: String,
        @Query("status") status: String? = null,
        @Query("tableId") tableId: String? = null
    ): Response<List<Order>>

    /**
     * Get single order by ID
     *
     * GET /tpv/venues/{venueId}/orders/{orderId}
     */
    @GET("tpv/venues/{venueId}/orders/{orderId}")
    suspend fun getOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String
    ): Response<Order>

    /**
     * Create new order
     *
     * POST /tpv/venues/{venueId}/orders
     */
    @POST("tpv/venues/{venueId}/orders")
    suspend fun createOrder(
        @Path("venueId") venueId: String,
        @Body request: CreateOrderRequest
    ): Response<Order>

    /**
     * Update order
     *
     * PATCH /tpv/venues/{venueId}/orders/{orderId}
     */
    @PATCH("tpv/venues/{venueId}/orders/{orderId}")
    suspend fun updateOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: UpdateOrderRequest
    ): Response<Order>

    /**
     * Add items to order
     *
     * POST /tpv/venues/{venueId}/orders/{orderId}/items
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/items")
    suspend fun addOrderItems(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body items: List<OrderItemRequest>
    ): Response<Order>

    /**
     * Close order (send to kitchen)
     *
     * POST /tpv/venues/{venueId}/orders/{orderId}/close
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/close")
    suspend fun closeOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String
    ): Response<Order>

    // ========== Menu / Products ==========

    /**
     * Get all products for venue
     *
     * GET /api/v1/dashboard/venues/{venueId}/products
     *
     * Returns all products with categories, modifiers, and inventory info.
     * Backend uses productService.getProducts() with includeRecipe=true.
     *
     * @param venueId Venue identifier
     * @param categoryId Optional category filter
     * @return Response with list of products (with nested category and modifierGroups)
     */
    @GET("dashboard/venues/{venueId}/products")
    suspend fun getProducts(
        @Path("venueId") venueId: String,
        @Query("categoryId") categoryId: String? = null
    ): Response<com.jaac.avoqado_tpv.features.ordering.data.dto.ProductsResponse>

    /**
     * Get menu categories for venue
     *
     * GET /api/v1/dashboard/venues/{venueId}/categories
     *
     * @param venueId Venue identifier
     * @return Response with list of categories
     */
    @GET("dashboard/venues/{venueId}/categories")
    suspend fun getCategories(
        @Path("venueId") venueId: String
    ): Response<List<com.jaac.avoqado_tpv.features.ordering.data.dto.CategoryDto>>

    // ========== Legacy Menu Endpoints (DEPRECATED - Use getProducts instead) ==========

    /**
     * Get menu for venue
     *
     * GET /tpv/venues/{venueId}/menu
     *
     * @deprecated Use getProducts() instead - returns richer data with modifiers
     */
    @Deprecated("Use getProducts() instead")
    @GET("tpv/venues/{venueId}/menu")
    suspend fun getMenu(
        @Path("venueId") venueId: String,
        @Query("categoryId") categoryId: String? = null,
        @Query("available") available: Boolean? = true
    ): Response<List<MenuItem>>

    // ========== Payments ==========

    /**
     * Register payment
     *
     * POST /tpv/venues/{venueId}/orders/{orderId}/payments
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/payments")
    suspend fun registerPayment(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: PaymentRequest
    ): Response<Payment>

    // ========== Tables ==========

    /**
     * Get all tables
     *
     * GET /tpv/venues/{venueId}/tables
     */
    @GET("tpv/venues/{venueId}/tables")
    suspend fun getTables(
        @Path("venueId") venueId: String
    ): Response<List<Table>>

    /**
     * Update table status
     *
     * PATCH /tpv/venues/{venueId}/tables/{tableId}
     */
    @PATCH("tpv/venues/{venueId}/tables/{tableId}")
    suspend fun updateTable(
        @Path("venueId") venueId: String,
        @Path("tableId") tableId: String,
        @Body request: UpdateTableRequest
    ): Response<Table>

    // ========== Shifts Management (Toast/Square Pattern) ==========

    /**
     * Open a new shift
     *
     * POST /tpv/venues/{venueId}/shifts/open
     *
     * Opens a new work shift with starting cash amount.
     * Similar to Toast/Square POS shift management.
     *
     * Flow:
     * 1. Staff member enters starting cash amount
     * 2. Backend creates shift record with status = OPEN
     * 3. Shift tracks all payments/orders automatically
     * 4. Returns created shift with ID
     *
     * @param venueId Venue identifier
     * @param request Open shift request with staffId, startingCash
     * @return Created shift object
     */
    @POST("tpv/venues/{venueId}/shifts/open")
    suspend fun openShift(
        @Path("venueId") venueId: String,
        @Body request: com.jaac.avoqado_tpv.features.shift.data.dto.OpenShiftRequest
    ): Response<com.jaac.avoqado_tpv.features.shift.data.dto.ShiftResponse>

    /**
     * Close an existing shift
     *
     * POST /tpv/venues/{venueId}/shifts/{shiftId}/close
     *
     * Closes an open shift with automatic calculation of:
     * - Payment breakdown (cash, card, voucher, other)
     * - Products sold count
     * - Inventory consumed (FIFO batches)
     * - Total sales, tips, orders
     *
     * Backend automatically calculates all metrics.
     * Optional closeData for future manual reconciliation (FASE 2).
     *
     * @param venueId Venue identifier
     * @param shiftId Shift identifier
     * @param request Close shift request (can be empty, backend auto-calculates)
     * @return Updated shift with automatic calculations
     */
    @POST("tpv/venues/{venueId}/shifts/{shiftId}/close")
    suspend fun closeShift(
        @Path("venueId") venueId: String,
        @Path("shiftId") shiftId: String,
        @Body request: com.jaac.avoqado_tpv.features.shift.data.dto.CloseShiftRequest
    ): Response<com.jaac.avoqado_tpv.features.shift.data.dto.ShiftResponse>

    /**
     * Get current active shift for venue
     *
     * GET /tpv/venues/{venueId}/shift
     *
     * Returns the currently open shift for the venue, or null if no shift is open.
     * Backend returns: {"shift": ShiftDto | null}
     *
     * @param venueId Venue identifier
     * @return Response wrapper containing shift (or null if no active shift)
     */
    @GET("tpv/venues/{venueId}/shift")
    suspend fun getCurrentShift(
        @Path("venueId") venueId: String
    ): Response<com.jaac.avoqado_tpv.features.shift.data.dto.CurrentShiftResponse>

    /**
     * Get shift history for venue
     *
     * GET /tpv/venues/{venueId}/shifts
     *
     * Returns paginated list of shifts for the venue.
     * Backend returns: {"success": true, "data": [ShiftDto, ...], "meta": {...}}
     * Used to display shift history on Turnos screen (Square/Toast POS pattern).
     *
     * **IMPORTANT**: Backend does NOT support status filter. Returns all shifts (OPEN + CLOSED).
     * Filter on Android side if you need only CLOSED shifts.
     *
     * @param venueId Venue identifier
     * @param pageSize Number of shifts per page (default: 10)
     * @param pageNumber Page number (default: 1)
     * @return Paginated response with shifts list and metadata
     */
    @GET("tpv/venues/{venueId}/shifts")
    suspend fun getShiftHistory(
        @Path("venueId") venueId: String,
        @Query("pageSize") pageSize: Int = 10,
        @Query("pageNumber") pageNumber: Int = 1
    ): Response<com.jaac.avoqado_tpv.features.shift.data.dto.ShiftHistoryResponse>

    // ========== Reports (Analytics) ==========

    /**
     * Get historical sales summaries grouped by time period
     *
     * GET /tpv/venues/{venueId}/reports/historical
     *
     * Retrieves aggregated sales data for multiple time periods with automatic
     * period-over-period comparisons for trend analysis.
     *
     * **World-Class Pattern (Toast POS + Square + Stripe)**:
     * - Efficient SQL aggregation with DATE_TRUNC
     * - Automatic previous period calculation
     * - Cursor-based pagination
     * - Timezone-aware grouping
     *
     * @param venueId Venue identifier
     * @param grouping Time grouping (DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY)
     * @param startDate Start of historical range (ISO 8601)
     * @param endDate End of historical range (ISO 8601)
     * @param cursor Pagination cursor (timestamp)
     * @param limit Number of periods to fetch (default 20)
     * @return Paginated historical periods with comparisons
     */
    @GET("tpv/venues/{venueId}/reports/historical")
    suspend fun getHistoricalReports(
        @Path("venueId") venueId: String,
        @Query("grouping") grouping: String,
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20
    ): Response<com.jaac.avoqado_tpv.features.reports.data.dto.HistoricalReportsResponse>

    // ========== Shifts (Timeclock) - DEPRECATED ==========
    // TODO: Remove these after shift management migration complete

    /**
     * Clock in (start shift)
     *
     * POST /tpv/venues/{venueId}/shifts/clock-in
     *
     * @deprecated Use openShift() instead
     */
    @Deprecated("Use openShift() instead")
    @POST("tpv/venues/{venueId}/shifts/clock-in")
    suspend fun clockIn(
        @Path("venueId") venueId: String
    ): Response<Shift>

    /**
     * Clock out (end shift)
     *
     * POST /tpv/venues/{venueId}/shifts/clock-out
     */
    @POST("tpv/venues/{venueId}/shifts/clock-out")
    suspend fun clockOut(
        @Path("venueId") venueId: String
    ): Response<Shift>

    /**
     * Get active shift
     *
     * GET /tpv/venues/{venueId}/shifts/active
     */
    @GET("tpv/venues/{venueId}/shifts/active")
    suspend fun getActiveShift(
        @Path("venueId") venueId: String
    ): Response<Shift>
}

// ========== Request/Response DTOs ==========
// These are placeholder classes - define full models later

data class PinLoginRequest(
    val pin: String
)

data class AuthResponse(
    val token: String,
    val user: StaffMember,
    val venueId: String,
    val permissions: List<String>
)

data class StaffMember(
    val id: String,
    val name: String,
    val email: String?,
    val role: String,
    val permissions: List<String>
)

data class Order(
    val id: String,
    val orderNumber: String,
    val tableId: String?,
    val tableName: String?,
    val status: String,
    val items: List<OrderItem>,
    val subtotal: Double,
    val tax: Double,
    val total: Double,
    val createdAt: String,
    val closedAt: String?
)

data class OrderItem(
    val id: String,
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPrice: Double,
    val subtotal: Double
)

data class CreateOrderRequest(
    val tableId: String?,
    val items: List<OrderItemRequest>
)

data class UpdateOrderRequest(
    val status: String?,
    val tableId: String?
)

data class OrderItemRequest(
    val productId: String,
    val quantity: Int,
    val modifiers: List<String>? = null
)

data class MenuItem(
    val id: String,
    val name: String,
    val description: String?,
    val price: Double,
    val categoryId: String,
    val categoryName: String,
    val available: Boolean,
    val imageUrl: String?
)

data class Category(
    val id: String,
    val name: String,
    val description: String?,
    val order: Int
)

data class PaymentRequest(
    val amount: Double,
    val method: String, // CASH, CARD, TRANSFER
    val transactionId: String?,
    val cardLastFour: String?,
    val authorizationCode: String?
)

data class Payment(
    val id: String,
    val orderId: String,
    val amount: Double,
    val method: String,
    val status: String,
    val transactionId: String?,
    val createdAt: String
)

data class Table(
    val id: String,
    val name: String,
    val capacity: Int,
    val status: String, // AVAILABLE, OCCUPIED, RESERVED
    val currentOrderId: String?
)

data class UpdateTableRequest(
    val status: String
)

data class Shift(
    val id: String,
    val staffId: String,
    val staffName: String,
    val clockIn: String,
    val clockOut: String?,
    val status: String // ACTIVE, CLOSED
)
