package com.jaac.avoqado_tpv.features.kiosk.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskCartItem
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskCategory
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskProduct
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskStaffSession
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskState
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.data.MultiMerchantSDKManager
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
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
    private val customerRepository: CustomerRepository,
    private val secureStorage: SecureStorage,
    // 🔧 Blumon SDK Initialization - Required when entering Kiosk directly (bypassing HomeViewModel)
    private val initializationManager: InitializationManager,
    // 🏪 Get merchants from backend to use correct serial for SDK init
    private val getMerchantsUseCase: GetMerchantsUseCase,
    // 🥝 Multi-merchant SDK switching - For kiosk default merchant pre-initialization
    private val multiMerchantSDKManager: MultiMerchantSDKManager,
    private val tpvSettingsRepository: TpvSettingsRepository
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

    // 🥝 Staff session for sales attribution (commissions/tips)
    // - In-memory only (not persisted - each shift starts fresh)
    // - Card payments use staffSession.staffId automatically
    // - Cash payments always require PIN (for physical money confirmation)
    private val _staffSession = MutableStateFlow<KioskStaffSession?>(null)
    val staffSession: StateFlow<KioskStaffSession?> = _staffSession.asStateFlow()

    // 👥 Customer search state for KioskCustomerDialog
    private val _customerSearchResults = MutableStateFlow<List<Customer>>(emptyList())
    val customerSearchResults: StateFlow<List<Customer>> = _customerSearchResults.asStateFlow()

    private val _recentCustomers = MutableStateFlow<List<Customer>>(emptyList())
    val recentCustomers: StateFlow<List<Customer>> = _recentCustomers.asStateFlow()

    private val _isSearchingCustomers = MutableStateFlow(false)
    val isSearchingCustomers: StateFlow<Boolean> = _isSearchingCustomers.asStateFlow()

    private val _isCreatingCustomer = MutableStateFlow(false)
    val isCreatingCustomer: StateFlow<Boolean> = _isCreatingCustomer.asStateFlow()

    // Current order ID (set after order creation, before customer assignment)
    private var currentOrderId: String? = null

    // ==================== ORDER REUSE STATE (FIX: Avoid duplicate orders) ====================
    // These track the created order to prevent creating new orders when user goes back and returns
    // Bug: Previously, each "Pay Now" click created a NEW order, causing "cuentas por cobrar"

    // Created order ID (persists until payment success or explicit clear)
    private val _createdOrderId = MutableStateFlow<String?>(null)
    val createdOrderId: StateFlow<String?> = _createdOrderId.asStateFlow()

    // Created order number (for display)
    private val _createdOrderNumber = MutableStateFlow<String?>(null)
    val createdOrderNumber: StateFlow<String?> = _createdOrderNumber.asStateFlow()

    // Cart hash when order was created (to detect cart changes)
    private var cartHashWhenOrderCreated: Int = 0

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
     * **BLOCKS UI** until SDK is ready - shows loader to prevent user from going to payment
     * before SDK is initialized.
     *
     * **Why in KioskViewModel?**
     * - When user enters Kiosk mode directly (bypassing HomeScreen), HomeViewModel is not created
     * - Without this, SDK is not initialized → payments fail with "NO AUTORIZADO" (NA_002)
     * - This replicates the initialization that HomeViewModel does
     *
     * **Flow:**
     * 1. Set isBlumonInitializing = true (shows loader)
     * 2. Fetch merchants from backend (to get real serial numbers)
     * 3. Get kiosk default merchant from TpvSettings
     * 4. Initialize SDK with correct serial
     * 5. Switch to kiosk default merchant (OAuth + DUKPT key download)
     * 6. Set isBlumonReady = true (hides loader, enables payment)
     *
     * Benefits:
     * - User cannot proceed to payment until SDK is ready
     * - No "NO AUTORIZADO" errors from uninitialized SDK
     * - Clear feedback to user that system is preparing
     * - No 30+ second delay when customer taps "Pay" - SDK already switched
     */
    private fun initializeBlumonSDK() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 🔄 Start initialization - show loader
                _state.update { it.copy(isBlumonInitializing = true, isBlumonReady = false, blumonInitError = null) }

                Timber.i("🥝 [KIOSK-VM] Starting Blumon SDK initialization...")

                // Step 1: Fetch merchants from backend to get real serial numbers
                val merchants = getMerchantsUseCase().firstOrNull()
                if (merchants.isNullOrEmpty()) {
                    Timber.w("🥝 [KIOSK-VM] No merchants found - SDK init will use default serial")
                    // Still initialize basic SDK
                    initializationManager.ensureInitialized(defaultMerchantPosId = null)
                    _state.update { it.copy(isBlumonInitializing = false, isBlumonReady = true, blumonInitError = null) }
                    return@launch
                }

                // Step 2: Get kiosk default merchant from TpvSettings
                val tpvSettings = tpvSettingsRepository.getCurrentSettings()
                val kioskDefaultMerchantId = tpvSettings.kioskDefaultMerchantId

                // Step 3: Find the target merchant (kiosk default or first available)
                val targetMerchant = if (!kioskDefaultMerchantId.isNullOrBlank()) {
                    merchants.find { it.id == kioskDefaultMerchantId || it.merchantAccountId == kioskDefaultMerchantId }
                        ?: merchants.first().also {
                            Timber.w("🥝 [KIOSK-VM] Kiosk default merchant '$kioskDefaultMerchantId' not found, using first: ${it.displayName}")
                        }
                } else {
                    merchants.first().also {
                        Timber.d("🥝 [KIOSK-VM] No kiosk default merchant configured, using first: ${it.displayName}")
                    }
                }

                Timber.i("🥝 [KIOSK-VM] Target merchant for SDK init: ${targetMerchant.displayName} (${targetMerchant.serialNumber})")
                TerminalConfig.updateSerial(targetMerchant.serialNumber)

                // Step 4: Basic SDK initialization
                initializationManager.ensureInitialized(defaultMerchantPosId = targetMerchant.posId)
                    .onFailure { error ->
                        Timber.e(error, "🥝 [KIOSK-VM] ❌ Basic SDK initialization failed")
                        _state.update { it.copy(
                            isBlumonInitializing = false,
                            isBlumonReady = false,
                            blumonInitError = "Error al inicializar sistema de pagos. Intente reiniciar."
                        ) }
                        return@launch
                    }

                // Step 5: Switch to kiosk default merchant (OAuth + DUKPT key download)
                // This is the CRITICAL step that takes 3-30 seconds and was previously done at payment time
                if (multiMerchantSDKManager.isMerchantActive(targetMerchant)) {
                    Timber.i("🥝 [KIOSK-VM] ✅ SDK already on correct merchant: ${targetMerchant.displayName}")
                } else {
                    Timber.i("🥝 [KIOSK-VM] Switching SDK to kiosk merchant: ${targetMerchant.displayName}...")
                    val switchResult = multiMerchantSDKManager.switchMerchant(targetMerchant)

                    if (switchResult.isFailure) {
                        Timber.w(switchResult.exceptionOrNull(), "🥝 [KIOSK-VM] ⚠️ Merchant switch failed - payment flow will retry")
                        // Don't fail completely - payment flow can retry
                    } else {
                        Timber.i("🥝 [KIOSK-VM] ✅ SDK switched to: ${targetMerchant.displayName}")
                    }
                }

                // Step 6: Mark as ready
                Timber.i("🥝 [KIOSK-VM] ✅ Blumon SDK initialized and ready for kiosk payments")
                _state.update { it.copy(isBlumonInitializing = false, isBlumonReady = true, blumonInitError = null) }

            } catch (e: Exception) {
                Timber.e(e, "🥝 [KIOSK-VM] ❌ Unexpected error during Blumon SDK initialization")
                _state.update { it.copy(
                    isBlumonInitializing = false,
                    isBlumonReady = false,
                    blumonInitError = "Error inesperado: ${e.message}"
                ) }
            }
        }
    }

    /**
     * 🔄 Retry Blumon SDK initialization
     * Called from UI when user taps "Reintentar" after init failure
     */
    fun retryBlumonInit() {
        Timber.i("🥝 [KIOSK-VM] Retrying Blumon SDK initialization...")
        initializeBlumonSDK()
    }

    /**
     * Load products and categories from repository
     */
    fun loadProducts() {
        Timber.i("🥝 [KIOSK-VM] loadProducts() called - venueId: $venueId")
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            try {
                // Load categories (filter out inactive categories)
                val categoriesResult = productRepository.getCategories(venueId)
                val categories = categoriesResult.getOrNull()
                    ?.filter { it.isActive }  // Only show active categories
                    ?.map { cat ->
                        KioskCategory(
                            id = cat.id,
                            name = cat.name,
                            sortOrder = cat.displayOrder
                        )
                    }
                    ?.sortedBy { it.sortOrder } ?: emptyList()

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
     * Add product to cart (NO modifiers)
     *
     * Used for products without modifier groups.
     * For products WITH modifiers, use addToCartWithModifiers() instead.
     */
    fun addToCart(product: KioskProduct) {
        // 🚫 Prevent direct add for products with modifiers
        if (product.hasModifiers) {
            Timber.w("🥝 [KIOSK-VM] addToCart() called for product WITH modifiers - should use addToCartWithModifiers()")
            return
        }

        val existingItem = _cartItems.value.find { it.productId == product.id && !it.hasSelectedModifiers }
        val newQty = if (existingItem != null) existingItem.quantity + 1 else 1

        _cartItems.update { currentItems ->
            if (existingItem != null) {
                // Increase quantity
                currentItems.map {
                    if (it.productId == product.id && !it.hasSelectedModifiers) {
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
     * Add product to cart WITH selected modifiers
     *
     * Used when user selects modifiers from KioskModifierDialog.
     * Each unique combination of modifiers creates a separate cart item.
     *
     * @param product The product being added
     * @param selectedModifiers List of modifiers the user selected
     * @param unitPriceWithModifiers Total price (base price + modifier adjustments)
     */
    fun addToCartWithModifiers(
        product: KioskProduct,
        selectedModifiers: List<com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier>,
        unitPriceWithModifiers: BigDecimal
    ) {
        // For items with modifiers, always add as new item (different combinations = different items)
        // This follows Toast/Square pattern where each modifier selection is a unique line item
        _cartItems.update { currentItems ->
            currentItems + KioskCartItem(
                productId = product.id,
                productName = product.name,
                unitPrice = unitPriceWithModifiers,  // Includes modifier price adjustments
                quantity = 1,
                imageUrl = product.imageUrl,
                selectedModifiers = selectedModifiers
            )
        }

        val modifierNames = selectedModifiers.joinToString(", ") { it.name }
        Timber.i("🥝 [KIOSK-VM] addToCartWithModifiers() - product: ${product.name}, " +
                "modifiers: [$modifierNames], " +
                "price: $unitPriceWithModifiers, " +
                "cartSize: ${_cartItems.value.size}")
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
     *
     * Also clears any created order state since it's no longer valid
     */
    fun clearCart() {
        val previousSize = _cartItems.value.size
        _cartItems.value = emptyList()
        // 🔧 FIX: Also clear order state when cart is cleared (order no longer valid)
        clearCreatedOrder()
        Timber.i("🥝 [KIOSK-VM] clearCart() - cleared $previousSize items and order state")
    }

    /**
     * Set staff session for sales attribution
     *
     * Called when staff enters PIN in KioskStaffAssignDialog.
     * The staffId will be used for Card payments (processedById).
     *
     * @param session The staff session with staffId, name, initials, and role
     */
    fun setStaffSession(session: KioskStaffSession) {
        val previousStaff = _staffSession.value?.staffName
        _staffSession.value = session
        if (previousStaff != null) {
            Timber.i("🥝 [KIOSK-VM] Staff session CHANGED: $previousStaff → ${session.staffName} (${session.role})")
        } else {
            Timber.i("🥝 [KIOSK-VM] Staff session SET: ${session.staffName} (${session.role})")
        }
    }

    /**
     * Clear staff session
     *
     * Typically not used (staff changes by entering new PIN).
     * Provided for completeness if needed for logout/reset scenarios.
     */
    fun clearStaffSession() {
        val previousStaff = _staffSession.value?.staffName
        _staffSession.value = null
        Timber.i("🥝 [KIOSK-VM] Staff session CLEARED (was: ${previousStaff ?: "none"})")
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
            // 🥝 KIOSK source: Abandoned orders are excluded from pay-later/open orders lists
            val createResult = orderRepository.createOrder(
                venueId = venueId,
                tableId = null,  // TAKEOUT - no table
                covers = 1,
                waiterId = null,  // Self-service - no waiter
                orderType = OrderType.TAKEOUT,
                source = "KIOSK"  // 🔑 Mark as KIOSK to exclude from "cuentas por cobrar" if abandoned
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
                    // 🔧 Include modifier IDs if any were selected
                    modifierIds = cartItem.selectedModifiers
                        .map { it.id }
                        .ifEmpty { null }  // null if no modifiers selected
                )
            }

            // 🔍 Log each item being added for debugging
            addItemRequests.forEachIndexed { index, item ->
                val modifiersInfo = item.modifierIds?.joinToString(",") ?: "none"
                Timber.d("🥝 [KIOSK-VM] Item[$index]: productId=${item.productId}, qty=${item.quantity}, modifiers=[$modifiersInfo]")
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
            // Save order ID for customer assignment
            currentOrderId = order.id

            // 🔧 FIX: Save order for reuse (prevents duplicate orders on back navigation)
            _createdOrderId.value = order.id
            _createdOrderNumber.value = order.orderNumber
            cartHashWhenOrderCreated = computeCartHash()
            Timber.i("🥝 [KIOSK-VM] Order saved for reuse (cartHash=$cartHashWhenOrderCreated)")

            Pair(order.id, order.orderNumber)
        } catch (e: Exception) {
            Timber.e(e, "🥝 [KIOSK-VM] createOrder() EXCEPTION")
            null
        }
    }

    /**
     * Get existing order or create new one
     *
     * **FIX for duplicate orders bug:**
     * - If order exists AND cart hasn't changed → reuse existing order
     * - If order exists AND cart changed → create new order (old one becomes orphan but better than duplicate)
     * - If no order exists → create new order
     *
     * @return Pair(orderId, orderNumber) or null if failed
     */
    suspend fun getOrCreateOrder(): Pair<String, String>? {
        val existingOrderId = _createdOrderId.value
        val existingOrderNumber = _createdOrderNumber.value
        val currentCartHash = computeCartHash()

        // Case 1: Order exists and cart hasn't changed → reuse
        if (existingOrderId != null && existingOrderNumber != null && currentCartHash == cartHashWhenOrderCreated) {
            Timber.i("🥝 [KIOSK-VM] getOrCreateOrder() - REUSING existing order (id=$existingOrderId, cartHash unchanged)")
            currentOrderId = existingOrderId  // Ensure currentOrderId is set for customer assignment
            return Pair(existingOrderId, existingOrderNumber)
        }

        // Case 2: Order exists but cart changed → log warning and create new
        if (existingOrderId != null) {
            Timber.w("🥝 [KIOSK-VM] getOrCreateOrder() - Cart changed since order creation! Creating NEW order (old order $existingOrderId may become orphan)")
            // Note: The old order will remain in backend as unpaid. In the future, we could add logic to cancel/void it.
        }

        // Case 3: Create new order
        return createOrder()
    }

    /**
     * Clear the created order state
     *
     * Called after:
     * - Payment SUCCESS (order is complete, no need to track)
     * - User explicitly cancels order flow
     * - Cart is cleared
     */
    fun clearCreatedOrder() {
        val orderId = _createdOrderId.value
        _createdOrderId.value = null
        _createdOrderNumber.value = null
        cartHashWhenOrderCreated = 0
        Timber.i("🥝 [KIOSK-VM] clearCreatedOrder() - Cleared order state (was: $orderId)")
    }

    /**
     * Check if we should show the customer dialog or go directly to payment
     *
     * @return true if we have a valid order ready for payment
     */
    fun hasValidOrder(): Boolean {
        return _createdOrderId.value != null && _createdOrderNumber.value != null
    }

    /**
     * Compute a hash of the current cart contents
     * Used to detect if cart has changed since order was created
     */
    private fun computeCartHash(): Int {
        val items = _cartItems.value
        return items.sumOf { item ->
            // Hash based on product ID, quantity, and modifiers
            val modifiersHash = item.selectedModifiers.sumOf { it.id.hashCode() }
            item.productId.hashCode() * 31 + item.quantity * 17 + modifiersHash
        }
    }

    // ==================== CUSTOMER FUNCTIONS ====================

    /**
     * Load recent customers for quick selection in KioskCustomerDialog
     */
    fun loadRecentCustomers() {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("👥 [KIOSK-VM] loadRecentCustomers() - venueId: $venueId")
            try {
                customerRepository.getRecentCustomers(venueId, limit = 5)
                    .onSuccess { customers ->
                        _recentCustomers.value = customers
                        Timber.i("👥 [KIOSK-VM] Loaded ${customers.size} recent customers")
                    }
                    .onFailure { error ->
                        Timber.e(error, "👥 [KIOSK-VM] Failed to load recent customers")
                        _recentCustomers.value = emptyList()
                    }
            } catch (e: Exception) {
                Timber.e(e, "👥 [KIOSK-VM] Exception loading recent customers")
                _recentCustomers.value = emptyList()
            }
        }
    }

    /**
     * Search customers by name or email
     * Called with debounce (300ms) from KioskCustomerDialog
     */
    fun searchCustomers(query: String) {
        if (query.length < 2) {
            _customerSearchResults.value = emptyList()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("👥 [KIOSK-VM] searchCustomers() - query: '$query'")
            _isSearchingCustomers.value = true
            try {
                customerRepository.searchCustomers(venueId, query, limit = 5)
                    .onSuccess { customers ->
                        _customerSearchResults.value = customers
                        Timber.i("👥 [KIOSK-VM] Search found ${customers.size} customers for '$query'")
                    }
                    .onFailure { error ->
                        Timber.e(error, "👥 [KIOSK-VM] Search failed for '$query'")
                        _customerSearchResults.value = emptyList()
                    }
            } catch (e: Exception) {
                Timber.e(e, "👥 [KIOSK-VM] Exception searching customers")
                _customerSearchResults.value = emptyList()
            } finally {
                _isSearchingCustomers.value = false
            }
        }
    }

    /**
     * Add existing customer to current order
     */
    fun addCustomerToCurrentOrder(customer: Customer, onComplete: (Boolean) -> Unit) {
        val orderId = currentOrderId
        if (orderId == null) {
            Timber.e("👥 [KIOSK-VM] addCustomerToCurrentOrder() - No current order ID!")
            onComplete(false)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            Timber.i("👥 [KIOSK-VM] addCustomerToCurrentOrder() - orderId: $orderId, customerId: ${customer.id}")
            try {
                orderRepository.addCustomerToOrder(venueId, orderId, customer.id)
                    .onSuccess {
                        Timber.i("👥 [KIOSK-VM] ✅ Customer ${customer.displayName} added to order $orderId")
                        launch(Dispatchers.Main) { onComplete(true) }
                    }
                    .onFailure { error ->
                        Timber.e(error, "👥 [KIOSK-VM] ❌ Failed to add customer to order")
                        launch(Dispatchers.Main) { onComplete(false) }
                    }
            } catch (e: Exception) {
                Timber.e(e, "👥 [KIOSK-VM] Exception adding customer to order")
                launch(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    /**
     * Create new customer and add to current order
     */
    fun createAndAddCustomerToOrder(name: String?, email: String, onComplete: (Boolean) -> Unit) {
        val orderId = currentOrderId
        if (orderId == null) {
            Timber.e("👥 [KIOSK-VM] createAndAddCustomerToOrder() - No current order ID!")
            onComplete(false)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            Timber.i("👥 [KIOSK-VM] createAndAddCustomerToOrder() - orderId: $orderId, name: $name, email: $email")
            _isCreatingCustomer.value = true
            try {
                orderRepository.createAndAddCustomerToOrder(
                    venueId = venueId,
                    orderId = orderId,
                    firstName = name,
                    phone = null,
                    email = email
                )
                    .onSuccess {
                        Timber.i("👥 [KIOSK-VM] ✅ Customer created and added to order $orderId")
                        launch(Dispatchers.Main) { onComplete(true) }
                    }
                    .onFailure { error ->
                        Timber.e(error, "👥 [KIOSK-VM] ❌ Failed to create/add customer")
                        launch(Dispatchers.Main) { onComplete(false) }
                    }
            } catch (e: Exception) {
                Timber.e(e, "👥 [KIOSK-VM] Exception creating customer")
                launch(Dispatchers.Main) { onComplete(false) }
            } finally {
                _isCreatingCustomer.value = false
            }
        }
    }

    /**
     * Clear customer state (called when resetting the cart)
     */
    fun clearCustomerState() {
        _customerSearchResults.value = emptyList()
        _recentCustomers.value = emptyList()
        _isSearchingCustomers.value = false
        _isCreatingCustomer.value = false
        currentOrderId = null
        Timber.d("👥 [KIOSK-VM] Customer state cleared")
    }

    // ==================== HELPER FUNCTIONS ====================

    /**
     * Convert domain Product to KioskProduct
     *
     * Note: Backend returns prices in pesos (78.00 = $78.00)
     * Includes modifierGroups for products that have customization options
     */
    private fun Product.toKioskProduct(): KioskProduct {
        return KioskProduct(
            id = this.id,
            name = this.name,
            price = this.price,
            categoryId = this.categoryId,
            imageUrl = this.imageUrl,
            isAvailable = this.available,
            modifierGroups = this.modifierGroups  // 🔧 Include modifiers for kiosk selection
        )
    }
}
