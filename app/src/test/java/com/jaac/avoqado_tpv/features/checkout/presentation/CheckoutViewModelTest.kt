package com.jaac.avoqado_tpv.features.checkout.presentation

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.checkout.data.ActiveCartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut
import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedCart
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.ordering.domain.TpvCreateOrderWithItemsRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Follows the TPV ViewModel test pattern documented in MEMORY.md:
 * `UnconfinedTestDispatcher` so init coroutines run eagerly, with
 * `viewModelScope.cancel()` after each test to keep `runTest` from hanging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var productRepository: ProductRepository
    private lateinit var customerRepository: CustomerRepository
    private lateinit var orderRepository: OrderRepository
    private lateinit var discountRepository: com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
    private lateinit var savedCartsRepository: SavedCartsRepository
    private lateinit var mosaicRepository: MosaicRepository
    private lateinit var secureStorage: SecureStorage
    private lateinit var activeCartState: ActiveCartState
    private lateinit var validateReferralUseCase: com.jaac.avoqado_tpv.features.referrals.domain.usecase.ValidateReferralUseCase
    private lateinit var captureReferralUseCase: com.jaac.avoqado_tpv.features.referrals.domain.usecase.CaptureReferralUseCase

    private val savedCartsFlow = MutableStateFlow<List<SavedCart>>(emptyList())
    private val shortcutsFlow = MutableStateFlow<List<MosaicShortcut>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        productRepository = mockk(relaxed = true)
        customerRepository = mockk(relaxed = true)
        orderRepository = mockk(relaxed = true)
        discountRepository = mockk(relaxed = true)
        savedCartsRepository = mockk(relaxed = true)
        mosaicRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        activeCartState = ActiveCartState() // real instance — no behavior to mock
        validateReferralUseCase = mockk(relaxed = true)
        captureReferralUseCase = mockk(relaxed = true)

        coEvery { secureStorage.getVenueId() } returns "venue-1"
        coEvery { secureStorage.getStaffId() } returns "staff-1"
        coEvery { secureStorage.getStaffName() } returns "María"
        coEvery { productRepository.getProducts(any(), any()) } returns Result.success(emptyList())
        coEvery { productRepository.getCategories(any()) } returns Result.success(emptyList())
        coEvery { savedCartsRepository.observeAll() } returns savedCartsFlow
        coEvery { mosaicRepository.observe(any()) } returns shortcutsFlow
        coEvery { customerRepository.getRecentCustomers(any(), any()) } returns Result.success(emptyList())
        coEvery { customerRepository.searchCustomers(any(), any(), any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = CheckoutViewModel(
        productRepository = productRepository,
        customerRepository = customerRepository,
        orderRepository = orderRepository,
        discountRepository = discountRepository,
        savedCartsRepository = savedCartsRepository,
        mosaicRepository = mosaicRepository,
        activeCartState = activeCartState,
        secureStorage = secureStorage,
        validateReferralUseCase = validateReferralUseCase,
        captureReferralUseCase = captureReferralUseCase,
    )

    private fun fakeProduct(
        id: String = "p-1",
        name: String = "Café",
        priceCents: Int = 5000,
    ) = Product(
        id = id,
        name = name,
        sku = id,
        price = BigDecimal(priceCents).divide(BigDecimal(100)),
        categoryId = "cat-1",
        categoryName = "Bebidas",
        description = null,
        emoji = "☕",
        imageUrl = null,
        available = true,
    )

    @Test
    fun `addCustomAmount adds a CustomAmount line to the cart`() = runTest {
        val viewModel = createViewModel()

        viewModel.addCustomAmount(name = "Servicio", amountCents = 1500)

        val state = viewModel.cartState.value
        assertEquals(1, state.items.size)
        assertTrue(state.items.first().type is CartItemType.CustomAmount)
        assertEquals(1500, state.subtotalCents)
        assertEquals(1, activeCartState.itemCount.value)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `addCustomAmount with zero or negative is a noop`() = runTest {
        val viewModel = createViewModel()

        viewModel.addCustomAmount("Algo", 0)
        viewModel.addCustomAmount("Algo", -100)

        assertTrue(viewModel.cartState.value.isEmpty)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `addProduct converts BigDecimal price to cents and stores reference`() = runTest {
        val viewModel = createViewModel()

        viewModel.addProduct(fakeProduct(id = "p-7", priceCents = 12000), quantity = 2)

        val state = viewModel.cartState.value
        assertEquals(1, state.items.size)
        val item = state.items.first()
        assertEquals(CartItemType.ProductItem("p-7"), item.type)
        assertEquals(12000, item.unitPriceCents)
        assertEquals(2, item.quantity)
        assertEquals(24000, state.subtotalCents)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `incrementQuantity bumps an existing line by one`() = runTest {
        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct(priceCents = 5000))
        val itemId = viewModel.cartState.value.items.first().id

        viewModel.incrementQuantity(itemId)

        assertEquals(2, viewModel.cartState.value.items.first().quantity)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `decrementQuantity removes the line when reaching zero`() = runTest {
        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct())
        val itemId = viewModel.cartState.value.items.first().id

        viewModel.decrementQuantity(itemId) // 1 → would be 0, line removed

        assertTrue(viewModel.cartState.value.isEmpty)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `removeItem deletes the specified line`() = runTest {
        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct(id = "a"))
        viewModel.addProduct(fakeProduct(id = "b"))
        val firstId = viewModel.cartState.value.items.first().id

        viewModel.removeItem(firstId)

        assertEquals(1, viewModel.cartState.value.items.size)
        assertEquals(CartItemType.ProductItem("b"), viewModel.cartState.value.items.first().type)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `clearCart wipes state and updates ActiveCartState`() = runTest {
        val viewModel = createViewModel()
        viewModel.addCustomAmount("Item", 5000)
        assertEquals(1, activeCartState.itemCount.value)

        viewModel.clearCart()

        assertTrue(viewModel.cartState.value.isEmpty)
        assertEquals(0, activeCartState.itemCount.value)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `applyOrderTaxPercent is a no-op in Cobrar V1 — tax is disabled`() = runTest {
        val viewModel = createViewModel()

        // V1 of new Cobrar disables order tax until payment math, receipts,
        // and dashboard totals support it together. Any non-null call
        // is silently ignored — orderTaxPercent stays null regardless of input.
        viewModel.applyOrderTaxPercent(16)
        assertNull(viewModel.cartState.value.orderTaxPercent)

        viewModel.applyOrderTaxPercent(150)
        assertNull(viewModel.cartState.value.orderTaxPercent)

        viewModel.applyOrderTaxPercent(-5)
        assertNull(viewModel.cartState.value.orderTaxPercent)

        viewModel.applyOrderTaxPercent(null)
        assertNull(viewModel.cartState.value.orderTaxPercent)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `applyOrderDiscount stores the discount on the cart state`() = runTest {
        val viewModel = createViewModel()
        val discount = Discount(
            id = "d-1",
            name = "10 por ciento",
            type = DiscountType.PERCENTAGE,
            value = BigDecimal(10),
            scope = DiscountScope.ORDER,
            conditions = null,
            active = true,
            requiresAuthorization = false,
        )

        viewModel.applyOrderDiscount(discount)

        assertEquals(discount, viewModel.cartState.value.orderDiscount)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `setOrderNote stores trimmed non-blank note and clears on blank`() = runTest {
        val viewModel = createViewModel()

        viewModel.setOrderNote("Sin cebolla")
        assertEquals("Sin cebolla", viewModel.cartState.value.orderNote)

        viewModel.setOrderNote("")
        assertNull(viewModel.cartState.value.orderNote)

        viewModel.setOrderNote("   ")
        assertNull(viewModel.cartState.value.orderNote)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `setItemNote sets and clears the note on a specific line`() = runTest {
        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct())
        val itemId = viewModel.cartState.value.items.first().id

        viewModel.setItemNote(itemId, "Bien caliente")
        assertEquals("Bien caliente", viewModel.cartState.value.items.first().itemNote)

        viewModel.setItemNote(itemId, "")
        assertNull(viewModel.cartState.value.items.first().itemNote)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `selectStaff stores staff id and name`() = runTest {
        val viewModel = createViewModel()

        viewModel.selectStaff("staff-42", "María")

        assertEquals("staff-42", viewModel.cartState.value.selectedStaffId)
        assertEquals("María", viewModel.cartState.value.selectedStaffName)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `saveCurrentCart returns false when cart is empty`() = runTest {
        val viewModel = createViewModel()

        val saved = viewModel.saveCurrentCart()

        assertFalse(saved)
        coVerify(exactly = 0) { savedCartsRepository.save(any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `saveCurrentCart persists a snapshot when cart has items`() = runTest {
        val viewModel = createViewModel()
        viewModel.addCustomAmount("Algo", 5000)

        val saved = viewModel.saveCurrentCart(name = "Mesa 3 set aside")

        assertTrue(saved)
        coVerify(exactly = 1) {
            savedCartsRepository.save(match { it.name == "Mesa 3 set aside" && it.items.size == 1 })
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `refreshProducts fetches both products and categories`() = runTest {
        val viewModel = createViewModel()

        // init already triggers refreshProducts once
        coVerify(atLeast = 1) { productRepository.getProducts("venue-1", null) }
        coVerify(atLeast = 1) { productRepository.getCategories("venue-1") }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `updateSearchQuery filters products by name and sku`() = runTest {
        // Seed cached products via direct repository mock before VM construction
        coEvery { productRepository.getProducts(any(), any()) } returns Result.success(
            listOf(
                fakeProduct(id = "p-1", name = "Café Americano"),
                fakeProduct(id = "p-2", name = "Latte"),
                fakeProduct(id = "p-3", name = "Té verde"),
            ),
        )
        val viewModel = createViewModel()

        // searchResults uses SharingStarted.Lazily — it only starts emitting when
        // a collector subscribes. Use Turbine to subscribe and inspect emissions.
        viewModel.searchResults.test {
            // First emission: full list (empty query)
            assertEquals(3, awaitItem().size)

            viewModel.updateSearchQuery("caf")
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals("p-1", filtered.first().id)

            viewModel.updateSearchQuery("")
            assertEquals(3, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `findProductByBarcode invokes callback with product on success`() = runTest {
        coEvery { productRepository.getProductByBarcode("venue-1", "1234") } returns
            Result.success(fakeProduct(id = "by-barcode"))
        val viewModel = createViewModel()

        var captured: Product? = null
        viewModel.findProductByBarcode("1234") { captured = it }

        assertNotNull(captured)
        assertEquals("by-barcode", captured?.id)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `findProductByBarcode returns null when repo fails`() = runTest {
        coEvery { productRepository.getProductByBarcode("venue-1", "9999") } returns
            Result.failure(RuntimeException("not found"))
        val viewModel = createViewModel()

        var captured: Product? = fakeProduct() // sentinel non-null
        viewModel.findProductByBarcode("9999") { captured = it }

        assertNull(captured)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `addProduct ignores non-positive quantity`() = runTest {
        val viewModel = createViewModel()

        viewModel.addProduct(fakeProduct(), quantity = 0)
        viewModel.addProduct(fakeProduct(), quantity = -1)

        assertTrue(viewModel.cartState.value.isEmpty)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `assignShortcut creates a new shortcut at the given position`() = runTest {
        val viewModel = createViewModel()

        viewModel.assignShortcut(fakeProduct(id = "p-9", name = "Café"), position = 2)

        coVerify(exactly = 1) {
            mosaicRepository.upsert(
                match { it.productId == "p-9" && it.position == 2 && it.label == "Café" && it.venueId == "venue-1" },
            )
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `assignShortcut preserves existing id when overwriting a slot`() = runTest {
        // Stub the synchronous repo getter — that's the source-of-truth
        // assignShortcut uses to look up an existing slot.
        coEvery { mosaicRepository.get("venue-1") } returns listOf(
            MosaicShortcut(
                id = "existing-id",
                venueId = "venue-1",
                productId = "old-product",
                position = 0,
                label = "Old",
            ),
        )
        val viewModel = createViewModel()

        viewModel.assignShortcut(fakeProduct(id = "new-product", name = "New"), position = 0)

        coVerify(exactly = 1) {
            mosaicRepository.upsert(
                match { it.id == "existing-id" && it.productId == "new-product" && it.label == "New" },
            )
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `removeShortcut delegates to repository`() = runTest {
        val viewModel = createViewModel()

        viewModel.removeShortcut("shortcut-7")

        coVerify(exactly = 1) { mosaicRepository.delete("shortcut-7") }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `addShortcutToCart adds the underlying product when it exists in cache`() = runTest {
        coEvery { productRepository.getProducts(any(), any()) } returns Result.success(
            listOf(fakeProduct(id = "p-3", name = "Latte", priceCents = 6500)),
        )
        val viewModel = createViewModel()
        val shortcut = MosaicShortcut(
            id = "s-1",
            venueId = "venue-1",
            productId = "p-3",
            position = 0,
            label = "Latte",
        )

        viewModel.addShortcutToCart(shortcut)

        assertEquals(1, viewModel.cartState.value.items.size)
        assertEquals(6500, viewModel.cartState.value.subtotalCents)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cart state pre-seeds staff from SecureStorage at construction`() = runTest {
        val viewModel = createViewModel()

        assertEquals("staff-1", viewModel.cartState.value.selectedStaffId)
        assertEquals("María", viewModel.cartState.value.selectedStaffName)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `selectCustomer updates selectedCustomer flow`() = runTest {
        val viewModel = createViewModel()
        val customer = fakeCustomer(id = "c-1", firstName = "Ana", phone = "5551234567")

        viewModel.selectCustomer(customer)

        assertEquals(customer, viewModel.selectedCustomer.value)

        viewModel.selectCustomer(null)
        assertNull(viewModel.selectedCustomer.value)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `loadRecentCustomers fetches from repo when results are empty`() = runTest {
        coEvery { customerRepository.getRecentCustomers("venue-1", any()) } returns
            Result.success(listOf(fakeCustomer(id = "c-1", firstName = "Ana", phone = "555")))
        val viewModel = createViewModel()

        viewModel.loadRecentCustomers()

        assertEquals(1, viewModel.customerResults.value.size)
        assertEquals("c-1", viewModel.customerResults.value.first().id)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `updateCustomerSearchQuery delegates to searchCustomers when length is at least 2`() = runTest {
        coEvery { customerRepository.searchCustomers("venue-1", "An", any()) } returns
            Result.success(listOf(fakeCustomer(id = "c-2", firstName = "Andrea", phone = "555")))
        val viewModel = createViewModel()

        viewModel.updateCustomerSearchQuery("An")

        coVerify(exactly = 1) { customerRepository.searchCustomers("venue-1", "An", any()) }
        assertEquals("c-2", viewModel.customerResults.value.first().id)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `updateCustomerSearchQuery falls back to recent customers below 2 chars`() = runTest {
        coEvery { customerRepository.getRecentCustomers("venue-1", any()) } returns
            Result.success(emptyList())
        val viewModel = createViewModel()

        viewModel.updateCustomerSearchQuery("A")

        coVerify(atLeast = 1) { customerRepository.getRecentCustomers("venue-1", any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `clearCustomerSearch resets query and results`() = runTest {
        coEvery { customerRepository.getRecentCustomers(any(), any()) } returns
            Result.success(listOf(fakeCustomer(id = "c-3", firstName = "Berta", phone = "555")))
        val viewModel = createViewModel()
        viewModel.loadRecentCustomers()
        viewModel.updateCustomerSearchQuery("Berta")

        viewModel.clearCustomerSearch()

        assertEquals("", viewModel.customerSearchQuery.value)
        assertTrue(viewModel.customerResults.value.isEmpty())

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `prepareForPayment returns Fast when cart has only custom amounts`() = runTest {
        val viewModel = createViewModel()
        viewModel.addCustomAmount("Servicio", amountCents = 15000)

        val result = viewModel.prepareForPayment()

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertTrue(payload is PaymentNavigationPayload.Fast)
        assertEquals("150.0", payload.amountPesosString)
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `prepareForPayment returns Order with orderId when cart has product items`() = runTest {
        val fakeOrderWithItems = fakeOrder(id = "ord-1", orderNumber = "T-001", version = 1)
        coEvery {
            orderRepository.createOrderWithItems("venue-1", any())
        } returns Result.success(fakeOrderWithItems)

        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct(id = "p-1", priceCents = 5000), quantity = 2)

        val result = viewModel.prepareForPayment()

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertTrue(payload is PaymentNavigationPayload.Order)
        payload as PaymentNavigationPayload.Order
        assertEquals("ord-1", payload.orderId)
        assertEquals("T-001", payload.orderNumber)
        assertFalse(payload.wasPayLaterOrder)
        coVerify(exactly = 1) {
            orderRepository.createOrderWithItems(
                "venue-1",
                match { req ->
                    req.items.size == 1 &&
                        req.items.first().productId == "p-1" &&
                        req.items.first().quantity == 2
                },
            )
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `prepareForPayment with mixed cart sends both products and custom items in single call`() = runTest {
        // New TPV /tpv/.../orders/with-items endpoint accepts catalog-less
        // items (productId=null + name + unitPrice) so mixed carts are now
        // persisted faithfully on the backend with both products AND custom
        // amounts as line items — no more "phantom propina" on the receipt.
        val fakeOrderWithItems = fakeOrder(id = "ord-2", orderNumber = "T-002", version = 1)
        coEvery {
            orderRepository.createOrderWithItems(any(), any())
        } returns Result.success(fakeOrderWithItems)

        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct(id = "p-1", priceCents = 5000), quantity = 1)
        viewModel.addCustomAmount("Otro importe", amountCents = 2500)

        val result = viewModel.prepareForPayment()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            orderRepository.createOrderWithItems(
                any(),
                match { req ->
                    req.items.size == 2 &&
                        req.items.any { it.productId == "p-1" } &&
                        req.items.any { it.productId == null && it.name == "Otro importe" }
                },
            )
        }
        // Legacy 2-call path must NOT be invoked anymore.
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { orderRepository.addItemsToOrder(any(), any(), any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `prepareForPayment fails when cart is empty`() = runTest {
        val viewModel = createViewModel()

        val result = viewModel.prepareForPayment()

        assertTrue(result.isFailure)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `createPayLaterOrder fails when no customer is selected`() = runTest {
        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct(), quantity = 1)

        val result = viewModel.createPayLaterOrder()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `createPayLaterOrder succeeds with cart of custom amounts when customer is selected`() = runTest {
        // Pay-later doesn't require catalog products specifically — any
        // non-empty cart with a customer + non-zero total is valid. The
        // operator might legitimately give credit on a "$50 service" custom
        // line and need to record who owes it.
        val fakeOrder = fakeOrder(id = "ord-pl", orderNumber = "T-PL", version = 1)
        coEvery { orderRepository.createOrderWithItems(any(), any()) } returns Result.success(fakeOrder)

        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer(id = "c-1"))
        viewModel.addCustomAmount("Servicio", amountCents = 5000)

        val result = viewModel.createPayLaterOrder()

        assertTrue(result.isSuccess)
        assertEquals("ord-pl", result.getOrThrow().id)
        coVerify(exactly = 1) { orderRepository.createOrderWithItems(any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `createPayLaterOrder creates an order via the with-items endpoint when customer is set`() = runTest {
        // Pay-later in V1 reuses the new createOrderWithItems endpoint — the
        // order stays PENDING because the customerId travels in the request
        // but no Payment row is created. Dashboard "Cuentas por Cobrar"
        // picks it up via `onlyPayLater`.
        val fakeOrder = fakeOrder(id = "ord-99", orderNumber = "T-099", version = 1)
        coEvery { orderRepository.createOrderWithItems(any(), any()) } returns Result.success(fakeOrder)

        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer(id = "c-1", firstName = "Ana"))
        viewModel.addProduct(fakeProduct(id = "p-1"), quantity = 1)

        val result = viewModel.createPayLaterOrder()

        assertTrue(result.isSuccess)
        assertEquals("ord-99", result.getOrThrow().id)
        coVerify(exactly = 1) {
            orderRepository.createOrderWithItems(
                any(),
                match { req -> req.customerId == "c-1" && req.items.any { it.productId == "p-1" } },
            )
        }
        // Legacy 2-call path must NOT be invoked anymore.
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    private fun fakeOrder(
        id: String = "ord-1",
        orderNumber: String = "T-001",
        version: Int = 0,
    ) = Order(
        id = id,
        orderNumber = orderNumber,
        venueId = "venue-1",
        tableId = null,
        tableName = null,
        covers = 1,
        waiterId = "staff-1",
        waiterName = "María",
        status = OrderStatus.OPEN,
        kitchenStatus = KitchenStatus.PENDING,
        paymentStatus = PaymentStatus.PENDING,
        orderType = OrderType.TAKEOUT,
        items = emptyList(),
        subtotal = java.math.BigDecimal.ZERO,
        tax = java.math.BigDecimal.ZERO,
        total = java.math.BigDecimal.ZERO,
        notes = null,
        createdAt = java.time.Instant.now(),
        updatedAt = java.time.Instant.now(),
        version = version,
    )

    private fun fakeCustomer(
        id: String = "c-1",
        firstName: String? = "Ana",
        phone: String? = "5551234567",
    ) = Customer(
        id = id,
        firstName = firstName,
        lastName = null,
        email = null,
        phone = phone,
        loyaltyPoints = 0,
        totalVisits = 0,
        totalSpent = java.math.BigDecimal.ZERO,
        customerGroup = null,
    )

    @Test
    fun `addShortcutToCart is a noop when the product is not cached`() = runTest {
        val viewModel = createViewModel()
        val shortcut = MosaicShortcut(
            id = "s-1",
            venueId = "venue-1",
            productId = "missing-product",
            position = 0,
            label = "Ghost",
        )

        viewModel.addShortcutToCart(shortcut)

        assertTrue(viewModel.cartState.value.isEmpty)

        viewModel.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Idempotencia de createOrderWithItems (externalId por venta)
    //
    // Contexto (auditoría de inventario 2026-08-12, C6): un retry tras perder
    // la respuesta creaba una SEGUNDA orden (y en free-cart $0, segunda
    // deducción). El server ya deduplica por venueId+externalId; estos tests
    // fijan el ciclo de vida de la llave del lado del cliente:
    //   · MISMO carrito → misma llave (el retry recupera SU orden)
    //   · carrito EDITADO → llave nueva (jamás regresar una orden con items viejos)
    //   · venta CREADA → llave nueva para la siguiente venta
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `reintento con el mismo carrito manda el MISMO externalId`() = runTest {
        val captured = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(captured)) } returnsMany listOf(
            Result.failure(java.io.IOException("respuesta perdida")),
            Result.success(fakeOrder(id = "ord-r1", orderNumber = "T-R1", version = 1)),
        )
        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct(id = "p-1", priceCents = 5000))

        viewModel.prepareForPayment() // primer intento: la respuesta se pierde
        viewModel.prepareForPayment() // retry idéntico

        assertEquals(2, captured.size)
        assertNotNull(captured[0].externalId)
        assertEquals(captured[0].externalId, captured[1].externalId)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `carrito editado genera externalId NUEVO`() = runTest {
        val captured = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(captured)) } returnsMany listOf(
            Result.failure(java.io.IOException("respuesta perdida")),
            Result.success(fakeOrder(id = "ord-r2", orderNumber = "T-R2", version = 1)),
        )
        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct(id = "p-1", priceCents = 5000))

        viewModel.prepareForPayment() // falla
        viewModel.addProduct(fakeProduct(id = "p-2", priceCents = 3000)) // el cajero edita
        viewModel.prepareForPayment()

        assertEquals(2, captured.size)
        assertNotNull(captured[1].externalId)
        assertNotEquals(captured[0].externalId, captured[1].externalId)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `tras crear con exito, la siguiente venta lleva externalId nuevo`() = runTest {
        val captured = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(captured)) } returnsMany listOf(
            Result.success(fakeOrder(id = "ord-a", orderNumber = "T-A", version = 1)),
            Result.success(fakeOrder(id = "ord-b", orderNumber = "T-B", version = 1)),
        )
        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct(id = "p-1", priceCents = 5000))
        viewModel.prepareForPayment() // venta 1 creada

        viewModel.clearCart()
        viewModel.addProduct(fakeProduct(id = "p-1", priceCents = 5000)) // venta 2, MISMO contenido
        viewModel.prepareForPayment()

        assertEquals(2, captured.size)
        assertNotEquals(captured[0].externalId, captured[1].externalId)
        viewModel.viewModelScope.cancel()
    }
}
