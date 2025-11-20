package com.jaac.avoqado_tpv.features.ordering.domain

import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderDao
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderItemDao
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import com.jaac.avoqado_tpv.core.data.local.mappers.toDomain
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntity
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * OrderSyncCoordinator - Orchestrates local-first order management with backend sync
 *
 * **Architecture: Toast POS Hybrid Approach**
 * - Creates/updates orders locally first (instant UI, 0ms latency)
 * - Debounced auto-save every 5 seconds (batches rapid changes)
 * - Immediate sync for critical operations (sendToKitchen, payment, conflicts)
 * - Handles ID replacement (local UUID → server CUID)
 * - Handles version conflicts (409 responses)
 * - Retry with exponential backoff on network errors
 *
 * **Flow:**
 * ```
 * 1. User adds item → Save to Room immediately → UI updates (0ms)
 * 2. Schedule debounced sync (5s delay)
 * 3. If user adds another item → Cancel previous sync, restart 5s timer
 * 4. After 5s of no changes → Sync to backend
 * 5. On success → Replace local UUID with server CUID
 * 6. On 409 conflict → Store server version in conflictData, emit conflict event
 * ```
 *
 * **Critical Operations (Immediate Sync):**
 * - Send to Kitchen (cannot delay, kitchen needs to start cooking)
 * - Payment (must sync before charging card)
 * - Conflict resolution (user chose which version to keep)
 *
 * **Usage:**
 * ```kotlin
 * // In MenuViewModel
 * orderSyncCoordinator.createLocalOrder(venueId, tableId, covers)
 * orderSyncCoordinator.scheduleSync(orderId) // Debounced 5s
 * orderSyncCoordinator.syncOrderImmediately(orderId) // Before kitchen/payment
 * ```
 *
 * @see DraftOrderEntity
 * @see DraftOrderItemEntity
 * @see OrderRepository
 */
@Singleton
class OrderSyncCoordinator @Inject constructor(
    private val draftOrderDao: DraftOrderDao,
    private val draftOrderItemDao: DraftOrderItemDao,
    private val orderRepository: OrderRepository
) {

    // ========================================
    // SYNC STATE MANAGEMENT
    // ========================================

    /**
     * Tracks pending debounced sync jobs by order ID.
     * When user makes another change, cancel previous job and restart timer.
     */
    private val pendingSyncJobs = mutableMapOf<String, Job>()

    /**
     * Coroutine scope for background sync operations.
     * Uses Dispatchers.IO for database and network operations.
     */
    private val syncScope = CoroutineScope(Dispatchers.IO)

    /**
     * Sync events emitted to ViewModels for UI updates.
     * Use SharedFlow (not StateFlow) because:
     * - Multiple events for same order (syncing → synced → error)
     * - ViewModels need to react to each event, not just latest state
     */
    private val _syncEvents = MutableSharedFlow<SyncEvent>(
        extraBufferCapacity = 100 // Prevent backpressure on rapid events
    )
    val syncEvents: SharedFlow<SyncEvent> = _syncEvents.asSharedFlow()

    // ========================================
    // PUBLIC API
    // ========================================

    /**
     * Create order locally (instant UI).
     *
     * Generates local UUID and orderNumber, saves to Room DB.
     * Does NOT sync to backend immediately - use scheduleSync() after.
     *
     * **Why local-first?**
     * - Instant UI (0ms vs 300ms+ for API call)
     * - Works offline (sync later when connection restored)
     * - Reduces server load (batch multiple changes in one sync)
     *
     * @param venueId Tenant ID
     * @param tableId Table ID (null for TAKEOUT/DELIVERY)
     * @param covers Number of people
     * @param waiterId Waiter who created order
     * @param orderType DINE_IN, TAKEOUT, DELIVERY, PICKUP
     * @return Local order ID (e.g., "local_abc123")
     */
    suspend fun createLocalOrder(
        venueId: String,
        tableId: String?,
        covers: Int,
        waiterId: String?,
        orderType: OrderType
    ): String = withContext(Dispatchers.IO) {
        val localId = DraftOrderEntity.generateLocalId()
        val localOrderNumber = DraftOrderEntity.generateLocalOrderNumber()

        val draftOrder = DraftOrderEntity(
            id = localId,
            venueId = venueId,
            orderNumber = localOrderNumber,
            tableId = tableId,
            tableName = null, // Will be populated from table lookup if needed
            covers = covers,
            waiterId = waiterId,
            waiterName = null, // Will be populated from staff lookup if needed
            customerName = null,
            customerPhone = null,
            specialRequests = null,
            status = "OPEN",
            kitchenStatus = "PENDING",
            paymentStatus = "PENDING",
            orderType = orderType.name,
            subtotal = "0",
            discountAmount = "0",
            tax = "0",
            total = "0",
            notes = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            version = 0,
            syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING,
            isServerCreated = false,
            lastSyncAt = null,
            conflictData = null
        )

        draftOrderDao.insert(draftOrder)

        Timber.d("🆕 [Local] Created order | id=$localId | number=$localOrderNumber | type=$orderType")

        localId
    }

    /**
     * Load order from local database.
     *
     * Fetches order from Room DB and converts to domain model.
     * Returns null if order doesn't exist locally.
     *
     * **Use cases:**
     * - Check if order exists locally before fetching from backend
     * - Offline mode (work with locally-stored orders)
     * - Instant order loading (0ms vs 300ms+ for API call)
     *
     * @param orderId Order ID (local UUID or server CUID)
     * @return Domain Order model or null if not found
     */
    suspend fun getLocalOrder(orderId: String): Order? = withContext(Dispatchers.IO) {
        try {
            val draftOrder = draftOrderDao.getOrder(orderId) ?: return@withContext null
            val draftItems = draftOrderItemDao.getItemsByOrder(orderId)

            val order = draftOrder.toDomain(draftItems)
            Timber.d("📋 [Local] Loaded order from DB | id=$orderId | items=${draftItems.size}")

            order
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading order from local DB: $orderId")
            null
        }
    }

    /**
     * Add item to local order (instant UI).
     *
     * Generates local UUID for item, saves to Room DB, updates order totals.
     * Does NOT sync to backend immediately - use scheduleSync() after.
     *
     * @param orderId Local or server order ID
     * @param productId Product to add
     * @param productName Product display name
     * @param quantity How many
     * @param unitPrice Price per unit (BigDecimal as String)
     * @param modifiers List of modifiers applied
     * @param notes Special instructions
     * @return Local item ID (e.g., "local_item_xyz")
     */
    suspend fun addItemToLocalOrder(
        orderId: String,
        productId: String,
        productName: String,
        quantity: Int,
        unitPrice: String,
        modifiers: List<ProductModifier>,
        notes: String?
    ): String = withContext(Dispatchers.IO) {
        val localItemId = DraftOrderItemEntity.generateLocalId()
        val totalPrice = (unitPrice.toBigDecimal() * quantity.toBigDecimal()).toString()

        val draftItem = DraftOrderItemEntity(
            id = localItemId,
            orderId = orderId,
            productId = productId,
            productName = productName,
            productSku = null,
            quantity = quantity,
            unitPrice = unitPrice,
            totalPrice = totalPrice,
            modifiers = if (modifiers.isNotEmpty()) {
                com.google.gson.Gson().toJson(modifiers)
            } else {
                "[]"
            },
            notes = notes,
            kitchenStatus = "PENDING",
            createdAt = System.currentTimeMillis(),
            sentToKitchenAt = null,
            syncStatus = DraftOrderItemEntity.SYNC_STATUS_PENDING,
            isServerCreated = false
        )

        draftOrderItemDao.insert(draftItem)

        // Update order totals
        recalculateOrderTotals(orderId)

        Timber.d("➕ [Local] Added item | order=$orderId | product=$productName | qty=$quantity")

        localItemId
    }

    /**
     * Remove item from local order (soft delete, instant UI).
     *
     * Marks item as DELETED (soft delete pattern), updates order totals.
     * Does NOT physically delete - allows rollback if sync fails.
     *
     * @param orderId Local or server order ID
     * @param itemId Item to remove
     */
    suspend fun removeItemFromLocalOrder(
        orderId: String,
        itemId: String
    ) = withContext(Dispatchers.IO) {
        draftOrderItemDao.markAsDeleted(itemId)

        // Update order totals
        recalculateOrderTotals(orderId)

        Timber.d("➖ [Local] Soft deleted item | order=$orderId | item=$itemId")
    }

    /**
     * Schedule debounced sync (5 seconds after last change).
     *
     * Cancels any previous pending sync for this order and restarts timer.
     * This batches rapid changes (user adding multiple items) into one sync.
     *
     * **Example:**
     * ```
     * User adds item 1 → scheduleSync() → timer starts (5s)
     * User adds item 2 → scheduleSync() → previous timer cancelled, new timer starts (5s)
     * User adds item 3 → scheduleSync() → previous timer cancelled, new timer starts (5s)
     * 5 seconds of no changes → All 3 items synced in one API call
     * ```
     *
     * @param orderId Order to sync (local or server ID)
     */
    fun scheduleSync(orderId: String) {
        // Cancel previous pending sync for this order
        pendingSyncJobs[orderId]?.cancel()

        // Schedule new debounced sync
        val job = syncScope.launch {
            Timber.d("⏱️ [Sync] Scheduled debounced sync | order=$orderId | delay=5s")

            delay(5000) // 5 second debounce

            Timber.d("🔄 [Sync] Debounce expired, executing sync | order=$orderId")
            executeSyncWithRetry(orderId)
        }

        pendingSyncJobs[orderId] = job
    }

    /**
     * Sync order immediately (bypass debounce).
     *
     * Used for critical operations that cannot wait:
     * - Send to Kitchen (kitchen needs to start cooking NOW)
     * - Payment (must sync before charging card)
     * - Conflict resolution (user chose which version to keep)
     *
     * Cancels any pending debounced sync for this order.
     *
     * @param orderId Order to sync
     */
    suspend fun syncOrderImmediately(orderId: String) = withContext(Dispatchers.IO) {
        // Cancel pending debounced sync
        pendingSyncJobs[orderId]?.cancel()
        pendingSyncJobs.remove(orderId)

        Timber.d("⚡ [Sync] Immediate sync requested | order=$orderId")
        executeSyncWithRetry(orderId)
    }

    // ========================================
    // PRIVATE SYNC LOGIC
    // ========================================

    /**
     * Execute sync with exponential backoff retry.
     *
     * Retries up to 3 times on network errors with exponential backoff:
     * - Attempt 1: Immediate
     * - Attempt 2: 2s delay
     * - Attempt 3: 4s delay
     * - Attempt 4: 8s delay
     *
     * On 409 conflict: Do NOT retry, emit conflict event for user resolution.
     *
     * @param orderId Order to sync
     * @param attempt Current attempt number (1-indexed)
     */
    private suspend fun executeSyncWithRetry(
        orderId: String,
        attempt: Int = 1
    ) {
        if (attempt > 1) {
            val delayMs = (2.0.pow(attempt - 1) * 1000).toLong()
            Timber.d("🔄 [Sync] Retry attempt $attempt | order=$orderId | delay=${delayMs}ms")
            delay(delayMs)
        }

        try {
            executeSync(orderId)
            Timber.i("✅ [Sync] Success | order=$orderId")
        } catch (e: ConflictException) {
            Timber.w("⚠️ [Sync] Conflict detected | order=$orderId | version mismatch")
            // Do NOT retry on 409 - user must resolve conflict
            _syncEvents.emit(SyncEvent.Conflict(orderId, e.serverVersion))
        } catch (e: Exception) {
            Timber.e(e, "❌ [Sync] Failed (attempt $attempt) | order=$orderId")

            if (attempt < 4) {
                // Retry with exponential backoff
                executeSyncWithRetry(orderId, attempt + 1)
            } else {
                // Max retries exceeded
                Timber.e("❌ [Sync] Max retries exceeded | order=$orderId")
                _syncEvents.emit(SyncEvent.Error(orderId, e.message ?: "Sync failed"))
            }
        }
    }

    /**
     * Execute sync (create or update on server).
     *
     * **Logic:**
     * - If `isServerCreated = false` → Call createOrderOnServer()
     * - If `isServerCreated = true` → Call updateOrderOnServer()
     *
     * @param orderId Order to sync
     */
    private suspend fun executeSync(orderId: String) {
        val draftOrder = draftOrderDao.getOrder(orderId)
            ?: throw Exception("Order not found in local DB: $orderId")

        // Update sync status to SYNCING
        draftOrderDao.updateSyncStatus(orderId, DraftOrderEntity.SYNC_STATUS_SYNCING, System.currentTimeMillis())
        _syncEvents.emit(SyncEvent.Syncing(orderId))

        if (!draftOrder.isServerCreated) {
            // First sync - create on server
            createOrderOnServer(draftOrder)
        } else {
            // Subsequent sync - update on server
            updateOrderOnServer(draftOrder)
        }
    }

    /**
     * Create order on server (first sync).
     *
     * **Steps:**
     * 1. Create order on server (gets CUID + orderNumber from backend)
     * 2. Add all pending items to order
     * 3. Replace local UUID with server CUID in Room DB
     * 4. Update all items' orderId foreign key
     * 5. Mark as SYNCED
     *
     * **ID Replacement:**
     * ```
     * Before: id="local_abc123", orderNumber="LOCAL-123456"
     * After:  id="clw3k9x2b000...", orderNumber="ORD-0001234"
     * ```
     *
     * @param draftOrder Local draft order
     */
    private suspend fun createOrderOnServer(draftOrder: DraftOrderEntity) {
        val venueId = draftOrder.venueId

        Timber.d("🆕 [Sync] Creating order on server | localId=${draftOrder.id}")

        // Step 1: Create order on server
        val result = orderRepository.createOrder(
            venueId = venueId,
            tableId = draftOrder.tableId,
            covers = draftOrder.covers,
            waiterId = draftOrder.waiterId,
            orderType = OrderType.valueOf(draftOrder.orderType)
        )

        if (result.isFailure) {
            throw result.exceptionOrNull() ?: Exception("Failed to create order on server")
        }

        val serverOrder = result.getOrThrow()

        Timber.i("✅ [Sync] Order created on server | serverId=${serverOrder.id} | number=${serverOrder.orderNumber}")

        // Step 2: Add all pending items to order
        val pendingItems = draftOrderItemDao.getPendingItemsByOrder(draftOrder.id)

        if (pendingItems.isNotEmpty()) {
            val addItemRequests = pendingItems.map { item ->
                AddOrderItemRequest(
                    productId = item.productId,
                    quantity = item.quantity,
                    notes = item.notes,
                    modifierIds = null // TODO: Parse from JSON modifiers if needed
                )
            }

            val addItemsResult = orderRepository.addItemsToOrder(
                venueId = venueId,
                orderId = serverOrder.id,
                items = addItemRequests,
                currentVersion = serverOrder.version
            )

            if (addItemsResult.isFailure) {
                throw addItemsResult.exceptionOrNull() ?: Exception("Failed to add items to order on server")
            }

            val updatedOrder = addItemsResult.getOrThrow()

            Timber.i("✅ [Sync] Added ${pendingItems.size} items to server order | version=${updatedOrder.version}")

            // Step 3: Replace local item IDs with server IDs
            // Match by productId + quantity (best effort - may have duplicates)
            val serverItems = updatedOrder.items
            for ((index, localItem) in pendingItems.withIndex()) {
                val serverItem = serverItems.getOrNull(index)
                if (serverItem != null) {
                    draftOrderItemDao.replaceLocalIdWithServerCuid(
                        localId = localItem.id,
                        newId = serverItem.id
                    )
                }
            }
        }

        // Step 4: Update items' orderId foreign key BEFORE replacing order ID
        draftOrderItemDao.updateOrderId(
            oldOrderId = draftOrder.id,
            newOrderId = serverOrder.id
        )

        // Step 5: Replace local order ID with server CUID
        draftOrderDao.replaceLocalIdWithServerCuid(
            localId = draftOrder.id,
            newId = serverOrder.id,
            orderNumber = serverOrder.orderNumber,
            version = serverOrder.version,
            timestamp = System.currentTimeMillis()
        )

        _syncEvents.emit(SyncEvent.Synced(serverOrder.id, serverOrder.version))
    }

    /**
     * Update order on server (subsequent sync).
     *
     * **Steps:**
     * 1. Add pending items (syncStatus = PENDING)
     * 2. Remove deleted items (syncStatus = DELETED)
     * 3. Handle 409 conflict if version mismatch
     * 4. Mark all items as SYNCED
     * 5. Update order syncStatus to SYNCED
     *
     * @param draftOrder Draft order with server ID
     */
    private suspend fun updateOrderOnServer(draftOrder: DraftOrderEntity) {
        val venueId = draftOrder.venueId
        val orderId = draftOrder.id

        Timber.d("🔄 [Sync] Updating order on server | id=$orderId | version=${draftOrder.version}")

        // Step 1: Add pending items
        val pendingItems = draftOrderItemDao.getPendingItemsByOrder(orderId)

        if (pendingItems.isNotEmpty()) {
            val addItemRequests = pendingItems.map { item ->
                AddOrderItemRequest(
                    productId = item.productId,
                    quantity = item.quantity,
                    notes = item.notes,
                    modifierIds = null
                )
            }

            val result = orderRepository.addItemsToOrder(
                venueId = venueId,
                orderId = orderId,
                items = addItemRequests,
                currentVersion = draftOrder.version
            )

            if (result.isFailure) {
                val exception = result.exceptionOrNull()
                if (exception is ConflictException) {
                    throw exception
                }
                throw exception ?: Exception("Failed to add items")
            }

            val updatedOrder = result.getOrThrow()

            // Mark items as SYNCED
            pendingItems.forEach { item ->
                draftOrderItemDao.updateSyncStatus(item.id, DraftOrderItemEntity.SYNC_STATUS_SYNCED)
            }

            Timber.i("✅ [Sync] Added ${pendingItems.size} items | version=${updatedOrder.version}")
        }

        // Step 2: Remove deleted items
        val deletedItems = draftOrderItemDao.getDeletedItemsByOrder(orderId)

        for (deletedItem in deletedItems) {
            if (deletedItem.isServerCreated) {
                val result = orderRepository.removeOrderItem(
                    venueId = venueId,
                    orderId = orderId,
                    orderItemId = deletedItem.id,
                    currentVersion = draftOrder.version
                )

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    if (exception is ConflictException) {
                        throw exception
                    }
                    throw exception ?: Exception("Failed to remove item")
                }
            }

            // Hard delete from local DB after successful server deletion
            draftOrderItemDao.delete(deletedItem)
        }

        if (deletedItems.isNotEmpty()) {
            Timber.i("✅ [Sync] Removed ${deletedItems.size} items")
        }

        // Step 3: Mark order as SYNCED
        draftOrderDao.updateSyncStatus(orderId, DraftOrderEntity.SYNC_STATUS_SYNCED, System.currentTimeMillis())
        _syncEvents.emit(SyncEvent.Synced(orderId, draftOrder.version))
    }

    /**
     * Recalculate order totals (subtotal, tax, total).
     *
     * Sums all non-deleted items, calculates tax (10% for now).
     *
     * @param orderId Order to recalculate
     */
    private suspend fun recalculateOrderTotals(orderId: String) {
        val items = draftOrderItemDao.getItemsByOrder(orderId)

        val subtotal = items.sumOf { it.totalPrice.toBigDecimal() }
        val tax = subtotal * 0.10.toBigDecimal() // 10% tax
        val total = subtotal + tax

        val order = draftOrderDao.getOrder(orderId) ?: return

        draftOrderDao.update(
            order.copy(
                subtotal = subtotal.toString(),
                tax = tax.toString(),
                total = total.toString(),
                updatedAt = System.currentTimeMillis(),
                syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING // Mark for sync
            )
        )
    }

    // ========================================
    // SYNC EVENTS
    // ========================================

    /**
     * Sync events emitted to ViewModels.
     *
     * **Usage in ViewModel:**
     * ```kotlin
     * viewModelScope.launch {
     *     orderSyncCoordinator.syncEvents.collect { event ->
     *         when (event) {
     *             is SyncEvent.Syncing -> showSyncIndicator()
     *             is SyncEvent.Synced -> hideSyncIndicator()
     *             is SyncEvent.Error -> showErrorMessage(event.message)
     *             is SyncEvent.Conflict -> showConflictDialog(event.serverVersion)
     *         }
     *     }
     * }
     * ```
     */
    sealed class SyncEvent {
        data class Syncing(val orderId: String) : SyncEvent()
        data class Synced(val orderId: String, val version: Int) : SyncEvent()
        data class Error(val orderId: String, val message: String) : SyncEvent()
        data class Conflict(val orderId: String, val serverVersion: String) : SyncEvent()
    }
}

/**
 * Exception thrown on 409 conflict (version mismatch).
 */
class ConflictException(
    val serverVersion: String,
    message: String = "Order was modified by another terminal"
) : Exception(message)
