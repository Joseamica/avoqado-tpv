package com.jaac.avoqado_tpv.core.data.network

import com.jaac.avoqado_tpv.core.data.network.dto.AckMessageRequest
import com.jaac.avoqado_tpv.core.data.network.dto.SurveyResponseRequest
import com.jaac.avoqado_tpv.core.data.network.dto.TpvMessageHistoryResponse
import com.jaac.avoqado_tpv.core.data.network.dto.TpvMessageListResponse
import com.jaac.avoqado_tpv.features.training.data.dto.TrainingsListResponse
import com.jaac.avoqado_tpv.features.training.data.dto.TrainingDetailResponse
import com.jaac.avoqado_tpv.features.training.data.dto.TrainingProgressListResponse
import com.jaac.avoqado_tpv.features.training.data.dto.TrainingProgressResponse
import com.jaac.avoqado_tpv.features.training.data.dto.UpdateProgressRequest
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
     * - **Square Terminal API Pattern**: Returns pending commands in response
     *
     * Flow:
     * 1. Worker collects device health metrics every 30s
     * 2. Sends heartbeat with terminal ID, status, system info
     * 3. Backend updates Terminal.lastHeartbeat and status
     * 4. Returns server's view of terminal status for sync
     * 5. **NEW**: Returns pending commands for polling-based delivery
     *
     * @param request Heartbeat data with health metrics
     * @return Heartbeat response with server status and pending commands
     */
    @POST("tpv/heartbeat")
    suspend fun sendHeartbeat(
        @Body request: com.jaac.avoqado_tpv.core.data.network.dto.HeartbeatRequestDto
    ): Response<com.jaac.avoqado_tpv.core.data.network.dto.HeartbeatResponseDto>

    /**
     * Acknowledge command execution
     *
     * POST /tpv/command-ack
     *
     * **PUBLIC ENDPOINT** - No authentication required
     * - Allows terminals to report command execution results
     * - Called after processing commands received via heartbeat
     *
     * **Square Terminal API Pattern**: Commands are delivered via heartbeat
     * response (polling), terminal sends ACK after execution via this endpoint.
     *
     * Flow:
     * 1. Heartbeat response includes pending commands
     * 2. Terminal executes command via CommandExecutor
     * 3. Terminal sends ACK with result (SUCCESS/FAILED/REJECTED/TIMEOUT)
     * 4. Backend updates TpvCommandQueue status and broadcasts to dashboard
     *
     * @param request Command acknowledgment with result
     * @return ACK response confirming receipt
     */
    @POST("tpv/command-ack")
    suspend fun sendCommandAck(
        @Body request: com.jaac.avoqado_tpv.core.data.network.dto.CommandAckRequestDto
    ): Response<com.jaac.avoqado_tpv.core.data.network.dto.CommandAckResponseDto>

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

    /**
     * Update TPV Settings for a specific terminal
     *
     * PUT /tpv/terminals/{serialNumber}/settings
     *
     * **AUTHENTICATED ENDPOINT** - Requires valid JWT token
     *
     * Updates the TPV screen configuration settings for this terminal.
     * Each terminal can have individual settings (different from other terminals in the same venue).
     *
     * @param serialNumber Terminal serial number
     * @param settings Updated TPV settings
     * @return Updated settings response
     */
    @PUT("tpv/terminals/{serialNumber}/settings")
    suspend fun updateTpvSettings(
        @Path("serialNumber") serialNumber: String,
        @Body settings: com.jaac.avoqado_tpv.core.data.network.dto.TpvSettingsDto
    ): Response<com.jaac.avoqado_tpv.core.data.network.dto.TpvSettingsUpdateResponse>

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
     * Master TOTP Login (SUPERADMIN Emergency Access)
     *
     * POST /tpv/venues/{venueId}/master-login
     *
     * Emergency access for SUPERADMINs using Google Authenticator TOTP codes.
     * This allows SUPERADMIN access to ANY terminal without knowing staff PINs.
     *
     * Flow:
     * 1. Superadmin opens Google Authenticator
     * 2. Enters 8-digit TOTP code on TPV login screen
     * 3. TPV detects 8-digit code and calls master-login endpoint
     * 4. Backend validates TOTP and returns SUPERADMIN session
     * 5. All master logins are audit logged (IP, terminal, timestamp)
     *
     * Security:
     * - 8-digit TOTP codes change every 60 seconds
     * - All usage is audit logged in ActivityLog
     * - Requires TOTP_MASTER_SECRET configured on backend
     *
     * @param venueId Venue identifier (grants access to THIS venue)
     * @param request TOTP code + serial number
     * @return Auth response with SUPERADMIN role
     */
    @POST("tpv/venues/{venueId}/auth/master")
    suspend fun masterLogin(
        @Path("venueId") venueId: String,
        @Body request: com.jaac.avoqado_tpv.features.authentication.data.dto.MasterLoginRequestDto
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

    /**
     * Get staff permissions
     *
     * GET /tpv/auth/permissions
     *
     * Returns resolved permissions for the authenticated staff member.
     * Permissions are merged from:
     * 1. Base permissions (DEFAULT_PERMISSIONS by role)
     * 2. Custom permissions (VenueRolePermission - dashboard-configured)
     * 3. Implicit permissions (dependencies)
     *
     * **Caching Strategy:**
     * - Response is cached locally for 5 minutes
     * - Called on login and stored in PermissionsRepository
     * - Cache invalidated on logout
     *
     * Flow:
     * 1. Login → Fetch permissions → Cache locally
     * 2. UI checks → Use cached permissions (5min TTL)
     * 3. Logout → Clear cache
     *
     * @return Staff permissions response with role and permissions list
     */
    @GET("tpv/auth/permissions")
    suspend fun getStaffPermissions():
        Response<com.jaac.avoqado_tpv.features.authentication.data.dto.StaffPermissionsResponse>

    // ========== Venue Settings ==========

    /**
     * Get venue details with TPV settings
     *
     * GET /tpv/venues/{venueId}
     *
     * Returns venue info and tpvSettings object containing:
     * - showReviewScreen: Whether to show star rating screen
     * - showTipScreen: Whether to show tip selection screen
     * - showReceiptScreen: Whether to show receipt options
     * - defaultTipPercentage: Pre-selected tip percentage (optional)
     * - tipSuggestions: List of tip percentage options
     * - requirePinLogin: Whether PIN is required for login
     *
     * @param venueId Venue identifier
     * @return VenueWithSettingsResponse containing venue and tpvSettings
     */
    @GET("tpv/venues/{venueId}")
    suspend fun getVenueWithSettings(
        @Path("venueId") venueId: String
    ): Response<com.jaac.avoqado_tpv.core.data.network.dto.VenueWithSettingsResponse>

    /**
     * Get venue details for receipt/header printing.
     *
     * GET /tpv/venues/{venueId}
     *
     * Returns the full venue object; we parse only the fields needed by the TPV.
     */
    @GET("tpv/venues/{venueId}")
    suspend fun getVenueDetails(
        @Path("venueId") venueId: String
    ): Response<com.jaac.avoqado_tpv.core.data.network.dto.VenueDetailsDto>

    // ========== Orders ==========

    /**
     * Get all orders for venue
     *
     * GET /tpv/venues/{venueId}/orders
     *
     * @param venueId Venue identifier
     * @param status Optional status filter (OPEN, CLOSED, CANCELLED)
     * @param tableId Optional table filter
     * @param includePayLater If true, include pay-later orders (orders with customers)
     * @param onlyPayLater If true, ONLY return pay-later orders
     */
    @GET("tpv/venues/{venueId}/orders")
    suspend fun getOrders(
        @Path("venueId") venueId: String,
        @Query("status") status: String? = null,
        @Query("tableId") tableId: String? = null,
        @Query("includePayLater") includePayLater: Boolean? = null,
        @Query("onlyPayLater") onlyPayLater: Boolean? = null
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
     * Search product by barcode
     *
     * GET /api/v1/tpv/venues/{venueId}/products/barcode/{barcode}
     *
     * ✅ BARCODE QUICK ADD: Search product by scanning barcode
     * Returns 404 if product not found (trigger QuickAddProductDialog)
     *
     * @param venueId Venue identifier
     * @param barcode Scanned barcode value (SKU)
     * @return Response with single product or 404
     */
    @GET("tpv/venues/{venueId}/products/barcode/{barcode}")
    suspend fun getProductByBarcode(
        @Path("venueId") venueId: String,
        @Path("barcode") barcode: String
    ): Response<com.jaac.avoqado_tpv.features.ordering.data.dto.ProductResponse>

    /**
     * Create product quickly from barcode scan
     *
     * POST /api/v1/tpv/venues/{venueId}/products/quick-add
     *
     * ✅ BARCODE QUICK ADD: Create product on-the-fly when barcode not found
     * (Square POS pattern)
     *
     * @param venueId Venue identifier
     * @param request Quick-add product request
     * @return Response with created product
     */
    @POST("tpv/venues/{venueId}/products/quick-add")
    suspend fun createQuickAddProduct(
        @Path("venueId") venueId: String,
        @Body request: com.jaac.avoqado_tpv.features.ordering.data.dto.QuickAddProductRequest
    ): Response<com.jaac.avoqado_tpv.features.ordering.data.dto.ProductResponse>

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

    /**
     * Upload proof-of-sale photo
     *
     * POST /tpv/verification/proof-of-sale
     *
     * Simple endpoint to attach proof-of-sale photo to a payment.
     * Used when SERIALIZED_INVENTORY module is active.
     *
     * @param request Proof-of-sale request with paymentId and photo URLs
     * @return Success response with verification ID
     */
    @POST("tpv/verification/proof-of-sale")
    suspend fun uploadProofOfSale(
        @Body request: ProofOfSaleRequest
    ): Response<ProofOfSaleResponse>

    /**
     * Get pending verifications for the authenticated staff
     *
     * GET /tpv/verification/pending
     *
     * Returns verifications with status=PENDING for the staff in the JWT.
     * Used by the "Pendientes Verificacion" screen on WelcomeScreen.
     */
    @GET("tpv/verification/pending")
    suspend fun getPendingVerifications(): Response<PendingVerificationsResponse>

    // ========== TPV Messages (Dashboard → Terminal) ==========

    /**
     * Get pending messages for this terminal
     *
     * GET /tpv/messages/pending
     *
     * Used for offline recovery — fetches messages that haven't been
     * acknowledged or dismissed when terminal reconnects.
     */
    @GET("tpv/messages/pending")
    suspend fun getPendingMessages(
        @Query("terminalId") terminalId: String
    ): Response<TpvMessageListResponse>

    /**
     * Get message history for this terminal (all messages, including handled)
     *
     * GET /tpv/messages/history
     *
     * Returns paginated list of all messages delivered to this terminal
     * with their delivery status (PENDING, DELIVERED, ACKNOWLEDGED, DISMISSED).
     * Used for the message inbox UI.
     */
    @GET("tpv/messages/history")
    suspend fun getMessageHistory(
        @Query("terminalId") terminalId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<TpvMessageHistoryResponse>

    /**
     * Acknowledge a message (REST backup for Socket.IO)
     *
     * POST /tpv/messages/{messageId}/acknowledge
     */
    @POST("tpv/messages/{messageId}/acknowledge")
    suspend fun acknowledgeMessage(
        @Path("messageId") messageId: String,
        @Query("terminalId") terminalId: String,
        @Body body: AckMessageRequest
    ): Response<Unit>

    /**
     * Dismiss a message (REST backup for Socket.IO)
     *
     * POST /tpv/messages/{messageId}/dismiss
     */
    @POST("tpv/messages/{messageId}/dismiss")
    suspend fun dismissMessage(
        @Path("messageId") messageId: String,
        @Query("terminalId") terminalId: String
    ): Response<Unit>

    /**
     * Submit a survey response (REST backup for Socket.IO)
     *
     * POST /tpv/messages/{messageId}/respond
     */
    @POST("tpv/messages/{messageId}/respond")
    suspend fun respondToMessage(
        @Path("messageId") messageId: String,
        @Query("terminalId") terminalId: String,
        @Body body: SurveyResponseRequest
    ): Response<Unit>

    // ========== Training / LMS ==========

    /**
     * List available training modules (auto-filtered by org modules)
     *
     * GET /tpv/trainings
     */
    @GET("tpv/trainings")
    suspend fun getTrainings(): Response<TrainingsListResponse>

    /**
     * Get training detail with steps and quiz
     *
     * GET /tpv/trainings/{trainingId}
     */
    @GET("tpv/trainings/{trainingId}")
    suspend fun getTrainingDetail(
        @Path("trainingId") trainingId: String
    ): Response<TrainingDetailResponse>

    /**
     * Update training progress for a staff member
     *
     * POST /tpv/trainings/{trainingId}/progress
     */
    @POST("tpv/trainings/{trainingId}/progress")
    suspend fun updateTrainingProgress(
        @Path("trainingId") trainingId: String,
        @Body request: UpdateProgressRequest
    ): Response<TrainingProgressResponse>

    /**
     * Get all training progress for a staff member
     *
     * GET /tpv/trainings/progress
     */
    @GET("tpv/trainings/progress")
    suspend fun getTrainingProgress(
        @Query("staffId") staffId: String
    ): Response<TrainingProgressListResponse>

    // ========== Crypto Payments (B4Bit Integration) ==========

    /**
     * Initiate a crypto payment
     *
     * POST /tpv/venues/{venueId}/crypto/initiate
     *
     * Creates a crypto payment order via B4Bit gateway.
     * Returns a payment URL that should be displayed as QR code.
     *
     * **Flow:**
     * 1. TPV calls this endpoint with amount and tip
     * 2. Backend creates B4Bit order and returns payment URL
     * 3. TPV generates QR code from payment URL
     * 4. Customer scans QR and pays with crypto
     * 5. B4Bit webhook notifies backend → Socket.IO event to TPV
     *
     * **Supported Cryptocurrencies:**
     * BTC, ETH, USDT, USDC, DAI, SOL, AVAX, XRP, TRX, ALGO, DASH, BCH
     *
     * @param venueId Venue identifier
     * @param request Crypto payment request with amount, tip, and device info
     * @return Crypto payment response with QR URL and expiration
     */
    @POST("tpv/venues/{venueId}/crypto/initiate")
    suspend fun initiateCryptoPayment(
        @Path("venueId") venueId: String,
        @Body request: CryptoPaymentRequest
    ): Response<CryptoPaymentResponse>

    /**
     * Cancel a pending crypto payment
     *
     * POST /tpv/venues/{venueId}/crypto/cancel
     *
     * Cancels a crypto payment that is awaiting customer payment.
     * Should be called when user taps "Cancel" on QR screen.
     *
     * @param venueId Venue identifier
     * @param request Cancel request with requestId
     * @return Generic response with success status
     */
    @POST("tpv/venues/{venueId}/crypto/cancel")
    suspend fun cancelCryptoPayment(
        @Path("venueId") venueId: String,
        @Body request: CancelCryptoPaymentRequest
    ): Response<GenericResponse>

    /**
     * Get crypto payment status
     *
     * GET /tpv/venues/{venueId}/crypto/status/{requestId}
     *
     * Polls for crypto payment status (fallback if Socket.IO fails).
     * Normally not needed - Socket.IO events notify TPV of status changes.
     *
     * **Status codes:**
     * - PENDING: Awaiting customer payment
     * - PROCESSING: Payment detected, awaiting confirmations
     * - COMPLETED: Payment confirmed
     * - FAILED: Payment failed or expired
     *
     * @param venueId Venue identifier
     * @param requestId B4Bit request ID
     * @return Current payment status
     */
    @GET("tpv/venues/{venueId}/crypto/status/{requestId}")
    suspend fun getCryptoPaymentStatus(
        @Path("venueId") venueId: String,
        @Path("requestId") requestId: String
    ): Response<CryptoPaymentStatusResponse>

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

    /**
     * GET /tpv/venues/{venueId}/shifts-summary
     *
     * Backend-aggregated sales summary including orphan payments (no shift).
     * Works for venues with AND without the shifts module enabled.
     */
    @GET("tpv/venues/{venueId}/shifts-summary")
    suspend fun getShiftsSummary(
        @Path("venueId") venueId: String,
        @Query("startTime") startTime: String? = null,
        @Query("endTime") endTime: String? = null,
        @Query("staffId") staffId: String? = null
    ): Response<com.jaac.avoqado_tpv.features.reports.data.dto.ShiftsSummaryResponse>

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

    // ========== Time Entries (Employee Timeclock) ==========

    /**
     * Clock in a staff member
     *
     * POST /tpv/venues/{venueId}/time-entries/clock-in
     *
     * Creates a new time entry for the staff member.
     * Requires PIN verification.
     *
     * @param venueId Venue identifier
     * @param request Clock in request with staffId, pin, optional jobRole
     * @return Created time entry
     */
    @POST("tpv/venues/{venueId}/time-entries/clock-in")
    suspend fun timeEntryClockIn(
        @Path("venueId") venueId: String,
        @Body request: com.jaac.avoqado_tpv.features.timeclock.data.dto.ClockInRequestDto
    ): Response<com.jaac.avoqado_tpv.features.timeclock.data.dto.TimeEntryResponseDto>

    /**
     * Clock out a staff member
     *
     * POST /tpv/venues/{venueId}/time-entries/clock-out
     *
     * Closes the active time entry for the staff member.
     * Automatically calculates total hours worked minus breaks.
     *
     * @param venueId Venue identifier
     * @param request Clock out request with staffId, pin
     * @return Updated time entry with totalHours
     */
    @POST("tpv/venues/{venueId}/time-entries/clock-out")
    suspend fun timeEntryClockOut(
        @Path("venueId") venueId: String,
        @Body request: com.jaac.avoqado_tpv.features.timeclock.data.dto.ClockOutRequestDto
    ): Response<com.jaac.avoqado_tpv.features.timeclock.data.dto.TimeEntryResponseDto>

    /**
     * Start a break
     *
     * POST /tpv/time-entries/{timeEntryId}/break/start
     *
     * Creates a new break record for the active time entry.
     *
     * @param timeEntryId Time entry identifier
     * @return Updated time entry with active break
     */
    @POST("tpv/time-entries/{timeEntryId}/break/start")
    suspend fun timeEntryStartBreak(
        @Path("timeEntryId") timeEntryId: String
    ): Response<com.jaac.avoqado_tpv.features.timeclock.data.dto.TimeEntryResponseDto>

    /**
     * End a break
     *
     * POST /tpv/time-entries/{timeEntryId}/break/end
     *
     * Ends the active break for the time entry.
     * Automatically calculates break duration.
     *
     * @param timeEntryId Time entry identifier
     * @return Updated time entry with ended break
     */
    @POST("tpv/time-entries/{timeEntryId}/break/end")
    suspend fun timeEntryEndBreak(
        @Path("timeEntryId") timeEntryId: String
    ): Response<com.jaac.avoqado_tpv.features.timeclock.data.dto.TimeEntryResponseDto>

    /**
     * Get time entries for venue
     *
     * GET /tpv/venues/{venueId}/time-entries
     *
     * Returns paginated list of time entries.
     * Can filter by staffId, date range, status.
     *
     * @param venueId Venue identifier
     * @param staffId Optional staff filter
     * @param startDate Optional start date (ISO 8601)
     * @param endDate Optional end date (ISO 8601)
     * @param status Optional status filter (CLOCKED_IN, ON_BREAK, CLOCKED_OUT)
     * @param limit Number of entries (default 20)
     * @param offset Pagination offset (default 0)
     * @return Paginated time entries list
     */
    @GET("tpv/venues/{venueId}/time-entries")
    suspend fun getTimeEntries(
        @Path("venueId") venueId: String,
        @Query("staffId") staffId: String? = null,
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<com.jaac.avoqado_tpv.features.timeclock.data.dto.TimeEntriesListResponseDto>

    /**
     * Get currently clocked-in staff
     *
     * GET /tpv/venues/{venueId}/time-entries/active
     *
     * Returns list of staff members currently clocked in.
     *
     * @param venueId Venue identifier
     * @return List of active time entries
     */
    @GET("tpv/venues/{venueId}/time-entries/active")
    suspend fun getActiveTimeEntries(
        @Path("venueId") venueId: String
    ): Response<com.jaac.avoqado_tpv.features.timeclock.data.dto.TimeEntriesListResponseDto>

    /**
     * Get MY time entries (self-service, no special permissions required)
     *
     * GET /tpv/venues/{venueId}/staff/{staffId}/time-entries
     *
     * Allows any authenticated staff member to view ONLY their own time entries.
     * No shifts:manage permission required - staff can always see their own clock-in/out history.
     *
     * Used by TimeclockScreen to show the current clock-in status for the logged-in user.
     *
     * @param venueId Venue identifier
     * @param staffId Staff member ID (must be the logged-in user's ID)
     * @param startDate Optional start date (ISO 8601)
     * @param endDate Optional end date (ISO 8601)
     * @param limit Number of entries (default 10)
     * @return List of time entries for this staff member
     */
    @GET("tpv/venues/{venueId}/staff/{staffId}/time-entries")
    suspend fun getMyTimeEntries(
        @Path("venueId") venueId: String,
        @Path("staffId") staffId: String,
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null,
        @Query("limit") limit: Int = 10
    ): Response<com.jaac.avoqado_tpv.features.timeclock.data.dto.TimeEntriesListResponseDto>

    // ========== Venue Modules ==========

    /**
     * Get enabled modules for venue
     *
     * GET /tpv/modules
     *
     * Returns all enabled modules for the venue with merged config.
     * Called at app startup (SplashScreen) to determine available functionality and UI configuration.
     *
     * Semi-public endpoint:
     * - If user is logged in: Backend uses authContext.venueId
     * - If no session (pre-login): Backend uses X-Venue-Id header from activated device
     *
     * Each module contains:
     * - code: Module identifier (e.g., "SERIALIZED_INVENTORY")
     * - config: Merged configuration (default + preset + custom)
     *   - labels: Industry-specific terminology
     *   - features: Feature toggles
     *   - ui: UI/flow configuration (skipTipScreen, simplifiedOrderFlow, etc.)
     *   - attendance: Clock-in/out requirements (photo, GPS)
     *
     * @param venueId Venue ID from device activation (passed as X-Venue-Id header)
     * @return List of enabled modules with their configurations
     */
    @GET("tpv/modules")
    suspend fun getModules(
        @Header("X-Venue-Id") venueId: String
    ): Response<com.jaac.avoqado_tpv.features.modules.data.dto.ModulesApiResponse>

    /**
     * Toggle a module ON/OFF for the current venue (SUPERADMIN only).
     *
     * POST /tpv/superadmin/modules/toggle
     * Body: { moduleCode: String, enabled: Boolean }
     */
    @POST("tpv/superadmin/modules/toggle")
    suspend fun toggleModule(
        @Body request: com.jaac.avoqado_tpv.features.modules.data.dto.ToggleModuleRequest
    ): Response<com.jaac.avoqado_tpv.features.modules.data.dto.ToggleModuleResponse>

    // ========== Sales Goal ==========

    /**
     * Get current staff's sales goal with progress
     *
     * GET /tpv/sales-goal
     *
     * Returns the active sales goal for the logged-in staff member,
     * or the venue-wide goal if no staff-specific goal exists.
     * Includes real-time current sales calculation based on completed payments.
     *
     * **Response:**
     * - If goal exists: { salesGoal: { goal, period, currentSales, staffId } }
     * - If no goal: { salesGoal: null }
     *
     * **Use case:**
     * Display sales progress bar on WelcomeScreen to motivate staff
     * toward daily/weekly/monthly sales targets.
     *
     * @return Sales goal with current progress, or null if not configured
     */
    @GET("tpv/sales-goal")
    suspend fun getSalesGoal(): Response<SalesGoalResponse>

    /**
     * GET /tpv/sales-goals (plural)
     * Get all effective sales goals resolved via venue > organization hierarchy.
     *
     * Returns goals relevant to this staff member (staff-specific + venue-wide).
     * Each goal includes a `source` field indicating whether it came from
     * the venue's own config or the organization's default.
     *
     * @return List of sales goals with source info
     */
    @GET("tpv/sales-goals")
    suspend fun getSalesGoals(): Response<SalesGoalsResponse>

    // ========== Shifts (Timeclock) - DEPRECATED ==========
    // TODO: Remove these after shift management migration complete

    /**
     * Clock in (start shift)
     *
     * POST /tpv/venues/{venueId}/shifts/clock-in
     *
     * @deprecated Use timeEntryClockIn() instead
     */
    @Deprecated("Use timeEntryClockIn() instead")
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

    // ========== Payment History ==========

    /**
     * Get payment history for a venue
     *
     * POST /tpv/venues/{venueId}/payments
     *
     * Retrieves paginated payment history with filtering options.
     * Used by PaymentsScreen for displaying transaction history.
     *
     * **Pagination** (1GB RAM optimization):
     * - pageSize: 20 items per page (default) - QUERY PARAM
     * - pageNumber: 1-indexed page number - QUERY PARAM
     *
     * **Filters** (in request body):
     * - fromDate: ISO 8601 start date (optional)
     * - toDate: ISO 8601 end date (optional)
     * - staffId: Filter by staff member (optional)
     *
     * **Response includes**:
     * - Payment details (amount, tip, method, status)
     * - Processed by staff info
     * - Order details (orderNumber, table)
     * - Pagination metadata (total, pageSize, pageNumber)
     *
     * **Backend expects**:
     * - Query params: pageSize, pageNumber
     * - Body: fromDate, toDate, staffId
     *
     * @param venueId Venue identifier
     * @param pageSize Number of payments per page (default: 20)
     * @param pageNumber Page number (default: 1)
     * @param request Filter parameters in body
     * @return Paginated response with payments list and metadata
     */
    @POST("tpv/venues/{venueId}/payments")
    suspend fun getPaymentHistory(
        @Path("venueId") venueId: String,
        @Query("pageSize") pageSize: Int,
        @Query("pageNumber") pageNumber: Int,
        @Body request: com.jaac.avoqado_tpv.features.payments.data.dto.PaymentHistoryRequestBody
    ): Response<com.jaac.avoqado_tpv.features.payments.data.dto.PaymentHistoryResponse>

    /**
     * Send TPV feedback (bug report or feature suggestion)
     *
     * POST /api/v1/tpv/feedback
     *
     * Sends bug reports or feature suggestions to hola@avoqado.io
     * with device information for debugging purposes.
     *
     * @param request Feedback data including message and device info
     * @return Success response
     */
    @POST("tpv/feedback")
    suspend fun sendFeedback(
        @Body request: TpvFeedbackRequest
    ): Response<TpvFeedbackResponse>

    // ========== Serialized Inventory (Telecom/Jewelry/Electronics) ==========

    /**
     * Scan a serialized item by barcode
     *
     * POST /tpv/serialized-inventory/scan
     *
     * Looks up a serial number (ICCID, certificate, etc.) and returns:
     * - 'available': Item exists and can be sold
     * - 'already_sold': Item was already sold
     * - 'not_registered': Item doesn't exist (can be registered on-the-fly)
     * - 'module_disabled': Serialized inventory module not enabled
     *
     * @param request Serial number to scan
     * @return ScanResult with status, item info, and suggested price
     */
    @POST("tpv/serialized-inventory/scan")
    suspend fun scanSerializedItem(
        @Body request: com.jaac.avoqado_tpv.features.serialized_sale.data.dto.ScanRequestDto
    ): Response<com.jaac.avoqado_tpv.features.serialized_sale.data.dto.ScanResponseDto>

    /**
     * Get item categories with stock counts
     *
     * GET /tpv/serialized-inventory/categories
     *
     * Returns all categories (SIM types, jewelry types, etc.) with available/sold counts.
     * Used for category selection during registration and sale.
     *
     * @return List of categories with stock information
     */
    @GET("tpv/serialized-inventory/categories")
    suspend fun getSerializedCategories(): Response<com.jaac.avoqado_tpv.features.serialized_sale.data.dto.CategoriesResponseDto>

    /**
     * Create a new serialized inventory category
     *
     * POST /tpv/serialized-inventory/categories
     *
     * Creates a new category (e.g., "SIM Movistar", "Anillo de Oro").
     * Used when no categories exist and user needs to create one from TPV.
     *
     * @param request Category name, description (optional), suggested price (optional)
     * @return Created category with ID
     */
    @POST("tpv/serialized-inventory/categories")
    suspend fun createSerializedCategory(
        @Body request: com.jaac.avoqado_tpv.features.serialized_sale.data.dto.CreateCategoryRequestDto
    ): Response<com.jaac.avoqado_tpv.features.serialized_sale.data.dto.CreateCategoryResponseDto>

    /**
     * Quick sell a serialized item
     *
     * POST /tpv/serialized-inventory/sell
     *
     * Creates an order with a single serialized item in one shot.
     * If the item is not registered, it will be registered automatically
     * if categoryId is provided.
     *
     * Requires: serialized-inventory:sell permission
     *
     * @param request Serial number, categoryId (optional), price, paymentMethodId, notes
     * @return Created order with order number
     */
    @POST("tpv/serialized-inventory/sell")
    suspend fun quickSellSerializedItem(
        @Body request: com.jaac.avoqado_tpv.features.serialized_sale.data.dto.QuickSellRequestDto
    ): Response<com.jaac.avoqado_tpv.features.serialized_sale.data.dto.QuickSellResponseDto>

    // ==========================================
    // SIM CUSTODY (PlayTelecom chain-of-custody, plan §3)
    // ==========================================

    /**
     * Lists SIMs in the current promoter's custody inbox.
     * Filters by (assignedPromoterId = current staff) AND custodyState IN
     * (PROMOTER_PENDING, PROMOTER_HELD, SOLD).
     */
    @GET("tpv/sim-custody/my-sims")
    suspend fun getMySims(): Response<com.jaac.avoqado_tpv.features.sim_custody.data.dto.MySimsResponseDto>

    /**
     * Bulk-accepts pending SIMs. Backend transitions PROMOTER_PENDING → PROMOTER_HELD.
     * Requires Idempotency-Key header for dedupe.
     */
    @POST("tpv/sim-custody/accept")
    suspend fun acceptSims(
        @retrofit2.http.Header("Idempotency-Key") idempotencyKey: String,
        @Body request: com.jaac.avoqado_tpv.features.sim_custody.data.dto.AcceptRequestDto,
    ): Response<com.jaac.avoqado_tpv.features.sim_custody.data.dto.BulkSimCustodyResponseDto>

    /**
     * Rejects ONE pending SIM. Idempotency-Key is optional for single-item calls.
     */
    @POST("tpv/sim-custody/reject")
    suspend fun rejectSim(
        @Body request: com.jaac.avoqado_tpv.features.sim_custody.data.dto.RejectRequestDto,
    ): Response<com.jaac.avoqado_tpv.features.sim_custody.data.dto.RejectResponseDto>

    /**
     * Register batch of serialized items
     *
     * POST /tpv/serialized-inventory/register-batch
     *
     * Registers multiple items (e.g., scanning box of SIMs).
     * Returns count of created items and list of duplicates.
     *
     * Requires: serialized-inventory:create permission
     *
     * @param request Category ID and list of serial numbers
     * @return Count of created items and duplicate serial numbers
     */
    @POST("tpv/serialized-inventory/register-batch")
    suspend fun registerSerializedBatch(
        @Body request: com.jaac.avoqado_tpv.features.serialized_sale.data.dto.RegisterBatchRequestDto
    ): Response<com.jaac.avoqado_tpv.features.serialized_sale.data.dto.RegisterBatchResponseDto>

    /**
     * Get current staff's serialized item sales history
     *
     * GET /tpv/serialized-inventory/my-sales
     *
     * Returns the logged-in promoter's sales grouped by month.
     * Includes daily breakdown, total count, and total amount.
     *
     * @param month Target month in "yyyy-MM" format (defaults to current month)
     * @param limit Max items per page
     * @param offset Pagination offset
     * @return Sales history with monthly totals
     */
    @GET("tpv/serialized-inventory/my-sales")
    suspend fun getMySalesHistory(
        @Query("month") month: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): Response<com.jaac.avoqado_tpv.features.serialized_sale.data.dto.MySalesResponse>

    // ========== Geolocation (Cell ID) ==========

    /**
     * Get location from cell tower + WiFi info
     *
     * POST /tpv/geolocation/cell-towers
     *
     * Converts cell tower + WiFi information to GPS coordinates.
     * Uses Google Geolocation API on the backend.
     *
     * **Use case:** Indoor location for PAX devices (no GPS satellite visibility)
     * **Accuracy:**
     * - Cell only: ~100-1000 meters
     * - Cell + WiFi: ~20-50 meters (MUCH BETTER!)
     *
     * @param request Cell tower + WiFi information
     * @return Location with latitude, longitude, and accuracy
     */
    @POST("tpv/geolocation/cell-towers")
    suspend fun getLocationFromCellTowers(
        @Body request: com.jaac.avoqado_tpv.core.location.NetworkLocationRequest
    ): Response<com.jaac.avoqado_tpv.core.location.CellLocationResponse>

    // ==========================================
    // APP UPDATE (Avoqado dual update system)
    // ==========================================

    /**
     * Check for app updates from Avoqado backend
     *
     * **Dual Update System:**
     * - Blumon: Provider-managed updates (via Blumon SDK)
     * - Avoqado: Self-managed updates (via this endpoint)
     *
     * **No authentication required** - called before login
     *
     * @param currentVersion Current versionCode (e.g., 6)
     * @param environment "SANDBOX" or "PRODUCTION"
     * @return Update info if available
     */
    @GET("tpv/check-update")
    suspend fun checkForAvoqadoUpdate(
        @Query("currentVersion") currentVersion: Int,
        @Query("environment") environment: String
    ): Response<AvoqadoUpdateResponse>

    /**
     * Report that an update was successfully installed (analytics)
     *
     * @param request Update installation report
     */
    @POST("tpv/report-update-installed")
    suspend fun reportUpdateInstalled(
        @Body request: ReportUpdateInstalledRequest
    ): Response<GenericResponse>

    /**
     * Report an APK install attempt (success or failure) for diagnostics
     *
     * Sends structured data about the install strategy used, timing,
     * error details, and device info. Backend uses this for monitoring
     * and alerting on install failures in production.
     *
     * @param request Install attempt report with full diagnostics
     */
    @POST("tpv/report-install-attempt")
    suspend fun reportInstallAttempt(
        @Body request: ReportInstallAttemptRequest
    ): Response<GenericResponse>

    /**
     * Get specific app version by versionCode (for INSTALL_VERSION command)
     *
     * Used for rollback/upgrade to a specific version.
     * SUPERADMIN can trigger this via INSTALL_VERSION command.
     *
     * @param versionCode Target versionCode (e.g., 5)
     * @param environment "SANDBOX" or "PRODUCTION"
     * @return Version info if found and active
     */
    @GET("tpv/get-version")
    suspend fun getSpecificVersion(
        @Query("versionCode") versionCode: Int,
        @Query("environment") environment: String
    ): Response<SpecificVersionResponse>
}

// ========== App Update DTOs ==========

/**
 * Response from Avoqado check-update endpoint
 */
data class AvoqadoUpdateResponse(
    val success: Boolean,
    val hasUpdate: Boolean,
    val message: String? = null,
    val update: AvoqadoUpdateInfo? = null
)

/**
 * Update notification mode
 * - NONE: No notification, user must check manually
 * - BANNER: Persistent banner, user can ignore/dismiss
 * - FORCE: Blocking modal, app blocked until updated
 */
enum class UpdateMode {
    NONE,
    BANNER,
    FORCE
}

/**
 * Update information from Avoqado backend
 */
data class AvoqadoUpdateInfo(
    val id: String,
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val fileSize: String, // BigInt as string
    val checksum: String,
    val releaseNotes: String?,
    val updateMode: UpdateMode = UpdateMode.NONE,
    val minAndroidSdk: Int,
    val publishedAt: String
) {
    /** Backwards compatibility: true if updateMode is FORCE */
    val isRequired: Boolean get() = updateMode == UpdateMode.FORCE
}

/**
 * Request to report update installation
 */
data class ReportUpdateInstalledRequest(
    val versionCode: Int,
    val versionName: String,
    val updateSource: String, // "AVOQADO" or "BLUMON"
    val serialNumber: String?
)

/**
 * Request to report an APK install attempt (success or failure) for diagnostics.
 *
 * Sent after every install attempt so the backend can monitor install success rates
 * and alert on failures in production.
 */
data class ReportInstallAttemptRequest(
    val versionName: String,
    val serialNumber: String?,
    val success: Boolean,
    val strategy: String,        // "PACKAGE_INSTALLER" | "PAX_SDK" | "BOTH_FAILED"
    val errorMessage: String?,
    val androidVersion: Int,
    val durationMs: Long,
    val updateSource: String,    // "AVOQADO" | "BLUMON"
    val deviceModel: String,
    val packageName: String
)

/**
 * Response from get-version endpoint (for INSTALL_VERSION command)
 */
data class SpecificVersionResponse(
    val success: Boolean,
    val found: Boolean,
    val message: String? = null,
    val version: AvoqadoUpdateInfo? = null
)

/**
 * Generic success/failure response
 */
data class GenericResponse(
    val success: Boolean,
    val message: String? = null
)

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

// ========== TPV Feedback DTOs ==========

data class TpvFeedbackRequest(
    val feedbackType: String, // "bug" or "feature"
    val message: String,
    val venueSlug: String,
    val appVersion: String,
    val buildVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val deviceManufacturer: String
)

data class TpvFeedbackResponse(
    val success: Boolean,
    val message: String,
    val correlationId: String?
)

data class ApiErrorResponse(
    val error: String?,
    val message: String?,
    val statusCode: Int?
)

// ========== Proof-of-Sale DTOs ==========

data class ProofOfSaleRequest(
    val paymentId: String,
    val photoUrls: List<String>,
    val verificationId: String? = null, // Non-blocking flow: update existing PENDING verification
    val replaceIndex: Int? = null, // Replace photo at this index instead of appending (0=Vinculacion, 1=Portabilidad)
    val photoLabel: String? = null // Fixed slot: "Vinculacion"=index 0, "Portabilidad"=index 1
)

data class ProofOfSaleResponse(
    val success: Boolean,
    val verificationId: String,
    val status: String? = null, // "PENDING" or "COMPLETED"
    val message: String?
)

// ========== Pending Verifications DTOs ==========

data class PendingVerificationsResponse(
    val success: Boolean,
    val data: List<PendingVerificationDto>
)

data class PendingVerificationDto(
    val id: String,
    val paymentId: String,
    val amount: Double,
    val orderNumber: String?,
    val date: String, // ISO timestamp
    val serialNumbers: List<String>,
    val isPortabilidad: Boolean,
    val photos: List<String>,
    val requiredPhotos: Int
)

// ========== Sales Goal DTOs ==========

/**
 * Response from GET /tpv/sales-goal
 * Contains the staff's active sales goal with real-time progress
 */
data class SalesGoalResponse(
    val salesGoal: SalesGoalDto?
)

/**
 * Sales goal data from backend
 * All monetary values are strings (BigDecimal on backend)
 */
data class SalesGoalDto(
    val goal: String,           // Target amount (e.g., "10000") or unit count (e.g., "50")
    val goalType: String? = null, // "AMOUNT" or "QUANTITY" (defaults to AMOUNT for backward compat)
    val period: String,         // "DAILY", "WEEKLY", or "MONTHLY"
    val currentSales: String,   // Current sales amount or unit count (e.g., "5000")
    val staffId: String? = null, // null = venue-wide goal
    val source: String? = null  // "venue" | "organization" (null for old endpoint backward compat)
)

/**
 * Response from GET /tpv/sales-goals (plural)
 * Contains all effective sales goals resolved via venue > organization hierarchy
 */
data class SalesGoalsResponse(
    val salesGoals: List<SalesGoalDto>
)

// ========== Crypto Payment DTOs (B4Bit Integration) ==========

/**
 * Request to initiate a crypto payment
 *
 * @param amount Amount in centavos (e.g., 5500 = $55.00 MXN)
 * @param tip Tip amount in centavos (optional)
 * @param staffId Staff member processing the payment
 * @param orderId Associated order ID (optional for fast payments)
 * @param orderNumber Order number for display (optional)
 * @param deviceSerialNumber Terminal serial number
 */
data class CryptoPaymentRequest(
    val amount: Int,
    val tip: Int? = null,
    val staffId: String,
    val orderId: String? = null,
    val orderNumber: String? = null,
    val deviceSerialNumber: String
)

/**
 * Response from crypto payment initiation
 *
 * Backend returns nested structure:
 * { success: true, data: { requestId, paymentId, ... }, message: "..." }
 */
data class CryptoPaymentResponse(
    val success: Boolean,
    val data: CryptoPaymentData?,
    val message: String? = null
)

/**
 * Crypto payment data nested inside response
 *
 * @param requestId B4Bit request ID for tracking
 * @param paymentId Avoqado payment ID
 * @param paymentUrl URL for customer to pay (display as QR code)
 * @param expiresAt ISO 8601 timestamp when payment expires
 * @param expiresInSeconds Seconds until expiration (for countdown)
 * @param cryptoAddress Crypto wallet address (optional)
 * @param cryptoSymbol Default crypto symbol (optional)
 */
data class CryptoPaymentData(
    val requestId: String,
    val paymentId: String,
    val paymentUrl: String,
    val expiresAt: String,
    val expiresInSeconds: Int,
    val cryptoAddress: String? = null,
    val cryptoSymbol: String? = null
)

/**
 * Request to cancel a pending crypto payment
 *
 * @param requestId B4Bit request ID to cancel
 * @param reason Cancellation reason (optional)
 */
data class CancelCryptoPaymentRequest(
    val requestId: String,
    val reason: String? = null
)

/**
 * Response from crypto payment status check
 *
 * @param success Whether the request was successful
 * @param requestId B4Bit request ID
 * @param status Current payment status (PENDING, PROCESSING, COMPLETED, FAILED)
 * @param paymentId Avoqado payment ID
 * @param txHash Blockchain transaction hash (if completed)
 * @param cryptoAmount Amount in crypto (e.g., "0.00123")
 * @param cryptoCurrency Cryptocurrency used (e.g., "BTC")
 * @param confirmedAt ISO 8601 timestamp when payment was confirmed
 */
data class CryptoPaymentStatusResponse(
    val success: Boolean,
    val requestId: String,
    val status: String,
    val paymentId: String? = null,
    val txHash: String? = null,
    val cryptoAmount: String? = null,
    val cryptoCurrency: String? = null,
    val confirmedAt: String? = null
)
