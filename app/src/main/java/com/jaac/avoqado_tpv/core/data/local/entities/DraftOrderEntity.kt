package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * DraftOrderEntity - Local-first order storage
 *
 * Room entity for storing orders with hybrid sync strategy:
 * - Create orders locally (instant UI, no network latency)
 * - Debounced auto-save to backend (5s delay, batches changes)
 * - Immediate sync on critical actions (Send to Kitchen, Payment)
 *
 * ## Sync States
 * - **SYNCED**: Local and server state match (version numbers equal)
 * - **PENDING**: Local changes not yet synced (waiting for debounce or immediate trigger)
 * - **SYNCING**: Currently syncing to server (API call in progress)
 * - **CONFLICT**: Server state diverged (version mismatch, requires user resolution)
 *
 * ## ID Strategy
 * - Start with local UUID (e.g., "local_a1b2c3d4")
 * - Replace with server CUID after first sync (e.g., "clw3k9x2b000...")
 * - `isServerCreated` flag tracks this state
 *
 * ## Multi-Terminal Sync
 * - Socket.IO `OrderUpdated` events trigger local refresh
 * - Version-based conflict detection (optimistic concurrency control)
 * - `lastSyncAt` timestamp helps detect stale data
 *
 * @property id Local UUID initially, replaced with backend CUID after creation
 * @property venueId Tenant isolation (all queries filtered by venueId)
 * @property orderNumber Human-readable (e.g., "LOCAL-123456" → "ORD-0001234")
 * @property syncStatus Current sync state (SYNCED, PENDING, SYNCING, CONFLICT)
 * @property isServerCreated False = local-only, True = has backend CUID
 * @property version Optimistic concurrency control (incremented on each server update)
 * @property conflictData JSON of server state on 409 conflict (for user resolution)
 */
@Entity(
    tableName = "draft_orders",
    indices = [
        Index(value = ["venue_id", "order_number"], unique = true),
        Index(value = ["table_id"]),
        Index(value = ["sync_status"]),
        Index(value = ["updated_at"])
    ]
)
data class DraftOrderEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "venue_id")
    val venueId: String,

    @ColumnInfo(name = "order_number")
    val orderNumber: String,

    @ColumnInfo(name = "table_id")
    val tableId: String?,

    @ColumnInfo(name = "table_name")
    val tableName: String?,

    @ColumnInfo(name = "covers")
    val covers: Int,

    @ColumnInfo(name = "waiter_id")
    val waiterId: String?,

    @ColumnInfo(name = "waiter_name")
    val waiterName: String?,

    @ColumnInfo(name = "customer_name")
    val customerName: String?,

    @ColumnInfo(name = "customer_phone")
    val customerPhone: String?,

    @ColumnInfo(name = "special_requests")
    val specialRequests: String?,

    @ColumnInfo(name = "status")
    val status: String, // OrderStatus enum as string

    @ColumnInfo(name = "kitchen_status")
    val kitchenStatus: String, // KitchenStatus enum as string

    @ColumnInfo(name = "payment_status")
    val paymentStatus: String, // PaymentStatus enum as string

    @ColumnInfo(name = "order_type")
    val orderType: String, // OrderType enum as string

    @ColumnInfo(name = "subtotal")
    val subtotal: String, // BigDecimal as String (Room doesn't support BigDecimal)

    @ColumnInfo(name = "discount_amount")
    val discountAmount: String,

    @ColumnInfo(name = "tax")
    val tax: String,

    @ColumnInfo(name = "total")
    val total: String,

    @ColumnInfo(name = "notes")
    val notes: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long, // Unix timestamp (milliseconds)

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "version")
    val version: Int, // Optimistic concurrency control

    @ColumnInfo(name = "sync_status")
    val syncStatus: String, // SYNCED, PENDING, SYNCING, CONFLICT

    @ColumnInfo(name = "is_server_created")
    val isServerCreated: Boolean, // False = local-only, True = has backend CUID

    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long? = null, // Last successful sync timestamp

    @ColumnInfo(name = "conflict_data")
    val conflictData: String? = null, // JSON of server state on 409 conflict

    @ColumnInfo(name = "merchant_account_id")
    val merchantAccountId: String? = null, // Locked merchant for this order (P0 fix - split payment validation)

    @ColumnInfo(name = "merchant_account_name")
    val merchantAccountName: String? = null // Merchant display name (for user-friendly error messages)
) {
    companion object {
        const val SYNC_STATUS_SYNCED = "SYNCED"
        const val SYNC_STATUS_PENDING = "PENDING"
        const val SYNC_STATUS_SYNCING = "SYNCING"
        const val SYNC_STATUS_CONFLICT = "CONFLICT"

        /**
         * Generate local order ID for new orders.
         * Replaced with server CUID after first sync.
         */
        fun generateLocalId(): String = "local_${java.util.UUID.randomUUID()}"

        /**
         * Generate local order number for new orders.
         * Format: LOCAL-{timestamp_last_6_digits}
         * Replaced with server order number after first sync.
         */
        fun generateLocalOrderNumber(): String {
            val timestamp = System.currentTimeMillis().toString().takeLast(6)
            return "LOCAL-$timestamp"
        }
    }
}
