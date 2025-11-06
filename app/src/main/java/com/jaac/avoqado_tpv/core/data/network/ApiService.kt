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
     * @return Activation status with venueId if activated
     */
    @GET("tpv/terminals/{serialNumber}/activation-status")
    suspend fun checkActivationStatus(
        @Path("serialNumber") serialNumber: String
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

    // ========== Menu ==========

    /**
     * Get menu for venue
     *
     * GET /tpv/venues/{venueId}/menu
     */
    @GET("tpv/venues/{venueId}/menu")
    suspend fun getMenu(
        @Path("venueId") venueId: String,
        @Query("categoryId") categoryId: String? = null,
        @Query("available") available: Boolean? = true
    ): Response<List<MenuItem>>

    /**
     * Get menu categories
     *
     * GET /tpv/venues/{venueId}/menu/categories
     */
    @GET("tpv/venues/{venueId}/menu/categories")
    suspend fun getCategories(
        @Path("venueId") venueId: String
    ): Response<List<Category>>

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

    // ========== Shifts (Timeclock) ==========

    /**
     * Clock in (start shift)
     *
     * POST /tpv/venues/{venueId}/shifts/clock-in
     */
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
