package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderWithItems
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
     * Get order WITH items as Flow (reactive) - Single Source of Truth pattern.
     *
     * This is the PRIMARY method for observing orders in the UI layer.
     * Room automatically emits whenever the order OR any of its items change.
     *
     * ## Usage (Single Source of Truth)
     * ```kotlin
     * // In ViewModel
     * val orderState: StateFlow<OrderUiState> = orderDao
     *     .observeOrderWithItems(orderId)
     *     .filterNotNull()
     *     .map { OrderUiState.Success(it.toDomain()) }
     *     .stateIn(viewModelScope, WhileSubscribed(5000), OrderUiState.Loading)
     * ```
     *
     * ## Benefits
     * - Room handles JOIN automatically (order + items in single emission)
     * - Flow emits on ANY change (order fields, items added/removed/modified)
     * - Eliminates race conditions between separate queries
     * - UI always has fresh data without manual refresh
     *
     * @param orderId Order ID (local UUID or server CUID)
     * @return Flow<DraftOrderWithItems?> that emits on every order/item change
     */
    @Transaction
    @Query("SELECT * FROM draft_orders WHERE id = :orderId")
    fun observeOrderWithItems(orderId: String): Flow<DraftOrderWithItems?>

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
     * Get all orders for a venue (no status filter).
     * Used by OrderListViewModel to fetch local orders for merge with backend.
     *
     * @param venueId Tenant ID
     * @return List of all local orders for venue
     */
    @Query("SELECT * FROM draft_orders WHERE venue_id = :venueId ORDER BY updated_at DESC")
    suspend fun getOrdersByVenue(venueId: String): List<DraftOrderEntity>

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

    /**
     * Update only the version field atomically.
     *
     * ⭐ CRITICAL: This prevents version mismatch on sync retry.
     * Must be called immediately after each successful API response to persist
     * the new version before any retry logic kicks in.
     *
     * Uses UPDATE (not INSERT/REPLACE) to avoid CASCADE DELETE on child items.
     *
     * @param orderId Order ID
     * @param version New version from server response
     * @param updatedAt Timestamp (defaults to current time)
     */
    @Query("""
        UPDATE draft_orders
        SET version = :version,
            updated_at = :updatedAt
        WHERE id = :orderId
    """)
    suspend fun updateVersion(
        orderId: String,
        version: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    // ============================================================================
    // D16 v4.2 · Regla A (hz:e82938e1:D16, Codex r5 #1 y #6): la ÚNICA puerta de una lectura del SERVIDOR a Room
    // ============================================================================

    /** Renglones de la cuenta que la cola todavía no sube (altas o cambios `PENDING`, retiros `DELETED`). */
    @Query("SELECT COUNT(*) FROM draft_order_items WHERE order_id = :orderId AND sync_status IN ('PENDING', 'DELETED')")
    suspend fun contarRenglonesEnCola(orderId: String): Int

    @Query("SELECT * FROM draft_order_items WHERE order_id = :orderId")
    suspend fun renglonesDeLaCuenta(orderId: String): List<DraftOrderItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarRenglones(renglones: List<DraftOrderItemEntity>)

    /** Con renglones en cola: toma del SERVIDOR lo que la caja no puede calcular, sin tocar renglones, versión ni estado de sincronización. */
    @Query("""
        UPDATE draft_orders
        SET discount_amount = :descuento, total = :total, paid_amount = :pagado, payment_status = :estadoDePago,
            remaining_balance = :falta, updated_at = :ahora
        WHERE id = :orderId
    """)
    suspend fun tomarDelServidorSinTocarRenglones(
        orderId: String,
        descuento: String,
        total: String,
        pagado: String,
        estadoDePago: String,
        falta: String,
        ahora: Long,
    )

    /** Datos del cliente de la cuenta (personas, nombre, teléfono, notas), sin tocar renglones ni totales. */
    @Query("""
        UPDATE draft_orders
        SET covers = :personas, customer_name = :nombre, customer_phone = :telefono, special_requests = :notas, updated_at = :ahora
        WHERE id = :orderId
    """)
    suspend fun guardarDatosDelCliente(orderId: String, personas: Int, nombre: String?, telefono: String?, notas: String?, ahora: Long)

    /**
     * Una lectura del SERVIDOR entra a Room SÓLO por aquí, en una transacción (nadie más escribe Room entre la pregunta y la escritura):
     *  1. **Lectura vieja** (la versión de Room ya es mayor): no se toca nada.
     *  2. **Con renglones en cola:** renglones, versión y estado de sincronización de Room se quedan; del SERVIDOR se toman lo pagado, el
     *     estado de pago y el descuento, y el total es el del SERVIDOR más lo que suman los renglones que el SERVIDOR todavía no tiene.
     *     Antes, el `insert` REPLACE borraba en cascada el renglón que la caja aún no subía (Codex r3 #1, r4 #1, r5 #1).
     *  3. **Sin cola:** Room queda igual al SERVIDOR (REPLACE + sus renglones), conservando la hora de cocina y la posición locales.
     *
     * @return true si Room quedó igual a la cuenta del SERVIDOR (paso 3).
     */
    @Transaction
    suspend fun guardarCuentaDelServidor(cuenta: DraftOrderEntity, renglones: List<DraftOrderItemEntity>): Boolean {
        val enRoom = getOrder(cuenta.id)
        if (enRoom != null && enRoom.version > cuenta.version) return false
        if (enRoom != null && contarRenglonesEnCola(cuenta.id) > 0) {
            val subtotalLocal = renglonesDeLaCuenta(cuenta.id)
                .filter { it.syncStatus != DraftOrderItemEntity.SYNC_STATUS_DELETED }
                .fold(java.math.BigDecimal.ZERO) { suma, r -> suma + (r.totalPrice.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) }
            val subtotalDelServidor = cuenta.subtotal.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val totalDelServidor = cuenta.total.toBigDecimalOrNull() ?: subtotalDelServidor
            val total = (totalDelServidor + (subtotalLocal - subtotalDelServidor)).max(java.math.BigDecimal.ZERO)
            val pagado = cuenta.paidAmount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            tomarDelServidorSinTocarRenglones(
                orderId = cuenta.id,
                descuento = cuenta.discountAmount,
                total = total.toPlainString(),
                pagado = cuenta.paidAmount,
                estadoDePago = cuenta.paymentStatus,
                falta = (total - pagado).max(java.math.BigDecimal.ZERO).toPlainString(),
                ahora = System.currentTimeMillis(),
            )
            return false
        }
        val previos = renglonesDeLaCuenta(cuenta.id)
        val cocina = previos.associate { it.id to it.sentToKitchenAt }
        val posicion = previos.associate { it.id to it.linePosition }
        insert(cuenta)  // REPLACE: borra en cascada los renglones viejos; ninguno está en cola (paso 2)
        insertarRenglones(renglones.map { r ->
            val conCocina = if (r.sentToKitchenAt == null && cocina[r.id] != null) r.copy(sentToKitchenAt = cocina[r.id]) else r
            posicion[r.id]?.let { conCocina.copy(linePosition = it) } ?: conCocina
        })
        return true
    }
}
