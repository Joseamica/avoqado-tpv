package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import kotlinx.coroutines.flow.Flow

/**
 * DraftOrderDao - Room database access object for draft orders
 *
 * Provides CRUD operations and specialized queries for local-first order management.
 * All operations are suspend functions for Kotlin coroutines compatibility.
 *
 * ## Key Operations
 * - **getOrder()**: Single order by ID
 * - **getOrderByTable()**: Find active order for a table (payment_status != 'PAID')
 * - **getOrdersByStatus()**: Filter by sync status (PENDING, SYNCED, CONFLICT)
 * - **replaceLocalIdWithServerCuid()**: Update ID after first sync to server
 *
 * ## Indexing Strategy
 * Queries use indexed columns for performance:
 * - (venue_id, order_number): Unique constraint + fast lookup
 * - table_id: Fast table-based queries
 * - sync_status: Filter pending/conflict orders
 * - updated_at: Sort by modification time
 *
 * @see DraftOrderEntity
 */
@Dao
interface DraftOrderDao {

    /**
     * Get single order by ID.
     * Returns null if order doesn't exist.
     *
     * @param orderId Order ID (local UUID or server CUID)
     * @return Order entity or null
     */
    @Query("SELECT * FROM draft_orders WHERE id = :orderId")
    suspend fun getOrder(orderId: String): DraftOrderEntity?

    /**
     * Get single order by ID as Flow (reactive).
     * Useful for observing order changes in UI.
     *
     * @param orderId Order ID
     * @return Flow<DraftOrderEntity?> that emits on every DB change
     */
    @Query("SELECT * FROM draft_orders WHERE id = :orderId")
    fun getOrderFlow(orderId: String): Flow<DraftOrderEntity?>

    /**
     * Get active order for a table.
     * Returns first order where payment_status != 'PAID'.
     * Used to check if table already has an open order.
     *
     * @param tableId Table ID (e.g., "table_5")
     * @return Active order or null
     */
    @Query("SELECT * FROM draft_orders WHERE table_id = :tableId AND payment_status != 'PAID' LIMIT 1")
    suspend fun getOrderByTable(tableId: String): DraftOrderEntity?

    /**
     * Get all orders for a venue filtered by sync status.
     * Useful for finding pending/conflict orders that need attention.
     *
     * @param venueId Tenant ID (all queries isolated by venue)
     * @param syncStatus SYNCED, PENDING, SYNCING, or CONFLICT
     * @return List of orders with matching status
     */
    @Query("SELECT * FROM draft_orders WHERE venue_id = :venueId AND sync_status = :syncStatus")
    suspend fun getOrdersByStatus(venueId: String, syncStatus: String): List<DraftOrderEntity>

    /**
     * Get all pending orders for a venue (need sync).
     * Convenience method for finding orders that need to sync to server.
     *
     * @param venueId Tenant ID
     * @return List of orders with PENDING status
     */
    @Query("SELECT * FROM draft_orders WHERE venue_id = :venueId AND sync_status = 'PENDING'")
    suspend fun getPendingOrders(venueId: String): List<DraftOrderEntity>

    /**
     * Get all conflict orders for a venue (need user resolution).
     *
     * @param venueId Tenant ID
     * @return List of orders with CONFLICT status
     */
    @Query("SELECT * FROM draft_orders WHERE venue_id = :venueId AND sync_status = 'CONFLICT'")
    suspend fun getConflictOrders(venueId: String): List<DraftOrderEntity>

    /**
     * Insert new order.
     * Uses REPLACE strategy: If order with same ID exists, it will be replaced.
     *
     * @param order Order entity to insert
     * @return Row ID of inserted order
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: DraftOrderEntity): Long

    /**
     * Update existing order.
     * Order must exist (same ID), otherwise throws exception.
     *
     * @param order Order entity with updated fields
     */
    @Update
    suspend fun update(order: DraftOrderEntity)

    /**
     * Delete order (and cascade delete all items via foreign key).
     *
     * @param order Order entity to delete
     */
    @Delete
    suspend fun delete(order: DraftOrderEntity)

    /**
     * Update sync status and last sync timestamp.
     * Used after successful/failed sync attempts.
     *
     * @param orderId Order ID
     * @param syncStatus New sync status (SYNCED, PENDING, SYNCING, CONFLICT)
     * @param timestamp Unix timestamp in milliseconds
     */
    @Query("UPDATE draft_orders SET sync_status = :syncStatus, last_sync_at = :timestamp WHERE id = :orderId")
    suspend fun updateSyncStatus(orderId: String, syncStatus: String, timestamp: Long)

    /**
     * Replace local ID with server CUID after first sync.
     *
     * When order is created locally, it has UUID like "local_abc123".
     * After first sync to server, backend assigns CUID like "clw3k9x2b000...".
     * This method atomically updates:
     * - id: local UUID → server CUID
     * - orderNumber: LOCAL-123456 → ORD-0001234
     * - version: Server version number
     * - isServerCreated: true
     * - syncStatus: SYNCED
     *
     * **IMPORTANT:** This must also update DraftOrderItemEntity.orderId for all items!
     * Use foreign key cascading or manual update in DraftOrderItemDao.
     *
     * @param localId Current local UUID (e.g., "local_abc123")
     * @param newId Server-assigned CUID (e.g., "clw3k9x2b000...")
     * @param orderNumber Server-assigned order number (e.g., "ORD-0001234")
     * @param version Server version number (usually 1 for new orders)
     */
    @Query("""
        UPDATE draft_orders
        SET id = :newId,
            order_number = :orderNumber,
            version = :version,
            is_server_created = 1,
            sync_status = 'SYNCED',
            last_sync_at = :timestamp
        WHERE id = :localId
    """)
    suspend fun replaceLocalIdWithServerCuid(
        localId: String,
        newId: String,
        orderNumber: String,
        version: Int,
        timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Update conflict data field with server state JSON.
     * Used when 409 conflict detected to store server version for user comparison.
     *
     * @param orderId Order ID
     * @param conflictData JSON string of server Order state
     */
    @Query("UPDATE draft_orders SET conflict_data = :conflictData, sync_status = 'CONFLICT' WHERE id = :orderId")
    suspend fun setConflictData(orderId: String, conflictData: String)

    /**
     * Clear conflict data after user resolves conflict.
     *
     * @param orderId Order ID
     */
    @Query("UPDATE draft_orders SET conflict_data = NULL, sync_status = 'SYNCED' WHERE id = :orderId")
    suspend fun clearConflictData(orderId: String)

    /**
     * Get stale orders (not synced in last N milliseconds).
     * Useful for background sync of orders that might be outdated.
     *
     * @param venueId Tenant ID
     * @param staleThresholdMs Time threshold in milliseconds (e.g., 30000 = 30 seconds)
     * @return List of orders where (current_time - last_sync_at) > threshold
     */
    @Query("""
        SELECT * FROM draft_orders
        WHERE venue_id = :venueId
        AND is_server_created = 1
        AND (:currentTime - last_sync_at) > :staleThresholdMs
    """)
    suspend fun getStaleOrders(
        venueId: String,
        staleThresholdMs: Long,
        currentTime: Long = System.currentTimeMillis()
    ): List<DraftOrderEntity>
}
