package com.jaac.avoqado_tpv.features.checkout.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.checkout.data.ActiveCartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut
import com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload
import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedCart
import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedCartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedDiscount
import com.jaac.avoqado_tpv.features.checkout.domain.model.SelectedModifier
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.ordering.domain.TpvCreateOrderWithItemsItem
import com.jaac.avoqado_tpv.features.ordering.domain.TpvCreateOrderWithItemsRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

/**
 * State machine for the unified Checkout screen.
 *
 * Phase 1 scope: in-memory cart manipulation, product cache, saved carts, and
 * a bridge to [ActiveCartState] so the home screen knows there's an active
 * cart. Customer + staff selection arrive in Phase 4. Order creation and
 * `PaymentFlowOrigin` routing arrive in Phase 5.
 */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val customerRepository: CustomerRepository,
    private val orderRepository: OrderRepository,
    private val discountRepository: DiscountRepository,
    private val savedCartsRepository: SavedCartsRepository,
    private val mosaicRepository: MosaicRepository,
    private val activeCartState: ActiveCartState,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _cartState = MutableStateFlow(
        // Pre-seed the staff who logged in so the cart panel shows a real name
        // instead of "Staff" placeholder. PIN-based reassignment is out of
        // scope for this ola.
        CartState(
            selectedStaffId = secureStorage.getStaffId().orEmpty(),
            selectedStaffName = secureStorage.getStaffName().orEmpty().ifBlank { "Staff" },
        ),
    )
    val cartState: StateFlow<CartState> = _cartState.asStateFlow()

    private val _selectedCustomer = MutableStateFlow<Customer?>(null)
    val selectedCustomer: StateFlow<Customer?> = _selectedCustomer.asStateFlow()

    private val _customerSearchQuery = MutableStateFlow("")
    val customerSearchQuery: StateFlow<String> = _customerSearchQuery.asStateFlow()

    private val _customerResults = MutableStateFlow<List<Customer>>(emptyList())
    val customerResults: StateFlow<List<Customer>> = _customerResults.asStateFlow()

    private val _isSearchingCustomers = MutableStateFlow(false)
    val isSearchingCustomers: StateFlow<Boolean> = _isSearchingCustomers.asStateFlow()

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _categories = MutableStateFlow<List<ProductCategory>>(emptyList())
    val categories: StateFlow<List<ProductCategory>> = _categories.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Filters [products] by [searchQuery]. Case- AND accent-insensitive match
     * on name and SKU (so "datiles" finds "Dátiles", "cafe" finds "Café", etc.).
     * Empty query returns the full list.
     */
    val searchResults: StateFlow<List<Product>> = combine(_products, _searchQuery) { all, query ->
        if (query.isBlank()) {
            all
        } else {
            val needle = query.normalizeForSearch()
            all.filter { product ->
                product.name.normalizeForSearch().contains(needle) ||
                    product.sku.normalizeForSearch().contains(needle)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val savedCarts: StateFlow<List<SavedCart>> = savedCartsRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Shortcuts configured for the current venue. Emits an empty list when no
     * venue is selected — the Shortcuts tab then shows its onboarding empty
     * state.
     */
    val shortcuts: StateFlow<List<MosaicShortcut>> = run {
        val venueId = secureStorage.getVenueId()
        if (venueId.isNullOrBlank()) {
            MutableStateFlow<List<MosaicShortcut>>(emptyList()).asStateFlow()
        } else {
            mosaicRepository.observe(venueId)
                .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        }
    }

    init {
        // Keep ActiveCartState in sync so other screens can check the cart status.
        viewModelScope.launch {
            _cartState.collect { state ->
                activeCartState.update(state.itemCount, state.totalDisplay)
            }
        }
        // Initial product load — non-blocking.
        viewModelScope.launch {
            refreshProducts()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cart mutations
    // ─────────────────────────────────────────────────────────────────────

    fun addCustomAmount(name: String, amountCents: Int) {
        if (amountCents <= 0) return
        val item = CartItem(
            type = CartItemType.CustomAmount,
            name = name.ifBlank { "Importe personalizado" },
            unitPriceCents = amountCents,
        )
        _cartState.update { it.copy(items = it.items + item) }
    }

    fun addProduct(
        product: Product,
        quantity: Int = 1,
        modifiers: List<SelectedModifier> = emptyList(),
        itemNote: String? = null,
    ) {
        if (quantity <= 0) return
        val unitPriceCents = product.price.multiply(BigDecimal(100)).toInt()
        val item = CartItem(
            type = CartItemType.ProductItem(product.id),
            name = product.name,
            subtitle = product.categoryName,
            unitPriceCents = unitPriceCents,
            quantity = quantity,
            imageUrl = product.imageUrl,
            colorHex = product.categoryColor,
            categoryId = product.categoryId,
            selectedModifiers = modifiers,
            itemNote = itemNote,
        )
        _cartState.update { it.copy(items = it.items + item) }
    }

    fun removeItem(itemId: String) {
        _cartState.update { current ->
            current.copy(items = current.items.filterNot { it.id == itemId })
        }
    }

    fun incrementQuantity(itemId: String) {
        _cartState.update { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == itemId) it.copy(quantity = it.quantity + 1) else it
                },
            )
        }
    }

    fun decrementQuantity(itemId: String) {
        _cartState.update { current ->
            current.copy(
                items = current.items.mapNotNull {
                    when {
                        it.id != itemId -> it
                        it.quantity > 1 -> it.copy(quantity = it.quantity - 1)
                        else -> null // remove when reaching zero
                    }
                },
            )
        }
    }

    fun setItemNote(itemId: String, note: String?) {
        _cartState.update { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == itemId) it.copy(itemNote = note?.takeIf { n -> n.isNotBlank() }) else it
                },
            )
        }
    }

    fun setOrderNote(note: String?) {
        _cartState.update { it.copy(orderNote = note?.takeIf { n -> n.isNotBlank() }) }
    }

    fun applyOrderDiscount(discount: Discount?) {
        _cartState.update { it.copy(orderDiscount = discount) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Shortcuts actions: cortesía, manual discount, coupon, void
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Toggles cortesía on a single cart line. Cortesía wipes the line price
     * to $0 (see `CartItem.totalPriceCents`) and writes `isCortesia=true`
     * plus a required `reason` for the backend audit trail.
     *
     * Trying to mark an item that already has `itemDiscountId` set raises an
     * IllegalArgumentException from CartItem.init — backend rejects the
     * combination too. UI must guard so the operator can't even attempt it.
     */
    fun applyCortesiaToItem(itemId: String, reason: String) {
        val trimmed = reason.trim()
        require(trimmed.isNotEmpty()) { "Cortesía reason cannot be blank" }
        _cartState.update { current ->
            current.copy(
                items = current.items.map { item ->
                    if (item.id == itemId) {
                        item.copy(
                            isCortesia = true,
                            cortesiaReason = trimmed,
                            itemDiscountId = null,
                        )
                    } else item
                },
            )
        }
    }

    /** Reverts a cortesía back to a billable item. Keeps everything else intact. */
    fun removeCortesiaFromItem(itemId: String) {
        _cartState.update { current ->
            current.copy(
                items = current.items.map { item ->
                    if (item.id == itemId) {
                        item.copy(isCortesia = false, cortesiaReason = null)
                    } else item
                },
            )
        }
    }

    /**
     * Applies a manual (operator-typed) order-level discount. The amount is
     * stored locally on the cart; when the cart is submitted via
     * `createOrderWithItems`, it travels as `discount` (no `orderDiscountId`).
     * Backend accepts freeform manual discounts when no Discount entity is
     * referenced.
     *
     * @param amountCents Positive cents to deduct from the cart total. Must
     *   not exceed the post-item-discount subtotal (UI clamps the input).
     * @param reason Optional reason. Kept on the cart for receipt rendering
     *   in a future iteration; not currently sent to the backend (the
     *   endpoint stores it on OrderDiscount.compReason via the discount entity
     *   only — manual discounts skip that path).
     */
    fun applyManualOrderDiscount(amountCents: Int, reason: String?) {
        require(amountCents >= 0) { "Manual discount must be non-negative" }
        _cartState.update { current ->
            current.copy(
                manualDiscountCents = amountCents,
                manualDiscountReason = reason?.trim()?.takeIf { it.isNotEmpty() },
                // Manual + predefined are mutually exclusive — clear the
                // predefined discount so subtotals stay consistent.
                orderDiscount = null,
            )
        }
    }

    /** Clears any manual order discount currently applied. */
    fun clearManualOrderDiscount() {
        _cartState.update { it.copy(manualDiscountCents = 0, manualDiscountReason = null) }
    }

    /**
     * Validates a coupon code against the backend (no order required — the
     * validation is venue-scoped). On success, the resolved coupon is
     * converted into a synthetic `Discount` and applied at order level so
     * the rest of the cart math + the `createOrderWithItems` request works
     * the same as for predefined discounts. Returns the validation outcome
     * so UI can show an inline error.
     */
    suspend fun validateAndApplyCoupon(code: String): Result<Unit> {
        val trimmed = code.trim().uppercase()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Código requerido"))
        }
        val venueId = secureStorage.getVenueId()
            ?: return Result.failure(IllegalStateException("No venueId in secure storage"))
        val cartTotal = _cartState.value.totalCents / 100.0
        return discountRepository.validateCoupon(venueId, trimmed, cartTotal).fold(
            onSuccess = { coupon ->
                if (!coupon.isValid) {
                    Result.failure(IllegalStateException(coupon.invalidReason ?: "Cupón inválido"))
                } else {
                    val asDiscount = Discount(
                        id = coupon.id,
                        name = "Cupón ${coupon.code}",
                        type = coupon.type,
                        value = coupon.value,
                        scope = com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope.ORDER,
                        conditions = null,
                        active = true,
                        requiresAuthorization = false,
                    )
                    _cartState.update {
                        it.copy(
                            orderDiscount = asDiscount,
                            manualDiscountCents = 0,
                            manualDiscountReason = null,
                        )
                    }
                    Result.success(Unit)
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    fun applyOrderTaxPercent(percent: Int?) {
        // New Cobrar V1 intentionally disables order tax. Backend rejects
        // taxAmount != 0 until payment math, receipts, and dashboard totals
        // are updated together. Keep this no-op so stale UI/previews cannot
        // accidentally create a taxable checkout request.
        if (percent != null) {
            Timber.w("🛒 Ignoring tax selection in new Cobrar V1")
        }
        _cartState.update { it.copy(orderTaxPercent = null) }
    }

    fun clearCart() {
        _cartState.value = CartState()
        // ActiveCartState is updated by the collector in init.
    }

    fun selectStaff(staffId: String, staffName: String) {
        _cartState.update {
            it.copy(selectedStaffId = staffId, selectedStaffName = staffName)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Customer selection (used by the cart panel header)
    // ─────────────────────────────────────────────────────────────────────

    fun selectCustomer(customer: Customer?) {
        _selectedCustomer.value = customer
    }

    fun updateCustomerSearchQuery(query: String) {
        _customerSearchQuery.value = query
        searchCustomers(query)
    }

    fun clearCustomerSearch() {
        _customerSearchQuery.value = ""
        _customerResults.value = emptyList()
    }

    /**
     * Fires off a search against [CustomerRepository.searchCustomers]. The repo
     * requires at least 2 characters for a general query; below that we
     * fall back to recent customers so the sheet always shows something useful
     * when it opens. Cancels any in-flight search on each call.
     */
    private var customerSearchJob: kotlinx.coroutines.Job? = null
    private fun searchCustomers(query: String) {
        val venueId = secureStorage.getVenueId() ?: return
        customerSearchJob?.cancel()
        customerSearchJob = viewModelScope.launch {
            _isSearchingCustomers.value = true
            val trimmed = query.trim()
            val result = if (trimmed.length < 2) {
                customerRepository.getRecentCustomers(venueId)
            } else {
                customerRepository.searchCustomers(venueId, trimmed)
            }
            result.fold(
                onSuccess = { _customerResults.value = it },
                onFailure = { e ->
                    Timber.e(e, "🛒 Customer search failed")
                    _customerResults.value = emptyList()
                },
            )
            _isSearchingCustomers.value = false
        }
    }

    /**
     * Eagerly load recent customers so the sheet has content when it opens.
     * Called by the UI when the sheet appears.
     */
    fun loadRecentCustomers() {
        if (_customerResults.value.isEmpty() && _customerSearchQuery.value.isBlank()) {
            searchCustomers("")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Search and product cache
    // ─────────────────────────────────────────────────────────────────────

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Re-fetches the product catalog from the backend. Safe to call repeatedly. */
    fun refreshProducts() {
        val venueId = secureStorage.getVenueId() ?: run {
            Timber.w("🛒 refreshProducts skipped: no venueId in secure storage")
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            productRepository.getProducts(venueId).fold(
                onSuccess = { list -> _products.value = list },
                onFailure = { e -> Timber.e(e, "🛒 Failed to load products") },
            )
            productRepository.getCategories(venueId).fold(
                onSuccess = { list -> _categories.value = list },
                onFailure = { e -> Timber.e(e, "🛒 Failed to load categories") },
            )
            _isLoading.value = false
        }
    }

    /**
     * Lookup a product by barcode (called from the QR scanner). Returns null
     * via callback if no match. Implementation note: a future iteration could
     * cache barcode→product to avoid the network round-trip.
     */
    fun findProductByBarcode(barcode: String, onResult: (Product?) -> Unit) {
        val venueId = secureStorage.getVenueId() ?: run {
            onResult(null)
            return
        }
        viewModelScope.launch {
            productRepository.getProductByBarcode(venueId, barcode).fold(
                onSuccess = { onResult(it) },
                onFailure = { e ->
                    Timber.e(e, "🛒 Barcode lookup failed")
                    onResult(null)
                },
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Saved carts (set aside / resume)
    // ─────────────────────────────────────────────────────────────────────

    /** Persists the current cart for later resumption. Returns true if saved. */
    suspend fun saveCurrentCart(name: String? = null): Boolean {
        val current = _cartState.value
        if (current.isEmpty) return false
        val snapshot = SavedCart(
            id = UUID.randomUUID().toString(),
            name = name?.takeIf { it.isNotBlank() }
                ?: "Carrito ${current.itemCount} item${if (current.itemCount == 1) "" else "s"}",
            items = current.items.map { it.toSavedCartItem() },
            orderDiscount = current.orderDiscount?.toSaved(),
            manualDiscountCents = current.manualDiscountCents,
            manualDiscountReason = current.manualDiscountReason,
            orderNote = current.orderNote,
            orderTaxPercent = current.orderTaxPercent,
        )
        savedCartsRepository.save(snapshot)
        return true
    }

    suspend fun deleteSavedCart(id: String) = savedCartsRepository.delete(id)

    // ─────────────────────────────────────────────────────────────────────
    // Shortcuts (Mosaico) — venue-scoped, persisted in Room
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Assign or reassign a shortcut at [position]. If a shortcut already exists
     * at that position for the current venue, it is replaced (the unique index
     * on `(venue_id, position)` enforces this at the DB level).
     */
    fun assignShortcut(product: Product, position: Int) {
        val venueId = secureStorage.getVenueId() ?: run {
            Timber.w("🛒 assignShortcut skipped: no venueId")
            return
        }
        viewModelScope.launch {
            // Query the repo directly instead of `shortcuts.value`. The cached
            // StateFlow uses `SharingStarted.Lazily` so its value may be stale
            // when the UI isn't actively collecting (e.g. during the brief
            // window the user is on the Configurar tab without having opened
            // the Shortcuts tab first).
            val existing = mosaicRepository.get(venueId).firstOrNull { it.position == position }
            mosaicRepository.upsert(
                MosaicShortcut(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    venueId = venueId,
                    productId = product.id,
                    position = position,
                    label = product.name,
                    colorHex = product.categoryColor,
                ),
            )
        }
    }

    fun removeShortcut(id: String) {
        viewModelScope.launch { mosaicRepository.delete(id) }
    }

    /** Tap on a shortcut — looks up the product and adds it to the cart. */
    fun addShortcutToCart(shortcut: MosaicShortcut) {
        val product = _products.value.firstOrNull { it.id == shortcut.productId }
        if (product != null) {
            addProduct(product)
        } else {
            Timber.w("🛒 Shortcut ${shortcut.id} references missing product ${shortcut.productId}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Payment / order handoff (Phase 5)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Inspects the current cart and produces the payload the navigation layer
     * needs to open `PaymentScreen`.
     *
     * Routing:
     * - All items are `CustomAmount` → [PaymentNavigationPayload.Fast] (no
     *   order is created, mirrors today's "Pago Rápido" flow exactly).
     * - At least one `ProductItem` → the new TPV `/orders/with-items`
     *   endpoint creates one complete order with catalog products, custom
     *   amounts, cortesía metadata, and discounts preserved.
     */
    suspend fun prepareForPayment(): Result<PaymentNavigationPayload> {
        val current = _cartState.value
        if (current.isEmpty) {
            return Result.failure(IllegalStateException("Cart is empty"))
        }
        if (current.taxCents != 0) {
            return Result.failure(IllegalStateException("El impuesto no está disponible en Cobrar V1"))
        }

        val amountPesos = (current.totalCents / 100.0).toString()

        val productItems = current.items.filter { it.type is CartItemType.ProductItem }
        if (productItems.isEmpty()) {
            return Result.success(PaymentNavigationPayload.Fast(amountPesos))
        }

        return createOrderWithCurrentItems().map { order ->
            if (current.totalCents == 0) {
                PaymentNavigationPayload.CompletedFreeCart(
                    amountPesosString = amountPesos,
                    orderId = order.id,
                    orderNumber = order.orderNumber,
                )
            } else {
                PaymentNavigationPayload.Order(
                    amountPesosString = amountPesos,
                    orderId = order.id,
                    orderNumber = order.orderNumber,
                    wasPayLaterOrder = false,
                )
            }
        }
    }

    /**
     * Creates an order with the current cart items linked to the selected
     * customer, leaving it `paymentStatus = PENDING`. The order shows up in
     * the dashboard's "Cuentas por Cobrar" view and in the operator's
     * pay-later list. No PaymentScreen navigation — operator goes back to a
     * fresh cart after creation.
     *
     * Requires `_selectedCustomer != null` (the whole point is binding the
     * debt to a customer). Cart must have items (no $0 free-cart in this path).
     *
     * **Known drift**: when the dashboard later settles this order via the
     * existing `settleOrder` endpoint, inventory is NOT deducted (the
     * inventory hook only fires on the regular fully-paid path). Operations
     * accepts this until `settleOrder` is fixed in a separate ticket. The
     * order itself is faithful — items, discounts, cortesía all persisted as
     * with regular Cobrar orders.
     */
    suspend fun createPayLaterOrder(): Result<Order> {
        val current = _cartState.value
        if (current.isEmpty) {
            return Result.failure(IllegalStateException("El carrito está vacío"))
        }
        if (_selectedCustomer.value == null) {
            return Result.failure(IllegalStateException("Selecciona un cliente para Pagar después"))
        }
        if (current.totalCents == 0) {
            return Result.failure(
                IllegalStateException("El total es \$0 — usa cortesía/cobrar normal en lugar de Pagar después"),
            )
        }
        // The backend createOrderWithItems endpoint already accepts customerId
        // and leaves the order in PENDING + PENDING when total > 0. That's
        // exactly the pay-later shape. No separate "leave open" flag needed.
        return createOrderWithCurrentItems()
    }

    /**
     * Creates the full order in one backend call. This is intentionally only
     * used by the new Cobrar flow; legacy order screens keep their existing
     * create-order + add-items path.
     */
    private suspend fun createOrderWithCurrentItems(): Result<Order> {
        val venueId = secureStorage.getVenueId() ?: return Result.failure(
            IllegalStateException("No venueId in secure storage"),
        )
        val current = _cartState.value
        val staffId = current.selectedStaffId.takeIf { it.isNotBlank() }
            ?: secureStorage.getStaffId()
            ?: return Result.failure(IllegalStateException("No staffId in secure storage"))

        val requestItems = current.items.map { item ->
            val productId = (item.type as? CartItemType.ProductItem)?.productId
            TpvCreateOrderWithItemsItem(
                productId = productId,
                name = if (productId == null) item.name else null,
                quantity = item.quantity,
                unitPrice = if (productId == null) item.effectiveUnitPriceCents.centsToPesos() else null,
                modifierIds = item.selectedModifiers.map { it.modifierId },
                notes = item.itemNote,
                isCortesia = item.isCortesia,
                cortesiaReason = item.cortesiaReason,
                itemDiscountId = item.itemDiscountId,
            )
        }

        val grossSubtotalCents = current.items.sumOf { it.grossLineCents() }

        // When `orderDiscount` is null but `manualDiscountCents` > 0, the
        // freeform manual discount travels as `discount`; backend treats it
        // as an unbacked reduction with no Discount entity. When predefined
        // is set, `discount` may stay 0 — the service recomputes from the
        // discount's value/type against the gross subtotal.
        val discountForWire = when {
            current.orderDiscount != null -> 0 // backend recomputes from id
            else -> current.manualDiscountCents
        }

        return orderRepository.createOrderWithItems(
            venueId = venueId,
            request = TpvCreateOrderWithItemsRequest(
                items = requestItems,
                staffId = staffId,
                orderType = OrderType.TAKEOUT,
                source = "TPV",
                customerId = _selectedCustomer.value?.id,
                discount = discountForWire.centsToPesos(),
                orderDiscountId = current.orderDiscount?.id,
                taxAmount = BigDecimal.ZERO,
                tip = BigDecimal.ZERO,
                subtotal = grossSubtotalCents.centsToPesos(),
                total = current.totalCents.centsToPesos(),
                note = current.orderNote,
            ),
        )
    }
}

private fun CartItem.grossLineCents(): Int =
    (effectiveUnitPriceCents + selectedModifiers.sumOf { it.priceInCents }) * quantity

// Converts internal integer-cents math to BigDecimal pesos for wire format.
// The new TPV /tpv/.../orders/with-items endpoint accepts pesos as decimals
// matching the rest of the /tpv API.
private fun Int.centsToPesos(): BigDecimal =
    BigDecimal(this).divide(BigDecimal(100), 2, java.math.RoundingMode.HALF_UP)

// Strips diacritics (acentos, ñ→n) and lowercases so search treats
// "datiles" and "Dátiles" as the same string. Uses NFD normalization +
// removes the combining mark range (\\p{M}) to peel off accents without
// touching base letters. Cheap enough to run per-keystroke against the
// product catalog (typically <500 items).
private fun String.normalizeForSearch(): String =
    java.text.Normalizer.normalize(this.trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .lowercase()

// ─────────────────────────────────────────────────────────────────────────
// Mapping helpers (CartItem ↔ SavedCartItem)
// ─────────────────────────────────────────────────────────────────────────

private fun CartItem.toSavedCartItem(): SavedCartItem = SavedCartItem(
    productId = (type as? CartItemType.ProductItem)?.productId,
    name = name,
    unitPriceCents = unitPriceCents,
    quantity = quantity,
    modifiers = selectedModifiers,
    note = itemNote,
    isCortesia = isCortesia,
    cortesiaReason = cortesiaReason,
    priceAdjustmentCents = priceAdjustmentCents,
    itemDiscountId = itemDiscountId,
)

private fun Discount.toSaved(): SavedDiscount = SavedDiscount(
    id = id,
    name = name,
    typeName = type.name,
    value = value.toPlainString(),
)
