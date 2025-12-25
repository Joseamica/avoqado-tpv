package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.core.data.local.mappers.toCategoryDomain
import com.jaac.avoqado_tpv.core.data.local.mappers.toCategoryEntities
import com.jaac.avoqado_tpv.core.data.local.mappers.toDomain
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntities
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderCustomer
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderDiscount
import com.jaac.avoqado_tpv.features.ordering.domain.CouponCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * MenuViewModel - Manages order state for MenuScreen
 *
 * Responsibilities:
 * - Load/create order for a table
 * - Load products from backend ProductRepository
 * - Add/remove/update items
 * - Calculate totals (subtotal, tax, total)
 * - Send order to kitchen
 * - Navigate to payment
 *
 * State management: StateFlow for reactive UI updates
 */
@HiltViewModel
class MenuViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,  // ✨ BARCODE QUICK ADD: For BroadcastReceiver
    private val secureStorage: SecureStorage,
    private val deviceInfoManager: DeviceInfoManager,
    private val productRepository: com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository,
    private val orderRepository: com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository,
    private val tableRepository: com.jaac.avoqado_tpv.features.ordering.domain.TableRepository,
    private val discountRepository: DiscountRepository,  // 🎟️ Discount/Coupon operations
    private val customerRepository: com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository,  // 👤 Customer search
    private val socketManager: com.jaac.avoqado_tpv.core.data.realtime.SocketManager,
    private val orderSyncCoordinator: com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator,
    private val productDao: ProductDao,  // ⚡ Cache-first product loading
    private val productCategoryDao: ProductCategoryDao,  // ⚡ Cache-first category loading
    private val printerManager: PrinterManager  // 🖨️ Kitchen ticket printing
) : ViewModel() {

    private val _state = MutableStateFlow<MenuState>(MenuState.Loading)
    val state: StateFlow<MenuState> = _state.asStateFlow()

    /**
     * Current order ID being observed - Single Source of Truth Pattern.
     *
     * When this changes, the Room Flow automatically emits the new order data.
     * This eliminates the need for manual _state.value updates after sync.
     *
     * @see collectOrderFromRoom
     */
    private val _currentOrderId = MutableStateFlow<String?>(null)

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _categories = MutableStateFlow<List<com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory>>(emptyList())
    val categories: StateFlow<List<com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory>> = _categories.asStateFlow()

    // 🔍 Search functionality (Issue #4)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Processing flag to prevent rapid clicks during backend operations
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Products loading state (for AvoqadoLoadingOverlay)
    private val _isLoadingProducts = MutableStateFlow(true)  // Start as true since products load in init
    val isLoadingProducts: StateFlow<Boolean> = _isLoadingProducts.asStateFlow()

    // 🔄 Local-first sync status
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // Pull-to-refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // 🎁 Loyalty program status (Toast/Square pattern)
    // If false, hide loyalty points in customer UI
    val loyaltyActive: Boolean
        get() = secureStorage.isLoyaltyActive()

    // ============================================================================
    // 🎟️ Discount & Coupon State (ActionsTab)
    // ============================================================================

    // Available predefined discounts for the venue
    private val _availableDiscounts = MutableStateFlow<List<Discount>>(emptyList())
    val availableDiscounts: StateFlow<List<Discount>> = _availableDiscounts.asStateFlow()

    // Applied discounts on current order
    private val _appliedDiscounts = MutableStateFlow<List<OrderDiscount>>(emptyList())
    val appliedDiscounts: StateFlow<List<OrderDiscount>> = _appliedDiscounts.asStateFlow()

    // Loading state for discounts
    private val _isLoadingDiscounts = MutableStateFlow(false)
    val isLoadingDiscounts: StateFlow<Boolean> = _isLoadingDiscounts.asStateFlow()

    // Coupon validation state
    private val _couponValidationState = MutableStateFlow<CouponValidationState>(CouponValidationState.Idle)
    val couponValidationState: StateFlow<CouponValidationState> = _couponValidationState.asStateFlow()

    // ============================================================================
    // 💳 Payment Preparation State
    // ============================================================================

    /**
     * Payment preparation state - shows loading while syncing order before payment.
     *
     * ⚠️ CRITICAL: When user clicks "Pagar", we must sync pending changes and fetch
     * recalculated discounts from backend BEFORE navigating to PaymentScreen.
     * This prevents charging wrong amounts due to stale percentage discounts.
     */
    private val _isPreparingPayment = MutableStateFlow(false)
    val isPreparingPayment: StateFlow<Boolean> = _isPreparingPayment.asStateFlow()

    // ============================================================================
    // 🎯 UX Events (Snackbar, Navigation)
    // ============================================================================

    /**
     * UI events for one-time actions (snackbar, navigation).
     * Uses SharedFlow instead of StateFlow to prevent event replay on recomposition.
     *
     * @see MenuScreen for event collection and handling
     */
    sealed class MenuUiEvent {
        data class ShowSnackbar(val message: String, val isError: Boolean = false) : MenuUiEvent()
        data object NavigateBack : MenuUiEvent()
    }

    private val _uiEvents = MutableSharedFlow<MenuUiEvent>(replay = 0)
    val uiEvents: SharedFlow<MenuUiEvent> = _uiEvents.asSharedFlow()

    // ============================================================================
    // 👤 Customer Search State (GuestTab)
    // ============================================================================

    // Customer search results state
    private val _customerSearchState = MutableStateFlow<com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState>(
        com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Idle
    )
    val customerSearchState: StateFlow<com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState> = _customerSearchState.asStateFlow()

    // @DEPRECATED: Use orderCustomers instead for multi-customer support
    // Kept for backward compatibility - will return first (primary) customer
    private val _selectedCustomer = MutableStateFlow<com.jaac.avoqado_tpv.features.ordering.domain.Customer?>(null)
    val selectedCustomer: StateFlow<com.jaac.avoqado_tpv.features.ordering.domain.Customer?> = _selectedCustomer.asStateFlow()

    // ============================================================================
    // 👥 Multi-Customer per Order State (NEW)
    // ============================================================================

    // Customers associated with the current order (many-to-many via OrderCustomer junction)
    // First customer added is isPrimary=true and receives loyalty points on payment
    private val _orderCustomers = MutableStateFlow<List<OrderCustomer>>(emptyList())
    val orderCustomers: StateFlow<List<OrderCustomer>> = _orderCustomers.asStateFlow()

    // Loading state for adding/removing customers
    private val _isAddingCustomer = MutableStateFlow(false)
    val isAddingCustomer: StateFlow<Boolean> = _isAddingCustomer.asStateFlow()

    // Recent customers for quick selection (Toast/Square pattern)
    private val _recentCustomers = MutableStateFlow<List<com.jaac.avoqado_tpv.features.ordering.domain.Customer>>(emptyList())
    val recentCustomers: StateFlow<List<com.jaac.avoqado_tpv.features.ordering.domain.Customer>> = _recentCustomers.asStateFlow()

    // Loading state for recent customers
    private val _isLoadingRecentCustomers = MutableStateFlow(false)
    val isLoadingRecentCustomers: StateFlow<Boolean> = _isLoadingRecentCustomers.asStateFlow()

    /**
     * Filtered products based on search query.
     * Combines products and searchQuery flows to provide reactive filtering.
     *
     * **Performance Optimizations (Toast POS + Cache-first pattern):**
     * - Filtering runs on Dispatchers.Default (off main thread)
     * - distinctUntilChanged() prevents redundant recompositions
     * - Local filtering for instant results (~1ms filtering time)
     */
    val filteredProducts: StateFlow<List<Product>> = _products.combine(_searchQuery) { productsList: List<Product>, query: String ->
        // Move filtering to background thread
        withContext(Dispatchers.Default) {
            if (query.isBlank()) {
                productsList
            } else {
                productsList.filter { product: Product ->
                    product.name.contains(query, ignoreCase = true) ||
                    product.description?.contains(query, ignoreCase = true) == true ||
                    product.sku?.contains(query, ignoreCase = true) == true
                }
            }
        }
    }
        .distinctUntilChanged()  // Prevent redundant emissions
        .flowOn(Dispatchers.Default)  // Ensure off main thread
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // ✨ BARCODE QUICK ADD: BroadcastReceiver for VOLUME_UP button
    private val barcodeScannerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.jaac.avoqado_tpv.OPEN_BARCODE_SCANNER") {
                Timber.d("📷 [BarcodeQuickAdd] Broadcast received - opening scanner")
                openBarcodeQuickAdd()
            }
        }
    }

    init {
        // Load products on ViewModel creation
        loadProducts()

        // 🎟️ Load available discounts for the venue
        loadAvailableDiscounts()

        // 🔄 Collect sync events from OrderSyncCoordinator
        collectSyncEvents()

        // 🔄 [SSOT] Observe order from Room DB - Single Source of Truth Pattern
        // When Room DB changes (sync, add item, etc.), _state updates automatically
        collectOrderFromRoom()

        // Listen to Socket.IO events for real-time inventory updates
        listenToSocketEvents()

        // ✨ BARCODE QUICK ADD: Register BroadcastReceiver for VOLUME_UP button
        val intentFilter = android.content.IntentFilter("com.jaac.avoqado_tpv.OPEN_BARCODE_SCANNER")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(barcodeScannerReceiver, intentFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(barcodeScannerReceiver, intentFilter)
        }
        Timber.d("📷 [BarcodeQuickAdd] BroadcastReceiver registered")
    }

    override fun onCleared() {
        super.onCleared()
        // ✨ BARCODE QUICK ADD: Unregister BroadcastReceiver
        try {
            context.unregisterReceiver(barcodeScannerReceiver)
            Timber.d("📷 [BarcodeQuickAdd] BroadcastReceiver unregistered")
        } catch (e: Exception) {
            Timber.w(e, "📷 [BarcodeQuickAdd] Error unregistering BroadcastReceiver")
        }
    }

    /**
     * 🔄 Listen to Socket.IO Events for Real-time Updates
     *
     * **Critical for inventory sync:**
     * - When ORDER_UPDATED event is received, reload products to update inventory counts
     * - Ensures inventory decrements immediately when items are added to orders
     * - No manual refresh needed!
     *
     * **Backend emits ORDER_UPDATED when:**
     * - Items added to order (PATCH /orders/{orderId}/items)
     * - Items removed from order
     * - Order quantities updated
     *
     * **Performance Optimization (2025-11-24):**
     * - Debounce(500ms) to prevent spam reloads when multiple events arrive rapidly
     * - Example: 5 events in 1 second → 1 reload instead of 5 reloads
     * - Reduces network calls and UI jank
     *
     * **Pattern (Toast POS / Square POS):**
     * - Optimistic update: Show change immediately in UI
     * - Backend emits event: All terminals receive update
     * - Reload data: Sync with server state (inventory, prices, etc.)
     */
    private fun listenToSocketEvents() {
        // Listen for ORDER_UPDATED events (inventory sync)
        viewModelScope.launch {
            socketManager.events
                .filter { it is com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent.OrderUpdated }
                .debounce(500)  // ⚡ Wait 500ms after last event before reloading
                .collect { event ->
                    val orderEvent = event as com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent.OrderUpdated
                    Timber.i("🔄 [MenuViewModel] Order updated (debounced) - reloading products to sync inventory")
                    Timber.d("   OrderId: ${orderEvent.orderId} | Items: ${orderEvent.items?.size ?: 0} | Total: ${orderEvent.total}")

                    // Reload products to get updated inventory counts
                    loadProducts()
                }
        }

        // ⭐ Listen for PAYMENT_COMPLETED events to refresh order with lastSplitType
        // Bug fix: After payment, order needs to be refreshed from backend to get updated lastSplitType
        // This is critical for split payment restrictions (EQUALPARTS should hide PERPRODUCT option)
        viewModelScope.launch {
            socketManager.events
                .filter { it is com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent.PaymentCompleted }
                .collect { event ->
                    val paymentEvent = event as com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent.PaymentCompleted
                    val currentState = _state.value
                    val eventOrderId = paymentEvent.orderId

                    // Only refresh if this payment is for our current order (and orderId is not null)
                    if (currentState is MenuState.Success &&
                        eventOrderId != null &&
                        eventOrderId == currentState.order.id) {
                        Timber.i("💳 [MenuViewModel] Payment completed for current order - refreshing to get lastSplitType")
                        Timber.d("   PaymentId: ${paymentEvent.paymentId} | OrderId: $eventOrderId | Amount: ${paymentEvent.amount}")

                        // Refresh order from backend to get updated lastSplitType, paidAmount, remainingBalance
                        refreshOrderFromBackend(eventOrderId)
                    }
                }
        }
    }

    /**
     * Refresh order from backend (force reload, bypassing cache)
     *
     * Used after payment completes to get updated fields like:
     * - lastSplitType (for split payment restrictions)
     * - paidAmount (for partial payment banner)
     * - remainingBalance (for payment calculations)
     * - paymentStatus (may change to PAID if fully paid)
     */
    private fun refreshOrderFromBackend(orderId: String) {
        viewModelScope.launch {
            try {
                val venueId = deviceInfoManager.getVenueId() ?: return@launch

                val backendOrder = orderRepository.getOrder(
                    venueId = venueId,
                    orderId = orderId
                ).getOrElse { error ->
                    Timber.e(error, "❌ Failed to refresh order after payment")
                    return@launch
                }

                // Cache to local DB (preserves local-only fields like sentToKitchenAt)
                orderSyncCoordinator.cacheBackendOrder(backendOrder)

                // Load from local DB to get merged data (backend + local fields)
                val mergedOrder = orderSyncCoordinator.getLocalOrder(orderId) ?: backendOrder

                // Update state with refreshed order
                val currentState = _state.value
                if (currentState is MenuState.Success) {
                    _state.value = currentState.copy(order = mergedOrder)

                    // 🔍 DEBUG: Detailed order state after payment
                    Timber.i("✅ [MenuViewModel] Order refreshed after payment")
                    Timber.i("═══════════════════════════════════════════════════════════")
                    Timber.i("📋 ORDER STATE AFTER PAYMENT:")
                    Timber.i("   orderId: ${mergedOrder.id}")
                    Timber.i("   orderNumber: ${mergedOrder.orderNumber}")
                    Timber.i("   status: ${mergedOrder.status}")
                    Timber.i("   paymentStatus: ${mergedOrder.paymentStatus}")
                    Timber.i("   ─────────────────────────────────────────────────────")
                    Timber.i("   💰 AMOUNTS:")
                    Timber.i("      subtotal: ${mergedOrder.subtotal}")
                    Timber.i("      total: ${mergedOrder.total}")
                    Timber.i("      paidAmount: ${mergedOrder.paidAmount}")
                    Timber.i("      remainingBalance: ${mergedOrder.remainingBalance}")
                    Timber.i("   ─────────────────────────────────────────────────────")
                    Timber.i("   🔀 SPLIT PAYMENT:")
                    Timber.i("      lastSplitType: ${mergedOrder.lastSplitType}")
                    Timber.i("      hasRemainingBalance: ${mergedOrder.hasRemainingBalance}")
                    Timber.i("   ─────────────────────────────────────────────────────")
                    Timber.i("   📦 ITEMS (${mergedOrder.items.size}):")
                    mergedOrder.items.forEach { item ->
                        Timber.i("      - ${item.productName} x${item.quantity} = ${item.totalPrice}")
                    }
                    Timber.i("═══════════════════════════════════════════════════════════")
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error refreshing order after payment")
            }
        }
    }

    /**
     * Refresh order from backend after sync completes.
     *
     * ⚡ OPTIMIZATION: Only fetch if order has discounts applied.
     * This avoids unnecessary HTTP calls for orders without discounts.
     *
     * Critical for discount recalculation:
     * - Backend recalculates percentage discounts when items are added/removed
     * - We need to fetch the updated discountAmount from backend
     * - Also refresh _appliedDiscounts StateFlow with recalculated amounts
     *
     * @see avoqado-server/src/services/tpv/order.tpv.service.ts lines 504-540
     */
    private fun refreshOrderAfterSync(orderId: String) {
        val currentState = _state.value
        if (currentState !is MenuState.Success) return

        // ⚡ OPTIMIZATION: Only fetch if order has discounts
        val appliedDiscountsCount = _appliedDiscounts.value.size
        val orderDiscountAmount = currentState.order.discountAmount
        val hasDiscounts = appliedDiscountsCount > 0 || orderDiscountAmount > BigDecimal.ZERO

        Timber.d("🔍 [Sync] Checking discounts | _appliedDiscounts.size=$appliedDiscountsCount | order.discountAmount=$orderDiscountAmount | hasDiscounts=$hasDiscounts")

        if (!hasDiscounts) {
            Timber.d("⏭️ [Sync] No discounts applied - skipping backend refresh")
            return
        }

        viewModelScope.launch {
            try {
                val venueId = deviceInfoManager.getVenueId() ?: return@launch

                Timber.d("🔄 [Sync] Refreshing order to get recalculated discounts | orderId=$orderId")

                // Fetch fresh order from backend (includes recalculated discounts)
                val backendOrder = orderRepository.getOrder(
                    venueId = venueId,
                    orderId = orderId
                ).getOrElse { error ->
                    Timber.w(error, "⚠️ Failed to refresh order after sync - using local data")
                    return@launch
                }

                // Cache to local DB
                orderSyncCoordinator.cacheBackendOrder(backendOrder)

                // Load merged order (preserves local-only fields like sentToKitchenAt)
                val mergedOrder = orderSyncCoordinator.getLocalOrder(orderId) ?: backendOrder

                // ⭐ FIX: Only update _appliedDiscounts if backend has discount data
                // Backend may return empty discounts[] even when discountAmount > 0
                // Don't overwrite local _appliedDiscounts with empty list
                if (backendOrder.discounts.isNotEmpty()) {
                    _appliedDiscounts.value = backendOrder.discounts
                    Timber.d("🔄 [refreshOrderAfterSync] Updated _appliedDiscounts from backend (${backendOrder.discounts.size})")
                }

                // Update state
                val latestState = _state.value
                if (latestState is MenuState.Success) {
                    _state.value = latestState.copy(order = mergedOrder)
                    Timber.i("✅ [Sync] Order refreshed from backend | discountAmount=${mergedOrder.discountAmount} | discounts=${backendOrder.discounts.size}")
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error refreshing order after sync")
            }
        }
    }

    // ============================================================================
    // 💳 Payment Preparation (Force Sync Before Payment)
    // ============================================================================

    /**
     * Prepare order for payment by forcing sync and fetching recalculated totals.
     *
     * ⚠️ CRITICAL: Must be called before navigating to PaymentScreen when order may have
     * pending changes or percentage discounts. Backend recalculates percentage discounts
     * when items change - we need the authoritative amount.
     *
     * **Flow:**
     * 1. Force sync any pending local changes (bypass 5-second debounce)
     * 2. Fetch order from backend (gets recalculated discountAmount)
     * 3. Update local state with correct totals
     * 4. Return the prepared order
     *
     * **Why This is Necessary:**
     * - User applies 10% coupon on $149 → discount = $14.9
     * - User adds another $149 item → subtotal = $298
     * - LOCAL shows: total = $283.1 (old discount $14.9) ❌
     * - BACKEND calculates: total = $268.2 (recalculated $29.8) ✅
     * - If user clicks "Pagar" before 2s sync → CHARGES WRONG AMOUNT!
     *
     * @return Result with prepared Order (correct totals) or error
     */
    private suspend fun prepareForPayment(): Result<Order> {
        val currentState = _state.value
        if (currentState !is MenuState.Success) {
            return Result.failure(Exception("No order loaded"))
        }

        val order = currentState.order
        // 💳 Capture orderCustomers at start to avoid race conditions
        // Defensive pattern: ensures consistency even if _orderCustomers is updated during payment prep
        val customersSnapshot = _orderCustomers.value

        return try {
            // Step 1: Force sync pending changes (bypass 2s debounce)
            val syncedOrderId = orderSyncCoordinator.syncOrderImmediately(order.id)
            Timber.d("⚡ [Payment Prep] Order synced | id=$syncedOrderId")

            // Step 2: Fetch order from backend with recalculated discounts
            val venueId = deviceInfoManager.getVenueId()
                ?: return Result.failure(Exception("Venue not configured"))

            val backendOrder = orderRepository.getOrder(venueId, syncedOrderId).getOrElse { error ->
                Timber.e(error, "❌ [Payment Prep] Failed to fetch order")
                return Result.failure(error)
            }

            // Step 3: Cache and update local state
            orderSyncCoordinator.cacheBackendOrder(backendOrder)
            // 💳 FIX: Merge orderCustomers from StateFlow with backend order
            // API doesn't return orderCustomers, but we already loaded them in _orderCustomers
            val mergedOrder = backendOrder.copy(orderCustomers = customersSnapshot)

            Timber.d("💳 [Payment Prep] Merged customers | backend=${backendOrder.orderCustomers.size} | snapshot=${customersSnapshot.size} | final=${mergedOrder.orderCustomers.size}")

            // Step 4: Update UI state with correct amounts
            // ⭐ FIX: Only update _appliedDiscounts if backend has discount data
            // Backend may return empty discounts[] even when discountAmount > 0
            // Don't overwrite local _appliedDiscounts with empty list
            if (backendOrder.discounts.isNotEmpty()) {
                _appliedDiscounts.value = backendOrder.discounts
                Timber.d("🔄 [prepareOrderForPayment] Updated _appliedDiscounts from backend (${backendOrder.discounts.size})")
            }
            val latestState = _state.value
            if (latestState is MenuState.Success) {
                _state.value = latestState.copy(order = mergedOrder)
            }

            Timber.i("✅ [Payment Prep] Order ready | total=${mergedOrder.total} | discount=${mergedOrder.discountAmount}")
            Result.success(mergedOrder)

        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Prep] Failed")
            Result.failure(e)
        }
    }

    /**
     * Public function to prepare order for payment with loading state.
     *
     * Called when user clicks "Pagar" button. Forces sync, fetches recalculated
     * totals from backend, then calls onReady with the prepared order.
     *
     * **UX:**
     * - Shows "Preparando pago..." overlay (~400ms)
     * - Prevents navigation to PaymentScreen with wrong amounts
     * - Handles errors gracefully
     *
     * @param onReady Called with the prepared Order when ready to navigate
     * @param onError Called with error message if preparation fails
     */
    fun onPaymentRequested(onReady: (Order) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _isPreparingPayment.value = true
            try {
                prepareForPayment().fold(
                    onSuccess = { order ->
                        _isPreparingPayment.value = false
                        onReady(order)
                    },
                    onFailure = { error ->
                        _isPreparingPayment.value = false
                        onError(error.message ?: "Error preparando pago")
                    }
                )
            } catch (e: Exception) {
                _isPreparingPayment.value = false
                onError(e.message ?: "Error preparando pago")
            }
        }
    }

    /**
     * 🔄 [SSOT] Collect order changes from Room DB - Single Source of Truth Pattern.
     *
     * **This is the core of the Flow migration!**
     *
     * When `_currentOrderId` changes:
     * 1. flatMapLatest switches to observe the new orderId
     * 2. Room DB emits Order whenever it changes (sync, add item, remove item, etc.)
     * 3. _state is updated automatically with fresh data
     *
     * **Benefits:**
     * - Eliminates manual _state.value updates scattered across the codebase
     * - Room is the Single Source of Truth - no dual state issues
     * - UI always has fresh data (no stale order IDs after sync)
     * - Works with ID replacement (local_xxx → CUID) automatically
     *
     * **How ID replacement works:**
     * 1. Order created with local_xxx ID → _currentOrderId = "local_xxx"
     * 2. Sync completes → Room DB updates ID to CUID
     * 3. Flow emits null (old ID not found)
     * 4. collectSyncEvents() updates _currentOrderId to CUID
     * 5. Flow switches to observe CUID → emits fresh order
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun collectOrderFromRoom() {
        viewModelScope.launch {
            _currentOrderId
                .filterNotNull()
                .flatMapLatest { orderId ->
                    Timber.d("🔄 [SSOT] Observing order from Room | orderId=$orderId")
                    orderSyncCoordinator.observeOrder(orderId)
                }
                .distinctUntilChanged()
                .collect { order ->
                    Timber.d("🔄 [SSOT] Room emitted order update | id=${order.id} | items=${order.items.size}")

                    // Update _state with fresh order from Room
                    val currentState = _state.value
                    when (currentState) {
                        is MenuState.Success -> {
                            // Preserve other state fields, only update order
                            updateStateWithOrder(order)  // 🔄 Syncs _appliedDiscounts
                            Timber.i("✅ [SSOT] State updated from Room | orderId=${order.id}")
                        }
                        is MenuState.Loading -> {
                            // First load - transition to Success
                            updateStateWithOrder(order)  // 🔄 Syncs _appliedDiscounts
                            Timber.i("✅ [SSOT] Initial order loaded from Room | orderId=${order.id}")
                        }
                        else -> {
                            // Error state - stay in error (user should manually retry)
                            Timber.w("⚠️ [SSOT] Room emitted but state is ${currentState::class.simpleName}, ignoring")
                        }
                    }
                }
        }
    }

    /**
     * Collect sync events from OrderSyncCoordinator
     *
     * **Local-First Architecture Pattern:**
     * Listens to sync status changes and updates UI accordingly.
     * - Syncing → Show sync indicator
     * - Synced → Hide sync indicator, reload order from DB
     * - Error → Show error message
     * - Conflict → Show conflict resolution dialog
     */
    private fun collectSyncEvents() {
        viewModelScope.launch {
            orderSyncCoordinator.syncEvents.collect { event ->
                when (event) {
                    is com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator.SyncEvent.Syncing -> {
                        Timber.d("🔄 [Sync] Order syncing: ${event.orderId}")
                        _isSyncing.value = true
                    }
                    is com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator.SyncEvent.Synced -> {
                        val receivedAt = System.currentTimeMillis()
                        Timber.i("✅ [Sync] Order synced successfully | id=${event.orderId} | version=${event.version}")
                        Timber.i("📬 [VM-DEBUG] SyncEvent.Synced RECEIVED | serverId=${event.orderId} | timestamp=$receivedAt")
                        _isSyncing.value = false

                        // ⭐ [SSOT] Update _currentOrderId when ID changes (local_xxx → CUID)
                        // This triggers collectOrderFromRoom() to switch to the new ID
                        val previousId = _currentOrderId.value
                        if (previousId != event.orderId) {
                            Timber.i("🔄 [SSOT] Order ID changed after sync | old=$previousId → new=${event.orderId}")
                            _currentOrderId.value = event.orderId
                            // Flow will automatically emit the new order, updating _state
                        }

                        // ✅ [SSOT Phase 4] Legacy manual reload REMOVED
                        // collectOrderFromRoom() now handles _state updates automatically via Room Flow
                        // When _currentOrderId changes → Flow switches → Room emits → _state updates

                        // ⭐ FIX: Refresh order from backend to get recalculated discounts
                        // Backend recalculates percentage discounts when items change
                        // Only fetches if order has discounts (optimization)
                        refreshOrderAfterSync(event.orderId)
                    }
                    is com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator.SyncEvent.Error -> {
                        Timber.e("❌ [Sync] Sync error | order=${event.orderId} | error=${event.message}")
                        _isSyncing.value = false

                        // ✅ P0 FIX: Don't replace Success state with Error (user loses order!)
                        // Instead, preserve the order and show error via syncError field
                        val currentState = _state.value
                        if (currentState is MenuState.Success) {
                            val errorMessage = "Error sincronizando: ${event.message}\n" +
                                "Los cambios se guardarán cuando la conexión se restablezca."
                            _state.value = currentState.copy(syncError = errorMessage)
                            Timber.d("🔄 [Sync] Preserved Success state with syncError banner")
                        } else {
                            // Only set full Error state if we're not in Success (e.g., still Loading)
                            _state.value = MenuState.Error(
                                "Error sincronizando orden.\n\n" +
                                "Los cambios se guardarán cuando la conexión se restablezca.\n\n" +
                                "${event.message}"
                            )
                        }
                    }
                    is com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator.SyncEvent.Conflict -> {
                        Timber.w("⚠️ [Sync] Conflict detected | order=${event.orderId}")
                        _isSyncing.value = false

                        // ✅ P0 FIX: Don't replace Success state with Error (user loses order!)
                        // Preserve the order and show conflict via syncError field
                        val currentState = _state.value
                        if (currentState is MenuState.Success) {
                            val conflictMessage = "Conflicto de versión detectado.\n" +
                                "Esta orden fue modificada por otra terminal.\n" +
                                "Por favor, recarga la orden para ver los últimos cambios."
                            _state.value = currentState.copy(syncError = conflictMessage)
                            Timber.d("🔄 [Sync] Preserved Success state with conflict banner")
                        } else {
                            // Only set full Error state if we're not in Success
                            _state.value = MenuState.Error(
                                "Conflicto de versión detectado.\n\n" +
                                "Esta orden fue modificada por otra terminal.\n\n" +
                                "Por favor, recarga la orden para ver los últimos cambios."
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Load products using cache-first strategy (Toast POS pattern).
     *
     * **Performance Optimization (2025-11-24):**
     * - Cache-first: Emit cached products immediately (~10ms DB read)
     * - Background refresh: Fetch fresh data from API and update cache
     * - No loading overlay: Products appear instantly from cache
     *
     * **Cache TTL:** 24 hours (auto-expires stale data)
     *
     * **Flow:**
     * ```
     * 1. Read from cache (~10ms)        → Emit immediately (instant UI)
     * 2. Fetch from API (500-1000ms)    → Update cache (background)
     * 3. Emit fresh data                → UI updates with latest data
     * ```
     *
     * **Result:** First paint goes from 500-1000ms → ~10ms (50-100x faster!)
     */
    private fun loadProducts() {
        viewModelScope.launch {
            loadProductsInternal()
        }
    }

    /**
     * Refresh products from backend (public API for MenuScreen lifecycle)
     *
     * Called when:
     * - Screen is resumed after navigation (e.g., navigating between screens)
     * - User manually triggers refresh (pull-to-refresh)
     *
     * This ensures inventory is always up-to-date when screen becomes visible.
     */
    fun refreshProducts() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                loadProductsInternal()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Internal product loading logic (shared by init and refresh)
     */
    private suspend fun loadProductsInternal() {
        try {
            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ Cannot load products: venueId is null")
                return
            }

            Timber.d("📦 [Cache-First] Loading products for venue: $venueId")

            // ⚡ STEP 1: Emit cached products immediately (~10ms)
            withContext(Dispatchers.IO) {
                val cachedProducts = productDao.getAllProducts(venueId).toDomain()
                val cachedCategories = productCategoryDao.getAllCategories(venueId).toCategoryDomain()

                if (cachedProducts.isNotEmpty()) {
                    _products.value = cachedProducts
                    _categories.value = listOf(com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory.ALL) + cachedCategories
                    Timber.i("⚡ [Cache Hit] Loaded ${cachedProducts.size} products from cache (~10ms)")

                    // 🔍 DEBUG: Log inventory for each product
                    Timber.i("═══════════════════════════════════════════════════════════")
                    Timber.i("📦 CACHED PRODUCT INVENTORY (${cachedProducts.size} products)")
                    cachedProducts.forEach { product ->
                        if (product.trackInventory && product.availableQuantity != null) {
                            Timber.i("   - ${product.name}: availableQty=${product.availableQuantity} | method=${product.inventoryMethod}")
                        }
                    }
                    Timber.i("═══════════════════════════════════════════════════════════")
                } else {
                    Timber.d("💾 [Cache Miss] No cached products found, fetching from backend...")
                    _isLoadingProducts.value = true
                }
            }

            // ⚡ STEP 2: Fetch fresh data from backend (background refresh)
            val cachedAt = System.currentTimeMillis()

            // Fetch products from backend
            productRepository.getProducts(venueId).fold(
                onSuccess = { freshProducts ->
                    withContext(Dispatchers.IO) {
                        productDao.upsertProducts(freshProducts.toEntities(venueId, cachedAt))
                    }
                    _products.value = freshProducts
                    Timber.i("✅ [Backend] Loaded ${freshProducts.size} products and updated cache")

                    // 🔍 DEBUG: Log inventory for each product from backend
                    Timber.i("═══════════════════════════════════════════════════════════")
                    Timber.i("📦 FRESH PRODUCT INVENTORY FROM BACKEND (${freshProducts.size} products)")
                    freshProducts.forEach { product ->
                        if (product.trackInventory && product.availableQuantity != null) {
                            Timber.i("   - ${product.name}: availableQty=${product.availableQuantity} | method=${product.inventoryMethod}")
                        }
                    }
                    Timber.i("═══════════════════════════════════════════════════════════")
                },
                onFailure = { error ->
                    Timber.e(error, "❌ [Backend] Failed to load products (using cached data)")
                }
            )

            // Fetch categories from backend
            productRepository.getCategories(venueId).fold(
                onSuccess = { freshCategories ->
                    withContext(Dispatchers.IO) {
                        productCategoryDao.upsertCategories(freshCategories.toCategoryEntities(venueId, cachedAt))
                    }
                    _categories.value = listOf(com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory.ALL) + freshCategories
                    Timber.i("✅ [Backend] Loaded ${freshCategories.size} categories and updated cache")
                },
                onFailure = { error ->
                    Timber.e(error, "❌ [Backend] Failed to load categories (using cached data)")
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading products")
        } finally {
            _isLoadingProducts.value = false
        }
    }

    /**
     * Set search query for product filtering (Issue #4)
     *
     * **Toast POS pattern**: Local filtering for instant results.
     *
     * @param query Search text (filters by product name, description, or SKU)
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        Timber.d("🔍 [Search] Query updated: '$query'")
    }

    /**
     * Clear search query
     */
    fun clearSearch() {
        _searchQuery.value = ""
        Timber.d("🔍 [Search] Query cleared")
    }

    /**
     * Clear sync error banner from UI.
     *
     * Called when user dismisses the sync error/conflict notification.
     * Preserves order data, only clears the error message.
     */
    fun clearSyncError() {
        val currentState = _state.value
        if (currentState is MenuState.Success && currentState.syncError != null) {
            _state.value = currentState.copy(syncError = null)
            Timber.d("🔄 [Sync] Error banner dismissed by user")
        }
    }

    /**
     * Load order for a table or create quick order (LOCAL-FIRST)
     *
     * ⚠️ LOCAL-FIRST TRANSFORMATION ⚠️
     *
     * **Toast/Square Pattern**: Each order = fresh ViewModel instance
     * No need to check if order is paid - navigation ensures fresh state
     *
     * Supports three modes:
     * 1. CREATE_QUICK_ORDER: Creates new TAKEOUT order LOCALLY (instant, 0ms)
     * 2. CREATE_TABLE_ORDER:tableId: Creates table order (keeps immediate backend for table status)
     * 3. Existing orderId: Loads from Room DB first, fallback to backend if not found
     *
     * Performance improvements:
     * - CREATE_QUICK_ORDER: 300ms+ → 0ms (instant local creation)
     * - Existing orders: 0ms if cached in Room DB, 300ms if backend fallback needed
     * - Offline support: Orders created locally can be used offline, sync later
     *
     * @param orderId "CREATE_QUICK_ORDER", "CREATE_TABLE_ORDER:tableId", or existing CUID
     */
    fun loadOrder(orderId: String) {
        Timber.d("📖 [VM-DEBUG] loadOrder() START | orderId=$orderId | timestamp=${System.currentTimeMillis()}")
        viewModelScope.launch {
            try {
                // 🔄 Toast/Square Pattern: Don't reload if order already loaded
                // Each MenuScreen instance = one order (no state reuse)
                val currentState = _state.value
                if (currentState is MenuState.Success) {
                    // ⚠️ P0 FIX: If trying to create a NEW quick order, check if current order is PAID
                    // If it's paid, we need to create a new order (don't reuse paid orders)
                    if (orderId == "CREATE_QUICK_ORDER" && currentState.order.paymentStatus == PaymentStatus.PAID) {
                        Timber.w("⚠️ Current order is PAID, creating NEW order instead of reusing | currentId=${currentState.order.id}")
                        // Continue to create new order (don't return early)
                    } else {
                        Timber.d("📋 Order already loaded: ${currentState.order.id} - Skipping loadOrder()")
                        return@launch
                    }
                }

                _state.value = MenuState.Loading

                val venueId = deviceInfoManager.getVenueId()
                val waiterId = secureStorage.getStaffId()

                if (venueId == null) {
                    Timber.e("❌ Cannot load order: venueId is null")
                    _state.value = MenuState.Error("Error: No se encontró el ID del local")
                    return@launch
                }

                val order = when {
                    // ✅ LOCAL-FIRST: Create new quick order locally (INSTANT - 0ms)
                    orderId == "CREATE_QUICK_ORDER" -> {
                        Timber.d("🆕 [Local-First] Creating quick order locally (TAKEOUT)")

                        // STEP 1: Create order in Room DB (instant)
                        val localOrderId = orderSyncCoordinator.createLocalOrder(
                            venueId = venueId,
                            tableId = null,  // No table for quick orders
                            covers = 1,  // Quick order = 1 person
                            waiterId = waiterId,
                            orderType = OrderType.TAKEOUT
                        )

                        // STEP 2: Load from Room DB and convert to domain model
                        val localOrder = orderSyncCoordinator.getLocalOrder(localOrderId)
                        if (localOrder == null) {
                            Timber.e("❌ Failed to load locally created order: $localOrderId")
                            _state.value = MenuState.Error("Error creando orden localmente")
                            return@launch
                        }

                        // STEP 3: Schedule debounced sync (2 seconds)
                        orderSyncCoordinator.scheduleSync(localOrderId)
                        Timber.i("✅ [Local-First] Quick order created instantly | id=$localOrderId | syncScheduled=2s")

                        localOrder
                    }

                    // Create new table order (format: "CREATE_TABLE_ORDER:tableId")
                    orderId.startsWith("CREATE_TABLE_ORDER:") -> {
                        val tableId = orderId.removePrefix("CREATE_TABLE_ORDER:")
                        Timber.d("🆕 Creating new table order for table: $tableId")

                        if (waiterId == null) {
                            Timber.e("❌ Cannot assign table: waiterId is null")
                            _state.value = MenuState.Error("Error: No se encontró el ID del mesero")
                            return@launch
                        }

                        // 🪑 Use assignTable() instead of createOrder()
                        // This updates table status to OCCUPIED and emits Socket.IO event
                        val assignResult = when (val result = tableRepository.assignTable(
                            venueId = venueId,
                            tableId = tableId,
                            staffId = waiterId,
                            covers = 2  // Default 2 people, can be updated later
                        )) {
                            is com.jaac.avoqado_tpv.core.domain.models.Result.Success -> result.data
                            is com.jaac.avoqado_tpv.core.domain.models.Result.Error -> {
                                Timber.e(result.exception, "❌ Failed to assign table")
                                _state.value = MenuState.Error("Error asignando mesa: ${result.exception.userMessage}")
                                return@launch
                            }
                        }

                        Timber.i("✅ Table assigned | orderId=${assignResult.orderId} | orderNumber=${assignResult.orderNumber} | isNew=${assignResult.isNewOrder}")

                        // Fetch the created order to get full order object
                        val backendOrder = orderRepository.getOrder(
                            venueId = venueId,
                            orderId = assignResult.orderId
                        ).getOrElse { error ->
                            Timber.e(error, "❌ Failed to load order after assignment")
                            _state.value = MenuState.Error("Error cargando orden: ${error.message}")
                            return@launch
                        }

                        // ✅ FIX: Cache to local DB to prevent FOREIGN KEY errors when adding items
                        // (Same fix as quick orders - ensures DraftOrder exists in Room before adding items)
                        orderSyncCoordinator.cacheBackendOrder(backendOrder)
                        Timber.d("💾 [Cache] Table order cached to local DB | id=${assignResult.orderId}")

                        // ⭐ FIX: Load from local DB to get preserved sentToKitchenAt timestamps
                        // Backend doesn't have this field, so we must use local DB which preserves it
                        orderSyncCoordinator.getLocalOrder(assignResult.orderId) ?: backendOrder
                    }

                    // ✅ LOCAL-FIRST: Load existing order from Room DB first, fallback to backend
                    else -> {
                        Timber.d("📋 [Local-First] Loading existing order: $orderId")

                        // STEP 1: Try loading from Room DB first (0ms if cached)
                        val localOrder = orderSyncCoordinator.getLocalOrder(orderId)

                        if (localOrder != null) {
                            // Found in local DB - instant load!
                            Timber.i("✅ [Local-First] Order loaded from local DB | id=$orderId | items=${localOrder.items.size}")
                            localOrder
                        } else {
                            // ⚠️ DEFENSIVE CHECK: Detect local-only IDs that can't be fetched from backend
                            // These IDs were used by mock data or are local Quick Orders that never synced
                            // Regex check removed (Issue #5): was too restrictive for CUIDs
                            val isLocalOnlyId = orderId.startsWith("local_") ||
                                orderId.startsWith("order_table") ||
                                orderId.startsWith("order_quick")

                            if (isLocalOnlyId) {
                                Timber.w("⚠️ [Defensive] Local-only ID cannot be fetched from backend: $orderId")
                                _state.value = MenuState.Error(
                                    "Esta orden no está disponible.\n\n" +
                                    "Puede que haya expirado o no se haya sincronizado correctamente.\n\n" +
                                    "Por favor crea una nueva orden."
                                )
                                return@launch
                            }

                            // STEP 2: Not in local DB - fallback to backend (300ms+)
                            Timber.d("⚠️ [Fallback] Order not in local DB, fetching from backend: $orderId")

                            val backendOrder = orderRepository.getOrder(
                                venueId = venueId,
                                orderId = orderId
                            ).getOrElse { error ->
                                Timber.e(error, "❌ Failed to load order from backend")
                                _state.value = MenuState.Error("Error cargando orden: ${error.message}")
                                return@launch
                            }

                            // Cache to local DB to prevent FOREIGN KEY errors when adding items
                            orderSyncCoordinator.cacheBackendOrder(backendOrder)
                            Timber.d("💾 [Cache] Backend order cached to local DB | id=$orderId")

                            // ⭐ FIX: Load from local DB to get preserved sentToKitchenAt timestamps
                            orderSyncCoordinator.getLocalOrder(orderId) ?: backendOrder
                        }
                    }
                }

                // ⭐ [SSOT] Set current order ID for Flow observation
                // This triggers collectOrderFromRoom() to start observing this order
                _currentOrderId.value = order.id
                Timber.d("🔄 [SSOT] _currentOrderId set to ${order.id}")

                _state.value = MenuState.Success(order)

                // 🎟️ Sync applied discounts from order
                _appliedDiscounts.value = order.discounts
                if (order.discounts.isNotEmpty()) {
                    Timber.i("🎟️ Synced ${order.discounts.size} applied discounts from order")
                }

                // 👥 Load order customers (multi-customer support)
                loadOrderCustomers(order.id)

                // 🔍 DEBUG: Detailed order state on load
                Timber.i("═══════════════════════════════════════════════════════════")
                Timber.i("✅ ORDER LOADED SUCCESSFULLY")
                Timber.i("   orderId: ${order.id}")
                Timber.i("   orderNumber: ${order.orderNumber}")
                Timber.i("   orderType: ${order.orderType}")
                Timber.i("   status: ${order.status}")
                Timber.i("   paymentStatus: ${order.paymentStatus}")
                Timber.i("   ─────────────────────────────────────────────────────")
                Timber.i("   💰 AMOUNTS ON LOAD:")
                Timber.i("      subtotal: ${order.subtotal}")
                Timber.i("      total: ${order.total}")
                Timber.i("      paidAmount: ${order.paidAmount}")
                Timber.i("      remainingBalance: ${order.remainingBalance}")
                Timber.i("   ─────────────────────────────────────────────────────")
                Timber.i("   🔀 SPLIT PAYMENT STATE:")
                Timber.i("      lastSplitType: ${order.lastSplitType}")
                Timber.i("      hasRemainingBalance: ${order.hasRemainingBalance}")
                Timber.i("   ─────────────────────────────────────────────────────")
                Timber.i("   📦 ITEMS (${order.items.size}):")
                order.items.forEach { item ->
                    Timber.i("      - ${item.productName} x${item.quantity} = ${item.totalPrice} | kitchenStatus=${item.kitchenStatus}")
                }
                Timber.i("═══════════════════════════════════════════════════════════")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error in loadOrder")
                _state.value = MenuState.Error("Error: ${e.message}")
            }
        }
    }

    /**
     * Add item to order (LOCAL-FIRST)
     *
     * **🆕 Local-First Flow (Toast POS Pattern):**
     * 1. Add to Room DB via OrderSyncCoordinator → INSTANT UI (0ms)
     * 2. Update state with new item (optimistic)
     * 3. Schedule debounced sync (2s delay)
     * 4. (Eventually) Sync to backend automatically
     *
     * **Benefits:**
     * - 0ms UI latency (vs 300ms+ with immediate backend call)
     * - Works offline (syncs when connection restored)
     * - Batches rapid changes (multiple items = 1 API call)
     * - Reduces server load by 80%
     *
     * **Comparison:**
     * - OLD: Add → API call (300ms) → UI update
     * - NEW: Add → Room DB (0ms) → UI update → Schedule sync (2s) → Backend
     */
    fun addItem(
        product: Product,
        quantity: Int,
        modifiers: List<ProductModifier>,
        notes: String
    ) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) return@launch

            // Check if order can accept items
            val order = currentState.order
            Timber.d("➕ [VM-DEBUG] addItem() START | stateOrderId=${order.id} | product=${product.name} | timestamp=${System.currentTimeMillis()}")
            if (!order.canAddItems) {
                Timber.w("⚠️ Cannot add items to order in status: ${order.status}")
                _state.value = MenuState.Error(
                    "No se pueden agregar items a esta orden.\n\n" +
                    "Estado de la orden: ${order.status.displayName}"
                )
                return@launch
            }

            try {
                // ✅ STEP 1: Calculate price
                val modifiersTotal = modifiers.sumOf { it.priceAdjustment }
                val unitPrice = product.price + modifiersTotal
                val totalPrice = unitPrice * BigDecimal(quantity.toString())

                Timber.d("🛒 [Local-First] Adding item to Room DB: ${product.name} x$quantity")
                Timber.d("➕ [VM-DEBUG] addItem() CALLING addItemToLocalOrder | orderId=${order.id} | timestamp=${System.currentTimeMillis()}")

                // ✅ STEP 2: Add to Room DB (INSTANT - 0ms latency)
                val localItemId = orderSyncCoordinator.addItemToLocalOrder(
                    orderId = order.id,
                    productId = product.id,
                    productName = product.name,
                    quantity = quantity,
                    unitPrice = unitPrice.toString(),
                    modifiers = modifiers,
                    notes = notes.ifBlank { null }
                )

                // ✅ STEP 3: Update UI state (optimistic update)
                val newItem = OrderItem(
                    id = localItemId,
                    orderId = order.id,
                    productId = product.id,
                    productName = product.name,
                    productSku = product.sku,
                    quantity = quantity,
                    unitPrice = unitPrice,
                    totalPrice = totalPrice,
                    modifiers = modifiers,
                    notes = notes.ifBlank { null },
                    kitchenStatus = KitchenStatus.PENDING,
                    createdAt = Instant.now(),
                    sentToKitchenAt = null
                )

                val updatedOrder = recalculateOrder(order.copy(items = order.items + newItem))
                updateStateWithOrder(updatedOrder)  // 🔄 Syncs _appliedDiscounts with order.discounts

                // 🔍 DEBUG: Item added details
                Timber.i("═══════════════════════════════════════════════════════════")
                Timber.i("✅ ITEM ADDED TO ORDER")
                Timber.i("   localItemId: $localItemId")
                Timber.i("   productId: ${product.id}")
                Timber.i("   productName: ${product.name}")
                Timber.i("   quantity: $quantity")
                Timber.i("   unitPrice: $unitPrice")
                Timber.i("   totalPrice: $totalPrice")
                Timber.i("   modifiers: ${modifiers.size}")
                Timber.i("   notes: ${notes.ifBlank { "none" }}")
                Timber.i("   ─────────────────────────────────────────────────────")
                Timber.i("   📊 ORDER TOTALS AFTER ADD:")
                Timber.i("      items count: ${updatedOrder.items.size}")
                Timber.i("      subtotal: ${updatedOrder.subtotal}")
                Timber.i("      total: ${updatedOrder.total}")
                Timber.i("═══════════════════════════════════════════════════════════")

                // ✅ STEP 4: Schedule debounced sync (2 seconds)
                orderSyncCoordinator.scheduleSync(order.id)
                Timber.d("⏱️ [Sync] Scheduled debounced sync for order: ${order.id}")

                // 🎯 Result: User sees item added INSTANTLY (0ms)
                // Backend sync happens automatically 2 seconds later (or sooner if user sends to kitchen)

            } catch (e: Exception) {
                Timber.e(e, "❌ Error in addItem (local-first)")
                _state.value = MenuState.Error("Error agregando producto: ${e.message}")
            }
        }
    }

    /**
     * Update item quantity (LOCAL-FIRST with database persistence)
     *
     * **🆕 Local-First Flow:**
     * 1. Update quantity in Room DB → Marks as PENDING
     * 2. Update UI state (optimistic)
     * 3. Recalculate totals
     * 4. Schedule debounced sync (2s delay)
     * 5. (Eventually) Sync to backend
     *
     * **Key Fix (Issue #XXX):**
     * - Previously ONLY updated UI state, never persisted to database
     * - Caused items to disappear after sync (stale database data)
     * - Now persists to database FIRST, then updates UI
     */
    fun updateItemQuantity(item: OrderItem, newQuantity: Int) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) return@launch

            try {
                val order = currentState.order
                Timber.d("📝 [VM-DEBUG] updateItemQuantity() | stateOrderId=${order.id} | itemId=${item.id} | newQty=$newQuantity | timestamp=${System.currentTimeMillis()}")

                // ✅ FIX: Persist quantity update to database (marks as PENDING for sync)
                orderSyncCoordinator.updateItemQuantityInLocalOrder(
                    orderId = order.id,
                    itemId = item.id,
                    newQuantity = newQuantity,
                    unitPrice = item.unitPrice
                )

                // Update UI state (optimistic)
                val updatedItems = if (newQuantity <= 0) {
                    // Remove item if quantity is 0
                    order.items.filter { it.id != item.id }
                } else {
                    // Update quantity
                    order.items.map {
                        if (it.id == item.id) {
                            it.copy(
                                quantity = newQuantity,
                                totalPrice = it.unitPrice * BigDecimal(newQuantity.toString())
                            )
                        } else {
                            it
                        }
                    }
                }

                val updatedOrder = recalculateOrder(order.copy(items = updatedItems))
                updateStateWithOrder(updatedOrder)  // 🔄 Syncs _appliedDiscounts with order.discounts
                Timber.d("✏️ Item quantity updated: ${item.productName} → $newQuantity")

                // ✅ FIX: Schedule sync to send updated quantity to backend
                orderSyncCoordinator.scheduleSync(order.id)
            } catch (e: Exception) {
                Timber.e(e, "❌ Error updating item quantity")
            }
        }
    }

    /**
     * Remove item from order (LOCAL-FIRST with SOFT DELETE)
     *
     * **🆕 Local-First Flow with Soft Delete:**
     * 1. Mark as DELETED in Room DB → INSTANT UI (0ms)
     * 2. Remove from UI state (optimistic)
     * 3. Schedule debounced sync (2s delay)
     * 4. (Eventually) Sync deletion to backend
     * 5. Hard delete from DB after server confirms
     *
     * **Soft Delete Pattern Benefits:**
     * - Can rollback if sync fails
     * - Track what needs to be deleted on server
     * - Audit trail of deletions
     * - 0ms UI latency
     *
     * @param item Order item to remove
     */
    fun removeItem(item: OrderItem) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) return@launch

            try {
                val order = currentState.order
                Timber.d("🗑️ [VM-DEBUG] removeItem() | stateOrderId=${order.id} | itemId=${item.id} | product=${item.productName} | timestamp=${System.currentTimeMillis()}")

                Timber.d("🗑️ [Local-First] Soft deleting item from Room DB: ${item.productName}")

                // ✅ STEP 1: Soft delete in Room DB (mark as DELETED)
                orderSyncCoordinator.removeItemFromLocalOrder(
                    orderId = order.id,
                    itemId = item.id
                )

                // ✅ STEP 2: Update UI state (remove from list)
                val updatedItems = order.items.filter { it.id != item.id }
                val updatedOrder = recalculateOrder(order.copy(items = updatedItems))
                updateStateWithOrder(updatedOrder)  // 🔄 Syncs _appliedDiscounts with order.discounts

                Timber.i("✅ [Local-First] Item removed from UI instantly | id=${item.id}")

                // ✅ STEP 3: Schedule debounced sync (2 seconds)
                orderSyncCoordinator.scheduleSync(order.id)
                Timber.d("⏱️ [Sync] Scheduled debounced sync for order: ${order.id}")

                // 🎯 Result: User sees item removed INSTANTLY (0ms)
                // Backend sync happens automatically 2 seconds later
                // If sync fails, item can be restored from DELETED status

            } catch (e: Exception) {
                Timber.e(e, "❌ Error in removeItem (local-first)")
                _state.value = MenuState.Error("Error eliminando producto: ${e.message}")
            }
        }
    }

    /**
     * Send order to kitchen (CRITICAL - IMMEDIATE SYNC)
     *
     * ⚠️ LOCAL-FIRST TRANSFORMATION ⚠️
     *
     * Flow:
     * 1. Force immediate sync (bypass 2s debounce) - kitchen cannot wait
     * 2. Update kitchen status on backend
     * 3. Update UI state
     *
     * Why immediate sync?
     * - Kitchen needs to start cooking NOW
     * - Cannot wait 2 seconds for debounced sync
     * - Order must exist on backend before changing kitchen status
     *
     * Performance:
     * - Old: Local update only (no backend sync) - inconsistent state
     * - New: Force sync → Backend update → Consistent across all terminals
     */
    fun sendToKitchen() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot send to kitchen - no order loaded")
                return@launch
            }

            try {
                val order = currentState.order

                if (!order.canSendToKitchen) {
                    Timber.w("⚠️ Cannot send order to kitchen: ${order.id}")
                    _state.value = MenuState.Error("No se puede enviar a cocina: orden vacía o ya enviada")
                    return@launch
                }

                val venueId = deviceInfoManager.getVenueId()
                if (venueId == null) {
                    Timber.e("❌ No venue ID found")
                    _state.value = MenuState.Error("Error: No se encontró el ID del local")
                    return@launch
                }

                // 🖨️ STEP 0: Print ONLY pending items (incremental printing - Toast/Square pattern)
                val pendingItems = order.pendingKitchenItems
                val now = Instant.now()

                if (pendingItems.isNotEmpty()) {
                    Timber.d("🖨️ [SendToKitchen] Printing ${pendingItems.size} pending items (incremental)")
                    try {
                        printerManager.printKitchenTicket(
                            orderNumber = order.orderNumber,
                            tableName = order.tableName,
                            orderItems = pendingItems,  // ← Only pending items!
                            staffName = secureStorage.getStaffName()
                        )
                        Timber.i("✅ [SendToKitchen] Kitchen ticket printed for pending items")

                        // ✅ STEP 1a: Persist sentToKitchenAt to Room DB
                        // CRITICAL: Must persist BEFORE sync event reloads from DB!
                        val pendingItemIds = pendingItems.map { it.id }
                        orderSyncCoordinator.markItemsAsSentToKitchen(
                            orderId = order.id,
                            itemIds = pendingItemIds,
                            sentAt = now.toEpochMilli()
                        )

                        // ✅ STEP 1b: Update local state to mark items as sent
                        // This makes UI show correct status immediately
                        val updatedItems = order.items.map { item ->
                            if (item.isPendingKitchen) {
                                item.copy(sentToKitchenAt = now)
                            } else {
                                item
                            }
                        }
                        val updatedOrder = order.copy(
                            items = updatedItems,
                            kitchenStatus = KitchenStatus.PREPARING
                        )
                        updateStateWithOrder(updatedOrder)  // 🔄 Syncs _appliedDiscounts
                        Timber.i("✅ [SendToKitchen] Local state updated - ${pendingItems.size} items marked as sent")

                    } catch (e: Exception) {
                        Timber.e(e, "⚠️ [SendToKitchen] Printer failed")
                        _state.value = MenuState.Error(
                            "Error imprimiendo comanda.\n\n" +
                            "Verifique que la impresora esté conectada."
                        )
                        return@launch
                    }
                } else {
                    Timber.d("📋 [SendToKitchen] No pending items to print")
                }

                // ✅ STEP 2: Force immediate sync (background - don't block UI)
                Timber.d("🍳 [SendToKitchen] Syncing order to backend...")
                try {
                    orderSyncCoordinator.syncOrderImmediately(order.id)
                    Timber.i("✅ [SendToKitchen] Order synced to backend")
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ [SendToKitchen] Backend sync failed (will retry)")
                    // Don't error out - items are already printed and UI updated
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ Error in sendToKitchen")
                _state.value = MenuState.Error(
                    "Error enviando a cocina: ${e.message}\n\n" +
                    "Verifique su conexión e intente nuevamente."
                )
            }
        }
    }

    // ============================================================================
    // Kitchen Printing
    // ============================================================================

    /**
     * Print a single item (for reprinting or incremental sending).
     * Prints ONLY this specific item and marks it as sent.
     *
     * Use case: User taps printer icon on specific item to print just that one.
     */
    fun printSingleItem(item: OrderItem) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("📋 Cannot print - no order loaded")
                return@launch
            }

            val order = currentState.order

            Timber.d("🖨️ Printing single item: ${item.productName}")

            try {
                // Print kitchen ticket with just this item
                printerManager.printKitchenTicket(
                    orderNumber = order.orderNumber,
                    tableName = order.tableName,
                    orderItems = listOf(item),  // ← Only this item!
                    staffName = secureStorage.getStaffName()
                )
                Timber.i("✅ Single item printed successfully: ${item.productName}")

                // Persist sentToKitchenAt to Room DB
                val now = Instant.now()
                orderSyncCoordinator.markItemAsSentToKitchen(
                    itemId = item.id,
                    sentAt = now.toEpochMilli()
                )

                // Update local state
                val updatedItems = order.items.map { existingItem ->
                    if (existingItem.id == item.id) {
                        existingItem.copy(sentToKitchenAt = now)
                    } else {
                        existingItem
                    }
                }
                val updatedOrder = order.copy(items = updatedItems)
                updateStateWithOrder(updatedOrder)  // 🔄 Syncs _appliedDiscounts
                Timber.i("✅ Item marked as sent: ${item.productName}")

            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to print single item: ${item.productName}")
            }
        }
    }

    /**
     * Print pending items comanda (Toast/Square pattern)
     *
     * Prints ONLY items NOT yet sent to kitchen (sentToKitchenAt == null)
     * and marks them as sent after printing.
     *
     * Use case: Header [🖨️] button in "Resumen de Orden"
     */
    fun printFullComanda() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("📋 Cannot print - no order loaded")
                return@launch
            }

            val order = currentState.order

            // 1. Filter ONLY pending items (not yet printed)
            val pendingItems = order.pendingKitchenItems

            if (pendingItems.isEmpty()) {
                Timber.d("🖨️ No pending items to print - all items already sent to kitchen")
                return@launch
            }

            val now = Instant.now()
            Timber.d("🖨️ [PrintComanda] Printing ${pendingItems.size} pending items")

            try {
                // 2. Print ONLY pending items
                printerManager.printKitchenTicket(
                    orderNumber = order.orderNumber,
                    tableName = order.tableName,
                    orderItems = pendingItems,  // ← Only pending items!
                    staffName = secureStorage.getStaffName()
                )
                Timber.i("✅ [PrintComanda] Kitchen ticket printed for ${pendingItems.size} pending items")

                // 3. Persist sentToKitchenAt to Room DB
                val pendingItemIds = pendingItems.map { it.id }
                orderSyncCoordinator.markItemsAsSentToKitchen(
                    orderId = order.id,
                    itemIds = pendingItemIds,
                    sentAt = now.toEpochMilli()
                )

                // 4. Update UI state to mark items as sent
                val updatedItems = order.items.map { item ->
                    if (item.isPendingKitchen) {
                        item.copy(sentToKitchenAt = now)
                    } else {
                        item
                    }
                }
                val updatedOrder = order.copy(items = updatedItems)
                updateStateWithOrder(updatedOrder)  // 🔄 Syncs _appliedDiscounts
                Timber.i("✅ [PrintComanda] Local state updated - ${pendingItems.size} items marked as sent")

            } catch (e: Exception) {
                Timber.e(e, "❌ [PrintComanda] Failed to print comanda")
            }
        }
    }

    // ============================================================================
    // Guest Information Management (Step 9)
    // ============================================================================

    /**
     * Update guest information
     *
     * Updates customer details based on order type:
     * - DINE_IN: covers, customerName (optional), specialRequests (allergies)
     * - TAKEOUT/DELIVERY/PICKUP: customerName (required), customerPhone (required), specialRequests
     *
     * @param covers Number of guests (DINE_IN only)
     * @param customerName Guest name
     * @param customerPhone Guest phone (TAKEOUT/DELIVERY/PICKUP only)
     * @param specialRequests Allergies, dietary restrictions, or special instructions
     */
    fun updateGuest(
        covers: Int?,
        customerName: String?,
        customerPhone: String?,
        specialRequests: String?
    ) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot update guest - no order loaded")
                return@launch
            }

            val order = currentState.order
            val venueId = deviceInfoManager.getVenueId()

            if (venueId == null) {
                Timber.e("❌ Cannot update guest: venueId is null")
                return@launch
            }

            try {
                val customerId = _selectedCustomer.value?.id
                Timber.d("👤 Updating guest info: covers=$covers, name=$customerName, phone=$customerPhone, customerId=$customerId")

                // Call repository
                orderRepository.updateGuest(
                    venueId = venueId,
                    orderId = order.id,
                    covers = covers,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    specialRequests = specialRequests,
                    customerId = customerId
                ).fold(
                    onSuccess = { updatedOrder ->
                        updateStateWithOrder(updatedOrder)  // 🔄 Syncs _appliedDiscounts
                        Timber.d("✅ Guest info updated successfully")
                        // TODO Step 10: Show success Snackbar
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Error updating guest info")
                        // TODO Step 10: Show error Snackbar
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ Error updating guest info")
                // TODO Step 10: Show error Snackbar
            }
        }
    }

    // ============================================================================
    // Order Actions (Step 9)
    // ============================================================================

    /**
     * Comp items or entire order
     *
     * Makes items complimentary (no charge) for service recovery or manager discretion.
     * If itemIds is empty, comps the entire order.
     *
     * @param itemIds List of item IDs to comp (empty = comp entire order)
     * @param reason Reason for comp (e.g., "Service recovery", "Manager discretion")
     * @param staffId ID of staff member performing the comp
     * @param notes Optional additional notes
     */
    fun compItems(
        itemIds: List<String>,
        reason: String,
        staffId: String,
        notes: String? = null
    ) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot comp items - no order loaded")
                return@launch
            }

            val order = currentState.order
            val venueId = deviceInfoManager.getVenueId()

            if (venueId == null) {
                Timber.e("❌ Cannot comp items: venueId is null")
                return@launch
            }

            try {
                Timber.d("🎁 Comping items: ${itemIds.size} items, reason: $reason")

                // Call repository
                orderRepository.compItems(
                    venueId = venueId,
                    orderId = order.id,
                    itemIds = itemIds,
                    reason = reason,
                    staffId = staffId,
                    notes = notes
                ).fold(
                    onSuccess = { updatedOrder ->
                        updateStateWithOrder(updatedOrder)  // 🔄 CRITICAL: Comp adds discount!
                        Timber.d("✅ Items comped successfully")
                        // TODO Step 10: Show success Snackbar
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Error comping items")
                        // TODO Step 10: Show error Snackbar
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ Error comping items")
                // TODO Step 10: Show error Snackbar
            }
        }
    }

    /**
     * Void items from order
     *
     * Cancels items with audit trail. Items are removed from the order.
     * Requires version field for optimistic concurrency control.
     *
     * @param itemIds List of item IDs to void
     * @param reason Reason for void (e.g., "Customer changed mind", "Out of stock")
     */
    fun voidItems(
        itemIds: List<String>,
        reason: String
    ) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot void items - no order loaded")
                return@launch
            }

            var order = currentState.order
            val venueId = deviceInfoManager.getVenueId()
            val staffId = secureStorage.getStaffId()  // Get staffId internally

            if (venueId == null) {
                Timber.e("❌ Cannot void items: venueId is null")
                return@launch
            }

            if (staffId == null) {
                Timber.e("❌ Cannot void items: staffId is null")
                return@launch
            }

            try {
                Timber.d("🗑️ Voiding items: ${itemIds.size} items, reason: $reason, staffId: $staffId")

                // ⭐ FIX: Sync order to backend BEFORE voiding to get valid backend item IDs
                // Local item IDs are NOT CUIDs and will be rejected by backend validation
                Timber.d("🔄 [VoidItems] Syncing order to get backend item IDs...")
                val syncedOrderId = orderSyncCoordinator.syncOrderImmediately(order.id)

                if (syncedOrderId != order.id) {
                    Timber.i("✅ [VoidItems] Order synced | original=${order.id} → synced=$syncedOrderId")
                    order = order.copy(id = syncedOrderId)
                }

                // ⭐ FIX: Fetch order DIRECTLY from backend to get real item IDs (not local cache)
                // Local DB still has local_item_ IDs even after sync
                Timber.d("🔄 [VoidItems] Fetching order from backend to get real item IDs...")
                val freshOrder = orderRepository.getOrder(venueId, syncedOrderId).getOrElse { error ->
                    Timber.e(error, "❌ Failed to fetch order from backend")
                    _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                        message = "Error al cargar orden: ${error.message}",
                        isError = true
                    ))
                    return@launch
                }
                Timber.d("🔄 [VoidItems] Backend order loaded | items=${freshOrder.items.size}")

                // ⭐ Guard: If order has no items, it was already voided
                if (freshOrder.items.isEmpty()) {
                    Timber.w("⚠️ [VoidItems] Order has no items - already voided")

                    // ⭐ FIX: Sync local DB with backend state (remove local items, update status)
                    updateStateWithOrder(freshOrder)
                    Timber.i("🔄 [VoidItems] Local DB synced with backend (0 items)")

                    _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                        message = "La orden ya fue anulada",
                        isError = true
                    ))
                    _uiEvents.emit(MenuUiEvent.NavigateBack)
                    return@launch
                }

                // Map local itemIds to backend itemIds by matching productId + quantity
                // (In case user selected items before sync)
                val backendItemIds = itemIds.mapNotNull { localId ->
                    val localItem = order.items.find { it.id == localId }
                    if (localItem != null) {
                        // Find matching item in fresh order by productId
                        val backendItem = freshOrder.items.find {
                            it.productId == localItem.productId &&
                            it.quantity == localItem.quantity &&
                            it.unitPrice == localItem.unitPrice
                        }
                        if (backendItem != null) {
                            Timber.d("🔄 [VoidItems] Mapped local ID $localId → backend ID ${backendItem.id}")
                            backendItem.id
                        } else {
                            Timber.w("⚠️ [VoidItems] Could not find backend match for local item $localId")
                            null
                        }
                    } else {
                        // ID is already a backend ID (order was already synced)
                        localId
                    }
                }

                if (backendItemIds.isEmpty()) {
                    Timber.e("❌ [VoidItems] No valid backend IDs found for selected items")
                    _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                        message = "Error: No se pudieron identificar los items",
                        isError = true
                    ))
                    return@launch
                }

                // ⚠️ Check if voiding all items (Toast/Square pattern: auto-closes order)
                val remainingItems = freshOrder.items.filter { it.id !in backendItemIds }
                val isVoidingAllItems = remainingItems.isEmpty()

                if (isVoidingAllItems) {
                    Timber.w("⚠️ [VoidItems] Voiding ALL items - order will be auto-closed")
                }

                Timber.d("🗑️ [VoidItems] Calling backend with ${backendItemIds.size} backend IDs")

                // Call repository with version for optimistic concurrency control
                orderRepository.voidItems(
                    venueId = venueId,
                    orderId = syncedOrderId,
                    itemIds = backendItemIds,
                    reason = reason,
                    staffId = staffId,
                    currentVersion = freshOrder.version
                ).fold(
                    onSuccess = { updatedOrder ->
                        updateStateWithOrder(updatedOrder)  // 🔄 Syncs _appliedDiscounts

                        // ⭐ Show appropriate message based on void scope
                        val message = if (isVoidingAllItems) {
                            "Orden cancelada (todos los items anulados)"
                        } else {
                            "Items anulados correctamente"
                        }

                        Timber.i("✅ [VoidItems] Items voided successfully | voidedAll=$isVoidingAllItems")
                        _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                            message = message,
                            isError = false
                        ))

                        // ⭐ If voided all items, navigate back to order list
                        if (isVoidingAllItems) {
                            Timber.d("🔙 [VoidItems] Navigating back - order was cancelled")
                            _uiEvents.emit(MenuUiEvent.NavigateBack)
                        }
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Error voiding items")
                        _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                            message = "Error al anular items: ${error.message}",
                            isError = true
                        ))
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ Exception in voidItems")
                _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                    message = "Error inesperado: ${e.message}",
                    isError = true
                ))
            }
        }
    }

    /**
     * Apply discount to order
     *
     * Applies percentage or fixed discount to order or specific items.
     *
     * @param type Discount type: "PERCENTAGE" or "FIXED"
     * @param value Discount value (e.g., 10.0 for 10%, or 50.0 for $50 fixed)
     * @param reason Reason for discount (e.g., "Loyalty program", "Manager discretion")
     * @param staffId ID of staff member applying discount
     * @param itemIds Optional list of item IDs (if null, applies to entire order)
     */
    fun applyDiscount(
        type: String,
        value: Double,
        staffId: String,
        reason: String? = null,
        itemIds: List<String>? = null
    ) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot apply discount - no order loaded")
                return@launch
            }

            val order = currentState.order
            val venueId = deviceInfoManager.getVenueId()

            if (venueId == null) {
                Timber.e("❌ Cannot apply discount: venueId is null")
                return@launch
            }

            try {
                Timber.d("💰 Applying discount: type=$type, value=$value, reason=$reason")

                // Call repository with version for optimistic concurrency control
                orderRepository.applyDiscount(
                    venueId = venueId,
                    orderId = order.id,
                    type = type,
                    value = value,
                    staffId = staffId,
                    reason = reason,
                    itemIds = itemIds,
                    currentVersion = order.version
                ).fold(
                    onSuccess = { updatedOrder ->
                        updateStateWithOrder(updatedOrder)  // 🔄 Syncs _appliedDiscounts
                        Timber.d("✅ Discount applied successfully")
                        // TODO Step 10: Show success Snackbar
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Error applying discount")
                        // TODO Step 10: Show error Snackbar
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ Error applying discount")
                // TODO Step 10: Show error Snackbar
            }
        }
    }

    // ============================================================================
    // 🎟️ Discount & Coupon Operations (NEW - DiscountRepository)
    // ============================================================================

    /**
     * Load available predefined discounts for the venue.
     *
     * Called on ViewModel init. Fetches all active discounts that can be applied.
     * Discounts may have conditions (time, minimum amount, day of week).
     */
    private fun loadAvailableDiscounts() {
        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId() ?: return@launch
            val currentState = _state.value
            val orderId = (currentState as? MenuState.Success)?.order?.id ?: return@launch

            _isLoadingDiscounts.value = true
            try {
                discountRepository.getAvailableDiscounts(venueId, orderId).fold(
                    onSuccess = { discounts ->
                        _availableDiscounts.value = discounts
                        Timber.i("🎟️ Loaded ${discounts.size} available discounts")
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Failed to load available discounts")
                        _availableDiscounts.value = emptyList()
                    }
                )
            } finally {
                _isLoadingDiscounts.value = false
            }
        }
    }

    /**
     * Refresh available discounts (public API for pull-to-refresh).
     */
    fun refreshDiscounts() {
        loadAvailableDiscounts()
    }

    /**
     * Apply a predefined discount to the current order.
     *
     * @param discountId ID of the predefined discount
     * @param itemIds Optional list of item IDs to apply discount to (null = entire order)
     * @param reason Optional reason/notes for applying discount
     */
    fun applyPredefinedDiscount(
        discountId: String,
        itemIds: List<String>? = null,
        reason: String? = null
    ) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot apply discount - no order loaded")
                return@launch
            }

            var order = currentState.order
            val venueId = deviceInfoManager.getVenueId()

            if (venueId == null) {
                Timber.e("❌ Cannot apply discount: venueId is null")
                return@launch
            }

            _isLoadingDiscounts.value = true
            try {
                // ⚡ CRITICAL: Force sync before discount (bypass 2s debounce)
                // Discount needs accurate totals from backend to calculate correctly
                Timber.d("🔄 [Discount] Forcing sync before applying predefined discount...")
                val syncedOrderId = orderSyncCoordinator.syncOrderImmediately(order.id)
                // Update order ID if it changed (local_ → server ID)
                if (syncedOrderId != order.id) {
                    order = order.copy(id = syncedOrderId)
                    Timber.i("✅ [Discount] Order synced | original → $syncedOrderId")
                }

                Timber.d("🎟️ Applying predefined discount: $discountId")

                discountRepository.applyPredefinedDiscount(
                    venueId = venueId,
                    orderId = order.id,
                    discountId = discountId
                ).fold(
                    onSuccess = { appliedDiscount ->
                        // Update applied discounts list
                        val updatedDiscounts = _appliedDiscounts.value + appliedDiscount
                        _appliedDiscounts.value = updatedDiscounts
                        // 🔄 Recalculate totals with new discount
                        val orderWithDiscounts = order.copy(discounts = updatedDiscounts)
                        val updatedOrder = recalculateOrder(orderWithDiscounts)
                        _state.value = MenuState.Success(updatedOrder)
                        Timber.i("✅ Predefined discount applied: ${appliedDiscount.name} | discount=${updatedOrder.discountAmount} | total=${updatedOrder.total}")
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Error applying predefined discount")
                    }
                )
            } finally {
                _isLoadingDiscounts.value = false
            }
        }
    }

    /**
     * Apply a manual discount to the current order.
     *
     * @param type Discount type: PERCENTAGE or FIXED
     * @param value Discount value (percentage as 0-100, or fixed amount)
     * @param reason Optional reason for the discount
     * @param itemIds Optional list of item IDs to apply discount to (null = entire order)
     */
    fun applyManualDiscount(
        type: DiscountType,
        value: Double,
        reason: String? = null,
        itemIds: List<String>? = null
    ) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot apply manual discount - no order loaded")
                return@launch
            }

            var order = currentState.order
            val venueId = deviceInfoManager.getVenueId()

            if (venueId == null) {
                Timber.e("❌ Cannot apply manual discount: venueId is null")
                return@launch
            }

            _isLoadingDiscounts.value = true
            try {
                // ⚡ CRITICAL: Force sync before discount (bypass 2s debounce)
                // Discount needs accurate totals from backend to calculate correctly
                Timber.d("🔄 [Discount] Forcing sync before applying manual discount...")
                val syncedOrderId = orderSyncCoordinator.syncOrderImmediately(order.id)
                // Update order ID if it changed (local_ → server ID)
                if (syncedOrderId != order.id) {
                    order = order.copy(id = syncedOrderId)
                    Timber.i("✅ [Discount] Order synced | original → $syncedOrderId")
                }

                Timber.d("🎟️ Applying manual discount: type=$type, value=$value")

                discountRepository.applyManualDiscount(
                    venueId = venueId,
                    orderId = order.id,
                    type = type,
                    value = value,
                    reason = reason
                ).fold(
                    onSuccess = { appliedDiscount ->
                        val updatedDiscounts = _appliedDiscounts.value + appliedDiscount
                        _appliedDiscounts.value = updatedDiscounts
                        // 🔄 Recalculate totals with new discount
                        val orderWithDiscounts = order.copy(discounts = updatedDiscounts)
                        val updatedOrder = recalculateOrder(orderWithDiscounts)
                        _state.value = MenuState.Success(updatedOrder)
                        Timber.i("✅ Manual discount applied: ${appliedDiscount.name} | discount=${updatedOrder.discountAmount} | total=${updatedOrder.total}")
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Error applying manual discount")
                    }
                )
            } finally {
                _isLoadingDiscounts.value = false
            }
        }
    }

    /**
     * Remove a discount from the current order.
     *
     * @param orderDiscountId ID of the applied discount to remove
     */
    fun removeDiscount(orderDiscountId: String) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot remove discount - no order loaded")
                return@launch
            }

            val order = currentState.order
            val venueId = deviceInfoManager.getVenueId()

            if (venueId == null) {
                Timber.e("❌ Cannot remove discount: venueId is null")
                return@launch
            }

            _isLoadingDiscounts.value = true
            try {
                Timber.d("🗑️ Removing discount: $orderDiscountId")

                discountRepository.removeDiscount(
                    venueId = venueId,
                    orderId = order.id,
                    orderDiscountId = orderDiscountId,
                    expectedVersion = order.version
                ).fold(
                    onSuccess = {
                        // Remove from local list
                        val updatedDiscounts = _appliedDiscounts.value.filter { it.id != orderDiscountId }
                        _appliedDiscounts.value = updatedDiscounts
                        // 🔄 Recalculate totals without the removed discount
                        val orderWithDiscounts = order.copy(discounts = updatedDiscounts)
                        val updatedOrder = recalculateOrder(orderWithDiscounts)
                        _state.value = MenuState.Success(updatedOrder)
                        Timber.i("✅ Discount removed | discount=${updatedOrder.discountAmount} | total=${updatedOrder.total}")
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Error removing discount")
                    }
                )
            } finally {
                _isLoadingDiscounts.value = false
            }
        }
    }

    /**
     * Validate a coupon code without applying it.
     *
     * Updates couponValidationState with the result.
     *
     * @param code Coupon code to validate
     */
    fun validateCoupon(code: String) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot validate coupon - no order loaded")
                return@launch
            }

            val order = currentState.order
            val venueId = deviceInfoManager.getVenueId()

            if (venueId == null) {
                Timber.e("❌ Cannot validate coupon: venueId is null")
                return@launch
            }

            _couponValidationState.value = CouponValidationState.Loading
            try {
                Timber.d("🎟️ Validating coupon: $code")

                discountRepository.validateCoupon(
                    venueId = venueId,
                    code = code,
                    orderTotal = order.total.toDouble()
                ).fold(
                    onSuccess = { couponCode ->
                        _couponValidationState.value = CouponValidationState.Valid(couponCode)
                        Timber.i("✅ Coupon validated: ${couponCode.code} - ${couponCode.name}")
                    },
                    onFailure = { error ->
                        _couponValidationState.value = CouponValidationState.Invalid(
                            error.message ?: "Código de cupón inválido"
                        )
                        Timber.e(error, "❌ Coupon validation failed")
                    }
                )
            } catch (e: Exception) {
                _couponValidationState.value = CouponValidationState.Error(
                    e.message ?: "Error validando cupón"
                )
                Timber.e(e, "❌ Error validating coupon")
            }
        }
    }

    /**
     * Apply a validated coupon to the current order.
     *
     * @param code Coupon code to apply (should be validated first)
     */
    fun applyCoupon(code: String) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                Timber.w("⚠️ Cannot apply coupon - no order loaded")
                return@launch
            }

            val order = currentState.order
            val venueId = deviceInfoManager.getVenueId()

            if (venueId == null) {
                Timber.e("❌ Cannot apply coupon: venueId is null")
                return@launch
            }

            // Show loading state for spinner in button (Toast/Square UX)
            _couponValidationState.value = CouponValidationState.Loading
            _isLoadingDiscounts.value = true
            try {
                Timber.d("🎟️ Applying coupon: $code")

                // ⭐ FIX: Get validated coupon's type and value for local recalculation
                // Backend applyCoupon returns OrderDiscount with value=0, but we need the
                // percentage value to recalculate discounts locally when items change.
                val validatedCoupon = (_couponValidationState.value as? CouponValidationState.Valid)?.coupon

                discountRepository.applyCoupon(
                    venueId = venueId,
                    orderId = order.id,
                    code = code
                ).fold(
                    onSuccess = { appliedDiscount ->
                        // ⭐ FIX: Copy type and value from validated coupon for local recalculation
                        // This enables recalculateOrder() to correctly recalculate percentage discounts
                        // when items are added/removed BEFORE backend sync.
                        //
                        // ⚠️ OPTIMISTIC UPDATE: This provides immediate UI feedback. Backend may apply
                        // different rules (max discount cap, eligibility conditions, item exclusions).
                        // refreshOrderAfterSync() fetches the authoritative amounts after sync completes.
                        val discountWithValue = if (validatedCoupon != null) {
                            appliedDiscount.copy(
                                type = validatedCoupon.type,
                                value = validatedCoupon.value
                            )
                        } else {
                            appliedDiscount
                        }

                        val updatedDiscounts = _appliedDiscounts.value + discountWithValue
                        _appliedDiscounts.value = updatedDiscounts
                        // 🔄 Recalculate totals with new discount
                        val orderWithDiscounts = order.copy(discounts = updatedDiscounts)
                        val updatedOrder = recalculateOrder(orderWithDiscounts)
                        _state.value = MenuState.Success(updatedOrder)
                        // Reset validation state after successful application
                        _couponValidationState.value = CouponValidationState.Idle
                        Timber.i("✅ Coupon applied: ${discountWithValue.name} | type=${discountWithValue.type} | value=${discountWithValue.value} | discount=${updatedOrder.discountAmount} | total=${updatedOrder.total}")
                    },
                    onFailure = { error ->
                        // Map backend error to user-friendly Spanish message
                        val userMessage = mapCouponErrorToSpanish(error.message)
                        _couponValidationState.value = CouponValidationState.Invalid(userMessage)
                        Timber.e(error, "❌ Error applying coupon: ${error.message} → $userMessage")
                    }
                )
            } finally {
                _isLoadingDiscounts.value = false
            }
        }
    }

    /**
     * Reset coupon validation state to Idle.
     * Called when user clears the coupon input field.
     */
    fun resetCouponValidation() {
        _couponValidationState.value = CouponValidationState.Idle
    }

    /**
     * Maps backend coupon error messages to user-friendly Spanish messages.
     * Toast/Square UX: specific error messages help users understand what went wrong.
     */
    private fun mapCouponErrorToSpanish(errorMessage: String?): String {
        return when {
            errorMessage == null -> "Error desconocido"
            "not found" in errorMessage.lowercase() ->
                "Cupón no encontrado. Verifica el código."
            "expired" in errorMessage.lowercase() ->
                "Este cupón ya expiró."
            "inactive" in errorMessage.lowercase() ->
                "Este cupón está desactivado."
            "usage limit" in errorMessage.lowercase() ->
                "Este cupón ya agotó todos sus usos disponibles."
            "already been applied" in errorMessage.lowercase() ->
                "Este cupón ya fue aplicado a esta orden."
            "already used this coupon" in errorMessage.lowercase() ->
                "Ya alcanzaste el límite de usos de este cupón."
            "minimum purchase" in errorMessage.lowercase() -> {
                // Extract amount from message: "Minimum purchase of X required"
                val amount = errorMessage.substringAfter("of ").substringBefore(" required")
                "Compra mínima requerida: $$amount"
            }
            "not valid yet" in errorMessage.lowercase() ->
                "Este cupón aún no está activo."
            else -> errorMessage
        }
    }

    // ============================================================================
    // 👤 Customer Search Functions (GuestTab)
    // ============================================================================

    /**
     * Search customers by query (name, phone, or email).
     *
     * Uses debounce (300ms) in the UI layer (GuestTab).
     * Requires minimum 2 characters to search.
     *
     * @param query Search query string
     */
    fun searchCustomers(query: String) {
        if (query.length < 2) {
            _customerSearchState.value = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Idle
            return
        }

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ Cannot search customers: venueId is null")
                _customerSearchState.value = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Error(
                    "Error de configuración"
                )
                return@launch
            }

            _customerSearchState.value = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Loading

            customerRepository.searchCustomers(
                venueId = venueId,
                query = query,
                limit = 10
            ).onSuccess { customers ->
                _customerSearchState.value = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Success(customers)
                Timber.d("👤 [Customer Search] Found ${customers.size} customers for query: $query")
            }.onFailure { error ->
                _customerSearchState.value = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Error(
                    error.message ?: "Error buscando clientes"
                )
                Timber.e(error, "❌ [Customer Search] Failed to search customers")
            }
        }
    }

    /**
     * Clear customer search state - reset to Idle.
     * Called when dismissing customer search modals.
     */
    fun clearCustomerSearch() {
        _customerSearchState.value = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Idle
    }

    /**
     * @DEPRECATED: Use addCustomerToOrder() instead.
     * Select a customer for the order - kept for backward compatibility.
     */
    fun selectCustomer(customer: com.jaac.avoqado_tpv.features.ordering.domain.Customer) {
        // Delegate to new multi-customer method
        addCustomerToOrder(customer)
    }

    /**
     * Clear the selected customer and reset search state.
     */
    fun clearSelectedCustomer() {
        _selectedCustomer.value = null
        _customerSearchState.value = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Idle
        Timber.d("👤 [Customer] Selection cleared")
    }

    /**
     * Load recent customers for the search modal (Toast/Square pattern).
     *
     * ⚠️ UX CRITICAL: If order is local, sync it FIRST before loading customers.
     * This ensures that when the user selects a customer, the order is already synced
     * and they don't have to wait. This is essential for fast workflows during rush hours.
     *
     * Flow:
     * 1. User clicks "Buscar y Agregar Cliente" button
     * 2. This function is called
     * 3. If order is local → sync immediately (background, user sees "Cargando clientes...")
     * 4. Load recent customers
     * 5. Modal opens with synced order - clicking a customer is instant!
     */
    fun loadRecentCustomers() {
        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ Cannot load recent customers: venueId is null")
                return@launch
            }

            _isLoadingRecentCustomers.value = true

            // 🚀 PROACTIVE SYNC: If order is local, sync BEFORE loading customers
            // This ensures instant customer selection without waiting
            val currentState = _state.value
            if (currentState is MenuState.Success) {
                val orderId = currentState.order.id
                if (orderId.startsWith("local_")) {
                    Timber.d("🔄 [loadRecentCustomers] Order is local, syncing proactively...")
                    try {
                        val localOrder = orderSyncCoordinator.getLocalOrder(orderId)
                        if (localOrder != null) {
                            val syncedOrderId = orderSyncCoordinator.syncOrderImmediately(orderId)
                            Timber.i("✅ [loadRecentCustomers] Order synced proactively | $orderId → $syncedOrderId")

                            // Update state with synced order
                            val syncedOrder = orderSyncCoordinator.getLocalOrder(syncedOrderId)
                            if (syncedOrder != null) {
                                updateStateWithOrder(syncedOrder)
                            }
                        } else {
                            Timber.d("🔄 [loadRecentCustomers] Order already synced by debounce")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "⚠️ [loadRecentCustomers] Proactive sync failed, will retry on customer selection")
                        // Continue loading customers - addCustomerToOrder will handle sync as fallback
                    }
                }
            }

            customerRepository.getRecentCustomers(
                venueId = venueId,
                limit = 10
            ).onSuccess { customers ->
                _recentCustomers.value = customers
                Timber.d("👤 [Recent Customers] Loaded ${customers.size} recent customers")
            }.onFailure { error ->
                Timber.e(error, "❌ [Recent Customers] Failed to load")
                // Don't show error to user - just empty list
                _recentCustomers.value = emptyList()
            }

            _isLoadingRecentCustomers.value = false
        }
    }

    // ============================================================================
    // 👥 Multi-Customer Order Functions (NEW)
    // ============================================================================

    /**
     * Load customers associated with the current order.
     *
     * Called when order is loaded (loadOrder, refreshOrder, etc.).
     * Updates _orderCustomers and _selectedCustomer for backward compatibility.
     *
     * @param orderId The order ID to load customers for
     */
    fun loadOrderCustomers(orderId: String) {
        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ Cannot load order customers: venueId is null")
                return@launch
            }

            // Skip loading for local-only orders (not synced to backend yet)
            if (orderId.startsWith("local_")) {
                Timber.d("👥 [Order Customers] Skipping load for local order: $orderId")
                return@launch
            }

            orderRepository.getOrderCustomers(
                venueId = venueId,
                orderId = orderId
            ).onSuccess { customers ->
                _orderCustomers.value = customers
                // Update legacy selectedCustomer with primary customer for backward compatibility
                _selectedCustomer.value = customers.firstOrNull { it.isPrimary }?.customer
                Timber.d("👥 [Order Customers] Loaded ${customers.size} customers for order $orderId")
            }.onFailure { error ->
                Timber.e(error, "❌ [Order Customers] Failed to load")
                // Don't clear existing - might be network error
            }
        }
    }

    /**
     * Add a customer to the current order.
     *
     * **Immediate Action:** No extra "save" step required.
     * Click on search result → customer is added immediately.
     * First customer added becomes isPrimary=true (receives loyalty points on payment).
     *
     * **Local Order Handling:** If order is local (not yet synced), automatically
     * syncs the order first before adding the customer. This ensures a seamless UX
     * where clicking a customer result always works.
     *
     * @param customer The customer to add
     */
    fun addCustomerToOrder(customer: com.jaac.avoqado_tpv.features.ordering.domain.Customer) {
        val currentState = _state.value
        if (currentState !is MenuState.Success) {
            Timber.w("⚠️ [addCustomerToOrder] Cannot add customer: no order loaded")
            return
        }

        var orderId = currentState.order.id

        // Check if customer is already in the list
        if (_orderCustomers.value.any { it.customerId == customer.id }) {
            Timber.d("👥 [addCustomerToOrder] Customer already in order: ${customer.displayName}")
            return
        }

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ Cannot add customer: venueId is null")
                return@launch
            }

            _isAddingCustomer.value = true

            // 🔄 If order is local, sync first before adding customer
            if (orderId.startsWith("local_")) {
                Timber.d("🔄 [addCustomerToOrder] Order is local, checking sync status...")

                // Check if local order still exists (might have been synced by debounce)
                val localOrder = orderSyncCoordinator.getLocalOrder(orderId)
                if (localOrder == null) {
                    // Order was already synced by debounce - check current state for new ID
                    val latestState = _state.value
                    if (latestState is MenuState.Success && !latestState.order.id.startsWith("local_")) {
                        orderId = latestState.order.id
                        Timber.i("✅ [addCustomerToOrder] Order was already synced by debounce | id=$orderId")
                    } else {
                        // Can't find the synced order - abort
                        Timber.e("❌ [addCustomerToOrder] Order not found after debounce sync")
                        _isAddingCustomer.value = false
                        return@launch
                    }
                } else {
                    // Order still local, sync it now
                    try {
                        val syncedOrderId = orderSyncCoordinator.syncOrderImmediately(orderId)
                        Timber.i("✅ [addCustomerToOrder] Order synced | $orderId → $syncedOrderId")
                        orderId = syncedOrderId

                        // Update state with synced order
                        val syncedOrder = orderSyncCoordinator.getLocalOrder(syncedOrderId)
                        if (syncedOrder != null) {
                            updateStateWithOrder(syncedOrder)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ [addCustomerToOrder] Failed to sync order before adding customer")
                        _isAddingCustomer.value = false
                        return@launch
                    }
                }
            }

            Timber.d("➕ [addCustomerToOrder] Adding customer: ${customer.displayName} | orderId=$orderId")

            orderRepository.addCustomerToOrder(
                venueId = venueId,
                orderId = orderId,
                customerId = customer.id
            ).onSuccess { updatedCustomers ->
                _orderCustomers.value = updatedCustomers
                // Update legacy selectedCustomer with primary customer
                _selectedCustomer.value = updatedCustomers.firstOrNull { it.isPrimary }?.customer
                // Clear search state after successful add
                _customerSearchState.value = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Idle
                Timber.i("✅ [addCustomerToOrder] Customer added | total=${updatedCustomers.size}")
            }.onFailure { error ->
                Timber.e(error, "❌ [addCustomerToOrder] Failed to add customer")
                // Could show snackbar here via a separate error state if needed
            }

            _isAddingCustomer.value = false
        }
    }

    /**
     * Remove a customer from the current order.
     *
     * **Note:** If removed customer was isPrimary, backend promotes next oldest to primary.
     * Does NOT delete the Customer record itself.
     *
     * @param customerId The customer ID to remove
     */
    fun removeCustomerFromOrder(customerId: String) {
        val currentState = _state.value
        if (currentState !is MenuState.Success) {
            Timber.w("⚠️ [removeCustomerFromOrder] Cannot remove customer: no order loaded")
            return
        }

        val orderId = currentState.order.id
        if (orderId.startsWith("local_")) {
            Timber.w("⚠️ [removeCustomerFromOrder] Cannot remove customer from local order")
            return
        }

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ Cannot remove customer: venueId is null")
                return@launch
            }

            _isAddingCustomer.value = true
            Timber.d("➖ [removeCustomerFromOrder] Removing customer: $customerId | orderId=$orderId")

            orderRepository.removeCustomerFromOrder(
                venueId = venueId,
                orderId = orderId,
                customerId = customerId
            ).onSuccess { updatedCustomers ->
                _orderCustomers.value = updatedCustomers
                // Update legacy selectedCustomer with primary customer (or null if empty)
                _selectedCustomer.value = updatedCustomers.firstOrNull { it.isPrimary }?.customer
                Timber.i("✅ [removeCustomerFromOrder] Customer removed | remaining=${updatedCustomers.size}")
            }.onFailure { error ->
                Timber.e(error, "❌ [removeCustomerFromOrder] Failed to remove customer")
            }

            _isAddingCustomer.value = false
        }
    }

    /**
     * Create a new customer and add to the current order.
     *
     * **Inline Creation:** For quick customer registration during checkout.
     * At least one of firstName, phone, or email is required.
     *
     * @param firstName Customer first name (optional)
     * @param phone Customer phone (optional)
     * @param email Customer email (optional)
     */
    fun createAndAddCustomerToOrder(firstName: String?, phone: String?, email: String?) {
        // Validate at least one field is provided
        if (firstName.isNullOrBlank() && phone.isNullOrBlank() && email.isNullOrBlank()) {
            Timber.w("⚠️ [createAndAddCustomer] At least one field required")
            return
        }

        val currentState = _state.value
        if (currentState !is MenuState.Success) {
            Timber.w("⚠️ [createAndAddCustomer] Cannot create customer: no order loaded")
            return
        }

        var orderId = currentState.order.id

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ Cannot create customer: venueId is null")
                return@launch
            }

            _isAddingCustomer.value = true

            // 🔄 If order is local, sync first before creating customer
            if (orderId.startsWith("local_")) {
                Timber.d("🔄 [createAndAddCustomer] Order is local, checking sync status...")

                // Check if local order still exists (might have been synced by debounce)
                val localOrder = orderSyncCoordinator.getLocalOrder(orderId)
                if (localOrder == null) {
                    // Order was already synced by debounce - check current state for new ID
                    val latestState = _state.value
                    if (latestState is MenuState.Success && !latestState.order.id.startsWith("local_")) {
                        orderId = latestState.order.id
                        Timber.i("✅ [createAndAddCustomer] Order was already synced by debounce | id=$orderId")
                    } else {
                        // Can't find the synced order - abort
                        Timber.e("❌ [createAndAddCustomer] Order not found after debounce sync")
                        _isAddingCustomer.value = false
                        return@launch
                    }
                } else {
                    // Order still local, sync it now
                    try {
                        val syncedOrderId = orderSyncCoordinator.syncOrderImmediately(orderId)
                        Timber.i("✅ [createAndAddCustomer] Order synced | $orderId → $syncedOrderId")
                        orderId = syncedOrderId

                        // Update state with synced order
                        val syncedOrder = orderSyncCoordinator.getLocalOrder(syncedOrderId)
                        if (syncedOrder != null) {
                            updateStateWithOrder(syncedOrder)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ [createAndAddCustomer] Failed to sync order before creating customer")
                        _isAddingCustomer.value = false
                        return@launch
                    }
                }
            }

            Timber.d("🆕 [createAndAddCustomer] Creating customer | name=$firstName | phone=$phone | email=$email")

            orderRepository.createAndAddCustomerToOrder(
                venueId = venueId,
                orderId = orderId,
                firstName = firstName?.trim()?.takeIf { it.isNotBlank() },
                phone = phone?.trim()?.takeIf { it.isNotBlank() },
                email = email?.trim()?.takeIf { it.isNotBlank() }
            ).onSuccess { updatedCustomers ->
                _orderCustomers.value = updatedCustomers
                // Update legacy selectedCustomer with primary customer
                _selectedCustomer.value = updatedCustomers.firstOrNull { it.isPrimary }?.customer
                Timber.i("✅ [createAndAddCustomer] Customer created and added | total=${updatedCustomers.size}")
            }.onFailure { error ->
                Timber.e(error, "❌ [createAndAddCustomer] Failed to create customer")
            }

            _isAddingCustomer.value = false
        }
    }

    /**
     * Clear all customers from UI state (on order change/close).
     * Does NOT remove from backend - just clears local state.
     */
    fun clearOrderCustomers() {
        _orderCustomers.value = emptyList()
        _selectedCustomer.value = null
        _customerSearchState.value = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Idle
        Timber.d("👥 [Order Customers] Cleared")
    }

    // ============================================================================
    // Pay Later (Pagar Después)
    // ============================================================================

    /**
     * Create a pay-later order by syncing current order to backend and linking customer.
     *
     * **Flow:**
     * 1. Validate order has items
     * 2. Sync local order to backend (if needed)
     * 3. Link customer to order (marks as pay-later)
     * 4. Clear current order from UI
     *
     * **Pay-later Definition:**
     * An order with PENDING payment status AND customer linkage.
     * This allows tracking who owes money without aggregating balances.
     *
     * @param customerId The customer ID to link to the order
     */
    fun createPayLaterOrder(customerId: String) {
        val currentState = _state.value
        if (currentState !is MenuState.Success) {
            Timber.w("⚠️ [createPayLaterOrder] Cannot create pay-later order: no order loaded")
            return
        }

        val order = currentState.order
        if (order.items.isEmpty()) {
            Timber.w("⚠️ [createPayLaterOrder] Cannot create pay-later order: order has no items")
            return
        }

        // ⚠️ Guard: Prevent adding customer to pay-later order
        if (_orderCustomers.value.isNotEmpty()) {
            Timber.w("⚠️ [createPayLaterOrder] Order already has ${_orderCustomers.value.size} customer(s)")
            viewModelScope.launch {
                _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                    message = "Esta orden ya tiene un cliente asignado",
                    isError = true
                ))
            }
            return
        }

        var orderId = order.id

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ [createPayLaterOrder] Cannot create pay-later order: venueId is null")
                return@launch
            }

            _isAddingCustomer.value = true

            try {
                // 🔄 If order is local, sync first before linking customer
                if (orderId.startsWith("local_")) {
                    Timber.d("🔄 [createPayLaterOrder] Order is local, syncing to backend...")

                    // Check if local order still exists (might have been synced by debounce)
                    val localOrder = orderSyncCoordinator.getLocalOrder(orderId)
                    if (localOrder == null) {
                        // Order was already synced by debounce - check current state for new ID
                        val latestState = _state.value
                        if (latestState is MenuState.Success && !latestState.order.id.startsWith("local_")) {
                            orderId = latestState.order.id
                            Timber.i("✅ [createPayLaterOrder] Order was already synced by debounce | id=$orderId")
                        } else {
                            // Can't find the synced order - abort
                            Timber.e("❌ [createPayLaterOrder] Order not found after debounce sync")
                            _isAddingCustomer.value = false
                            return@launch
                        }
                    } else {
                        // Order still local, sync it now
                        val syncedOrderId = orderSyncCoordinator.syncOrderImmediately(orderId)
                        Timber.i("✅ [createPayLaterOrder] Order synced | $orderId → $syncedOrderId")
                        orderId = syncedOrderId

                        // Update state with synced order
                        val syncedOrder = orderSyncCoordinator.getLocalOrder(syncedOrderId)
                        if (syncedOrder != null) {
                            updateStateWithOrder(syncedOrder)
                        }
                    }
                }

                // ✅ Link customer to order (marks as pay-later)
                Timber.d("💳 [createPayLaterOrder] Linking customer to order | customerId=$customerId | orderId=$orderId")

                orderRepository.addCustomerToOrder(
                    venueId = venueId,
                    orderId = orderId,
                    customerId = customerId
                ).onSuccess { updatedCustomers ->
                    Timber.i("✅ [createPayLaterOrder] Pay-later order created | orderId=$orderId | customer=${updatedCustomers.firstOrNull()?.customer?.displayName}")

                    // ✅ Emit success event and navigate back (like "Send to Kitchen")
                    viewModelScope.launch {
                        _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                            message = "Orden guardada para pagar después",
                            isError = false
                        ))

                        // ✅ Clear state + navigate back (Room Flow stops observing)
                        _currentOrderId.value = null  // Triggers Room Flow to stop observing
                        _state.value = MenuState.Loading
                        _uiEvents.emit(MenuUiEvent.NavigateBack)
                    }
                }.onFailure { error ->
                    Timber.e(error, "❌ [createPayLaterOrder] Failed to link customer")
                    viewModelScope.launch {
                        _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                            message = "Error al crear orden: ${error.message}",
                            isError = true
                        ))
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [createPayLaterOrder] Failed")
                viewModelScope.launch {
                    _uiEvents.emit(MenuUiEvent.ShowSnackbar(
                        message = "Error: ${e.message}",
                        isError = true
                    ))
                }
            } finally {
                _isAddingCustomer.value = false
            }
        }
    }

    // ============================================================================
    // Split Payment Support
    // ============================================================================

    /**
     * Force sync before navigating to split payment screens.
     *
     * **Why This Is Needed:**
     * Split screens (SplitByProduct, SplitByPerson) fetch order from BACKEND.
     * If items haven't been synced yet (2s debounce), backend returns 0 items.
     *
     * **Solution:**
     * Force immediate sync before navigation to ensure backend has all items.
     *
     * @param onComplete Callback with synced order ID (may be different from local ID)
     */
    fun syncBeforeNavigate(onComplete: (String) -> Unit) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) {
                // ✅ FIX: Still allow navigation when state is Error/Loading
                // User shouldn't be stuck - just navigate without sync
                Timber.w("⚠️ [syncBeforeNavigate] No order loaded (state=${currentState::class.simpleName}) - navigating anyway")
                onComplete("")  // Empty ID signals to navigate back without specific order
                return@launch
            }

            val order = currentState.order
            Timber.d("🔄 [syncBeforeNavigate] Forcing sync before navigation | orderId=${order.id}")

            try {
                // Force immediate sync (bypass 2s debounce) - returns synced order ID
                val syncedOrderId = orderSyncCoordinator.syncOrderImmediately(order.id)
                Timber.i("✅ [syncBeforeNavigate] Sync completed | original=${order.id} | synced=$syncedOrderId")

                // Update state if ID changed (local_ → CUID)
                if (syncedOrderId != order.id) {
                    val syncedOrder = orderSyncCoordinator.getLocalOrder(syncedOrderId)
                    if (syncedOrder != null) {
                        updateStateWithOrder(syncedOrder)
                        Timber.d("🔄 [syncBeforeNavigate] State updated with new ID | old=${order.id} | new=$syncedOrderId")
                    }
                }

                // Navigate with synced order ID (CUID)
                onComplete(syncedOrderId)

            } catch (e: Exception) {
                Timber.e(e, "❌ [syncBeforeNavigate] Sync failed, navigating anyway")
                // Navigate anyway - split screen will show current backend state
                onComplete(order.id)
            }
        }
    }

    // ============================================================================
    // Private Helpers
    // ============================================================================

    /**
     * Update state with a new order AND preserve _appliedDiscounts StateFlow.
     *
     * ⚠️ CRITICAL: Always use this instead of directly setting _state.value.
     * This keeps _appliedDiscounts in sync with order.discounts.
     *
     * **Room Limitation:**
     * Room doesn't persist `order.discounts` list (only `discountAmount` total).
     * When Room emits, `order.discounts` is always empty.
     * We must NOT overwrite `_appliedDiscounts` with empty list from Room.
     *
     * **Rule:**
     * - If `order.discounts` has data → use it (comes from backend/local calculation)
     * - If `order.discounts` is empty → PRESERVE `_appliedDiscounts` (Room emits before recalculate)
     * - `_appliedDiscounts` is only cleared explicitly via `clearAppliedDiscounts()`
     *
     * @param order The updated order (after recalculateOrder)
     */
    private fun updateStateWithOrder(order: Order) {
        _state.value = MenuState.Success(order)

        // 🔄 Only UPDATE _appliedDiscounts if order.discounts has real data
        // NEVER clear based on discountAmount (Room emits before recalculate, causing race condition)
        if (order.discounts.isNotEmpty()) {
            _appliedDiscounts.value = order.discounts
            Timber.d("🔄 [updateStateWithOrder] Updated _appliedDiscounts (${order.discounts.size} discounts)")
        }
        // If order.discounts is empty, PRESERVE existing _appliedDiscounts
        // This handles the race condition where Room emits before recalculateOrder() runs
    }

    /**
     * Recalculate order totals including discounts
     *
     * Subtotal = sum of all item totals
     * DiscountAmount = recalculated for PERCENTAGE discounts, preserved for FIXED_AMOUNT
     * Total = subtotal - discountAmount
     * Tax = 0% (backend handles tax separately)
     *
     * @see order.tpv.service.ts - Backend also recalculates percentage discounts on add/remove
     */
    private fun recalculateOrder(order: Order): Order {
        val subtotal = order.items.sumOf { it.totalPrice }
        val tax = BigDecimal.ZERO  // Backend handles tax (currently 0% for new orders)

        // 🔄 Recalculate discounts (PERCENTAGE type needs new amount based on new subtotal)
        var newDiscountAmount = BigDecimal.ZERO
        val updatedDiscounts = order.discounts.map { orderDiscount ->
            val discountType = orderDiscount.type
            val discountValue = orderDiscount.value

            if (discountType == DiscountType.PERCENTAGE && discountValue > BigDecimal.ZERO) {
                // Recalculate: new_amount = subtotal * percentage / 100
                val recalculatedAmount = subtotal.multiply(discountValue)
                    .divide(BigDecimal(100), 2, java.math.RoundingMode.HALF_UP)
                Timber.d("🔄 Recalculating PERCENTAGE discount: ${discountValue}% of $subtotal = $recalculatedAmount")
                newDiscountAmount += recalculatedAmount
                orderDiscount.copy(amount = recalculatedAmount)
            } else {
                // FIXED_AMOUNT or COMP - keep original amount
                newDiscountAmount += orderDiscount.amount
                orderDiscount
            }
        }

        // If no discounts list but order has discountAmount (legacy), preserve it
        if (order.discounts.isEmpty() && order.discountAmount > BigDecimal.ZERO) {
            newDiscountAmount = order.discountAmount
        }

        val total = subtotal - newDiscountAmount

        // Recalculate remainingBalance for split payments
        // Fresh order: paidAmount=0 → remainingBalance = total
        // Partial payment: paidAmount=50 → remainingBalance = total - 50
        val remainingBalance = total - order.paidAmount

        return order.copy(
            subtotal = subtotal,
            discountAmount = newDiscountAmount,
            tax = tax,
            total = total,
            remainingBalance = remainingBalance,
            discounts = updatedDiscounts,
            updatedAt = Instant.now()
        )
    }

    // ================================================================================================
    // BARCODE QUICK ADD - Square POS "Scan & Go" Pattern
    // ================================================================================================

    /**
     * Opens the barcode scanner in Quick Add mode.
     * ✅ BARCODE QUICK ADD: User presses VOLUME+ button to start scanning.
     *
     * **Safety Validations:**
     * - Only opens if MenuState.Success (screen is active)
     * - Only opens if there's an active order
     * - Only opens if order can receive items (OPEN or IN_PROGRESS status)
     *
     * **Why these validations?**
     * The BroadcastReceiver stays registered while ViewModel is alive.
     * If user navigates to PaymentScreen, VOLUME+ should NOT open scanner.
     * These checks prevent scanning when MenuScreen is not in foreground.
     */
    fun openBarcodeQuickAdd() {
        val currentState = _state.value

        // Validation 1: State must be Success
        if (currentState !is MenuState.Success) {
            Timber.w("📷 [BarcodeQuickAdd] Cannot open scanner - current state is ${currentState::class.simpleName} (not Success)")
            return
        }

        // Validation 2: Order must exist
        val order = currentState.order

        // Validation 3: Order must be able to accept items
        if (order.status != OrderStatus.OPEN && order.status != OrderStatus.IN_PROGRESS) {
            Timber.w("📷 [BarcodeQuickAdd] Cannot open scanner - order cannot accept items | status=${order.status}")
            return
        }

        // All validations passed - open scanner
        _state.value = currentState.copy(
            showBarcodeScanner = true,
            totalScannedCount = 0,
            lastScannedProductName = null
        )
        Timber.d("📷 [BarcodeQuickAdd] Scanner opened | orderId=${order.id} | status=${order.status}")
    }

    /**
     * Closes the barcode scanner.
     * ✅ BARCODE QUICK ADD: User taps "Listo" button or back.
     */
    fun closeBarcodeScanner() {
        val currentState = _state.value
        if (currentState is MenuState.Success) {
            val totalScanned = currentState.totalScannedCount
            _state.value = currentState.copy(
                showBarcodeScanner = false,
                barcodeProcessing = false,
                lastScannedProductName = null
            )
            Timber.d("📷 [BarcodeQuickAdd] Scanner closed | Total scanned: $totalScanned")
        }
    }

    /**
     * Handles barcode scanned event.
     * ✅ BARCODE QUICK ADD: Search product by SKU, add to order, or show quick-add dialog if not found.
     *
     * Flow:
     * 1. Search product by barcode (SKU)
     * 2a. Found + No modifiers → Add to order directly (quantity=1)
     * 2b. Found + Has modifiers → Close scanner, open ProductSelectorBottomSheet
     * 2c. Not found → Show Quick Add Product dialog
     */
    fun onBarcodeScanned(barcode: String, format: String) {
        val currentState = _state.value
        if (currentState !is MenuState.Success) {
            Timber.w("📷 [BarcodeQuickAdd] No active order")
            return
        }

        val order = currentState.order
        if (order.status != OrderStatus.OPEN && order.status != OrderStatus.IN_PROGRESS) {
            Timber.w("📷 [BarcodeQuickAdd] Order cannot accept items | status=${order.status}")
            return
        }

        Timber.d("📷 [BarcodeQuickAdd] Barcode scanned | barcode=$barcode | format=$format")

        _state.value = currentState.copy(barcodeProcessing = true)

        viewModelScope.launch {
            try {
                val venueId = secureStorage.getVenueId() ?: run {
                    Timber.e("📷 [BarcodeQuickAdd] No venueId in secure storage")
                    updateStateIfSuccess { it.copy(
                        barcodeProcessing = false,
                        lastScannedProductName = "❌ Error: No venue ID"
                    )}
                    return@launch
                }

                // Search product by barcode
                val result = productRepository.getProductByBarcode(venueId, barcode)

                result.fold(
                    onSuccess = { product ->
                        if (product == null) {
                            // Product not found - Show Quick Add dialog
                            Timber.w("📷 [BarcodeQuickAdd] Product not found | barcode=$barcode")
                            updateStateIfSuccess { it.copy(
                                barcodeProcessing = false,
                                showQuickAddDialog = true,
                                scannedBarcodeForQuickAdd = barcode
                            )}
                        } else {
                            // Product found
                            Timber.d("📷 [BarcodeQuickAdd] Product found | id=${product.id} | name=${product.name}")

                            if (product.modifierGroups.isNotEmpty()) {
                                // Has modifiers - Close scanner and open modifier selector
                                Timber.d("📷 [BarcodeQuickAdd] Product has modifiers | count=${product.modifierGroups.size}")
                                // TODO: Integrate with ProductSelectorBottomSheet
                                // For now, just add without modifiers
                                addProductToOrder(product, currentState)
                            } else {
                                // No modifiers - Add directly
                                addProductToOrder(product, currentState)
                            }
                        }
                    },
                    onFailure = { error ->
                        Timber.e(error, "📷 [BarcodeQuickAdd] Error searching product")
                        updateStateIfSuccess { it.copy(
                            barcodeProcessing = false,
                            lastScannedProductName = "❌ Error: ${error.message}"
                        )}

                        // Clear error after 2s
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(2000)
                            updateStateIfSuccess { it.copy(lastScannedProductName = null) }
                        }
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "📷 [BarcodeQuickAdd] Unexpected error")
                updateStateIfSuccess { it.copy(
                    barcodeProcessing = false,
                    lastScannedProductName = "❌ Error inesperado"
                )}
            }
        }
    }

    /**
     * Adds product to order (helper for barcode scanning).
     */
    private suspend fun addProductToOrder(product: Product, currentState: MenuState.Success) {
        // Add item using existing addItem logic
        addItem(
            product = product,
            quantity = 1,
            modifiers = emptyList(),
            notes = ""
        )

        val newCount = currentState.totalScannedCount + 1

        updateStateIfSuccess { it.copy(
            barcodeProcessing = false,
            lastScannedProductName = "✓ ${product.name}",
            totalScannedCount = newCount
        )}

        Timber.d("📷 [BarcodeQuickAdd] Product added | total=$newCount")

        // Clear feedback after 2s
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            updateStateIfSuccess { it.copy(lastScannedProductName = null) }
        }
    }

    /**
     * Shows Quick Add Product dialog when barcode not found.
     * ✅ BARCODE QUICK ADD: User can create product on-the-fly from scanned barcode.
     */
    fun showQuickAddProductDialog(barcode: String) {
        updateStateIfSuccess { it.copy(
            showQuickAddDialog = true,
            scannedBarcodeForQuickAdd = barcode,
            barcodeProcessing = false
        )}
        Timber.d("📦 [QuickAdd] Dialog opened | barcode=$barcode")
    }

    /**
     * Dismisses Quick Add Product dialog.
     */
    fun dismissQuickAddDialog() {
        updateStateIfSuccess { it.copy(
            showQuickAddDialog = false,
            scannedBarcodeForQuickAdd = null
        )}
    }

    /**
     * Creates product from Quick Add dialog.
     * ✅ BARCODE QUICK ADD: POST /venues/{venueId}/products/quick-add
     *
     * Flow:
     * 1. Create product via backend API
     * 2. Close dialog
     * 3a. Product has modifiers → Open modifier selector
     * 3b. No modifiers → Add to order directly
     * 4. Refresh product catalog to include new product
     */
    fun createQuickAddProduct(
        name: String,
        price: Double,
        categoryId: String?,
        trackInventory: Boolean
    ) {
        val currentState = _state.value
        if (currentState !is MenuState.Success) return

        val barcode = currentState.scannedBarcodeForQuickAdd ?: return

        _state.value = currentState.copy(isCreatingProduct = true)

        viewModelScope.launch {
            try {
                val venueId = secureStorage.getVenueId() ?: run {
                    Timber.e("📦 [QuickAdd] No venueId in secure storage")
                    return@launch
                }

                // Call backend to create product
                val result = productRepository.createQuickAddProduct(
                    venueId = venueId,
                    barcode = barcode,
                    name = name,
                    price = price.toBigDecimal(),
                    categoryId = categoryId,
                    trackInventory = trackInventory
                )

                result.fold(
                    onSuccess = { product ->
                        Timber.d("📦 [QuickAdd] Product created | id=${product.id} | name=$name")

                        // Close dialog
                        updateStateIfSuccess { it.copy(
                            showQuickAddDialog = false,
                            scannedBarcodeForQuickAdd = null,
                            isCreatingProduct = false
                        )}

                        // Add product to order
                        if (product.modifierGroups.isNotEmpty()) {
                            // TODO: Open ProductSelectorBottomSheet
                            // For now, add without modifiers
                            addProductToOrder(product, currentState)
                        } else {
                            addProductToOrder(product, currentState)
                        }

                        // Refresh product catalog
                        refreshProducts()
                    },
                    onFailure = { error ->
                        Timber.e(error, "📦 [QuickAdd] Error creating product")
                        updateStateIfSuccess { it.copy(
                            isCreatingProduct = false,
                            lastScannedProductName = "❌ Error: ${error.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "📦 [QuickAdd] Unexpected error")
                updateStateIfSuccess { it.copy(
                    isCreatingProduct = false,
                    lastScannedProductName = "❌ Error inesperado"
                )}
            }
        }
    }

    /**
     * Helper to update state only if current state is Success.
     */
    private fun updateStateIfSuccess(update: (MenuState.Success) -> MenuState.Success) {
        val currentState = _state.value
        if (currentState is MenuState.Success) {
            _state.value = update(currentState)
        }
    }
}

/**
 * Menu screen state
 */
sealed interface MenuState {
    data object Loading : MenuState
    data class Success(
        val order: Order,
        val syncError: String? = null,  // ✅ FIX: Sync errors don't replace state, just show warning

        // ✨ BARCODE QUICK ADD: Scanner state (Square POS "Scan & Go" pattern)
        val showBarcodeScanner: Boolean = false,
        val barcodeProcessing: Boolean = false,
        val lastScannedProductName: String? = null,  // Feedback: "✓ Pizza Margherita"
        val totalScannedCount: Int = 0,

        // ✨ BARCODE QUICK ADD: Create product dialog state
        val showQuickAddDialog: Boolean = false,
        val scannedBarcodeForQuickAdd: String? = null,  // Barcode for new product
        val isCreatingProduct: Boolean = false
    ) : MenuState
    data class Error(val message: String) : MenuState
}
