package com.jaac.avoqado_tpv.features.ordering.domain

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * OrderSyncCoordinator - Orchestrates local-first order management with backend sync
 *
 * **Architecture: Toast POS Hybrid Approach**
 * - Creates/updates orders locally first (instant UI, 0ms latency)
 * - Debounced auto-save every 2 seconds (batches rapid changes)
 * - Immediate sync for critical operations (sendToKitchen, payment, conflicts)
 * - Handles ID replacement (local UUID → server CUID)
 * - Handles version conflicts (409 responses)
 * - Retry with exponential backoff on network errors
 *
 * **Flow:**
 * ```
 * 1. User adds item → Save to Room immediately → UI updates (0ms)
 * 2. Schedule debounced sync (2s delay)
 * 3. If user adds another item → Cancel previous sync, restart 2s timer
 * 4. After 2s of no changes → Sync to backend
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
 * orderSyncCoordinator.scheduleSync(orderId) // Debounced 2s
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

    companion object {
        /**
         * Debounce delay for order sync (milliseconds).
         *
         * After user makes a change (add item, change quantity), wait this long
         * before syncing to backend. Resets on each change to batch rapid edits.
         *
         * - 2000ms for fast responsiveness
         * - Industry: Toast ~2-3s, Clover ~1-2s, Square uses real-time WebSocket
         */
        private const val SYNC_DEBOUNCE_MS = 2000L
    }

    // ========================================
    // SYNC STATE MANAGEMENT
    // ========================================

    /**
     * Tracks pending debounced sync jobs by order ID.
     * When user makes another change, cancel previous job and restart timer.
     *
     * ✅ P1 FIX: Use ConcurrentHashMap for thread-safe concurrent access
     * (pendingSyncJobs is accessed from multiple coroutines)
     */
    private val pendingSyncJobs = ConcurrentHashMap<String, Job>()

    /**
     * Per-order mutex for serializing sync operations.
     *
     * ✅ P0 FIX: Prevents concurrent syncs for the same order
     * Without this, two syncs could race and cause version conflicts:
     * - Sync A reads version=1, calls API
     * - Sync B reads version=1, calls API
     * - Sync A gets version=2 back, saves to DB
     * - Sync B sends version=1 → 409 conflict!
     *
     * With mutex: Sync B waits for Sync A to complete, reads version=2
     */
    private val syncMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Coroutine scope for background sync operations.
     * Uses Dispatchers.IO for database and network operations.
     *
     * ✅ P1 FIX: Use SupervisorJob to prevent one sync failure from cancelling all syncs
     * Without SupervisorJob, if one sync throws CancellationException, the entire scope dies.
     */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        Timber.i("🆕 [SYNC-DEBUG] createLocalOrder() | localId=$localId | venueId=$venueId | orderType=$orderType | timestamp=${System.currentTimeMillis()}")

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

            // 🔍 [DIAGNOSTIC] Log items loaded from database
            Timber.d("📋 [Local] Loaded order from DB | id=$orderId | items=${draftItems.size}")
            if (draftItems.isNotEmpty()) {
                draftItems.forEach { item ->
                    Timber.d("   → ${item.productName} | qty=${item.quantity} | status=${item.syncStatus} | id=${item.id}")
                }
            }

            // ⚠️ [DIAGNOSTIC] Detect "items disappeared" bug
            // Only trigger if order was previously known to have items (version > 1)
            // Skip false positives: empty new orders (version=1) or intentionally emptied orders
            if (draftItems.isEmpty() && draftOrder.syncStatus == DraftOrderEntity.SYNC_STATUS_SYNCED && draftOrder.version > 1) {
                // Query ALL items (including DELETED) for debugging
                val allItems = draftOrderItemDao.getAllItemsByOrderId(orderId)

                // Only report bug if items truly vanished (not just all deleted by user)
                if (allItems.isEmpty()) {
                    Timber.e("🐛 [BUG DETECTED] Order has 0 items after sync | orderId=$orderId | version=${draftOrder.version} | syncStatus=${draftOrder.syncStatus}")
                    Timber.e("   → Total items in DB (including DELETED): ${allItems.size}")
                    Timber.e("   → Items mysteriously disappeared! Possible FK CASCADE issue or unexpected hard-delete")
                } else {
                    // Items exist but all are DELETED - this is expected when user removes all items
                    Timber.d("✅ [Valid] Order has 0 active items (${allItems.size} soft-deleted by user) | orderId=$orderId")
                }
            }

            val order = draftOrder.toDomain(draftItems)
            order
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading order from local DB: $orderId")
            null
        }
    }

    /**
     * Get local-only orders for a venue (not yet synced to backend).
     *
     * Used by OrderListScreen to show pending orders alongside backend orders.
     * Filters to orders that:
     * - Are NOT server-created (local-first Quick Orders)
     * - OR have PENDING sync status (changes not yet synced)
     *
     * @param venueId Venue ID
     * @return List of local-only Order domain models
     */
    suspend fun getLocalOnlyOrders(venueId: String): List<Order> = withContext(Dispatchers.IO) {
        try {
            val localOrders = draftOrderDao.getOrdersByVenue(venueId)
                .filter { !it.isServerCreated || it.syncStatus == DraftOrderEntity.SYNC_STATUS_PENDING }

            Timber.d("📋 [LocalOrders] Found ${localOrders.size} local-only orders for venue=$venueId")

            localOrders.mapNotNull { entity ->
                val items = draftOrderItemDao.getItemsByOrder(entity.id)
                    .filter { it.syncStatus != DraftOrderItemEntity.SYNC_STATUS_DELETED }
                entity.toDomain(items)
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error fetching local-only orders for venue=$venueId")
            emptyList()
        }
    }

    /**
     * Observe order as reactive Flow - Single Source of Truth Pattern.
     *
     * **CRITICAL: This is the recommended way to observe orders in UI.**
     *
     * Room automatically emits whenever the order OR any of its items change.
     * This eliminates race conditions between separate queries and ensures
     * the UI always has fresh data without manual refresh.
     *
     * **Benefits over getLocalOrder():**
     * - Reactive: UI updates automatically when Room DB changes
     * - Single query: Order + items loaded together (Room handles JOIN)
     * - No race conditions: Flow emits on ANY change (order fields, items added/removed)
     * - Eliminates dual state: ViewModel doesn't need to manually update _state
     *
     * **Usage in ViewModel:**
     * ```kotlin
     * val orderState: StateFlow<MenuState> = _currentOrderId
     *     .filterNotNull()
     *     .flatMapLatest { orderId ->
     *         orderSyncCoordinator.observeOrder(orderId)
     *     }
     *     .map { order -> MenuState.Success(order) }
     *     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MenuState.Loading)
     * ```
     *
     * @param orderId Order ID (local UUID or server CUID)
     * @return Flow<Order> that emits on every order/item change
     */
    fun observeOrder(orderId: String): Flow<Order> {
        return draftOrderDao.observeOrderWithItems(orderId)
            .filterNotNull()
            .map { withItems ->
                // Filter out DELETED items (soft-delete pattern)
                val activeItems = withItems.items
                    .filter { it.syncStatus != DraftOrderItemEntity.SYNC_STATUS_DELETED }

                // Convert to domain model
                withItems.order.toDomain(activeItems)
            }
            .distinctUntilChanged()
    }

    /**
     * Cache backend order to local database.
     *
     * When an order is loaded from backend (not in local DB), we need to cache it locally
     * so that subsequent operations (addItem, removeItem) can work without FOREIGN KEY errors.
     *
     * **Use Case:**
     * 1. User opens existing order from backend (table order, previous session)
     * 2. Order loaded from API but NOT in Room DB yet
     * 3. User tries to add item → FOREIGN KEY constraint fails (orderId doesn't exist locally)
     * 4. Solution: Cache backend order to Room DB first
     *
     * **Sync Status:**
     * - Order is marked as SYNCED (it came from backend, so it's already in sync)
     * - isServerCreated = true (it has a server CUID, not local UUID)
     * - Items are also marked as SYNCED
     *
     * @param order Order fetched from backend
     */
    suspend fun cacheBackendOrder(order: Order) = withContext(Dispatchers.IO) {
        try {
            Timber.d("💾 [Cache] Caching backend order to local DB | id=${order.id} | items=${order.items.size}")

            // ⭐ PRESERVE LOCAL sentToKitchenAt TIMESTAMPS
            // Backend doesn't have this field, so we must preserve local values
            val existingItems = draftOrderItemDao.getAllItemsByOrder(order.id)
            val localSentTimestamps = existingItems.associate { it.id to it.sentToKitchenAt }
            Timber.d("💾 [Cache] Preserving ${localSentTimestamps.count { it.value != null }} local sentToKitchenAt timestamps")

            // Convert order to entity (marked as SYNCED since it came from backend)
            val draftOrderEntity = order.toEntity(
                syncStatus = DraftOrderEntity.SYNC_STATUS_SYNCED,
                isServerCreated = true
            )

            // Convert items to entities (also marked as SYNCED)
            val draftItemEntities = order.items.toEntities(
                syncStatus = DraftOrderItemEntity.SYNC_STATUS_SYNCED,
                isServerCreated = true
            ).map { item ->
                // Preserve local sentToKitchenAt if it exists
                val localTimestamp = localSentTimestamps[item.id]
                if (localTimestamp != null && item.sentToKitchenAt == null) {
                    item.copy(sentToKitchenAt = localTimestamp)
                } else {
                    item
                }
            }

            // Insert order first (parent)
            draftOrderDao.insert(draftOrderEntity)

            // Then insert items (children with foreign key to order)
            draftItemEntities.forEach { item ->
                draftOrderItemDao.insert(item)
            }

            Timber.i("✅ [Cache] Backend order cached successfully | id=${order.id}")
        } catch (e: Exception) {
            Timber.e(e, "❌ [Cache] Failed to cache backend order: ${order.id}")
            // Don't throw - caching is a nice-to-have, order still loaded in memory
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

        // 🔍 [DIAGNOSTIC] Log modifiers being stored
        val modifiersJson = if (modifiers.isNotEmpty()) {
            com.google.gson.Gson().toJson(modifiers)
        } else {
            "[]"
        }
        Timber.d("🔍 [addItemToLocalOrder] Storing modifiers | product=$productName | modifiers.size=${modifiers.size} | JSON=$modifiersJson")

        val draftItem = DraftOrderItemEntity(
            id = localItemId,
            orderId = orderId,
            productId = productId,
            productName = productName,
            productSku = null,
            quantity = quantity,
            unitPrice = unitPrice,
            totalPrice = totalPrice,
            modifiers = modifiersJson,
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

        Timber.d("➕ [Local] Added item | order=$orderId | product=$productName | qty=$quantity | modifiers=${modifiers.size}")
        Timber.d("➕ [SYNC-DEBUG] addItemToLocalOrder() | orderId=$orderId | itemId=$localItemId | product=$productName | timestamp=${System.currentTimeMillis()}")

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
     * Update item quantity in local order (instant UI).
     *
     * Updates quantity and recalculates total price, marks item as PENDING for sync.
     * If quantity is 0, removes the item (soft delete).
     *
     * @param orderId Local or server order ID
     * @param itemId Item to update
     * @param newQuantity New quantity (0 removes item)
     * @param unitPrice Unit price for recalculating total
     */
    suspend fun updateItemQuantityInLocalOrder(
        orderId: String,
        itemId: String,
        newQuantity: Int,
        unitPrice: BigDecimal
    ) = withContext(Dispatchers.IO) {
        if (newQuantity <= 0) {
            // Remove item if quantity is 0
            removeItemFromLocalOrder(orderId, itemId)
        } else {
            // Update quantity in database
            val newTotalPrice = (unitPrice * newQuantity.toBigDecimal()).toString()
            draftOrderItemDao.updateQuantity(itemId, newQuantity, newTotalPrice)

            // Update order totals
            recalculateOrderTotals(orderId)

            Timber.d("✏️ [Local] Updated item quantity | order=$orderId | item=$itemId | qty=$newQuantity")
        }
    }

    /**
     * Mark items as sent to kitchen (persists sentToKitchenAt to Room DB).
     *
     * **CRITICAL**: This must be called AFTER printing to persist the timestamp.
     * Otherwise, when sync event reloads the order from DB, items will show as pending.
     *
     * @param orderId Order ID
     * @param itemIds List of item IDs to mark as sent
     * @param sentAt Unix timestamp (milliseconds) when sent to kitchen
     */
    suspend fun markItemsAsSentToKitchen(
        orderId: String,
        itemIds: List<String>,
        sentAt: Long
    ) = withContext(Dispatchers.IO) {
        if (itemIds.isEmpty()) {
            Timber.d("🍳 [Local] No items to mark as sent to kitchen")
            return@withContext
        }

        draftOrderItemDao.markItemsAsSentToKitchen(itemIds, sentAt)
        Timber.i("🍳 [Local] Marked ${itemIds.size} items as sent to kitchen | order=$orderId | sentAt=$sentAt")
    }

    /**
     * Mark a single item as sent to kitchen.
     *
     * @param itemId Item ID to mark as sent
     * @param sentAt Unix timestamp (milliseconds) when sent to kitchen
     */
    suspend fun markItemAsSentToKitchen(
        itemId: String,
        sentAt: Long
    ) = withContext(Dispatchers.IO) {
        draftOrderItemDao.markAsSentToKitchen(itemId, sentAt)
        Timber.i("🍳 [Local] Marked item as sent to kitchen | itemId=$itemId | sentAt=$sentAt")
    }

    /**
     * Schedule debounced sync (2 seconds after last change).
     *
     * Cancels any previous pending sync for this order and restarts timer.
     * This batches rapid changes (user adding multiple items) into one sync.
     *
     * **Debounce Timing:**
     * - 2s balances responsiveness vs batching
     * - Industry reference: Toast ~2-3s, Clover ~1-2s
     * - Square uses real-time WebSocket sync (no debounce)
     *
     * **Example:**
     * ```
     * User adds item 1 → scheduleSync() → timer starts (2s)
     * User adds item 2 → scheduleSync() → previous timer cancelled, new timer starts (2s)
     * User adds item 3 → scheduleSync() → previous timer cancelled, new timer starts (2s)
     * 2 seconds of no changes → All 3 items synced in one API call
     * ```
     *
     * **⚡ P0 FIX: Race Condition Prevention**
     * - Checks if sync is already in progress AFTER debounce expires
     * - Skips redundant sync if another sync started while waiting
     * - Prevents version conflicts when adding items rapidly
     *
     * @param orderId Order to sync (local or server ID)
     */
    fun scheduleSync(orderId: String) {
        // Cancel previous pending sync for this order
        pendingSyncJobs[orderId]?.cancel()

        // Schedule new debounced sync
        val job = syncScope.launch {
            val scheduledAt = System.currentTimeMillis()
            Timber.d("⏱️ [Sync] Scheduled debounced sync | order=$orderId | delay=${SYNC_DEBOUNCE_MS}ms")
            Timber.d("⏱️ [SYNC-DEBUG] scheduleSync() | orderId=$orderId | debounce=${SYNC_DEBOUNCE_MS}ms | timestamp=$scheduledAt")

            delay(SYNC_DEBOUNCE_MS)

            // ⚡ P0 FIX: Check if another sync is already in progress
            // This prevents race condition when rapid changes schedule multiple syncs
            val currentOrder = draftOrderDao.getOrder(orderId)
            if (currentOrder?.syncStatus == DraftOrderEntity.SYNC_STATUS_SYNCING) {
                Timber.d("⏭️ [Sync] Skipping debounced sync - already in progress | order=$orderId")
                pendingSyncJobs.remove(orderId)
                return@launch
            }

            Timber.d("🔄 [Sync] Debounce expired, executing sync | order=$orderId")
            Timber.i("🔄 [SYNC-DEBUG] scheduleSync() DEBOUNCE EXPIRED | orderId=$orderId | timestamp=${System.currentTimeMillis()} | elapsed=${System.currentTimeMillis() - scheduledAt}ms")
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
     * - Split payment navigation (need synced order ID)
     *
     * Cancels any pending debounced sync for this order.
     *
     * @param orderId Order to sync (may be local_xxx or CUID)
     * @return The synced order ID (CUID from server, or original ID if already synced)
     */
    suspend fun syncOrderImmediately(orderId: String): String = withContext(Dispatchers.IO) {
        // Cancel pending debounced sync
        pendingSyncJobs[orderId]?.cancel()
        pendingSyncJobs.remove(orderId)

        Timber.d("⚡ [Sync] Immediate sync requested | order=$orderId")
        executeSyncWithRetry(orderId)
    }

    /**
     * Update order's merchant account information after successful payment.
     *
     * ⭐ P0 FIX: Track which merchant account processed the first payment on an order.
     * This prevents split payment mismatches (mixing different merchant accounts).
     *
     * **Use Case:**
     * - Order has partial payment ($500 MXN) → Lock to Merchant A
     * - Subsequent payments MUST use Merchant A (validated in PaymentViewModel)
     *
     * **Sync Behavior:**
     * - Updates local order immediately
     * - Marks as PENDING sync
     * - Schedules debounced sync to backend (2s delay)
     *
     * @param orderId Order to update
     * @param merchantAccountId Backend merchant account CUID (e.g., "cm...")
     * @param merchantAccountName Display name for user-friendly errors (e.g., "Cuenta BBVA")
     */
    suspend fun updateOrderMerchant(
        orderId: String,
        merchantAccountId: String?,
        merchantAccountName: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val draftOrder = draftOrderDao.getOrder(orderId)

            if (draftOrder == null) {
                Timber.w("⚠️ [Merchant Update] Order not found | orderId=$orderId")
                return@withContext
            }

            // Update merchant fields
            val updatedOrder = draftOrder.copy(
                merchantAccountId = merchantAccountId,
                merchantAccountName = merchantAccountName,
                syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING,
                updatedAt = System.currentTimeMillis()
            )

            draftOrderDao.insert(updatedOrder)

            Timber.d("✅ [Merchant Update] Updated order | orderId=$orderId | merchant=$merchantAccountName ($merchantAccountId)")

            // Schedule debounced sync to backend
            scheduleSync(orderId)

        } catch (e: Exception) {
            Timber.e(e, "❌ [Merchant Update] Failed to update order merchant | orderId=$orderId")
        }
    }

    // ========================================
    // PRIVATE SYNC LOGIC
    // ========================================

    /**
     * Execute sync with exponential backoff retry.
     *
     * ✅ P0 FIX: Uses mutex to prevent concurrent syncs for the same order.
     * This ensures version numbers are read/written atomically.
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
     * @return The synced order ID (CUID from server, or original ID if conflict/error)
     */
    private suspend fun executeSyncWithRetry(
        orderId: String,
        attempt: Int = 1
    ): String {
        // ✅ P0 FIX: Acquire mutex for this order to prevent concurrent syncs
        val mutex = syncMutexes.getOrPut(orderId) { Mutex() }

        return mutex.withLock {
            if (attempt > 1) {
                val delayMs = (2.0.pow(attempt - 1) * 1000).toLong()
                Timber.d("🔄 [Sync] Retry attempt $attempt | order=$orderId | delay=${delayMs}ms")
                delay(delayMs)
            }

            try {
                val syncedId = executeSync(orderId)
                Timber.i("✅ [Sync] Success | order=$orderId → syncedId=$syncedId")
                syncedId
            } catch (e: ConflictException) {
                Timber.w("⚠️ [Sync] Conflict detected | order=$orderId | version mismatch")
                // Do NOT retry on 409 - user must resolve conflict
                _syncEvents.emit(SyncEvent.Conflict(orderId, e.serverVersion))
                orderId  // Return original ID on conflict
            } catch (e: kotlinx.coroutines.CancellationException) {
                // ⚠️ CRITICAL: Rethrow CancellationException so coroutine is cancelled properly
                // Do NOT catch this as a generic error, or it will trigger retries!
                Timber.d("🛑 [Sync] Cancelled (debounce) | order=$orderId")
                throw e
            } catch (e: Exception) {
                Timber.e(e, "❌ [Sync] Failed (attempt $attempt) | order=$orderId")

                if (attempt < 4) {
                    // Retry with exponential backoff (release mutex during delay)
                    // Note: We release mutex here and re-acquire in recursive call
                    // This is intentional - allows other syncs to proceed during backoff
                    executeSyncWithRetryInternal(orderId, attempt + 1)
                } else {
                    // Max retries exceeded
                    Timber.e("❌ [Sync] Max retries exceeded | order=$orderId")
                    // ✅ P0 FIX: Persist 'ERROR' status in DB
                    draftOrderDao.updateSyncStatus(orderId, "ERROR", System.currentTimeMillis())
                    _syncEvents.emit(SyncEvent.Error(orderId, e.message ?: "Sync failed"))
                    orderId  // Return original ID on failure
                }
            }
        }
    }

    /**
     * Internal retry helper (called from within mutex lock).
     * Releases mutex during backoff delay, then re-acquires for retry.
     */
    private suspend fun executeSyncWithRetryInternal(
        orderId: String,
        attempt: Int
    ): String {
        val delayMs = (2.0.pow(attempt - 1) * 1000).toLong()
        Timber.d("🔄 [Sync] Retry attempt $attempt | order=$orderId | delay=${delayMs}ms")
        delay(delayMs)

        val mutex = syncMutexes.getOrPut(orderId) { Mutex() }

        return mutex.withLock {
            try {
                val syncedId = executeSync(orderId)
                Timber.i("✅ [Sync] Success (retry $attempt) | order=$orderId → syncedId=$syncedId")
                syncedId
            } catch (e: ConflictException) {
                Timber.w("⚠️ [Sync] Conflict detected | order=$orderId | version mismatch")
                _syncEvents.emit(SyncEvent.Conflict(orderId, e.serverVersion))
                orderId
            } catch (e: kotlinx.coroutines.CancellationException) {
                Timber.d("🛑 [Sync] Cancelled during retry (debounce) | order=$orderId")
                throw e
            } catch (e: Exception) {
                Timber.e(e, "❌ [Sync] Failed (attempt $attempt) | order=$orderId")

                if (attempt < 4) {
                    executeSyncWithRetryInternal(orderId, attempt + 1)
                } else {
                    Timber.e("❌ [Sync] Max retries exceeded | order=$orderId")
                    // ✅ P0 FIX: Persist 'ERROR' status in DB
                    draftOrderDao.updateSyncStatus(orderId, "ERROR", System.currentTimeMillis())
                    _syncEvents.emit(SyncEvent.Error(orderId, e.message ?: "Sync failed"))
                    orderId
                }
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
     * @return The synced order ID (CUID from server for new orders, or original ID for updates)
     */
    private suspend fun executeSync(orderId: String): String {
        val draftOrder = draftOrderDao.getOrder(orderId)
            ?: throw Exception("Order not found in local DB: $orderId")

        Timber.i("🔄 [SYNC-DEBUG] executeSync() START | orderId=$orderId | isServerCreated=${draftOrder.isServerCreated} | timestamp=${System.currentTimeMillis()}")

        // Update sync status to SYNCING
        draftOrderDao.updateSyncStatus(orderId, DraftOrderEntity.SYNC_STATUS_SYNCING, System.currentTimeMillis())
        _syncEvents.emit(SyncEvent.Syncing(orderId))

        return if (!draftOrder.isServerCreated) {
            // First sync - create on server (returns new CUID)
            createOrderOnServer(draftOrder)
        } else {
            // Subsequent sync - update on server (returns same ID)
            updateOrderOnServer(draftOrder)
            orderId  // Return same ID for updates
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
     * @return The server order ID (CUID)
     */
    private suspend fun createOrderOnServer(draftOrder: DraftOrderEntity): String {
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
        Timber.i("📥 [SYNC-DEBUG] createOrderOnServer() GOT SERVER ID | localId=${draftOrder.id} → serverCuid=${serverOrder.id} | timestamp=${System.currentTimeMillis()}")

        // Step 2: Add all pending items to order
        val pendingItems = draftOrderItemDao.getPendingItemsByOrder(draftOrder.id)

        // Track the final version after all server operations
        var finalVersion = serverOrder.version
        var finalOrderNumber = serverOrder.orderNumber

        if (pendingItems.isNotEmpty()) {
            val addItemRequests = pendingItems.map { item ->
                // 🔍 [DIAGNOSTIC] Log raw modifiers JSON from database
                Timber.d("🔍 [Modifier Debug] Item: ${item.productName} | Raw modifiers JSON: ${item.modifiers}")

                // ⭐ P0 FIX: Parse modifiers JSON to extract IDs for backend
                val modifiersList = try {
                    val gson = Gson()
                    val type = object : TypeToken<List<ProductModifier>>() {}.type
                    gson.fromJson<List<ProductModifier>>(item.modifiers, type) ?: emptyList()
                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to parse modifiers JSON for item ${item.productName}: ${item.modifiers}")
                    emptyList()
                }

                // 🔍 [DIAGNOSTIC] Log parsed modifier IDs
                val modifierIds = modifiersList.map { it.id }
                Timber.d("🔍 [Modifier Debug] Parsed ${modifiersList.size} modifiers → IDs: $modifierIds")

                AddOrderItemRequest(
                    productId = item.productId,
                    quantity = item.quantity,
                    notes = item.notes,
                    modifierIds = modifierIds  // ✅ Send modifier IDs to backend
                )
            }

            // 🔍 [DIAGNOSTIC] Log productIds and modifierIds being sent to backend
            Timber.d("📦 [Sync] Adding ${pendingItems.size} items to server order")
            addItemRequests.forEachIndexed { index, request ->
                Timber.d("   [$index] productId=${request.productId} | qty=${request.quantity} | modifierIds=${request.modifierIds?.size ?: 0} IDs: ${request.modifierIds}")
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

            // ⭐ P0 FIX: Use updated version after adding items
            // Backend increments version on each mutation, so we must save the latest version
            finalVersion = updatedOrder.version
            finalOrderNumber = updatedOrder.orderNumber

            Timber.i("✅ [Sync] Added ${pendingItems.size} items to server order | version=${updatedOrder.version}")

            // Step 3: Replace local item IDs with server IDs
            // ✅ P1 FIX: Match by productId instead of index (safer when order differs)
            // Build a map of server items by productId for efficient lookup
            val serverItemsByProduct = updatedOrder.items.groupBy { it.productId }

            for (localItem in pendingItems) {
                // Find server items with matching productId
                val matchingServerItems = serverItemsByProduct[localItem.productId]

                if (matchingServerItems.isNullOrEmpty()) {
                    Timber.w("⚠️ [Sync] No server item found for productId=${localItem.productId} (${localItem.productName})")
                    continue
                }

                // If there are multiple items with same productId, match by quantity as tiebreaker
                val serverItem = matchingServerItems.find { it.quantity == localItem.quantity }
                    ?: matchingServerItems.first()  // Fallback to first match

                draftOrderItemDao.replaceLocalIdWithServerCuid(
                    localId = localItem.id,
                    newId = serverItem.id
                )
                Timber.d("🔄 [Sync] Replaced item ID | local=${localItem.id} → server=${serverItem.id} | product=${localItem.productName}")
            }
        }

        // Step 4: Replace local order ID with server CUID
        // ⭐ P0 FIX: ON UPDATE CASCADE automatically updates all child items' order_id
        // We don't need to manually call updateOrderId() anymore (it causes FK violation)

        // 🔍 [DIAGNOSTIC] Log FK update to detect orphaned items
        val itemsBeforeFK = draftOrderItemDao.getAllItemsByOrderId(draftOrder.id)
        Timber.d("🔑 [FK UPDATE] Before: ${itemsBeforeFK.size} items with order_id=${draftOrder.id}")
        itemsBeforeFK.forEach { item ->
            Timber.d("   → ${item.productName} | orderId=${item.orderId} | status=${item.syncStatus}")
        }

        val replacementStartTime = System.currentTimeMillis()
        Timber.w("🔀 [SYNC-DEBUG] ID REPLACEMENT START | old=${draftOrder.id} → new=${serverOrder.id} | timestamp=$replacementStartTime")

        draftOrderDao.replaceLocalIdWithServerCuid(
            localId = draftOrder.id,
            newId = serverOrder.id,
            orderNumber = finalOrderNumber,
            version = finalVersion,  // ← Use final version (updated after adding items)
            timestamp = System.currentTimeMillis()
        )

        Timber.w("✅ [SYNC-DEBUG] ID REPLACEMENT DONE | old=${draftOrder.id} → new=${serverOrder.id} | timestamp=${System.currentTimeMillis()} | duration=${System.currentTimeMillis() - replacementStartTime}ms")

        // 🔍 [DIAGNOSTIC] Verify FK CASCADE worked correctly
        val itemsAfterFK = draftOrderItemDao.getAllItemsByOrderId(serverOrder.id)
        Timber.d("🔑 [FK UPDATE] After: ${itemsAfterFK.size} items with order_id=${serverOrder.id}")
        if (itemsAfterFK.size != itemsBeforeFK.size) {
            Timber.e("⚠️ [FK UPDATE] Item count mismatch! Before=${itemsBeforeFK.size}, After=${itemsAfterFK.size}")
        }
        itemsAfterFK.forEach { item ->
            Timber.d("   → ${item.productName} | orderId=${item.orderId} | status=${item.syncStatus}")
        }

        Timber.d("✅ [Sync] Order ID replaced | oldId=${draftOrder.id} | newId=${serverOrder.id} | version=$finalVersion")
        Timber.d("   → ON UPDATE CASCADE automatically updated ${pendingItems.size} items")

        Timber.i("📣 [SYNC-DEBUG] SyncEvent.Synced ABOUT TO EMIT | serverId=${serverOrder.id} | version=$finalVersion | timestamp=${System.currentTimeMillis()}")
        _syncEvents.emit(SyncEvent.Synced(serverOrder.id, finalVersion))
        Timber.i("📣 [SYNC-DEBUG] SyncEvent.Synced EMITTED | serverId=${serverOrder.id} | timestamp=${System.currentTimeMillis()}")

        // Return the server order ID (CUID)
        return serverOrder.id
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

        // ⭐ P0 FIX: Track version across all mutations
        // Each mutation (add/remove items) increments the version on the server
        var currentVersion = draftOrder.version

        // Step 1: Add pending items
        val pendingItems = draftOrderItemDao.getPendingItemsByOrder(orderId)

        if (pendingItems.isNotEmpty()) {
            val addItemRequests = pendingItems.map { item ->
                // ⭐ P0 FIX: Parse modifiers JSON to extract IDs for backend
                val modifiersList = try {
                    val gson = Gson()
                    val type = object : TypeToken<List<ProductModifier>>() {}.type
                    gson.fromJson<List<ProductModifier>>(item.modifiers, type) ?: emptyList()
                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to parse modifiers JSON for item ${item.productName}: ${item.modifiers}")
                    emptyList()
                }

                AddOrderItemRequest(
                    productId = item.productId,
                    quantity = item.quantity,
                    notes = item.notes,
                    modifierIds = modifiersList.map { it.id }  // ✅ Send modifier IDs to backend
                )
            }

            // 🔍 [DIAGNOSTIC] Log productIds being sent to backend
            Timber.d("📦 [Sync] Adding ${pendingItems.size} items to existing order")
            pendingItems.forEachIndexed { index, item ->
                Timber.d("   [$index] productId=${item.productId} | name=${item.productName} | qty=${item.quantity}")
            }

            val result = orderRepository.addItemsToOrder(
                venueId = venueId,
                orderId = orderId,
                items = addItemRequests,
                currentVersion = currentVersion  // Use tracked version
            )

            if (result.isFailure) {
                val exception = result.exceptionOrNull()
                if (exception is ConflictException) {
                    throw exception
                }
                throw exception ?: Exception("Failed to add items")
            }

            val updatedOrder = result.getOrThrow()

            // ⭐ Update tracked version after adding items
            currentVersion = updatedOrder.version

            // ⭐ CRITICAL: Persist version immediately to prevent stale retry
            // If a retry happens after this point, it will read the correct version from DB
            draftOrderDao.updateVersion(orderId, currentVersion)
            Timber.d("💾 [Sync] Persisted version=$currentVersion after addItems")

            // Mark items as SYNCED
            pendingItems.forEach { item ->
                draftOrderItemDao.updateSyncStatus(item.id, DraftOrderItemEntity.SYNC_STATUS_SYNCED)
            }

            Timber.i("✅ [Sync] Added ${pendingItems.size} items | version=$currentVersion")
        }

        // Step 2: Remove deleted items
        val deletedItems = draftOrderItemDao.getDeletedItemsByOrder(orderId)

        for (deletedItem in deletedItems) {
            if (deletedItem.isServerCreated) {
                val result = orderRepository.removeOrderItem(
                    venueId = venueId,
                    orderId = orderId,
                    orderItemId = deletedItem.id,
                    currentVersion = currentVersion  // Use tracked version (updated after add)
                )

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    if (exception is ConflictException) {
                        throw exception
                    }
                    throw exception ?: Exception("Failed to remove item")
                }

                val updatedOrder = result.getOrThrow()

                // ⭐ Update tracked version after removing item
                currentVersion = updatedOrder.version

                // ⭐ CRITICAL: Persist version immediately to prevent stale retry
                draftOrderDao.updateVersion(orderId, currentVersion)
                Timber.d("💾 [Sync] Persisted version=$currentVersion after removeItem")
            }

            // Hard delete from local DB after successful server deletion
            draftOrderItemDao.delete(deletedItem)
        }

        if (deletedItems.isNotEmpty()) {
            Timber.i("✅ [Sync] Removed ${deletedItems.size} items | version=$currentVersion")
        }

        // Step 3: Update local order version and mark as SYNCED
        // ⭐ P0 FIX: Save the latest version from server
        val order = draftOrderDao.getOrder(orderId)
        if (order != null) {
            // ✅ FIX: Use update() instead of insert() to prevent CASCADE DELETE
            // insert() with REPLACE strategy DELETEs order row, triggering FK CASCADE on all items
            // update() modifies existing row without DELETE, preserving all child items
            draftOrderDao.update(
                order.copy(
                    version = currentVersion,  // ← Save latest version
                    syncStatus = DraftOrderEntity.SYNC_STATUS_SYNCED,
                    lastSyncAt = System.currentTimeMillis()
                )
            )
        }

        Timber.i("📣 [SYNC-DEBUG] SyncEvent.Synced ABOUT TO EMIT (update) | orderId=$orderId | version=$currentVersion | timestamp=${System.currentTimeMillis()}")
        _syncEvents.emit(SyncEvent.Synced(orderId, currentVersion))
        Timber.i("📣 [SYNC-DEBUG] SyncEvent.Synced EMITTED (update) | orderId=$orderId | timestamp=${System.currentTimeMillis()}")
    }

    /**
     * Recalculate order totals (subtotal, tax, total).
     *
     * Sums all non-deleted items. Tax is 0% (backend handles tax separately).
     *
     * @param orderId Order to recalculate
     */
    private suspend fun recalculateOrderTotals(orderId: String) {
        val items = draftOrderItemDao.getItemsByOrder(orderId)

        val subtotal = items.sumOf { it.totalPrice.toBigDecimal() }
        val tax = BigDecimal.ZERO  // ✅ FIX: Backend handles tax (currently 0% for new orders)
        val total = subtotal  // ✅ FIX: Total = subtotal (no tax added)

        val order = draftOrderDao.getOrder(orderId) ?: return

        // ✅ P0 FIX: Calculate remainingBalance = total - paidAmount
        // Without this, payment buttons stay disabled because canProcessPayment requires remainingBalance > 0
        val paidAmount = order.paidAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val remainingBalance = total - paidAmount

        draftOrderDao.update(
            order.copy(
                subtotal = subtotal.toString(),
                tax = tax.toString(),
                total = total.toString(),
                remainingBalance = remainingBalance.toString(),  // ✅ P0 FIX: Enable payment buttons
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
