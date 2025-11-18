package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val secureStorage: SecureStorage,
    private val deviceInfoManager: DeviceInfoManager,
    private val productRepository: com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository,
    private val orderRepository: com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository,
    private val socketManager: com.jaac.avoqado_tpv.core.data.realtime.SocketManager
) : ViewModel() {

    private val _state = MutableStateFlow<MenuState>(MenuState.Loading)
    val state: StateFlow<MenuState> = _state.asStateFlow()

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _categories = MutableStateFlow<List<com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory>>(emptyList())
    val categories: StateFlow<List<com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory>> = _categories.asStateFlow()

    init {
        // Load products on ViewModel creation
        loadProducts()

        // Listen to Socket.IO events for real-time inventory updates
        listenToSocketEvents()
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
     * **Pattern (Toast POS / Square POS):**
     * - Optimistic update: Show change immediately in UI
     * - Backend emits event: All terminals receive update
     * - Reload data: Sync with server state (inventory, prices, etc.)
     */
    private fun listenToSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent.OrderUpdated -> {
                        Timber.i("🔄 [MenuViewModel] Order updated - reloading products to sync inventory")
                        Timber.d("   OrderId: ${event.orderId} | Items: ${event.items?.size ?: 0} | Total: ${event.total}")

                        // Reload products to get updated inventory counts
                        loadProducts()
                    }
                    else -> {
                        // Ignore other events
                    }
                }
            }
        }
    }

    /**
     * Load products from backend
     *
     * Fetches all products and categories for the venue.
     * Products are stored in StateFlow for reactive UI updates.
     */
    private fun loadProducts() {
        viewModelScope.launch {
            try {
                val venueId = deviceInfoManager.getVenueId()
                if (venueId == null) {
                    Timber.e("❌ Cannot load products: venueId is null")
                    return@launch
                }

                Timber.d("📦 Loading products for venue: $venueId")

                // Load products from backend
                productRepository.getProducts(venueId).fold(
                    onSuccess = { products ->
                        _products.value = products
                        Timber.i("✅ Loaded ${products.size} products from backend")
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Failed to load products")
                        // Keep empty list, will show error in UI
                        _products.value = emptyList()
                    }
                )

                // Load categories from backend
                productRepository.getCategories(venueId).fold(
                    onSuccess = { categories ->
                        // Add "All" category at the beginning
                        _categories.value = listOf(com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory.ALL) + categories
                        Timber.i("✅ Loaded ${categories.size} categories from backend")
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ Failed to load categories")
                        // Keep just "All" category
                        _categories.value = listOf(com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory.ALL)
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ Error loading products")
                _products.value = emptyList()
                _categories.value = listOf(com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory.ALL)
            }
        }
    }

    /**
     * Refresh products from backend (public API for MenuScreen lifecycle)
     *
     * Called when:
     * - Screen is resumed after navigation (e.g., navigating between screens)
     * - User manually triggers refresh
     *
     * This ensures inventory is always up-to-date when screen becomes visible.
     */
    fun refreshProducts() {
        loadProducts()
    }

    /**
     * Load order for a table or create quick order.
     *
     * **Toast/Square Pattern**: Each order = fresh ViewModel instance
     * No need to check if order is paid - navigation ensures fresh state
     *
     * Supports two modes:
     * 1. CREATE_QUICK_ORDER: Creates new TAKEOUT order via backend (gets CUID)
     * 2. Existing orderId (CUID): Fetches existing order from backend
     *
     * @param orderId "CREATE_QUICK_ORDER" for new quick orders, or existing CUID
     */
    fun loadOrder(orderId: String) {
        viewModelScope.launch {
            try {
                // 🔄 Toast/Square Pattern: Don't reload if order already loaded
                // Each MenuScreen instance = one order (no state reuse)
                val currentState = _state.value
                if (currentState is MenuState.Success) {
                    Timber.d("📋 Order already loaded: ${currentState.order.id} - Skipping loadOrder()")
                    return@launch
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
                    // Create new quick order
                    orderId == "CREATE_QUICK_ORDER" -> {
                        Timber.d("🆕 Creating new quick order (TAKEOUT)")

                        orderRepository.createOrder(
                            venueId = venueId,
                            tableId = null,  // No table for quick orders
                            covers = 1,  // Quick order = 1 person
                            waiterId = waiterId,
                            orderType = OrderType.TAKEOUT
                        ).getOrElse { error ->
                            Timber.e(error, "❌ Failed to create quick order")
                            _state.value = MenuState.Error("Error creando orden: ${error.message}")
                            return@launch
                        }
                    }

                    // Load existing order
                    else -> {
                        Timber.d("📋 Loading existing order: $orderId")

                        orderRepository.getOrder(
                            venueId = venueId,
                            orderId = orderId
                        ).getOrElse { error ->
                            Timber.e(error, "❌ Failed to load order")
                            _state.value = MenuState.Error("Error cargando orden: ${error.message}")
                            return@launch
                        }
                    }
                }

                _state.value = MenuState.Success(order)
                Timber.i("✅ Order ready | id=${order.id} | number=${order.orderNumber} | type=${order.orderType}")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error in loadOrder")
                _state.value = MenuState.Error("Error: ${e.message}")
            }
        }
    }

    /**
     * Add item to order
     *
     * **Flow:**
     * 1. Add item to local state for immediate UI feedback (optimistic update)
     * 2. Call backend API to persist item
     * 3. Update state with backend response (contains new version + CUID for item)
     * 4. On error, revert to previous state
     *
     * **Optimistic Concurrency Control:**
     * - Sends current order.version to backend
     * - If version mismatch (409 Conflict), shows error and refetches order
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

            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ Cannot add item: venueId is null")
                _state.value = MenuState.Error("Error: No se encontró el ID del local")
                return@launch
            }

            try {
                val order = currentState.order

                // ✅ STEP 1: Add to local state (optimistic update for instant UI feedback)
                val modifiersTotal = modifiers.sumOf { it.priceAdjustment }
                val unitPrice = product.price + modifiersTotal

                val tempLocalItem = OrderItem(
                    id = "temp_${UUID.randomUUID()}", // Temporary ID until backend assigns CUID
                    orderId = order.id,
                    productId = product.id,
                    productName = product.name,
                    productSku = product.sku,
                    quantity = quantity,
                    unitPrice = unitPrice,
                    totalPrice = unitPrice * BigDecimal(quantity.toString()),
                    modifiers = modifiers,
                    notes = notes.ifBlank { null },
                    kitchenStatus = KitchenStatus.PENDING,
                    createdAt = Instant.now(),
                    sentToKitchenAt = null
                )

                val optimisticOrder = recalculateOrder(order.copy(items = order.items + tempLocalItem))
                _state.value = MenuState.Success(optimisticOrder)

                Timber.d("🛒 [Optimistic] Item added to UI: ${product.name} x$quantity")

                // ✅ STEP 2: Persist to backend
                val backendRequest = AddOrderItemRequest(
                    productId = product.id,
                    quantity = quantity,
                    notes = notes.ifBlank { null }
                )

                val result = orderRepository.addItemsToOrder(
                    venueId = venueId,
                    orderId = order.id,
                    items = listOf(backendRequest),
                    currentVersion = order.version
                )

                // ✅ STEP 3: Update state with backend response (contains CUID + new version)
                result.fold(
                    onSuccess = { updatedOrder ->
                        _state.value = MenuState.Success(updatedOrder)
                        Timber.i("✅ [Backend] Item persisted successfully | version=${order.version} → ${updatedOrder.version} | items=${updatedOrder.items.size}")
                    },
                    onFailure = { error ->
                        Timber.e(error, "❌ [Backend] Failed to persist item")

                        // ✅ STEP 4: Revert to previous state on error
                        _state.value = MenuState.Success(order)

                        // Show user-friendly error
                        val errorMessage = when {
                            error.message?.contains("409") == true -> {
                                "La orden fue modificada por otra terminal.\n\n" +
                                "Por favor, intenta agregar el producto nuevamente."
                            }
                            error.message?.contains("404") == true -> {
                                "Orden no encontrada.\n\n" +
                                "Por favor, crea una nueva orden."
                            }
                            else -> {
                                "Error agregando producto.\n\n" +
                                "Verifica tu conexión e intenta nuevamente."
                            }
                        }

                        _state.value = MenuState.Error(errorMessage)
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ Error in addItem")
                _state.value = MenuState.Error("Error agregando producto: ${e.message}")
            }
        }
    }

    /**
     * Update item quantity
     */
    fun updateItemQuantity(item: OrderItem, newQuantity: Int) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) return@launch

            try {
                val order = currentState.order

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
                _state.value = MenuState.Success(updatedOrder)
                Timber.d("✏️ Item quantity updated: ${item.productName} → $newQuantity")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error updating item quantity")
            }
        }
    }

    /**
     * Remove item from order
     */
    fun removeItem(item: OrderItem) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) return@launch

            try {
                val order = currentState.order
                val updatedItems = order.items.filter { it.id != item.id }
                val updatedOrder = recalculateOrder(order.copy(items = updatedItems))

                _state.value = MenuState.Success(updatedOrder)
                Timber.d("🗑️ Item removed: ${item.productName}")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error removing item")
            }
        }
    }

    /**
     * Send order to kitchen
     * TODO: Call backend API in Phase 8
     */
    fun sendToKitchen() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MenuState.Success) return@launch

            try {
                val order = currentState.order

                if (!order.canSendToKitchen) {
                    Timber.w("⚠️ Cannot send order to kitchen: ${order.id}")
                    return@launch
                }

                // Mark items as sent to kitchen
                val updatedItems = order.items.map {
                    it.copy(
                        kitchenStatus = KitchenStatus.PREPARING,
                        sentToKitchenAt = Instant.now()
                    )
                }

                val updatedOrder = order.copy(
                    items = updatedItems,
                    kitchenStatus = KitchenStatus.PREPARING,
                    status = OrderStatus.IN_PROGRESS,
                    updatedAt = Instant.now()
                )

                _state.value = MenuState.Success(updatedOrder)
                Timber.d("🍳 Order sent to kitchen: ${order.id}")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error sending order to kitchen")
            }
        }
    }

    // ============================================================================
    // Private Helpers
    // ============================================================================

    /**
     * Recalculate order totals
     * Subtotal = sum of all item totals
     * Tax = subtotal * 0.16 (Mexico VAT)
     * Total = subtotal + tax
     */
    private fun recalculateOrder(order: Order): Order {
        val subtotal = order.items.sumOf { it.totalPrice }
        val tax = subtotal * BigDecimal("0.16")  // 16% IVA
        val total = subtotal + tax

        return order.copy(
            subtotal = subtotal,
            tax = tax,
            total = total,
            updatedAt = Instant.now()
        )
    }
}

/**
 * Menu screen state
 */
sealed interface MenuState {
    data object Loading : MenuState
    data class Success(val order: Order) : MenuState
    data class Error(val message: String) : MenuState
}
