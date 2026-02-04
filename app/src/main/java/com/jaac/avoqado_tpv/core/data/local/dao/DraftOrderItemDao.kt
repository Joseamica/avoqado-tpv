package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * DraftOrderItemDao - Room database access object for draft order items
 *
 * Provides CRUD operations for order items with soft delete support.
 * Items are linked to DraftOrderEntity via foreign key with CASCADE DELETE.
 *
 * ## Soft Delete Pattern
 * Items are NOT immediately deleted from DB when user removes them.
 * Instead, they're marked with `SYNC_STATUS_DELETED` and filtered from queries.
 * Actual deletion happens after server confirms removal (or on order deletion via CASCADE).
 *
 * Benefits:
 * - Rollback if server rejects deletion
 * - Track what needs to be removed on next sync
 * - Audit trail of deletions
 *
 * @see DraftOrderItemEntity
 * @see DraftOrderDao
 */
@Dao
interface DraftOrderItemDao {

    /**
     * Get all items for an order (excluding soft-deleted).
     * Used to display current order items in UI.
     *
     * @param orderId Order ID (local UUID or server CUID)
     * @return List of items (excludes DELETED status)
     */
    @Query("SELECT * FROM draft_order_items WHERE order_id = :orderId AND sync_status != 'DELETED'")
    suspend fun getItemsByOrder(orderId: String): List<DraftOrderItemEntity>

    /**
     * Get items for an order filtered by product ID (excluding soft-deleted).
     *
     * Used to merge identical items (same product + modifiers + notes) on add.
     *
     * @param orderId Order ID
     * @param productId Product ID
     * @return List of matching items (excludes DELETED status)
     */
    @Query("""
        SELECT * FROM draft_order_items
        WHERE order_id = :orderId
          AND product_id = :productId
          AND sync_status != 'DELETED'
    """)
    suspend fun getItemsByOrderAndProduct(orderId: String, productId: String): List<DraftOrderItemEntity>

    /**
     * Get all items for an order as Flow (reactive, excluding soft-deleted).
     * Emits new list whenever items change (add/remove/update).
     *
     * @param orderId Order ID
     * @return Flow<List<DraftOrderItemEntity>> that emits on every DB change
     */
    @Query("SELECT * FROM draft_order_items WHERE order_id = :orderId AND sync_status != 'DELETED'")
    fun getItemsByOrderFlow(orderId: String): Flow<List<DraftOrderItemEntity>>

    /**
     * Get ALL items for an order (including soft-deleted).
     * Used for debugging "items disappeared" bug.
     *
     * @param orderId Order ID
     * @return List of all items (including DELETED status)
     */
    @Query("SELECT * FROM draft_order_items WHERE order_id = :orderId")
    suspend fun getAllItemsByOrderId(orderId: String): List<DraftOrderItemEntity>

    /**
     * Get all items including soft-deleted.
     * Used for sync operations that need to know what to delete on server.
     *
     * @param orderId Order ID
     * @return List of ALL items (including DELETED status)
     */
    @Query("SELECT * FROM draft_order_items WHERE order_id = :orderId")
    suspend fun getAllItemsByOrder(orderId: String): List<DraftOrderItemEntity>

    /**
     * Get items pending sync for an order.
     * Used to batch-add new items to server.
     *
     * @param orderId Order ID
     * @return List of items with PENDING status
     */
    @Query("SELECT * FROM draft_order_items WHERE order_id = :orderId AND sync_status = 'PENDING'")
    suspend fun getPendingItemsByOrder(orderId: String): List<DraftOrderItemEntity>

    /**
     * Get soft-deleted items for an order.
     * Used to batch-remove items from server.
     *
     * @param orderId Order ID
     * @return List of items with DELETED status
     */
    @Query("SELECT * FROM draft_order_items WHERE order_id = :orderId AND sync_status = 'DELETED'")
    suspend fun getDeletedItemsByOrder(orderId: String): List<DraftOrderItemEntity>

    /**
     * Get single item by ID.
     * Returns null if item doesn't exist.
     *
     * @param itemId Item ID (local UUID or server CUID)
     * @return Item entity or null
     */
    @Query("SELECT * FROM draft_order_items WHERE id = :itemId")
    suspend fun getItemById(itemId: String): DraftOrderItemEntity?

    /**
     * Insert new item.
     * Uses REPLACE strategy: If item with same ID exists, it will be replaced.
     *
     * @param item Item entity to insert
     * @return Row ID of inserted item
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DraftOrderItemEntity): Long

    /**
     * Insert multiple items in a single transaction.
     * More efficient than multiple insert() calls.
     *
     * @param items List of items to insert
     * @return List of row IDs
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DraftOrderItemEntity>): List<Long>

    /**
     * Update existing item.
     * Item must exist (same ID), otherwise throws exception.
     *
     * @param item Item entity with updated fields
     */
    @Update
    suspend fun update(item: DraftOrderItemEntity)

    /**
     * Delete item permanently (hard delete).
     * ⚠️ Use `markAsDeleted()` instead for soft delete pattern.
     *
     * @param item Item entity to delete
     */
    @Delete
    suspend fun delete(item: DraftOrderItemEntity)

    /**
     * Delete multiple items permanently (hard delete).
     * Used after successful server sync to clean up soft-deleted items.
     *
     * @param items List of items to delete
     */
    @Delete
    suspend fun deleteAll(items: List<DraftOrderItemEntity>)

    /**
     * Soft delete: Mark item as deleted without removing from DB.
     * Item will be filtered from `getItemsByOrder()` queries.
     * Actual deletion happens after server confirms removal.
     *
     * @param itemId Item ID
     */
    @Query("UPDATE draft_order_items SET sync_status = 'DELETED' WHERE id = :itemId")
    suspend fun markAsDeleted(itemId: String)

    /**
     * Update sync status for an item.
     * Used after successful/failed sync attempts.
     *
     * @param itemId Item ID
     * @param syncStatus New sync status (SYNCED, PENDING, SYNCING, DELETED)
     */
    @Query("UPDATE draft_order_items SET sync_status = :syncStatus WHERE id = :itemId")
    suspend fun updateSyncStatus(itemId: String, syncStatus: String)

    /**
     * Replace local ID with server CUID after first sync.
     *
     * When item is created locally, it has UUID like "local_item_xyz".
     * After sync to server, backend assigns CUID like "clw3k9x2b001...".
     * This method atomically updates:
     * - id: local UUID → server CUID
     * - isServerCreated: true
     * - syncStatus: SYNCED
     *
     * @param localId Current local UUID (e.g., "local_item_xyz")
     * @param newId Server-assigned CUID (e.g., "clw3k9x2b001...")
     */
    @Query("""
        UPDATE draft_order_items
        SET id = :newId,
            is_server_created = 1,
            sync_status = 'SYNCED'
        WHERE id = :localId
    """)
    suspend fun replaceLocalIdWithServerCuid(localId: String, newId: String)

    /**
     * Update order ID for all items (used when parent order ID changes).
     *
     * When DraftOrderEntity ID changes from local UUID to server CUID,
     * all child items must update their `order_id` foreign key.
     *
     * ⚠️ CRITICAL: This must be called BEFORE DraftOrderDao.replaceLocalIdWithServerCuid()
     * to avoid foreign key constraint violation.
     *
     * @param oldOrderId Current order ID (local UUID)
     * @param newOrderId New order ID (server CUID)
     */
    @Query("UPDATE draft_order_items SET order_id = :newOrderId WHERE order_id = :oldOrderId")
    suspend fun updateOrderId(oldOrderId: String, newOrderId: String)

    /**
     * Update quantity for an item.
     * Convenience method for quantity adjustments.
     *
     * @param itemId Item ID
     * @param newQuantity New quantity (must be > 0)
     * @param newTotalPrice Recalculated total price (unit_price * newQuantity)
     */
    @Query("""
        UPDATE draft_order_items
        SET quantity = :newQuantity,
            total_price = :newTotalPrice,
            sync_status = 'PENDING'
        WHERE id = :itemId
    """)
    suspend fun updateQuantity(itemId: String, newQuantity: Int, newTotalPrice: String)

    /**
     * Count items in an order (excluding soft-deleted).
     * Used for displaying item count badge in UI.
     *
     * @param orderId Order ID
     * @return Number of items in order
     */
    @Query("SELECT COUNT(*) FROM draft_order_items WHERE order_id = :orderId AND sync_status != 'DELETED'")
    suspend fun getItemCount(orderId: String): Int

    /**
     * Get max line position for items in an order.
     * Used to generate stable insertion order for new items.
     *
     * @param orderId Order ID
     * @return Max line_position or null if none
     */
    @Query("SELECT MAX(line_position) FROM draft_order_items WHERE order_id = :orderId")
    suspend fun getMaxLinePosition(orderId: String): Long?

    /**
     * Get items by product ID across all orders.
     * Useful for inventory tracking or finding popular items.
     *
     * @param productId Product ID
     * @return List of items for this product
     */
    @Query("SELECT * FROM draft_order_items WHERE product_id = :productId AND sync_status != 'DELETED'")
    suspend fun getItemsByProduct(productId: String): List<DraftOrderItemEntity>

    /**
     * Mark item as sent to kitchen by setting sentToKitchenAt timestamp.
     * Used after printing kitchen ticket.
     *
     * @param itemId Item ID
     * @param sentAt Unix timestamp (milliseconds) when sent to kitchen
     */
    @Query("UPDATE draft_order_items SET sent_to_kitchen_at = :sentAt WHERE id = :itemId")
    suspend fun markAsSentToKitchen(itemId: String, sentAt: Long)

    /**
     * Mark multiple items as sent to kitchen in a single transaction.
     * Used after printing incremental kitchen ticket (only pending items).
     *
     * @param itemIds List of item IDs to mark
     * @param sentAt Unix timestamp (milliseconds) when sent to kitchen
     */
    @Query("UPDATE draft_order_items SET sent_to_kitchen_at = :sentAt WHERE id IN (:itemIds)")
    suspend fun markItemsAsSentToKitchen(itemIds: List<String>, sentAt: Long)
}
