package com.jaac.avoqado_tpv.features.kiosk.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskCartItem
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskCategory
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskProduct
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskState
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Kiosk ViewModel - Cart and Product Management
 *
 * Manages:
 * - Product loading from ProductRepository
 * - In-memory cart (not persisted)
 * - Category filtering
 * - Order creation for payment
 * - Blumon SDK initialization (for direct kiosk entry)
 *
 * Scoped to navigation graph for proper lifecycle.
 */
@HiltViewModel
class KioskViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val orderRepository: OrderRepository,
    private val secureStorage: SecureStorage,
    // 🔧 Blumon SDK Initialization - Required when entering Kiosk directly (bypassing HomeViewModel)
    private val initializationManager: InitializationManager,
    // 🏪 Get merchants from backend to use correct serial for SDK init
    private val getMerchantsUseCase: GetMerchantsUseCase
) : ViewModel() {

    // UI State
    private val _state = MutableStateFlow(KioskState())
    val state: StateFlow<KioskState> = _state.asStateFlow()

    // Cart items (in-memory only)
    private val _cartItems = MutableStateFlow<List<KioskCartItem>>(emptyList())
    val cartItems: StateFlow<List<KioskCartItem>> = _cartItems.asStateFlow()

    // Cart item count for FAB badge
    // Uses stateIn with Eagerly to maintain same behavior as before, but without memory leak
    val cartItemCount: StateFlow<Int> = _cartItems
        .map { items -> items.sumOf { it.quantity } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Cart total
    // Uses stateIn with Eagerly to maintain same behavior as before, but without memory leak
    val cartTotal: StateFlow<BigDecimal> = _cartItems
        .map { items -> items.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.lineTotal) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BigDecimal.ZERO)

    // 🥝 KIOSK: Prioritize kioskVenueId (configured separately for kiosk mode)
    // Falls back to regular venueId for backwards compatibility
    private val venueId: String
        get() = secureStorage.getKioskVenueId() ?: secureStorage.getVenueId() ?: ""

    init {
        // 🔧 Initialize Blumon SDK in background (so it's ready when user goes to payment)
        // CRITICAL: This is needed when user enters Kiosk directly without going through HomeScreen
        // (HomeViewModel does this initialization, but it's not created if user goes straight to Kiosk)
        initializeBlumonSDK()
    }

    /**
     * 🔧 Initialize Blumon SDK after entering Kiosk mode
     *
     * Starts SDK initialization in background so it's ready when user completes order and goes to payment.
     * Uses 3 second delay to let other operations settle first.
     *
     * **Why in KioskViewModel?**
     * - When user enters Kiosk mode directly (bypassing HomeScreen), HomeViewModel is not created
     * - Without this, SDK is not initialized → payments fail with "NO AUTORIZADO" (NA_002)
     * - This replicates the initialization that HomeViewModel does
     *
     * **Flow:**
     * 1. Wait 3 seconds for other operations to settle
     * 2. Fetch merchants from backend (to get real serial numbers)
     * 3. Use first merchant's serial for TerminalConfig
     * 4. Initialize SDK with correct serial
     *
     * Benefits:
     * - SDK ready before user completes order (no loading delay)
     * - OAuth + DUKPT keys downloaded in advance
     * - Uses real merchant serial (not hardcoded default)
     * - If initialization fails, payment screen will retry (graceful fallback)
     */
    private fun initializeBlumonSDK() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ⏳ Wait 3 seconds for other operations to settle
                // This prevents resource contention that causes GenericFailure
                delay(3000)

                Timber.i("🥝 [KIOSK-VM] Starting Blumon SDK initialization...")

                // Step 1: Fetch merchants from backend to get real serial numbers
                val merchants = getMerchantsUseCase().firstOrNull()
                val defaultMerchant = if (merchants.isNullOrEmpty()) {
                    Timber.w("🥝 [KIOSK-VM] No merchants found - SDK init will use default serial")
                    null
                } else {
                    // Step 2: Use first merchant's serial for TerminalConfig
                    val merchant = merchants.first()
                    Timber.i("🥝 [KIOSK-VM] Using merchant for SDK init: ${merchant.displayName} (${merchant.serialNumber})")
                    TerminalConfig.updateSerial(merchant.serialNumber)
                    merchant
                }

                // Step 3: Initialize SDK with correct serial AND posId
                // CRITICAL: Pass posId to handle app restart after merchant switch
                // Without this, SDK database has stale posId → "NO AUTORIZADO" on first payment
                initializationManager.ensureInitialized(defaultMerchantPosId = defaultMerchant?.posId)
                    .onSuccess {
                        Timber.i("🥝 [KIOSK-VM] ✅ Blumon SDK initialized successfully - ready for payments")
                    }
                    .onFailure { error ->
                        Timber.w(error, "🥝 [KIOSK-VM] ⚠️ Blumon SDK initialization failed - will retry when opening payment")
                        // Don't block kiosk flow - payment screen will retry if needed
                    }

            } catch (e: Exception) {
                Timber.e(e, "🥝 [KIOSK-VM] ❌ Unexpected error during Blumon SDK initialization")
                // Don't block kiosk flow - app can work, payment will retry
            }
        }
    }

    /**
     * Load products and categories from repository
     */
    fun loadProducts() {
        Timber.i("🥝 [KIOSK-VM] loadProducts() called - venueId: $venueId")
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            try {
                // Load categories
                val categoriesResult = productRepository.getCategories(venueId)
                val categories = categoriesResult.getOrNull()?.map { cat ->
                    KioskCategory(
                        id = cat.id,
                        name = cat.name,
                        sortOrder = cat.displayOrder
                    )
                }?.sortedBy { it.sortOrder } ?: emptyList()

                // Load products
                val productsResult = productRepository.getProducts(venueId)
                val products = productsResult.getOrNull()?.map { product ->
                    product.toKioskProduct()
                } ?: emptyList()

                _state.update {
                    it.copy(
                        isLoading = false,
                        products = products,
                        categories = categories
                    )
                }

                Timber.i("🥝 [KIOSK-VM] Products loaded successfully - ${products.size} products, ${categories.size} categories")

            } catch (e: Exception) {
                Timber.e(e, "🥝 [KIOSK-VM] Failed to load products")
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Error al cargar productos"
                    )
                }
            }
        }
    }

    /**
     * Select category for filtering
     */
    fun selectCategory(categoryId: String?) {
        Timber.d("🥝 [KIOSK-VM] selectCategory() - categoryId: ${categoryId ?: "ALL"}")
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    /**
     * Add product to cart
     */
    fun addToCart(product: KioskProduct) {
        val existingItem = _cartItems.value.find { it.productId == product.id }
        val newQty = if (existingItem != null) existingItem.quantity + 1 else 1

        _cartItems.update { currentItems ->
            if (existingItem != null) {
                // Increase quantity
                currentItems.map {
                    if (it.productId == product.id) {
                        it.copy(quantity = it.quantity + 1)
                    } else {
                        it
                    }
                }
            } else {
                // Add new item
                currentItems + KioskCartItem(
                    productId = product.id,
                    productName = product.name,
                    unitPrice = product.price,
                    imageUrl = product.imageUrl
                )
            }
        }
        Timber.i("🥝 [KIOSK-VM] addToCart() - product: ${product.name}, newQty: $newQty, cartSize: ${_cartItems.value.size}")
    }

    /**
     * Increase item quantity
     */
    fun increaseQuantity(productId: String) {
        _cartItems.update { items ->
            val item = items.find { it.productId == productId }
            val newQty = (item?.quantity ?: 0) + 1
            Timber.d("🥝 [KIOSK-VM] increaseQuantity() - product: ${item?.productName}, qty: ${item?.quantity} → $newQty")
            items.map {
                if (it.productId == productId) {
                    it.copy(quantity = it.quantity + 1)
                } else {
                    it
                }
            }
        }
    }

    /**
     * Decrease item quantity (removes if quantity becomes 0)
     */
    fun decreaseQuantity(productId: String) {
        _cartItems.update { items ->
            val item = items.find { it.productId == productId }
            val newQty = (item?.quantity ?: 0) - 1
            if (newQty <= 0) {
                Timber.d("🥝 [KIOSK-VM] decreaseQuantity() - REMOVING product: ${item?.productName} (qty was ${item?.quantity})")
            } else {
                Timber.d("🥝 [KIOSK-VM] decreaseQuantity() - product: ${item?.productName}, qty: ${item?.quantity} → $newQty")
            }
            items.mapNotNull {
                if (it.productId == productId) {
                    if (it.quantity > 1) {
                        it.copy(quantity = it.quantity - 1)
                    } else {
                        null // Remove item
                    }
                } else {
                    it
                }
            }
        }
    }

    /**
     * Clear entire cart
     */
    fun clearCart() {
        val previousSize = _cartItems.value.size
        _cartItems.value = emptyList()
        Timber.i("🥝 [KIOSK-VM] clearCart() - cleared $previousSize items")
    }

    /**
     * Create order from cart for payment
     *
     * Uses OrderRepository to:
     * 1. Create empty order (backend generates ID and orderNumber)
     * 2. Add items to the order
     *
     * @return Pair(orderId, orderNumber) or null if failed
     */
    suspend fun createOrder(): Pair<String, String>? {
        Timber.i("🥝 [KIOSK-VM] createOrder() called")
        return try {
            val items = _cartItems.value
            if (items.isEmpty()) {
                Timber.w("🥝 [KIOSK-VM] createOrder() FAILED - cart is empty")
                return null
            }

            Timber.d("🥝 [KIOSK-VM] createOrder() - ${items.size} items, venueId: $venueId")

            // Step 1: Create empty order via backend
            val createResult = orderRepository.createOrder(
                venueId = venueId,
                tableId = null,  // TAKEOUT - no table
                covers = 1,
                waiterId = null,  // Self-service - no waiter
                orderType = OrderType.TAKEOUT
            )

            // Extract order from result
            val order = createResult.getOrNull()
            if (order == null) {
                Timber.e("🥝 [KIOSK-VM] createOrder() FAILED - backend returned null")
                return null
            }

            Timber.i("🥝 [KIOSK-VM] Order created by backend - orderId: ${order.id}, orderNumber: ${order.orderNumber}")

            // Step 2: Add items to the order
            val addItemRequests = items.map { cartItem ->
                AddOrderItemRequest(
                    productId = cartItem.productId,
                    quantity = cartItem.quantity,
                    notes = null,
                    modifierIds = null
                )
            }

            // 🔍 Log each item being added for debugging
            addItemRequests.forEachIndexed { index, item ->
                Timber.d("🥝 [KIOSK-VM] Item[$index]: productId=${item.productId}, qty=${item.quantity}")
            }
            Timber.i("🥝 [KIOSK-VM] Adding ${addItemRequests.size} items to order ${order.id} (venueId: $venueId, version: ${order.version})...")

            val addItemsResult = orderRepository.addItemsToOrder(
                venueId = venueId,
                orderId = order.id,
                items = addItemRequests,
                currentVersion = order.version
            )

            // 🔴 CRITICAL: If items fail to add, DO NOT proceed to payment!
            // The order would have $0 total and the payment would be orphaned
            val updatedOrder = addItemsResult.getOrNull()
            if (updatedOrder == null) {
                val errorMessage = addItemsResult.exceptionOrNull()?.message ?: "Unknown error"
                Timber.e("🥝 [KIOSK-VM] ❌ addItemsToOrder() FAILED - error: $errorMessage")
                Timber.e("🥝 [KIOSK-VM] ❌ Order ${order.id} was created but has NO items - aborting payment flow!")
                // TODO: Consider deleting/voiding the empty order here
                return null  // 🔴 FIX: Return null to prevent payment with empty order
            }

            Timber.i("🥝 [KIOSK-VM] ✅ createOrder() SUCCESS - orderId: ${order.id}, orderNumber: ${order.orderNumber}, items: ${updatedOrder.items.size}, total: ${updatedOrder.total}")
            Pair(order.id, order.orderNumber)
        } catch (e: Exception) {
            Timber.e(e, "🥝 [KIOSK-VM] createOrder() EXCEPTION")
            null
        }
    }

    /**
     * Convert domain Product to KioskProduct
     *
     * Note: Backend returns prices in pesos (78.00 = $78.00)
     */
    private fun Product.toKioskProduct(): KioskProduct {
        return KioskProduct(
            id = this.id,
            name = this.name,
            price = this.price,
            categoryId = this.categoryId,
            imageUrl = this.imageUrl,
            isAvailable = this.available
        )
    }
}
