package com.jaac.avoqado_tpv.core.data.realtime.events

/**
 * SocketEvent - Type-safe Socket.IO events
 *
 * Sealed class representing ALL possible Socket.IO events from server.
 * Pattern: Each event type corresponds to server events in
 * `avoqado-server/src/communication/sockets/types/index.ts`
 *
 * @see avoqado-server/src/communication/sockets/types/index.ts:SocketEventType
 */
sealed interface SocketEvent {

    // ========================================
    // Connection Events
    // ========================================

    data object Connected : SocketEvent
    data object Disconnected : SocketEvent
    data class ConnectionError(val message: String, val cause: Throwable? = null) : SocketEvent

    // ========================================
    // Authentication Events
    // ========================================

    data class AuthSuccess(
        val userId: String,
        val venueId: String,
        val role: String,
        val connectedAt: String
    ) : SocketEvent

    data class AuthError(val error: String, val message: String) : SocketEvent

    // ========================================
    // Room Events
    // ========================================

    data class RoomJoined(
        val success: Boolean,
        val roomType: String,
        val venueId: String,
        val tableId: String? = null,
        val orderId: String? = null
    ) : SocketEvent

    data class RoomLeft(
        val success: Boolean,
        val roomType: String,
        val venueId: String
    ) : SocketEvent

    // ========================================
    // Payment Events (CRITICAL for TPV)
    // ========================================

    data class PaymentInitiated(
        val paymentId: String,
        val amount: Int, // in cents
        val currency: String,
        val tableId: String?,
        val orderId: String?,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    data class PaymentProcessing(
        val paymentId: String,
        val amount: Int,
        val currency: String,
        val tableId: String?,
        val orderId: String?,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    data class PaymentCompleted(
        val paymentId: String,
        val amount: Int,
        val currency: String,
        val tableId: String?,
        val orderId: String?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class PaymentFailed(
        val paymentId: String,
        val amount: Int,
        val currency: String,
        val tableId: String?,
        val orderId: String?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    // ========================================
    // Crypto Payment Events (B4Bit Integration)
    // ========================================

    /**
     * 🪙 Crypto payment confirmed by B4Bit webhook.
     *
     * Emitted when customer successfully pays with crypto and the blockchain
     * transaction has been confirmed.
     *
     * **Handling:**
     * - Transition from AwaitingCryptoPayment → Success
     * - Play success sound
     * - Show receipt with blockchain transaction hash
     *
     * @param requestId B4Bit request ID for matching
     * @param paymentId Our internal payment ID
     * @param amount Amount in centavos
     * @param currency Fiat currency ("MXN")
     * @param txHash Blockchain transaction hash
     * @param cryptoAmount Amount in crypto (e.g., "0.00123")
     * @param cryptoCurrency Cryptocurrency used (e.g., "BTC", "ETH")
     * @param confirmations Number of blockchain confirmations
     * @param orderId Associated order ID (if any)
     * @param orderNumber Order number for display
     * @param receiptUrl Digital receipt URL
     * @param receiptAccessKey Receipt access key for QR
     * @param venueId Venue ID
     * @param timestamp ISO timestamp
     */
    data class CryptoPaymentConfirmed(
        val requestId: String,
        val paymentId: String,
        val amount: Int,
        val currency: String,
        val txHash: String,
        val cryptoAmount: String,
        val cryptoCurrency: String,
        val confirmations: Int?,
        val orderId: String?,
        val orderNumber: String?,
        val receiptUrl: String?,
        val receiptAccessKey: String?,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    /**
     * 🚫 Crypto payment failed or expired.
     *
     * Emitted when:
     * - Customer didn't pay before order expired
     * - Customer paid insufficient amount
     * - Other blockchain/gateway error
     *
     * **Handling:**
     * - Transition from AwaitingCryptoPayment → Error
     * - Show error message with retry option
     *
     * @param requestId B4Bit request ID for matching
     * @param paymentId Our internal payment ID (if available)
     * @param reason Failure reason (human-readable)
     * @param status B4Bit status code ("OC" = insufficient, "EX" = expired)
     * @param venueId Venue ID
     * @param timestamp ISO timestamp
     */
    data class CryptoPaymentFailed(
        val requestId: String,
        val paymentId: String?,
        val reason: String,
        val status: String,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    // ========================================
    // Terminal Payment Request (iOS → Backend → TPV via Socket.IO)
    // ========================================

    /**
     * Payment request received via Socket.IO from backend.
     * iOS app sent an HTTP request to backend, which forwards it here.
     * TPV processes the payment and sends result back via Socket.IO.
     *
     * @param requestId Unique request ID for matching response
     * @param amountCents Payment amount in cents
     * @param tipCents Tip amount in cents
     * @param rating Customer rating (1-5)
     * @param skipReview Whether to skip review/tip screens
     * @param orderId Associated order ID (null for fast payments)
     * @param senderDeviceName Name of the iOS device that sent the request
     * @param venueId Venue ID
     * @param timestamp ISO timestamp
     */
    data class TerminalPaymentRequest(
        val requestId: String,
        val amountCents: Long,
        val tipCents: Long,
        val rating: Int?,
        val skipReview: Boolean,
        val orderId: String?,
        val senderDeviceName: String?,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    /**
     * Received when iOS user cancels a pending terminal payment.
     * TPV should only cancel if currentRequestId matches (idempotency).
     *
     * @param requestId The request ID to cancel (if it matches current payment)
     * @param reason Cancellation reason
     * @param timestamp ISO timestamp
     */
    data class TerminalPaymentCancel(
        val requestId: String?,
        val reason: String,
        val timestamp: String
    ) : SocketEvent

    // ========================================
    // Order Events
    // ========================================

    data class OrderCreated(
        val orderId: String,
        val venueId: String,
        val tableId: String?,
        val status: String?,
        val items: List<Any>?,
        val total: Double?,
        val timestamp: String
    ) : SocketEvent

    data class OrderUpdated(
        val orderId: String,
        val venueId: String,
        val tableId: String?,
        val status: String?,
        val items: List<Any>?,
        val total: Double?,
        val timestamp: String
    ) : SocketEvent

    data class OrderStatusChanged(
        val orderId: String,
        val venueId: String,
        val tableId: String?,
        val status: String,
        val previousStatus: String?,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class OrderDeleted(
        val orderId: String,
        val venueId: String,
        val userId: String?,
        val timestamp: String
    ) : SocketEvent

    // ========================================
    // System Events
    // ========================================

    data class SystemAlert(
        val level: String, // 'info' | 'warning' | 'error' | 'critical'
        val title: String,
        val message: String,
        val targetRoles: List<String>?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class VenueUpdate(
        val type: String, // 'user_connected' | 'user_disconnected'
        val userId: String,
        val role: String,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    data class TableStatusChange(
        val type: String, // 'user_joined_table' | 'user_left_table'
        val userId: String,
        val role: String,
        val tableId: String,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    // ========================================
    // Notification Events
    // ========================================

    data class NotificationNew(
        val notificationId: String,
        val recipientId: String,
        val type: String,
        val title: String,
        val message: String,
        val priority: String, // 'LOW' | 'NORMAL' | 'HIGH'
        val isRead: Boolean,
        val actionUrl: String?,
        val actionLabel: String?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class NotificationRead(
        val notificationId: String,
        val recipientId: String,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    data class NotificationDeleted(
        val notificationId: String,
        val recipientId: String,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    data class NotificationCountUpdated(
        val action: String, // 'increment' | 'decrement'
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    // ========================================
    // TPV Admin Commands (Enterprise Command System)
    // ========================================

    /**
     * Remote command sent from dashboard to this terminal
     * @see avoqado-server/src/services/tpv/command-execution.service.ts:CommandPayload
     */
    data class TPVCommand(
        val terminalId: String,
        val commandId: String, // Unique command ID for ACK tracking
        val correlationId: String, // For linking request/response
        val commandType: String, // TpvCommandType enum value
        val payload: Map<String, Any>?,
        val requiresPin: Boolean, // Whether PIN verification is needed
        val priority: String, // LOW | NORMAL | HIGH | CRITICAL
        val expiresAt: String, // ISO 8601 - Command expiration time
        val requestedBy: String, // User ID who sent the command
        val requestedByName: String?, // Display name of requester
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    /**
     * Command cancelled by admin before execution
     * @see avoqado-server event: tpv_command_cancelled
     */
    data class TPVCommandCancelled(
        val terminalId: String,
        val commandId: String,
        val correlationId: String,
        val cancelledBy: String, // User ID who cancelled
        val reason: String?, // Cancellation reason
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    /**
     * Response to a command (legacy - kept for backwards compatibility)
     */
    data class TPVCommandResponse(
        val terminalId: String,
        val commandType: String,
        val status: String, // 'success' | 'failed' | 'timeout'
        val message: String?,
        val executedAt: String,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class TPVStatusUpdate(
        val terminalId: String,
        val status: String, // 'ONLINE' | 'OFFLINE' | 'MAINTENANCE' | 'DISABLED'
        val lastHeartbeat: String?,
        val version: String?,
        val ipAddress: String?,
        val systemInfo: Map<String, Any>?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    // ========================================
    // Inventory Real-time Events (NEW)
    // ========================================

    data class InventoryLowStock(
        val rawMaterialId: String,
        val rawMaterialName: String,
        val currentStock: Double,
        val unit: String,
        val threshold: Double?,
        val batchInfo: Map<String, Any>?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class InventoryOutOfStock(
        val rawMaterialId: String,
        val rawMaterialName: String,
        val currentStock: Double,
        val unit: String,
        val threshold: Double?,
        val batchInfo: Map<String, Any>?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class InventoryUpdated(
        val rawMaterialId: String,
        val rawMaterialName: String,
        val currentStock: Double,
        val unit: String,
        val threshold: Double?,
        val batchInfo: Map<String, Any>?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    // ========================================
    // Menu & Product Real-time Events (NEW)
    // ========================================

    data class MenuUpdated(
        val updateType: String, // 'FULL_REFRESH' | 'PARTIAL_UPDATE'
        val categoryIds: List<String>?, // Categories affected (if partial)
        val productIds: List<String>?, // Products affected (if partial)
        val reason: String, // 'PRICE_CHANGE' | 'AVAILABILITY_CHANGE' | 'ITEM_ADDED' | 'ITEM_REMOVED' | 'CATEGORY_UPDATED'
        val updatedBy: String?, // User ID who made the change
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class MenuItemCreated(
        val itemId: String,
        val itemName: String,
        val sku: String?,
        val categoryId: String?,
        val categoryName: String?,
        val price: Double?,
        val available: Boolean?,
        val imageUrl: String?,
        val description: String?,
        val modifierGroupIds: List<String>?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class MenuItemUpdated(
        val itemId: String,
        val itemName: String,
        val sku: String?,
        val categoryId: String?,
        val categoryName: String?,
        val price: Double?,
        val available: Boolean?,
        val imageUrl: String?,
        val description: String?,
        val modifierGroupIds: List<String>?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class MenuItemDeleted(
        val itemId: String,
        val itemName: String,
        val sku: String?,
        val categoryId: String?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class MenuItemAvailabilityChanged(
        val itemId: String,
        val itemName: String,
        val available: Boolean,
        val previousAvailability: Boolean,
        val reason: String?, // 'OUT_OF_STOCK' | 'MANUAL' | 'TIME_BASED' | 'INVENTORY_DEPLETION'
        val affectedOrders: List<String>?, // Order IDs that might be affected
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class ProductPriceChanged(
        val productId: String,
        val productName: String,
        val sku: String,
        val oldPrice: Double,
        val newPrice: Double,
        val priceChange: Double, // Absolute difference
        val priceChangePercent: Double, // Percentage change
        val categoryId: String,
        val categoryName: String,
        val updatedBy: String?, // User ID who made the change
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class MenuCategoryUpdated(
        val categoryId: String,
        val categoryName: String,
        val action: String, // 'CREATED' | 'UPDATED' | 'DELETED' | 'ENABLED' | 'DISABLED' | 'REORDERED'
        val displayOrder: Int?,
        val active: Boolean?,
        val parentId: String?,
        val affectedItemCount: Int?, // Number of products in category
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class MenuCategoryDeleted(
        val categoryId: String,
        val categoryName: String,
        val action: String, // Always 'DELETED'
        val displayOrder: Int?,
        val active: Boolean?,
        val parentId: String?,
        val affectedItemCount: Int?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    // ========================================
    // Hardware Events (NEW)
    // ========================================

    data class PrinterStatus(
        val terminalId: String,
        val printerType: String, // 'THERMAL' | 'RECEIPT' | 'KITCHEN'
        val status: String, // 'ONLINE' | 'OFFLINE' | 'PAPER_LOW' | 'PAPER_OUT' | 'ERROR'
        val errorMessage: String?,
        val lastPrintedAt: String?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class CardReaderStatus(
        val terminalId: String,
        val readerType: String, // 'CONTACTLESS' | 'CHIP' | 'MAGNETIC'
        val status: String, // 'READY' | 'READING' | 'ERROR' | 'DISCONNECTED'
        val errorMessage: String?,
        val lastReadAt: String?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class PeripheralError(
        val terminalId: String,
        val peripheralType: String, // 'PRINTER' | 'CARD_READER' | 'CASH_DRAWER' | 'SCANNER' | 'OTHER'
        val errorCode: String,
        val errorMessage: String,
        val severity: String, // 'low' | 'medium' | 'high' | 'critical'
        val recoverable: Boolean,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    // ========================================
    // Error Events
    // ========================================

    data class Error(
        val correlationId: String?,
        val error: String,
        val statusCode: Int?,
        val message: String? = null
    ) : SocketEvent

    data class RateLimitExceeded(
        val error: String,
        val message: String
    ) : SocketEvent
}
