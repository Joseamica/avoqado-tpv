package com.jaac.avoqado_tpv.core.data.realtime

import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SocketManager - Centralized Socket.IO client management
 *
 * Pattern: Singleton instance managed by Hilt
 * Architecture: Clean Architecture - Data Layer (external communication)
 *
 * Responsibilities:
 * - Socket connection lifecycle (connect, disconnect, reconnect)
 * - JWT authentication (4 sources: auth object, query param, header, cookie)
 * - Room management (venue, table, order rooms)
 * - Event parsing (JSON → SocketEvent sealed class)
 * - Connection state monitoring
 * - Error handling & logging
 *
 * Based on: avoqado-server Socket.IO implementation
 * @see avoqado-server/src/communication/sockets/
 *
 * Usage (in ViewModel):
 * ```kotlin
 * viewModelScope.launch {
 *     socketManager.events.collect { event ->
 *         when (event) {
 *             is SocketEvent.PaymentCompleted -> handlePaymentComplete(event)
 *             is SocketEvent.SystemAlert -> showAlert(event)
 *             else -> {}
 *         }
 *     }
 * }
 * ```
 */
@Singleton
class SocketManager @Inject constructor(
    private val secureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage
) {

    // ========================================
    // Properties
    // ========================================

    private var socket: Socket? = null
    private var currentUrl: String? = null
    private var currentToken: String? = null

    private var currentTerminalId: String? = null

    /** Tracks consecutive auth failures to prevent infinite reconnect loops */
    @Volatile private var authRetryCount = 0
    private val MAX_AUTH_RETRIES = 3

    /** Handler + Runnable for delayed auth reconnect (cancellable) */
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var pendingReconnectRunnable: Runnable? = null

    /**
     * Shared Flow for all Socket.IO events
     * Replay = 1: New subscribers get the last emitted event
     * BufferOverflow.DROP_OLDEST: Prevent memory leaks from buffering too many events
     */
    private val _events = MutableSharedFlow<SocketEvent>(
        replay = 1,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    /**
     * Connection state
     */
    private val _isConnected = MutableSharedFlow<Boolean>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val isConnected: SharedFlow<Boolean> = _isConnected.asSharedFlow()

    // ========================================
    // Connection Management
    // ========================================

    /**
     * Connect to Socket.IO server with JWT authentication
     *
     * @param url Server URL (e.g., "https://api.avoqado.io" or dev URL)
     * @param token JWT access token from login
     * @param reconnection Enable automatic reconnection (default: true)
     * @param reconnectionAttempts Max reconnection attempts (default: unlimited for 3G resilience)
     */
    fun connect(
        url: String,
        token: String,
        terminalId: String? = null,
        reconnection: Boolean = true,
        reconnectionAttempts: Int = Int.MAX_VALUE
    ) {
        try {
            // Cancel any pending auth reconnect from a previous socket
            cancelPendingReconnect()

            // Disconnect existing socket if any
            disconnect()

            // Store for reconnection
            currentUrl = url
            currentToken = token
            currentTerminalId = terminalId

            // Socket.IO connection options
            val options = IO.Options().apply {
                // Authentication - Server checks 4 sources (auth, query, header, cookie)
                // We use auth object (most reliable for mobile)
                auth = buildMap {
                    put("token", token)
                    if (terminalId != null) put("terminalId", terminalId)
                }

                // Transports: WebSocket preferred, fallback to polling
                transports = arrayOf("websocket", "polling")

                // Reconnection settings
                this.reconnection = reconnection
                this.reconnectionAttempts = reconnectionAttempts
                reconnectionDelay = 1000 // 1 second
                reconnectionDelayMax = 30000 // 30 seconds max (slow 3G needs longer backoff)
                randomizationFactor = 0.5 // Add jitter to prevent thundering herd

                // Timeouts
                timeout = 30000 // 30 seconds connection timeout (3G WebSocket upgrade can be slow)

                // Force new connection (don't reuse existing)
                forceNew = true
            }

            // Create socket
            socket = IO.socket(URI(url), options).apply {
                // Attach event listeners
                setupEventListeners()

                // Connect
                connect()
            }

            Timber.d("✅ Socket.IO connecting to $url")

        } catch (e: URISyntaxException) {
            Timber.e(e, "❌ Invalid Socket.IO URL: $url")
            _events.tryEmit(SocketEvent.ConnectionError("Invalid server URL", e))
        } catch (e: Exception) {
            Timber.e(e, "❌ Socket.IO connection error")
            _events.tryEmit(SocketEvent.ConnectionError("Connection failed", e))
        }
    }

    /**
     * Disconnect from server
     */
    fun disconnect() {
        cancelPendingReconnect()
        socket?.apply {
            // Remove all listeners before disconnecting
            off()
            disconnect()
        }
        socket = null
        currentUrl = null
        currentToken = null
        _isConnected.tryEmit(false)
        Timber.d("🔌 Socket.IO disconnected")
    }

    /** Cancel any scheduled auth reconnect */
    private fun cancelPendingReconnect() {
        pendingReconnectRunnable?.let {
            reconnectHandler.removeCallbacks(it)
            Timber.d("🔄 [Socket.IO] Cancelled pending auth reconnect")
        }
        pendingReconnectRunnable = null
    }

    /**
     * Reconnect using stored credentials
     */
    fun reconnect() {
        val url = currentUrl
        val token = currentToken

        if (url != null && token != null) {
            Timber.d("🔄 Socket.IO reconnecting...")
            connect(url, token, currentTerminalId)
        } else {
            Timber.w("⚠️ Cannot reconnect: No stored credentials")
        }
    }

    /**
     * Reconnect using fresh token from SecureStorage (source of truth)
     *
     * Called when TokenAuthenticator refreshes the JWT. The in-memory `currentToken`
     * may be stale (expired), so we read the fresh token directly from SecureStorage.
     * This breaks the infinite reconnection loop caused by Socket.IO auto-reconnect
     * using the expired in-memory token.
     */
    fun reconnectWithFreshToken() {
        val url = currentUrl
        val freshToken = secureStorage.getToken()

        if (url != null && freshToken != null) {
            Timber.d("🔄 [Socket.IO] Reconnecting with fresh token from SecureStorage")
            connect(url, freshToken, currentTerminalId)
        } else {
            Timber.w("⚠️ [Socket.IO] Cannot reconnect with fresh token: url=$url, hasToken=${freshToken != null}")
        }
    }

    /**
     * Check if socket is connected
     */
    fun isConnected(): Boolean {
        return socket?.connected() == true
    }

    // ========================================
    // Room Management
    // ========================================

    /**
     * Join venue room (automatic on authentication, but can be called manually)
     *
     * Pattern: Server auto-joins venue room on authentication
     * This method is for explicit joining after connection
     */
    fun joinVenueRoom(venueId: String) {
        socket?.emit("join_room", JSONObject().apply {
            put("roomType", "venue")
            put("venueId", venueId)
        })
        Timber.d("📡 Joining venue room: venue_$venueId")
    }

    /**
     * Join table room
     */
    fun joinTableRoom(venueId: String, tableId: String) {
        socket?.emit("join_room", JSONObject().apply {
            put("roomType", "table")
            put("venueId", venueId)
            put("tableId", tableId)
        })
        Timber.d("📡 Joining table room: venue_${venueId}_table_${tableId}")
    }

    /**
     * Join order room
     */
    fun joinOrderRoom(venueId: String, orderId: String) {
        socket?.emit("join_room", JSONObject().apply {
            put("roomType", "order")
            put("venueId", venueId)
            put("orderId", orderId)
        })
        Timber.d("📡 Joining order room: venue_${venueId}_order_${orderId}")
    }

    /**
     * Leave room
     */
    fun leaveRoom(roomType: String, venueId: String, tableId: String? = null, orderId: String? = null) {
        socket?.emit("leave_room", JSONObject().apply {
            put("roomType", roomType)
            put("venueId", venueId)
            tableId?.let { put("tableId", it) }
            orderId?.let { put("orderId", it) }
        })
        Timber.d("📡 Leaving $roomType room")
    }

    // ========================================
    // Event Listeners Setup
    // ========================================

    private fun Socket.setupEventListeners() {
        // ========================================
        // Connection Lifecycle Events
        // ========================================

        on(Socket.EVENT_CONNECT, onConnect)
        on(Socket.EVENT_DISCONNECT, onDisconnect)
        on(Socket.EVENT_CONNECT_ERROR, onConnectError)
        // Note: EVENT_CONNECT_TIMEOUT and EVENT_ERROR don't exist in Socket.io 2.1.x
        // Connection errors are handled by EVENT_CONNECT_ERROR

        // ========================================
        // Authentication Events
        // ========================================

        on("authentication_success", onAuthSuccess)
        on("authentication_error", onAuthError)

        // ========================================
        // Room Events
        // ========================================

        on("room_joined", onRoomJoined)
        on("room_left", onRoomLeft)

        // ========================================
        // Payment Events (CRITICAL for TPV)
        // ========================================

        on("payment_initiated", onPaymentInitiated)
        on("payment_processing", onPaymentProcessing)
        on("payment_completed", onPaymentCompleted)
        on("payment_failed", onPaymentFailed)

        // ========================================
        // Crypto Payment Events (B4Bit Integration)
        // ========================================

        on("crypto:payment_confirmed", onCryptoPaymentConfirmed)
        on("crypto:payment_failed", onCryptoPaymentFailed)

        // ========================================
        // Order Events
        // ========================================

        on("order_created", onOrderCreated)
        on("order_updated", onOrderUpdated)
        on("order_status_changed", onOrderStatusChanged)
        on("order_deleted", onOrderDeleted)

        // ========================================
        // System Events
        // ========================================

        on("system_alert", onSystemAlert)
        on("venue_update", onVenueUpdate)
        on("table_status_change", onTableStatusChange)

        // ========================================
        // Notification Events
        // ========================================

        on("notification_new", onNotificationNew)
        on("notification_read", onNotificationRead)
        on("notification_deleted", onNotificationDeleted)
        on("notification_count_updated", onNotificationCountUpdated)

        // ========================================
        // TPV Admin Commands (Enterprise Command System)
        // ========================================

        on("tpv_command", onTPVCommand)
        on("tpv_command_cancelled", onTPVCommandCancelled)
        on("tpv_command_response", onTPVCommandResponse)
        on("tpv_status_update", onTPVStatusUpdate)

        // ========================================
        // Inventory Events (NEW)
        // ========================================

        on("inventory_low_stock", onInventoryLowStock)
        on("inventory_out_of_stock", onInventoryOutOfStock)
        on("inventory_updated", onInventoryUpdated)

        // ========================================
        // Menu & Product Events (NEW)
        // ========================================

        on("menu_updated", onMenuUpdated)
        on("menu_item_created", onMenuItemCreated)
        on("menu_item_updated", onMenuItemUpdated)
        on("menu_item_deleted", onMenuItemDeleted)
        on("menu_item_availability_changed", onMenuItemAvailabilityChanged)
        on("product_price_changed", onProductPriceChanged)
        on("menu_category_updated", onMenuCategoryUpdated)
        on("menu_category_deleted", onMenuCategoryDeleted)

        // ========================================
        // Terminal Payment Request (iOS → Backend → TPV)
        // ========================================

        on("terminal:payment_request", onTerminalPaymentRequest)
        on("terminal:payment_cancel", onTerminalPaymentCancel)

        // ========================================
        // Hardware Events (NEW)
        // ========================================

        on("printer_status", onPrinterStatus)
        on("card_reader_status", onCardReaderStatus)
        on("peripheral_error", onPeripheralError)

        // ========================================
        // TPV Messages (Dashboard → Terminal)
        // ========================================

        on("tpv_message", onTpvMessage)
        on("tpv_message_cancelled", onTpvMessageCancelled)

        // ========================================
        // Error Events
        // ========================================

        on("error", onSocketError)
        on("rate_limit_exceeded", onRateLimitExceeded)
    }

    // ========================================
    // Event Handlers - Connection
    // ========================================

    private val onConnect = Emitter.Listener {
        Timber.d("✅ Socket.IO connected: ${socket?.id()}")
        authRetryCount = 0 // Reset on successful connection
        _isConnected.tryEmit(true)
        _events.tryEmit(SocketEvent.Connected)
    }

    private val onDisconnect = Emitter.Listener { args ->
        val reason = args.getOrNull(0)?.toString() ?: "unknown"
        Timber.w("⚠️ Socket.IO disconnected: $reason")
        _isConnected.tryEmit(false)
        _events.tryEmit(SocketEvent.Disconnected)
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.getOrNull(0)
        val errorMsg = error?.toString() ?: ""
        Timber.e("❌ Socket.IO connect error: $errorMsg")
        _isConnected.tryEmit(false)
        _events.tryEmit(SocketEvent.ConnectionError("Connection error: $errorMsg"))

        // Detect auth-related errors and reconnect with fresh token from SecureStorage.
        // Socket.IO auto-reconnect reuses the same (expired) token, so we must intervene.
        val isAuthError = errorMsg.contains("token", ignoreCase = true) ||
            errorMsg.contains("expired", ignoreCase = true) ||
            errorMsg.contains("authentication", ignoreCase = true) ||
            errorMsg.contains("unauthorized", ignoreCase = true)

        if (isAuthError && authRetryCount < MAX_AUTH_RETRIES) {
            authRetryCount++
            Timber.w("🔄 [Socket.IO] Auth error detected (attempt $authRetryCount/$MAX_AUTH_RETRIES), reconnecting with fresh token...")
            // Stop Socket.IO's built-in auto-reconnect (it would use the stale token)
            socket?.off()
            socket?.disconnect()
            // Delay gives TokenAuthenticator time to refresh the JWT via HTTP interceptor.
            // Store the Runnable so connect()/disconnect() can cancel it if called during the delay.
            val runnable = Runnable { reconnectWithFreshToken() }
            pendingReconnectRunnable = runnable
            reconnectHandler.postDelayed(runnable, authRetryCount * 2000L) // 2s, 4s, 6s backoff
        } else if (isAuthError) {
            Timber.e("❌ [Socket.IO] Max auth retries reached ($MAX_AUTH_RETRIES). Giving up until next app restart or manual reconnect.")
        }
    }

    // Note: onConnectTimeout and onError handlers removed
    // Socket.io 2.1.x doesn't support EVENT_CONNECT_TIMEOUT or EVENT_ERROR constants
    // All connection errors are handled by onConnectError (EVENT_CONNECT_ERROR)

    // ========================================
    // Event Handlers - Authentication
    // ========================================

    private val onAuthSuccess = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            Timber.d("✅ Socket.IO authenticated: ${data.optString("authContext")}")
            _events.tryEmit(
                SocketEvent.AuthSuccess(
                    userId = data.optJSONObject("authContext")?.optString("userId") ?: "",
                    venueId = data.optJSONObject("authContext")?.optString("venueId") ?: "",
                    role = data.optJSONObject("authContext")?.optString("role") ?: "",
                    connectedAt = data.optString("connectedAt", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing auth success")
        }
    }

    private val onAuthError = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val error = data.optString("error", "Unknown error")
            val message = data.optString("message", "")
            Timber.e("❌ Socket.IO auth error: $error - $message")
            _events.tryEmit(SocketEvent.AuthError(error, message))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing auth error")
        }
    }

    // ========================================
    // Event Handlers - Rooms
    // ========================================

    private val onRoomJoined = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.RoomJoined(
                    success = data.optBoolean("success", false),
                    roomType = data.optString("roomType", ""),
                    venueId = data.optString("venueId", ""),
                    tableId = data.optString("tableId").takeIf { it.isNotEmpty() },
                    orderId = data.optString("orderId").takeIf { it.isNotEmpty() }
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing room_joined")
        }
    }

    private val onRoomLeft = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.RoomLeft(
                    success = data.optBoolean("success", false),
                    roomType = data.optString("roomType", ""),
                    venueId = data.optString("venueId", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing room_left")
        }
    }

    // ========================================
    // Event Handlers - Payment (CRITICAL)
    // ========================================

    private val onPaymentInitiated = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(parsePaymentEvent(data, "initiated"))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing payment_initiated")
        }
    }

    private val onPaymentProcessing = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(parsePaymentEvent(data, "processing"))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing payment_processing")
        }
    }

    private val onPaymentCompleted = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(parsePaymentEvent(data, "completed"))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing payment_completed")
        }
    }

    private val onPaymentFailed = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(parsePaymentEvent(data, "failed"))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing payment_failed")
        }
    }

    private fun parsePaymentEvent(data: JSONObject, status: String): SocketEvent {
        val paymentId = data.optString("paymentId", "")
        val amount = data.optInt("amount", 0)
        val currency = data.optString("currency", "USD")
        val tableId = data.optString("tableId").takeIf { it.isNotEmpty() }
        val orderId = data.optString("orderId").takeIf { it.isNotEmpty() }
        val venueId = data.optString("venueId", "")
        val timestamp = data.optString("timestamp", "")
        val metadata = data.optJSONObject("metadata")?.toMap()

        return when (status) {
            "initiated" -> SocketEvent.PaymentInitiated(paymentId, amount, currency, tableId, orderId, venueId, timestamp)
            "processing" -> SocketEvent.PaymentProcessing(paymentId, amount, currency, tableId, orderId, venueId, timestamp)
            "completed" -> SocketEvent.PaymentCompleted(paymentId, amount, currency, tableId, orderId, venueId, timestamp, metadata)
            "failed" -> SocketEvent.PaymentFailed(paymentId, amount, currency, tableId, orderId, venueId, timestamp, metadata)
            else -> throw IllegalArgumentException("Unknown payment status: $status")
        }
    }

    // ========================================
    // Event Handlers - Crypto Payment (B4Bit)
    // ========================================

    private val onCryptoPaymentConfirmed = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            Timber.i("🪙 [Socket] Crypto payment confirmed: ${data.optString("requestId")}")
            _events.tryEmit(
                SocketEvent.CryptoPaymentConfirmed(
                    requestId = data.optString("requestId", ""),
                    paymentId = data.optString("paymentId", ""),
                    amount = data.optInt("amount", 0),
                    currency = data.optString("currency", "MXN"),
                    txHash = data.optString("txHash", ""),
                    cryptoAmount = data.optString("cryptoAmount", ""),
                    cryptoCurrency = data.optString("cryptoCurrency", ""),
                    confirmations = data.optInt("confirmations").takeIf { data.has("confirmations") },
                    orderId = data.optString("orderId").takeIf { it.isNotEmpty() },
                    orderNumber = data.optString("orderNumber").takeIf { it.isNotEmpty() },
                    receiptUrl = data.optString("receiptUrl").takeIf { it.isNotEmpty() },
                    receiptAccessKey = data.optString("receiptAccessKey").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing crypto:payment_confirmed")
        }
    }

    private val onCryptoPaymentFailed = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            Timber.w("🪙 [Socket] Crypto payment failed: ${data.optString("requestId")} - ${data.optString("reason")}")
            _events.tryEmit(
                SocketEvent.CryptoPaymentFailed(
                    requestId = data.optString("requestId", ""),
                    paymentId = data.optString("paymentId").takeIf { it.isNotEmpty() },
                    reason = data.optString("reason", "Payment failed"),
                    status = data.optString("status", ""),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing crypto:payment_failed")
        }
    }

    // ========================================
    // Event Handlers - Orders
    // ========================================

    private val onOrderCreated = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(parseOrderEvent(data))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing order_created")
        }
    }

    private val onOrderUpdated = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(parseOrderUpdatedEvent(data))  // ✅ FIX: Use parseOrderUpdatedEvent
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing order_updated")
        }
    }

    private val onOrderStatusChanged = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.OrderStatusChanged(
                    orderId = data.optString("orderId", ""),
                    venueId = data.optString("venueId", ""),
                    tableId = data.optString("tableId").takeIf { it.isNotEmpty() },
                    status = data.optString("status", ""),
                    previousStatus = data.optJSONObject("metadata")?.optString("previousStatus"),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing order_status_changed")
        }
    }

    private val onOrderDeleted = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.OrderDeleted(
                    orderId = data.optString("orderId", ""),
                    venueId = data.optString("venueId", ""),
                    userId = data.optString("userId").takeIf { it.isNotEmpty() },
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing order_deleted")
        }
    }

    private fun parseOrderEvent(data: JSONObject): SocketEvent.OrderCreated {
        return SocketEvent.OrderCreated(
            orderId = data.optString("orderId", ""),
            venueId = data.optString("venueId", ""),
            tableId = data.optString("tableId").takeIf { it.isNotEmpty() },
            status = data.optString("status").takeIf { it.isNotEmpty() },
            items = data.optJSONArray("items")?.toList(),
            total = data.optDouble("total").takeIf { !it.isNaN() },
            timestamp = data.optString("timestamp", "")
        )
    }

    /**
     * Parse order updated event (critical fix for real-time inventory sync)
     */
    private fun parseOrderUpdatedEvent(data: JSONObject): SocketEvent.OrderUpdated {
        return SocketEvent.OrderUpdated(
            orderId = data.optString("orderId", ""),
            venueId = data.optString("venueId", ""),
            tableId = data.optString("tableId").takeIf { it.isNotEmpty() },
            status = data.optString("status").takeIf { it.isNotEmpty() },
            items = data.optJSONArray("items")?.toList(),
            total = data.optDouble("total").takeIf { !it.isNaN() },
            timestamp = data.optString("timestamp", "")
        )
    }

    // ========================================
    // Event Handlers - System
    // ========================================

    private val onSystemAlert = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.SystemAlert(
                    level = data.optString("level", "info"),
                    title = data.optString("title", ""),
                    message = data.optString("message", ""),
                    targetRoles = data.optJSONArray("targetRoles")?.toStringList(),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing system_alert")
        }
    }

    private val onVenueUpdate = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.VenueUpdate(
                    type = data.optString("type", ""),
                    userId = data.optString("userId", ""),
                    role = data.optString("role", ""),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing venue_update")
        }
    }

    private val onTableStatusChange = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.TableStatusChange(
                    type = data.optString("type", ""),
                    userId = data.optString("userId", ""),
                    role = data.optString("role", ""),
                    tableId = data.optString("tableId", ""),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing table_status_change")
        }
    }

    // ========================================
    // Event Handlers - Notifications
    // ========================================

    private val onNotificationNew = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.NotificationNew(
                    notificationId = data.optString("notificationId", ""),
                    recipientId = data.optString("recipientId", ""),
                    type = data.optString("type", ""),
                    title = data.optString("title", ""),
                    message = data.optString("message", ""),
                    priority = data.optString("priority", "NORMAL"),
                    isRead = data.optBoolean("isRead", false),
                    actionUrl = data.optString("actionUrl").takeIf { it.isNotEmpty() },
                    actionLabel = data.optString("actionLabel").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing notification_new")
        }
    }

    private val onNotificationRead = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.NotificationRead(
                    notificationId = data.optString("notificationId", ""),
                    recipientId = data.optString("recipientId", ""),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing notification_read")
        }
    }

    private val onNotificationDeleted = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.NotificationDeleted(
                    notificationId = data.optString("notificationId", ""),
                    recipientId = data.optString("recipientId", ""),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing notification_deleted")
        }
    }

    private val onNotificationCountUpdated = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.NotificationCountUpdated(
                    action = data.optString("action", ""),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing notification_count_updated")
        }
    }

    // ========================================
    // Event Handlers - TPV Commands (Enterprise Command System)
    // ========================================

    private val onTPVCommand = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            Timber.d("📡 [TPV Command] Received: ${data.optString("type")}")
            _events.tryEmit(
                SocketEvent.TPVCommand(
                    terminalId = data.optString("terminalId", ""),
                    commandId = data.optString("commandId", ""),
                    correlationId = data.optString("correlationId", ""),
                    commandType = data.optString("type", ""),
                    payload = data.optJSONObject("payload")?.toMap(),
                    requiresPin = data.optBoolean("requiresPin", false),
                    priority = data.optString("priority", "NORMAL"),
                    expiresAt = data.optString("expiresAt", ""),
                    requestedBy = data.optString("requestedBy", ""),
                    requestedByName = data.optString("requestedByName").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing tpv_command")
        }
    }

    private val onTPVCommandCancelled = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            Timber.d("🚫 [TPV Command] Cancelled: ${data.optString("commandId")}")
            _events.tryEmit(
                SocketEvent.TPVCommandCancelled(
                    terminalId = data.optString("terminalId", ""),
                    commandId = data.optString("commandId", ""),
                    correlationId = data.optString("correlationId", ""),
                    cancelledBy = data.optString("cancelledBy", ""),
                    reason = data.optString("reason").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing tpv_command_cancelled")
        }
    }

    private val onTPVCommandResponse = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.TPVCommandResponse(
                    terminalId = data.optString("terminalId", ""),
                    commandType = data.optString("commandType", ""),
                    status = data.optString("status", ""),
                    message = data.optString("message").takeIf { it.isNotEmpty() },
                    executedAt = data.optString("executedAt", ""),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing tpv_command_response")
        }
    }

    private val onTPVStatusUpdate = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.TPVStatusUpdate(
                    terminalId = data.optString("terminalId", ""),
                    status = data.optString("status", ""),
                    lastHeartbeat = data.optString("lastHeartbeat").takeIf { it.isNotEmpty() },
                    version = data.optString("version").takeIf { it.isNotEmpty() },
                    ipAddress = data.optString("ipAddress").takeIf { it.isNotEmpty() },
                    systemInfo = data.optJSONObject("systemInfo")?.toMap(),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing tpv_status_update")
        }
    }

    // ========================================
    // Event Handlers - Inventory (NEW)
    // ========================================

    private val onInventoryLowStock = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(parseInventoryEvent(data, "low_stock"))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing inventory_low_stock")
        }
    }

    private val onInventoryOutOfStock = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(parseInventoryEvent(data, "out_of_stock"))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing inventory_out_of_stock")
        }
    }

    private val onInventoryUpdated = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(parseInventoryEvent(data, "updated"))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing inventory_updated")
        }
    }

    private fun parseInventoryEvent(data: JSONObject, type: String): SocketEvent {
        val rawMaterialId = data.optString("rawMaterialId", "")
        val rawMaterialName = data.optString("rawMaterialName", "")
        val currentStock = data.optDouble("currentStock", 0.0)
        val unit = data.optString("unit", "")
        val threshold = data.optDouble("threshold").takeIf { !it.isNaN() }
        val batchInfo = data.optJSONObject("batchInfo")?.toMap()
        val venueId = data.optString("venueId", "")
        val timestamp = data.optString("timestamp", "")
        val metadata = data.optJSONObject("metadata")?.toMap()

        return when (type) {
            "low_stock" -> SocketEvent.InventoryLowStock(rawMaterialId, rawMaterialName, currentStock, unit, threshold, batchInfo, venueId, timestamp, metadata)
            "out_of_stock" -> SocketEvent.InventoryOutOfStock(rawMaterialId, rawMaterialName, currentStock, unit, threshold, batchInfo, venueId, timestamp, metadata)
            "updated" -> SocketEvent.InventoryUpdated(rawMaterialId, rawMaterialName, currentStock, unit, threshold, batchInfo, venueId, timestamp, metadata)
            else -> throw IllegalArgumentException("Unknown inventory event type: $type")
        }
    }

    // ========================================
    // Event Handlers - Menu & Products (NEW)
    // ========================================

    private val onMenuUpdated = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.MenuUpdated(
                    updateType = data.optString("updateType", "FULL_REFRESH"),
                    categoryIds = data.optJSONArray("categoryIds")?.toStringList(),
                    productIds = data.optJSONArray("productIds")?.toStringList(),
                    reason = data.optString("reason", ""),
                    updatedBy = data.optString("updatedBy").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
            Timber.d("🍽️ Menu updated: ${data.optString("reason")}")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing menu_updated")
        }
    }

    private val onMenuItemCreated = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.MenuItemCreated(
                    itemId = data.optString("itemId", ""),
                    itemName = data.optString("itemName", ""),
                    sku = data.optString("sku").takeIf { it.isNotEmpty() },
                    categoryId = data.optString("categoryId").takeIf { it.isNotEmpty() },
                    categoryName = data.optString("categoryName").takeIf { it.isNotEmpty() },
                    price = data.optDouble("price").takeIf { !it.isNaN() },
                    available = data.optBoolean("available"),
                    imageUrl = data.optString("imageUrl").takeIf { it.isNotEmpty() },
                    description = data.optString("description").takeIf { it.isNotEmpty() },
                    modifierGroupIds = data.optJSONArray("modifierGroupIds")?.toStringList(),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
            Timber.d("✅ Menu item created: ${data.optString("itemName")}")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing menu_item_created")
        }
    }

    private val onMenuItemUpdated = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.MenuItemUpdated(
                    itemId = data.optString("itemId", ""),
                    itemName = data.optString("itemName", ""),
                    sku = data.optString("sku").takeIf { it.isNotEmpty() },
                    categoryId = data.optString("categoryId").takeIf { it.isNotEmpty() },
                    categoryName = data.optString("categoryName").takeIf { it.isNotEmpty() },
                    price = data.optDouble("price").takeIf { !it.isNaN() },
                    available = data.optBoolean("available"),
                    imageUrl = data.optString("imageUrl").takeIf { it.isNotEmpty() },
                    description = data.optString("description").takeIf { it.isNotEmpty() },
                    modifierGroupIds = data.optJSONArray("modifierGroupIds")?.toStringList(),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
            Timber.d("🔄 Menu item updated: ${data.optString("itemName")}")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing menu_item_updated")
        }
    }

    private val onMenuItemDeleted = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.MenuItemDeleted(
                    itemId = data.optString("itemId", ""),
                    itemName = data.optString("itemName", ""),
                    sku = data.optString("sku").takeIf { it.isNotEmpty() },
                    categoryId = data.optString("categoryId").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
            Timber.d("🗑️ Menu item deleted: ${data.optString("itemName")}")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing menu_item_deleted")
        }
    }

    private val onMenuItemAvailabilityChanged = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.MenuItemAvailabilityChanged(
                    itemId = data.optString("itemId", ""),
                    itemName = data.optString("itemName", ""),
                    available = data.optBoolean("available"),
                    previousAvailability = data.optBoolean("previousAvailability"),
                    reason = data.optString("reason").takeIf { it.isNotEmpty() },
                    affectedOrders = data.optJSONArray("affectedOrders")?.toStringList(),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
            Timber.d("🔔 Item availability changed: ${data.optString("itemName")} -> ${data.optBoolean("available")}")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing menu_item_availability_changed")
        }
    }

    private val onProductPriceChanged = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.ProductPriceChanged(
                    productId = data.optString("productId", ""),
                    productName = data.optString("productName", ""),
                    sku = data.optString("sku", ""),
                    oldPrice = data.optDouble("oldPrice"),
                    newPrice = data.optDouble("newPrice"),
                    priceChange = data.optDouble("priceChange"),
                    priceChangePercent = data.optDouble("priceChangePercent"),
                    categoryId = data.optString("categoryId", ""),
                    categoryName = data.optString("categoryName", ""),
                    updatedBy = data.optString("updatedBy").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
            Timber.d("💰 Price changed: ${data.optString("productName")} ${data.optDouble("oldPrice")} -> ${data.optDouble("newPrice")}")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing product_price_changed")
        }
    }

    private val onMenuCategoryUpdated = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.MenuCategoryUpdated(
                    categoryId = data.optString("categoryId", ""),
                    categoryName = data.optString("categoryName", ""),
                    action = data.optString("action", "UPDATED"),
                    displayOrder = data.optInt("displayOrder").takeIf { data.has("displayOrder") },
                    active = data.optBoolean("active").takeIf { data.has("active") },
                    parentId = data.optString("parentId").takeIf { it.isNotEmpty() },
                    affectedItemCount = data.optInt("affectedItemCount").takeIf { data.has("affectedItemCount") },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
            Timber.d("📂 Category updated: ${data.optString("categoryName")} (${data.optString("action")})")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing menu_category_updated")
        }
    }

    private val onMenuCategoryDeleted = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.MenuCategoryDeleted(
                    categoryId = data.optString("categoryId", ""),
                    categoryName = data.optString("categoryName", ""),
                    action = data.optString("action", "DELETED"),
                    displayOrder = data.optInt("displayOrder").takeIf { data.has("displayOrder") },
                    active = data.optBoolean("active").takeIf { data.has("active") },
                    parentId = data.optString("parentId").takeIf { it.isNotEmpty() },
                    affectedItemCount = data.optInt("affectedItemCount").takeIf { data.has("affectedItemCount") },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
            Timber.d("🗑️ Category deleted: ${data.optString("categoryName")}")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing menu_category_deleted")
        }
    }

    // ========================================
    // Event Handlers - Hardware (NEW)
    // ========================================

    private val onPrinterStatus = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.PrinterStatus(
                    terminalId = data.optString("terminalId", ""),
                    printerType = data.optString("printerType", ""),
                    status = data.optString("status", ""),
                    errorMessage = data.optString("errorMessage").takeIf { it.isNotEmpty() },
                    lastPrintedAt = data.optString("lastPrintedAt").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing printer_status")
        }
    }

    private val onCardReaderStatus = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.CardReaderStatus(
                    terminalId = data.optString("terminalId", ""),
                    readerType = data.optString("readerType", ""),
                    status = data.optString("status", ""),
                    errorMessage = data.optString("errorMessage").takeIf { it.isNotEmpty() },
                    lastReadAt = data.optString("lastReadAt").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing card_reader_status")
        }
    }

    private val onPeripheralError = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.PeripheralError(
                    terminalId = data.optString("terminalId", ""),
                    peripheralType = data.optString("peripheralType", ""),
                    errorCode = data.optString("errorCode", ""),
                    errorMessage = data.optString("errorMessage", ""),
                    severity = data.optString("severity", "medium"),
                    recoverable = data.optBoolean("recoverable", true),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing peripheral_error")
        }
    }

    // ========================================
    // Event Handler - Terminal Payment Request
    // ========================================

    private val onTerminalPaymentRequest = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener

            Timber.i("💳 [Socket] Terminal payment request received: ${data.optString("requestId")}")
            _events.tryEmit(
                SocketEvent.TerminalPaymentRequest(
                    requestId = data.optString("requestId", ""),
                    amountCents = data.optLong("amountCents", 0),
                    tipCents = data.optLong("tipCents", 0),
                    rating = data.optInt("rating").takeIf { data.has("rating") },
                    skipReview = data.optBoolean("skipReview", true),
                    orderId = data.optString("orderId").takeIf { it.isNotEmpty() },
                    senderDeviceName = data.optString("senderDeviceName").takeIf { it.isNotEmpty() },
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing terminal:payment_request")
        }
    }

    private val onTerminalPaymentCancel = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener

            Timber.i("🚫 [Socket] Terminal payment cancel received: ${data.optString("requestId")}")
            _events.tryEmit(
                SocketEvent.TerminalPaymentCancel(
                    requestId = data.optString("requestId").takeIf { it.isNotEmpty() },
                    reason = data.optString("reason", "Cancelled by user"),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing terminal:payment_cancel")
        }
    }

    // ========================================
    // Event Handlers - TPV Messages
    // ========================================

    private val onTpvMessage = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener

            Timber.i("📨 [Socket] TPV message received: ${data.optString("title")} (${data.optString("type")})")

            // Parse survey options from JSON array
            val surveyOptionsArray = data.optJSONArray("surveyOptions")
            val surveyOptions = if (surveyOptionsArray != null) {
                (0 until surveyOptionsArray.length()).map { surveyOptionsArray.getString(it) }
            } else null

            _events.tryEmit(
                SocketEvent.TpvMessageReceived(
                    messageId = data.optString("messageId", ""),
                    type = data.optString("type", "ANNOUNCEMENT"),
                    title = data.optString("title", ""),
                    body = data.optString("body", ""),
                    priority = data.optString("priority", "NORMAL"),
                    requiresAck = data.optBoolean("requiresAck", false),
                    surveyOptions = surveyOptions,
                    surveyMultiSelect = data.optBoolean("surveyMultiSelect", false),
                    actionLabel = data.optString("actionLabel").takeIf { it.isNotEmpty() },
                    actionType = data.optString("actionType").takeIf { it.isNotEmpty() },
                    expiresAt = data.optString("expiresAt").takeIf { it.isNotEmpty() && it != "null" },
                    createdByName = data.optString("createdByName", ""),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing tpv_message")
        }
    }

    private val onTpvMessageCancelled = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener

            Timber.i("📨 [Socket] TPV message cancelled: ${data.optString("messageId")}")
            _events.tryEmit(
                SocketEvent.TpvMessageCancelled(
                    messageId = data.optString("messageId", ""),
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing tpv_message_cancelled")
        }
    }

    // ========================================
    // Emit Methods - TPV Messages
    // ========================================

    /**
     * Emit message acknowledgment to server
     */
    fun emitMessageAck(messageId: String, terminalId: String, action: String, staffId: String? = null) {
        socket?.emit("tpv_message_ack", JSONObject().apply {
            put("messageId", messageId)
            put("terminalId", terminalId)
            put("action", action) // ACKNOWLEDGED | DISMISSED
            put("staffId", staffId ?: JSONObject.NULL)
            put("timestamp", java.time.Instant.now().toString())
        })
        Timber.d("📤 Emitted tpv_message_ack: $messageId ($action)")
    }

    /**
     * Emit survey response to server
     */
    fun emitMessageResponse(
        messageId: String,
        terminalId: String,
        selectedOptions: List<String>,
        staffId: String? = null,
        staffName: String? = null
    ) {
        socket?.emit("tpv_message_response", JSONObject().apply {
            put("messageId", messageId)
            put("terminalId", terminalId)
            put("selectedOptions", org.json.JSONArray(selectedOptions))
            put("staffId", staffId ?: JSONObject.NULL)
            put("staffName", staffName ?: JSONObject.NULL)
            put("timestamp", java.time.Instant.now().toString())
        })
        Timber.d("📤 Emitted tpv_message_response: $messageId options=$selectedOptions")
    }

    // ========================================
    // Event Handlers - Errors
    // ========================================

    private val onSocketError = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.Error(
                    correlationId = data.optString("correlationId").takeIf { it.isNotEmpty() },
                    error = data.optString("error", ""),
                    statusCode = data.optInt("statusCode").takeIf { it > 0 },
                    message = data.optString("message").takeIf { it.isNotEmpty() }
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing socket error event")
        }
    }

    private val onRateLimitExceeded = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            _events.tryEmit(
                SocketEvent.RateLimitExceeded(
                    error = data.optString("error", ""),
                    message = data.optString("message", "")
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing rate_limit_exceeded")
        }
    }

    // ========================================
    // Generic Emit Methods
    // ========================================

    /**
     * Emit a custom event with data (for observability and other use cases)
     */
    fun emit(event: String, data: Map<String, Any?>) {
        try {
            val jsonData = mapToJsonObject(data)
            socket?.emit(event, jsonData)
            Timber.d("📤 Emitted event: $event")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to emit event: $event")
        }
    }

    /**
     * Convert Map to JSONObject (helper for emit)
     */
    private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        map.forEach { (key, value) ->
            when (value) {
                is Map<*, *> -> json.put(key, mapToJsonObject(value as Map<String, Any?>))
                is List<*> -> json.put(key, JSONArray(value))
                else -> json.put(key, value)
            }
        }
        return json
    }

    // ========================================
    // TPV Command ACK Emissions
    // ========================================

    /**
     * Send acknowledgment that command was received
     * Called immediately when tpv_command event arrives
     */
    fun emitCommandAck(commandId: String, terminalId: String) {
        try {
            socket?.emit("tpv_command_ack", JSONObject().apply {
                put("commandId", commandId)
                put("terminalId", terminalId)
                put("receivedAt", java.time.Instant.now().toString())
            })
            Timber.d("✅ [ACK] Command received: $commandId")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to emit command ACK")
        }
    }

    /**
     * Notify server that command execution has started
     * Called after ACK, before actual execution
     */
    fun emitCommandStarted(commandId: String, terminalId: String) {
        try {
            socket?.emit("tpv_command_started", JSONObject().apply {
                put("commandId", commandId)
                put("terminalId", terminalId)
                put("startedAt", java.time.Instant.now().toString())
            })
            Timber.d("🚀 [STARTED] Command executing: $commandId")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to emit command started")
        }
    }

    /**
     * Send command execution result to server
     * Called after command completes (success or failure)
     *
     * @param commandId Unique command ID
     * @param terminalId This terminal's ID
     * @param resultStatus SUCCESS, FAILED, TIMEOUT, REJECTED
     * @param message Human-readable result message
     * @param resultData Optional result data (e.g., exported log URL)
     */
    fun emitCommandResult(
        commandId: String,
        terminalId: String,
        resultStatus: String,
        message: String?,
        resultData: Map<String, Any>? = null
    ) {
        try {
            socket?.emit("tpv_command_result", JSONObject().apply {
                put("commandId", commandId)
                put("terminalId", terminalId)
                put("resultStatus", resultStatus)
                put("message", message ?: JSONObject.NULL)
                put("resultData", resultData?.let { JSONObject(it) } ?: JSONObject.NULL)
                put("completedAt", java.time.Instant.now().toString())
            })
            Timber.d("📊 [RESULT] Command $commandId: $resultStatus")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to emit command result")
        }
    }

    // ========================================
    // Terminal Payment Result Emission
    // ========================================

    /**
     * Send payment result back to backend via Socket.IO.
     * Backend resolves the pending HTTP request from iOS.
     *
     * @param requestId The original request ID from terminal:payment_request
     * @param status "success" or "failed"
     * @param transactionId Blumon transaction ID (if success)
     * @param cardDetails Card info for receipt
     * @param errorMessage Error description (if failed)
     * @param receiptUrl Receipt URL (if available)
     * @param receiptAccessKey Receipt access key for QR (if available)
     */
    fun emitTerminalPaymentResult(
        requestId: String,
        status: String,
        transactionId: String? = null,
        cardDetails: Map<String, String?>? = null,
        errorMessage: String? = null,
        receiptUrl: String? = null,
        receiptAccessKey: String? = null
    ) {
        try {
            socket?.emit("terminal:payment_result", JSONObject().apply {
                put("requestId", requestId)
                put("status", status)
                put("transactionId", transactionId ?: JSONObject.NULL)
                put("errorMessage", errorMessage ?: JSONObject.NULL)
                if (cardDetails != null) {
                    put("cardDetails", JSONObject().apply {
                        cardDetails.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
                    })
                }
                if (receiptUrl != null || receiptAccessKey != null) {
                    put("receipt", JSONObject().apply {
                        put("receiptUrl", receiptUrl ?: JSONObject.NULL)
                        put("receiptAccessKey", receiptAccessKey ?: JSONObject.NULL)
                    })
                }
                put("completedAt", java.time.Instant.now().toString())
            })
            Timber.i("📡 [Socket] Emitted terminal:payment_result | requestId=$requestId | status=$status")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to emit terminal:payment_result")
        }
    }

    // ========================================
    // Helper Extension Functions
    // ========================================

    /**
     * Convert JSONObject to Map<String, Any>
     */
    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next() as String
            val value = this.opt(key) ?: continue
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                else -> value
            }
        }
        return map
    }

    /**
     * Convert JSONArray to List<Any>
     */
    private fun JSONArray.toList(): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until length()) {
            val value = get(i)
            list.add(when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                else -> value
            })
        }
        return list
    }

    /**
     * Convert JSONArray to List<String>
     */
    private fun JSONArray.toStringList(): List<String> {
        val list = mutableListOf<String>()
        for (i in 0 until length()) {
            list.add(getString(i))
        }
        return list
    }
}
