package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * DraftOrderItemEntity - Local-first order item storage
 *
 * Room entity for storing order items with hybrid sync strategy.
 * Each item belongs to a DraftOrderEntity via foreign key with CASCADE DELETE and CASCADE UPDATE.
 *
 * ## Sync States
 * - **SYNCED**: Item exists on server with matching data
 * - **PENDING**: New item not yet synced to server
 * - **SYNCING**: Currently syncing to server
 * - **DELETED**: Soft delete (marked for removal on next sync)
 *
 * ## ID Strategy
 * - Start with local UUID (e.g., "local_item_xyz")
 * - Replace with server CUID after sync (e.g., "clw3k9x2b001...")
 * - `isServerCreated` flag tracks this state
 *
 * ## Soft Delete Pattern
 * Instead of immediate DELETE, items are marked with `SYNC_STATUS_DELETED`.
 * Actual deletion happens after successful server sync.
 * This allows rollback if server rejects the deletion.
 *
 * ## Modifier Storage
 * Product modifiers (e.g., "Extra Cheese", "No Onions") are serialized as JSON string.
 * Format: `[{"id":"mod_1","name":"Extra Cheese","priceAdjustment":"2.00"}]`
 * Deserialized to List<ProductModifier> when converting to domain model.
 *
 * @property id Local UUID initially, replaced with backend CUID
 * @property orderId Foreign key to DraftOrderEntity (CASCADE DELETE, CASCADE UPDATE)
 * @property syncStatus Current sync state (SYNCED, PENDING, SYNCING, DELETED)
 * @property isServerCreated False = local-only, True = has backend CUID
 * @property modifiers JSON serialized List<ProductModifier>
 */
@Entity(
    tableName = "draft_order_items",
    foreignKeys = [
        ForeignKey(
            entity = DraftOrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["order_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["order_id"]),
        Index(value = ["product_id"]),
        Index(value = ["sync_status"])
    ]
)
data class DraftOrderItemEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "external_id")
    val externalId: String? = null,

    @ColumnInfo(name = "line_position")
    val linePosition: Long = 0L,

    @ColumnInfo(name = "order_id")
    val orderId: String, // FK to DraftOrderEntity

    @ColumnInfo(name = "product_id")
    val productId: String,

    @ColumnInfo(name = "product_name")
    val productName: String,

    @ColumnInfo(name = "product_sku")
    val productSku: String?,

    @ColumnInfo(name = "quantity")
    val quantity: Int,

    @ColumnInfo(name = "unit_price")
    val unitPrice: String, // BigDecimal as String

    @ColumnInfo(name = "total_price")
    val totalPrice: String, // BigDecimal as String

    @ColumnInfo(name = "modifiers")
    val modifiers: String, // JSON serialized List<ProductModifier>

    @ColumnInfo(name = "notes")
    val notes: String?,

    @ColumnInfo(name = "kitchen_status")
    val kitchenStatus: String, // KitchenStatus enum as string

    @ColumnInfo(name = "created_at")
    val createdAt: Long, // Unix timestamp (milliseconds)

    @ColumnInfo(name = "sent_to_kitchen_at")
    val sentToKitchenAt: Long?, // Null if not sent yet

    @ColumnInfo(name = "sync_status")
    val syncStatus: String, // SYNCED, PENDING, SYNCING, DELETED

    @ColumnInfo(name = "is_server_created")
    val isServerCreated: Boolean // False = local-only, True = has backend CUID
) {
    companion object {
        const val SYNC_STATUS_SYNCED = "SYNCED"
        const val SYNC_STATUS_PENDING = "PENDING"
        const val SYNC_STATUS_SYNCING = "SYNCING"
        const val SYNC_STATUS_DELETED = "DELETED" // Soft delete for sync

        /**
         * Generate local item ID for new items.
         * Replaced with server CUID after first sync.
         */
        fun generateLocalId(): String = "local_item_${java.util.UUID.randomUUID()}"
    }
}
