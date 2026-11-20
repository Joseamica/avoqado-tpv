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
 * Qué hizo la puerta de la libreta con una cuenta que llegó del SERVIDOR (libreta de Órdenes, fase 1).
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

    // ========================================================================================================================
    // LA PUERTA DE LA LIBRETA (fase 1, encargo 95c69a; promesa: docs/audit/2026-11-20-libreta-fase-1/promesa.md del workspace)
    // ========================================================================================================================

    @Query("SELECT * FROM draft_order_items WHERE order_id = :orderId")
    suspend fun renglonesDeLaCuenta(orderId: String): List<DraftOrderItemEntity>

    /** Renglones que la caja todavía no sube: altas o cantidades (`PENDING`/`SYNCING`) y retiros (`DELETED`). */
    @Query("SELECT COUNT(*) FROM draft_order_items WHERE order_id = :orderId AND sync_status != 'SYNCED'")
    suspend fun renglonesSinSubir(orderId: String): Int

    @Query("DELETE FROM draft_order_items WHERE id IN (:ids)")
    suspend fun borrarRenglones(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarRenglones(renglones: List<DraftOrderItemEntity>)

    /** `UPDATE OR REPLACE`: la cuenta conserva su fila (sin borrado en cascada de sus renglones). */
    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun actualizarCuenta(order: DraftOrderEntity)

    /**
     * La ÚNICA entrada de una cuenta del SERVIDOR a la libreta, en una transacción de Room:
     * 1. Si la libreta ya tiene una versión más nueva, no se toca nada ([ResultadoDeLectura.VIEJA]).
     * 2. Lo ya subido (`SYNCED`) queda igual al SERVIDOR: lo que ya no tiene se borra, lo nuevo (de otro aparato) entra, y la marca de
     *    cocina y la posición pasan al renglón del SERVIDOR que coincide por id o por su llave (`externalId`).
     * 3. Lo que la caja todavía no sube (`PENDING`, `SYNCING`, `DELETED`) no se toca, ni el estado de sincronización de la cuenta;
     *    la cabecera se arma con la regla de [totalConLaRegla] sobre los renglones vivos de la libreta ([ResultadoDeLectura.CON_COLA]).
     * 4. Sin cola, la cabecera es la del SERVIDOR ([ResultadoDeLectura.REEMPLAZADA]). Nunca `INSERT REPLACE` sobre una cuenta que
     *    existe (borraba en cascada sus renglones, hz:nube0000:nacec621).
     */
    @Transaction
    suspend fun guardarCuentaDelServidor(
        cuenta: DraftOrderEntity,
        renglones: List<DraftOrderItemEntity>,
    ): ResultadoDeLectura {
        val actual = getOrder(cuenta.id)
        if (actual == null) {
            insert(cuenta)
            if (renglones.isNotEmpty()) guardarRenglones(renglones)
            return ResultadoDeLectura.NUEVA
        }
        if (actual.version > cuenta.version) return ResultadoDeLectura.VIEJA

        val locales = renglonesDeLaCuenta(cuenta.id)
        val (enCola, subidos) = locales.partition { it.syncStatus != DraftOrderItemEntity.SYNC_STATUS_SYNCED }
        val idsEnCola = enCola.flatMap { listOfNotNull(it.id, it.externalId) }.toSet()
        val subidosPorId = subidos.associateBy { it.id }
        val subidosPorLlave = subidos.filter { it.externalId != null }.associateBy { it.externalId!! }
        // Un renglón del SERVIDOR que es uno de la cola (por id o por la llave que mandó la caja) lo sigue llevando la caja.
        val nuevos = renglones.filter { it.id !in idsEnCola && (it.externalId == null || it.externalId !in idsEnCola) }.map { r ->
            val previo = subidosPorId[r.id] ?: r.externalId?.let { subidosPorId[it] ?: subidosPorLlave[it] }
            if (previo == null) r
            else r.copy(sentToKitchenAt = r.sentToKitchenAt ?: previo.sentToKitchenAt, linePosition = previo.linePosition)
        }
        val quedan = nuevos.map { it.id }.toSet()
        val sobran = subidos.map { it.id }.filter { it !in quedan }
        if (sobran.isNotEmpty()) borrarRenglones(sobran)

        val cabecera = cuenta.copy(
            merchantAccountId = cuenta.merchantAccountId ?: actual.merchantAccountId,
            merchantAccountName = cuenta.merchantAccountName ?: actual.merchantAccountName,
        )
        if (enCola.isEmpty()) {
            actualizarCuenta(cabecera)
            if (nuevos.isNotEmpty()) guardarRenglones(nuevos)
            return ResultadoDeLectura.REEMPLAZADA
        }
        val subtotal = subtotalDe(enCola + nuevos)
        val total = totalConLaRegla(subtotal, cabecera)
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

    /**
     * Un cambio local de renglones (agregar, quitar, cantidad, soltar un rechazo) recalcula la cabecera en UNA transacción con la
     * regla de [totalConLaRegla]. Aplicada dos veces da lo mismo, así que cruzarse con una lectura no cuenta nada dos veces
     * (Codex D16 r6 #1) y el descuento no se pierde (antes: total = subtotal).
     */
    @Transaction
    suspend fun recalcularCabecera(orderId: String, ahora: Long) {
        val cuenta = getOrder(orderId) ?: return
        val subtotal = subtotalDe(renglonesDeLaCuenta(orderId))
        val total = totalConLaRegla(subtotal, cuenta)
        update(
            cuenta.copy(
                subtotal = subtotal.toString(),
                total = total.toString(),
                remainingBalance = (total - dinero(cuenta.paidAmount)).max(BigDecimal.ZERO).toString(),
                updatedAt = ahora,
                syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING,
            )
        )
    }

    /** La versión de la cuenta sólo sube (un acuse atrasado no la regresa por debajo de una lectura que ya entró). */
    @Query("UPDATE draft_orders SET version = MAX(version, :version), updated_at = :updatedAt WHERE id = :orderId")
    suspend fun subirVersion(orderId: String, version: Int, updatedAt: Long = System.currentTimeMillis())

    /** Al terminar una vuelta de la cola: `SYNCED` sólo si ya no queda nada sin subir (si no, `PENDING`); la versión sólo sube. */
    @Query("""
        UPDATE draft_orders
        SET version = MAX(version, :version),
            last_sync_at = :ahora,
            sync_status = CASE WHEN EXISTS (
                SELECT 1 FROM draft_order_items WHERE order_id = :orderId AND sync_status != 'SYNCED'
            ) THEN 'PENDING' ELSE 'SYNCED' END
        WHERE id = :orderId
    """)
    suspend fun cerrarVuelta(orderId: String, version: Int, ahora: Long)

    // --- Un renglón se nombra por su id o por su llave, siempre DENTRO de su cuenta y con prioridad al id exacto -------------

    @Query("""
        SELECT * FROM draft_order_items WHERE order_id = :orderId AND (id = :itemId OR external_id = :itemId)
        ORDER BY CASE WHEN id = :itemId THEN 0 ELSE 1 END
    """)
    suspend fun candidatosDelRenglon(orderId: String, itemId: String): List<DraftOrderItemEntity>

    /** El renglón que la pantalla, la impresora o la cola nombran con `itemId` (aunque la puerta ya le haya puesto el id del
     *  SERVIDOR). null si no existe o si la llave es ambigua (dos renglones con esa llave y ninguno con ese id). */
    suspend fun renglonNombrado(orderId: String, itemId: String): DraftOrderItemEntity? {
        val candidatos = candidatosDelRenglon(orderId, itemId)
        return candidatos.firstOrNull { it.id == itemId } ?: candidatos.singleOrNull()
    }

    @Query("UPDATE draft_order_items SET sync_status = 'DELETED' WHERE id = :itemId")
    suspend fun marcarRenglonQuitado(itemId: String)

    @Query("UPDATE draft_order_items SET sent_to_kitchen_at = :sentAt WHERE id = :itemId")
    suspend fun marcarRenglonEnCocina(itemId: String, sentAt: Long)

    @Query("UPDATE draft_order_items SET quantity = :cantidad, total_price = :total, sync_status = 'PENDING' WHERE id = :itemId")
    suspend fun cambiarCantidadDelRenglon(itemId: String, cantidad: Int, total: String)

    @Update
    suspend fun actualizarRenglon(renglon: DraftOrderItemEntity)

    @Transaction
    suspend fun quitarRenglon(orderId: String, itemId: String, ahora: Long): Boolean {
        val r = renglonNombrado(orderId, itemId) ?: return false
        marcarRenglonQuitado(r.id)
        recalcularCabecera(orderId, ahora)
        return true
    }

    /** Cambiar la cantidad en el MISMO renglón (queda `PENDING`): el SERVIDOR, por la ruta de la TPV, actualiza la cantidad del
     *  renglón que tiene esa llave (`order.tpv.service` addItemsToOrder, «UPDATING by externalId»). */
    @Transaction
    suspend fun cambiarCantidad(orderId: String, itemId: String, cantidad: Int, total: String, ahora: Long): Boolean {
        val r = renglonNombrado(orderId, itemId) ?: return false
        cambiarCantidadDelRenglon(r.id, cantidad, total)
        recalcularCabecera(orderId, ahora)
        return true
    }

    /** Agregar un renglón nuevo y recalcular la cabecera en UNA transacción (Codex fase 1 r2 #9: si el proceso moría entre las
     *  dos, quedaba el renglón pendiente con la cuenta `SYNCED` y la reconexión no lo veía). */
    @Transaction
    suspend fun agregarRenglon(renglon: DraftOrderItemEntity, ahora: Long) {
        guardarRenglones(listOf(renglon))
        recalcularCabecera(renglon.orderId, ahora)
    }

    @Query("SELECT * FROM draft_order_items WHERE id = :itemId")
    suspend fun renglonPorId(itemId: String): DraftOrderItemEntity?

    /** Codex fase 1 r3 #5: si la cuenta cambió de id local al del SERVIDOR a media impresión, el renglón se busca por su id exacto
     *  (único en la tabla), como antes. */
    @Transaction
    suspend fun marcarEnCocina(orderId: String, itemIds: List<String>, sentAt: Long) {
        itemIds.forEach { id -> (renglonNombrado(orderId, id) ?: renglonPorId(id))?.let { marcarRenglonEnCocina(it.id, sentAt) } }
    }

    /** `SYNCED` sólo si no queda nada sin subir; si no, `PENDING` (en una sola sentencia: un alta que entra a la vez no se pierde). */
    @Query("""
        UPDATE draft_orders SET last_sync_at = :ahora, sync_status = CASE WHEN EXISTS (
            SELECT 1 FROM draft_order_items WHERE order_id = :orderId AND sync_status != 'SYNCED'
        ) THEN 'PENDING' ELSE 'SYNCED' END
        WHERE id = :orderId
    """)
    suspend fun marcarSegunLaCola(orderId: String, ahora: Long)

    /**
     * El SERVIDOR aceptó el alta (o el cambio de cantidad) de este renglón con `cantidadEnviada` piezas. Queda `SYNCED` sólo si la
     * cantidad sigue siendo ésa: si la caja la cambió mientras viajaba, sigue `PENDING` y la siguiente vuelta manda la nueva con
     * la misma llave, que el SERVIDOR actualiza (Codex fase 1 r1 #4; antes el acuse la marcaba subida). Conserva la marca de
     * cocina. Un renglón que la caja quitó mientras viajaba sigue quitado (D23).
     */
    @Transaction
    suspend fun confirmarAlta(orderId: String, itemId: String, cantidadEnviada: Int) {
        val r = renglonNombrado(orderId, itemId) ?: return
        if (r.syncStatus == DraftOrderItemEntity.SYNC_STATUS_DELETED) return
        val estado = if (r.quantity == cantidadEnviada) DraftOrderItemEntity.SYNC_STATUS_SYNCED
        else DraftOrderItemEntity.SYNC_STATUS_PENDING
        if (estado != r.syncStatus) actualizarRenglon(r.copy(syncStatus = estado))
    }

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
}

private fun dinero(texto: String?): BigDecimal = texto?.toBigDecimalOrNull() ?: BigDecimal.ZERO

private fun subtotalDe(renglones: List<DraftOrderItemEntity>): BigDecimal =
    renglones.filter { it.syncStatus != DraftOrderItemEntity.SYNC_STATUS_DELETED }
        .fold(BigDecimal.ZERO) { suma, r -> suma + dinero(r.totalPrice) }

/**
 * El total de la libreta: máx(0, subtotal − descuento) + lo demás que el SERVIDOR suma (cargos, impuesto aparte), donde «lo demás»
 * sale de la cabecera: total − máx(0, subtotal − descuento). Así queda la cabecera que llega del SERVIDOR y así la dejan los
 * cambios locales: la regla da lo mismo aplicada una o dos veces y no pierde el descuento al tocar cero (Codex fase 1 r1 #3). Un %
 * de descuento se queda con su importe anterior hasta que el SERVIDOR lo recalcule (declarado en la promesa).
 */
internal fun totalConLaRegla(subtotal: BigDecimal, cabecera: DraftOrderEntity): BigDecimal {
    val descuento = dinero(cabecera.discountAmount)
    val baseDeLaCabecera = (dinero(cabecera.subtotal) - descuento).max(BigDecimal.ZERO)
    val loDemas = dinero(cabecera.total) - baseDeLaCabecera
    return ((subtotal - descuento).max(BigDecimal.ZERO) + loDemas).max(BigDecimal.ZERO)
}
