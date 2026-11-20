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
import java.math.BigDecimal

/**
 * Qué hizo la puerta de la libreta con una cuenta que llegó del SERVIDOR (libreta de Órdenes, fase 1 mínima).
 * - [NUEVA]: no estaba en la libreta; se guardó tal cual.
 * - [REEMPLAZADA]: no había nada sin subir; la libreta quedó igual al SERVIDOR.
 * - [CON_COLA]: había renglones sin subir; se conservaron y la cabecera los suma.
 * - [VIEJA]: la libreta ya tenía una versión más nueva; no se tocó nada.
 */
enum class ResultadoDeLectura { NUEVA, REEMPLAZADA, CON_COLA, VIEJA }

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
        SET version = MAX(version, :version),
            updated_at = :updatedAt
        WHERE id = :orderId
    """)
    suspend fun updateVersion(
        orderId: String,
        version: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    // ========================================================================================================================
    // LA PUERTA DE LA LIBRETA (fase 1 mínima, encargo 3a34fd; promesa: docs/audit/2026-11-20-libreta-fase-1-minima/promesa.md)
    // ========================================================================================================================

    @Query("SELECT * FROM draft_order_items WHERE order_id = :orderId")
    suspend fun renglonesDeLaCuenta(orderId: String): List<DraftOrderItemEntity>

    /** Renglones que la caja todavía no sube: altas o cantidades (`PENDING`/`SYNCING`) y retiros (`DELETED`). */
    @Query("SELECT COUNT(*) FROM draft_order_items WHERE order_id = :orderId AND sync_status != 'SYNCED'")
    suspend fun renglonesSinSubir(orderId: String): Int

    /**
     * Un cambio local (agregar, quitar, cantidad) recalcula la cabecera como siempre (total = suma de lo que se ve), pero en UNA
     * transacción y sin reescribir la versión: antes copiaba la cabecera entera leída afuera y, si en medio entraba una lectura
     * del SERVIDOR, le regresaba la versión y el saldo viejos (Codex fase 1 mínima r1 #3).
     */
    @Transaction
    suspend fun recalcularTotalesLocales(orderId: String, ahora: Long) {
        val cuenta = getOrder(orderId) ?: return
        val subtotal = renglonesDeLaCuenta(orderId)
            .filter { it.syncStatus != DraftOrderItemEntity.SYNC_STATUS_DELETED }
            .fold(BigDecimal.ZERO) { suma, r -> suma + dinero(r.totalPrice) }
        update(
            cuenta.copy(
                subtotal = subtotal.toString(),
                tax = BigDecimal.ZERO.toString(),
                total = subtotal.toString(),
                remainingBalance = (subtotal - dinero(cuenta.paidAmount)).toString(),
                updatedAt = ahora,
                syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING,
            )
        )
    }

    /** Cierre de una vuelta de la cola: la versión sólo sube (sin copiar la cabecera entera, que pisaba lo que entró en medio). */
    @Query("UPDATE draft_orders SET version = MAX(version, :version), sync_status = 'SYNCED', last_sync_at = :ahora WHERE id = :orderId")
    suspend fun cerrarVueltaDeSubida(orderId: String, version: Int, ahora: Long)

    /**
     * Rechazo permanente: de los renglones rechazados ([ids]), lo que el SERVIDOR ya tenía (un cambio de cantidad, un retiro) vuelve
     * a `SYNCED` y la siguiente lectura lo deja como está allá; un alta que nunca aceptó se borra; y el total se recalcula en la
     * MISMA transacción. Antes todo pasaba a `DELETED` y, con la puerta, la siguiente vuelta habría quitado del SERVIDOR un
     * producto que nadie pidió quitar (Codex fase 1 mínima r1 #6). Lo que no está en [ids] no se toca (r2 #4, r3 #1 #2).
     */
    @Transaction
    suspend fun soltarRechazados(orderId: String, ids: List<String>, ahora: Long) {
        if (ids.isEmpty()) return
        val renglones = renglonesPorId(ids).filter { it.orderId == orderId && it.syncStatus != DraftOrderItemEntity.SYNC_STATUS_SYNCED }
        val (yaEnElServidor, nuevas) = renglones.partition { it.isServerCreated }
        if (nuevas.isNotEmpty()) borrarRenglones(nuevas.map { it.id })
        if (yaEnElServidor.isNotEmpty()) marcarRenglonesSincronizados(yaEnElServidor.map { it.id })
        recalcularTotalesLocales(orderId, ahora)
    }

    @Query("SELECT * FROM draft_order_items WHERE id IN (:ids)")
    suspend fun renglonesPorId(ids: List<String>): List<DraftOrderItemEntity>

    @Query("UPDATE draft_order_items SET sync_status = 'SYNCED' WHERE id IN (:ids)")
    suspend fun marcarRenglonesSincronizados(ids: List<String>)

    @Query("DELETE FROM draft_order_items WHERE id IN (:ids)")
    suspend fun borrarRenglones(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarRenglones(renglones: List<DraftOrderItemEntity>)

    /** `UPDATE OR REPLACE`: la cuenta conserva su fila (sin el borrado en cascada de sus renglones); ante un choque del índice
     *  único (venue, folio) con OTRA fila se comporta como el `INSERT REPLACE` de antes. */
    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun actualizarCuenta(order: DraftOrderEntity)

    /**
     * La ÚNICA entrada de una cuenta del SERVIDOR a la libreta, en una transacción de Room (hz:nube0000:nacec621: el `INSERT
     * REPLACE` de antes borraba en cascada los renglones que la caja todavía no subía).
     * 1. Si la libreta ya tiene una versión MÁS nueva, no se toca nada ([ResultadoDeLectura.VIEJA]). Igual versión sí entra: el
     *    SERVIDOR no sube la versión con un abono ni con los datos del cliente.
     * 2. Lo ya subido (`SYNCED`) queda igual al SERVIDOR: lo que ya no trae se borra, lo nuevo (de otra caja) entra, y la marca de
     *    cocina y la posición pasan al renglón del SERVIDOR que coincide por id o por la llave que mandó la caja.
     * 3. Lo que la caja todavía no sube (`PENDING`, `SYNCING`, `DELETED`) no se toca, y un renglón del SERVIDOR que es uno de ésos
     *    (por id o llave) no entra encima. Excepción, como hoy: un cambio de cantidad pendiente sobre un renglón del SERVIDOR que
     *    esta lectura ya no trae (otra caja lo quitó) se borra; si no, la cola lo volvería a dar de alta.
     * 4. Cabecera: sin cola, la del SERVIDOR, por `UPDATE` ([ResultadoDeLectura.REEMPLAZADA]). Con cola, la del SERVIDOR con el
     *    subtotal de los renglones vivos de la libreta y su estado de sincronización ([ResultadoDeLectura.CON_COLA]).
     * Un retiro en cola de algo que el SERVIDOR aceptó y que esta lectura ya no trae (otra caja ya lo quitó) se da por cumplido y se
     * borra: si no, la siguiente vuelta mandaría otro DELETE que contesta 404 (Codex r2 #1).
     *
     * @param llaves id del SERVIDOR → la llave (`externalId`) con la que la caja dio de alta ese renglón, si la trae.
     */
    @Transaction
    suspend fun guardarCuentaDelServidor(
        cuenta: DraftOrderEntity,
        renglones: List<DraftOrderItemEntity>,
        llaves: Map<String, String>,
    ): ResultadoDeLectura {
        val actual = getOrder(cuenta.id)
        if (actual == null) {
            insert(cuenta)
            if (renglones.isNotEmpty()) guardarRenglones(renglones)
            return ResultadoDeLectura.NUEVA
        }
        if (actual.version > cuenta.version) return ResultadoDeLectura.VIEJA

        fun nombres(id: String) = listOfNotNull(id, llaves[id])
        val delServidor = renglones.flatMap { nombres(it.id) }.toSet()
        var locales = renglonesDeLaCuenta(cuenta.id)
        val retiradosPorOtro = locales.filter {
            (it.syncStatus == DraftOrderItemEntity.SYNC_STATUS_PENDING || it.syncStatus == DraftOrderItemEntity.SYNC_STATUS_DELETED) &&
                it.isServerCreated && it.id !in delServidor && (it.externalId == null || it.externalId !in delServidor)
        }
        if (retiradosPorOtro.isNotEmpty()) {
            borrarRenglones(retiradosPorOtro.map { it.id })
            locales = locales - retiradosPorOtro.toSet()
        }
        val (enCola, subidos) = locales.partition { it.syncStatus != DraftOrderItemEntity.SYNC_STATUS_SYNCED }
        val nombresEnCola = enCola.flatMap { listOfNotNull(it.id, it.externalId) }.toSet()
        val subidosPorNombre = HashMap<String, DraftOrderItemEntity>()
        subidos.forEach { r -> r.externalId?.let { subidosPorNombre.putIfAbsent(it, r) } }
        subidos.forEach { r -> subidosPorNombre[r.id] = r } // el id exacto gana sobre una llave
        val nuevos = renglones.filter { r -> nombres(r.id).none { it in nombresEnCola } }.map { r ->
            val previo = nombres(r.id).firstNotNullOfOrNull { subidosPorNombre[it] }
            if (previo == null) r
            else r.copy(sentToKitchenAt = r.sentToKitchenAt ?: previo.sentToKitchenAt, linePosition = previo.linePosition)
        }
        val quedan = nuevos.map { it.id }.toSet()
        val sobran = subidos.map { it.id }.filter { it !in quedan }
        if (sobran.isNotEmpty()) borrarRenglones(sobran)

        val cabecera = cuenta.copy(
            merchantAccountId = cuenta.merchantAccountId ?: actual.merchantAccountId,
            merchantAccountName = cuenta.merchantAccountName ?: actual.merchantAccountName,
            lastSplitType = cuenta.lastSplitType ?: actual.lastSplitType,
        )
        if (enCola.isEmpty()) {
            actualizarCuenta(cabecera)
            if (nuevos.isNotEmpty()) guardarRenglones(nuevos)
            return ResultadoDeLectura.REEMPLAZADA
        }
        val subtotal = (enCola + nuevos)
            .filter { it.syncStatus != DraftOrderItemEntity.SYNC_STATUS_DELETED }
            .fold(BigDecimal.ZERO) { suma, r -> suma + dinero(r.totalPrice) }
        val total = (subtotal + dinero(cabecera.total) - dinero(cabecera.subtotal)).max(BigDecimal.ZERO)
        actualizarCuenta(
            cabecera.copy(
                subtotal = subtotal.toString(),
                total = total.toString(),
                remainingBalance = (total - dinero(cabecera.paidAmount)).max(BigDecimal.ZERO).toString(),
                syncStatus = actual.syncStatus,
                lastSyncAt = actual.lastSyncAt,
                conflictData = actual.conflictData,
            )
        )
        if (nuevos.isNotEmpty()) guardarRenglones(nuevos)
        return ResultadoDeLectura.CON_COLA
    }
}

private fun dinero(texto: String?): BigDecimal = texto?.toBigDecimalOrNull() ?: BigDecimal.ZERO
