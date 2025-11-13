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
class SocketManager @Inject constructor() {

    // ========================================
    // Properties
    // ========================================

    private var socket: Socket? = null
    private var currentUrl: String? = null
    private var currentToken: String? = null

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
     * @param reconnectionAttempts Max reconnection attempts (default: 5)
     */
    fun connect(
        url: String,
        token: String,
        reconnection: Boolean = true,
        reconnectionAttempts: Int = 5
    ) {
        try {
            // Disconnect existing socket if any
            disconnect()

            // Store for reconnection
            currentUrl = url
            currentToken = token

            // Socket.IO connection options
            val options = IO.Options().apply {
                // Authentication - Server checks 4 sources (auth, query, header, cookie)
                // We use auth object (most reliable for mobile)
                auth = mapOf("token" to token)

                // Transports: WebSocket preferred, fallback to polling
                transports = arrayOf("websocket", "polling")

                // Reconnection settings
                this.reconnection = reconnection
                this.reconnectionAttempts = reconnectionAttempts
                reconnectionDelay = 1000 // 1 second
                reconnectionDelayMax = 5000 // 5 seconds max
                randomizationFactor = 0.5 // Add jitter to prevent thundering herd

                // Timeouts
                timeout = 20000 // 20 seconds connection timeout

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

    /**
     * Reconnect using stored credentials
     */
    fun reconnect() {
        val url = currentUrl
        val token = currentToken

        if (url != null && token != null) {
            Timber.d("🔄 Socket.IO reconnecting...")
            connect(url, token)
        } else {
            Timber.w("⚠️ Cannot reconnect: No stored credentials")
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
        // TPV Admin Commands (NEW)
        // ========================================

        on("tpv_command", onTPVCommand)
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
        // Hardware Events (NEW)
        // ========================================

        on("printer_status", onPrinterStatus)
        on("card_reader_status", onCardReaderStatus)
        on("peripheral_error", onPeripheralError)

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
        Timber.e("❌ Socket.IO connect error: $error")
        _isConnected.tryEmit(false)
        _events.tryEmit(SocketEvent.ConnectionError("Connection error: $error"))
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
            _events.tryEmit(parseOrderEvent(data))
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
    // Event Handlers - TPV Commands (NEW)
    // ========================================

    private val onTPVCommand = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val command = data.optJSONObject("command")
            _events.tryEmit(
                SocketEvent.TPVCommand(
                    terminalId = data.optString("terminalId", ""),
                    commandType = command?.optString("type") ?: "",
                    payload = command?.optJSONObject("payload")?.toMap(),
                    requestedBy = command?.optString("requestedBy") ?: "",
                    venueId = data.optString("venueId", ""),
                    timestamp = data.optString("timestamp", ""),
                    metadata = data.optJSONObject("metadata")?.toMap()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing tpv_command")
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
