package com.jaac.avoqado_tpv.features.checkout.presentation

import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.features.checkout.VentaSinRedEnMemoria
import com.jaac.avoqado_tpv.features.checkout.data.ActiveCartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
import com.jaac.avoqado_tpv.features.ordering.domain.DescuentoDelCliente
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
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
import com.jaac.avoqado_tpv.features.ordering.domain.comoDescuento
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.permissions.presentation.override.Autorizacion
import com.jaac.avoqado_tpv.features.permissions.presentation.override.AutorizacionDeGerente
import com.jaac.avoqado_tpv.features.referrals.domain.model.ValidationResult
import com.jaac.avoqado_tpv.features.referrals.domain.usecase.ValidateReferralUseCase
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * D1 · Task 5: el descuento que el negocio le asignó al cliente se pone solo en «Cobrar» según el permiso `discounts:apply` de quien
 * cobra; si no, se avisa. «Quitar» vale para la venta. El cobro vuelve a revisar el permiso, crea orden aunque sean importes y, sin
 * red, no se cobra con el descuento del cliente (diseño v5). Base: `createViewModel()` de [CheckoutViewModelTest], con el aparato en
 * memoria de [CheckoutVentaSinRedTest] para restaurar el carrito y ver si quedó una venta local.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutDescuentoDelClienteTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var productRepository: ProductRepository
    private lateinit var customerRepository: CustomerRepository
    private lateinit var orderRepository: OrderRepository
    private lateinit var discountRepository: DiscountRepository
    private lateinit var secureStorage: SecureStorage
    private lateinit var validateReferralUseCase: ValidateReferralUseCase
    private lateinit var productDao: ProductDao
    private lateinit var productCategoryDao: ProductCategoryDao
    private lateinit var catalogoGuardado: CatalogoGuardado
    private lateinit var connectionStateManager: ConnectionStateManager
    private lateinit var permisos: PermissionsRepository
    private lateinit var autorizacion: AutorizacionDeGerente
    private lateinit var aparato: VentaSinRedEnMemoria

    private val diez = DescuentoDelCliente("d-10", "Cliente 10 %", DiscountType.PERCENTAGE, 10.0, null)
    private val treinta = DescuentoDelCliente("d-30", "Amigos 30 %", DiscountType.PERCENTAGE, 30.0, null)
    private val cecilia = cliente("c-cecilia", "Cecilia", diez)
    private val ernesto = cliente("c-ernesto", "Ernesto", treinta)
    private val ana = cliente("c-ana", "Ana", null)

    private val TEXTO_SIN_PERMISO = "Tu puesto no puede aplicar el descuento de Cecilia. Pide autorización o quítalo."
    private val TEXTO_SIN_RED = "Sin conexión: el descuento de Cecilia necesita internet. Quítalo o cobra cuando vuelva la red"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        CheckoutViewModel.despachadorDelCatalogo = Dispatchers.Unconfined

        productRepository = mockk(relaxed = true)
        customerRepository = mockk(relaxed = true)
        orderRepository = mockk(relaxed = true)
        discountRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        validateReferralUseCase = mockk(relaxed = true)
        productDao = mockk(relaxed = true)
        productCategoryDao = mockk(relaxed = true)
        catalogoGuardado = mockk()
        connectionStateManager = ConnectionStateManager() // arranca con red
        permisos = mockk(relaxed = true) { coEvery { hasPermission(any()) } returns true }
        autorizacion = mockk()
        coEvery { autorizacion.paraCortesia(any(), any(), any()) } returns Autorizacion.NoHaceFalta
        coEvery { autorizacion.pinPrendido(any()) } returns true
        aparato = VentaSinRedEnMemoria()

        coEvery { secureStorage.getVenueId() } returns "venue-1"
        coEvery { secureStorage.getStaffId() } returns "staff-1"
        coEvery { secureStorage.getStaffName() } returns "María"
        coEvery { productDao.getAllProducts(any()) } returns emptyList()
        coEvery { productCategoryDao.getAllCategories(any()) } returns emptyList()
        coEvery { catalogoGuardado.refrescar(any()) } returns Result.success(emptyList())
        coEvery { productRepository.getCategories(any()) } returns Result.success(emptyList())
        coEvery { orderRepository.createOrderWithItems(any(), any()) } returns Result.success(orden())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        CheckoutViewModel.despachadorDelCatalogo = Dispatchers.Default
    }

    private fun createViewModel(): CheckoutViewModel {
        val savedCarts = mockk<SavedCartsRepository>(relaxed = true)
        coEvery { savedCarts.observeAll() } returns MutableStateFlow(emptyList())
        val mosaic = mockk<MosaicRepository>(relaxed = true)
        coEvery { mosaic.observe(any()) } returns MutableStateFlow(emptyList())
        return CheckoutViewModel(
            productRepository = productRepository,
            customerRepository = customerRepository,
            orderRepository = orderRepository,
            discountRepository = discountRepository,
            savedCartsRepository = savedCarts,
            mosaicRepository = mosaic,
            activeCartState = ActiveCartState(),
            secureStorage = secureStorage,
            validateReferralUseCase = validateReferralUseCase,
            captureReferralUseCase = mockk(relaxed = true),
            productDao = productDao,
            productCategoryDao = productCategoryDao,
            catalogoGuardado = catalogoGuardado,
            connectionStateManager = connectionStateManager,
            ventaSinRed = aparato,
            impresoraDeVentaGuardada = mockk(relaxed = true),
            pendingPaymentDao = mockk(relaxed = true) {
                io.mockk.every { contarVentasGuardadas(any()) } returns MutableStateFlow(0)
                io.mockk.every { contarConProblema(any()) } returns MutableStateFlow(0)
            },
            autorizacionDeGerente = autorizacion,
            permissionsRepository = permisos,
        )
    }

    private fun cliente(id: String, nombre: String, descuento: DescuentoDelCliente?) = Customer(
        id = id, firstName = nombre, lastName = null, email = null, phone = null, loyaltyPoints = 0, totalVisits = 0,
        totalSpent = BigDecimal.ZERO, customerGroup = null, descuentoDelCliente = descuento,
    )

    private fun producto(centavos: Int = 10000) = Product(
        id = "p-1", name = "Café", sku = "p-1", price = BigDecimal(centavos).divide(BigDecimal(100)), categoryId = "cat-1",
        categoryName = "Bebidas", description = null, emoji = "☕", imageUrl = null, available = true,
    )

    private fun orden() = Order(
        id = "ord-1", orderNumber = "T-001", venueId = "venue-1", tableId = null, tableName = null, covers = 1, waiterId = "staff-1",
        waiterName = "María", status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING, paymentStatus = PaymentStatus.PENDING,
        orderType = OrderType.TAKEOUT, items = emptyList(), subtotal = BigDecimal.ZERO, tax = BigDecimal.ZERO, total = BigDecimal.ZERO,
        notes = null, createdAt = java.time.Instant.now(), updatedAt = java.time.Instant.now(), version = 1,
    )

    private fun sinPermisoDeDescuento() {
        coEvery { permisos.hasPermission("discounts:apply") } returns false
    }

    /** Un carrito de $100 en importe tecleado con el descuento de Cecilia puesto, guardado en el aparato antes de morir el proceso. */
    private fun carritoGuardadoConElDeCecilia(autorizacion: String?) {
        val cart = CartState(
            items = listOf(CartItem(type = CartItemType.CustomAmount, name = "Importe", unitPriceCents = 10000)),
            orderDiscount = diez.comoDescuento(),
            selectedStaffId = "staff-1",
            descuentoEsDelCliente = true,
            autorizacionDelDescuento = autorizacion,
        )
        aparato.carritos["venue-1"] = VentaSinRedEnMemoria.Guardado("llave-1", cart, cecilia, System.currentTimeMillis())
    }

    private fun cupon() = Discount(
        id = "cup-1", name = "Promo (PROMO5)", type = DiscountType.PERCENTAGE, value = BigDecimal(5), scope = DiscountScope.ORDER,
        conditions = null, active = true, requiresAuthorization = false, maxDiscountAmount = null, couponCode = "PROMO5",
    )

    // ─────────────────────────────────────────────────────────────────────
    // 1-2 · elegir al cliente: con permiso se pone, sin permiso se avisa
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `1 con permiso elegir a Cecilia pone su descuento y el total baja a 9000 sin aviso`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())

        vm.selectCustomer(cecilia)

        val cart = vm.cartState.value
        assertEquals(diez.comoDescuento(), cart.orderDiscount)
        assertTrue(cart.descuentoEsDelCliente)
        assertEquals(9000, cart.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `2 sin permiso y PIN prendido no rebaja y avisa con Pedir autorizacion`() = runTest {
        sinPermisoDeDescuento()
        val vm = createViewModel()
        vm.addProduct(producto())

        vm.selectCustomer(cecilia)

        assertEquals(10000, vm.cartState.value.totalCents)
        assertNull(vm.cartState.value.orderDiscount)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", true), vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `2 sin permiso y PIN apagado avisa sin Pedir autorizacion`() = runTest {
        sinPermisoDeDescuento()
        coEvery { autorizacion.pinPrendido("venue-1") } returns false
        val vm = createViewModel()
        vm.addProduct(producto())

        vm.selectCustomer(cecilia)

        assertEquals(10000, vm.cartState.value.totalCents)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", false), vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3 · ya hay otro descuento de cuenta: no se pisa, se ofrece «Usar el de Cecilia»
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `3 ya hay un descuento manual - no lo pisa, avisa y usar lo reemplaza`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.applyManualOrderDiscount(500, "5 %")

        vm.selectCustomer(cecilia)

        assertEquals(500, vm.cartState.value.manualDiscountCents)
        assertNull(vm.cartState.value.orderDiscount)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(AvisoDescuentoDelCliente.YaHayOtro("Cecilia", "10 %"), vm.avisoDescuentoDelCliente.value)

        vm.usarDescuentoDelCliente()

        val cart = vm.cartState.value
        assertEquals(diez.comoDescuento(), cart.orderDiscount)
        assertTrue(cart.descuentoEsDelCliente)
        assertEquals(0, cart.manualDiscountCents)
        assertEquals(9000, cart.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `3 ya hay un cupon - no lo pisa, avisa y usar lo reemplaza`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.applyOrderDiscount(cupon())

        vm.selectCustomer(cecilia)

        assertEquals("PROMO5", vm.cartState.value.couponCode)
        assertEquals(AvisoDescuentoDelCliente.YaHayOtro("Cecilia", "10 %"), vm.avisoDescuentoDelCliente.value)

        vm.usarDescuentoDelCliente()

        assertEquals(diez.comoDescuento(), vm.cartState.value.orderDiscount)
        assertNull(vm.cartState.value.couponCode)
        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `3 ya hay un referido - no lo pisa, avisa y usar lo reemplaza`() = runTest {
        coEvery { validateReferralUseCase(any(), any(), any()) } returns
            Result.success(ValidationResult.Valid(referrerName = "Ana López", discountPercent = 5, referrerCustomerId = "c-ana"))
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.onReferralCodeChange("ANA-2026")
        vm.validateReferralCode() // el referido reemplaza al de Cecilia (el último descuento gana, como hoy)
        assertEquals(500, vm.cartState.value.manualDiscountCents)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)

        vm.selectCustomer(cecilia) // la vuelve a elegir: el referido se queda y se avisa

        assertEquals(500, vm.cartState.value.manualDiscountCents)
        assertEquals(AvisoDescuentoDelCliente.YaHayOtro("Cecilia", "10 %"), vm.avisoDescuentoDelCliente.value)

        vm.usarDescuentoDelCliente()

        assertEquals(diez.comoDescuento(), vm.cartState.value.orderDiscount)
        assertEquals(0, vm.cartState.value.manualDiscountCents)
        assertNull(vm.cartState.value.manualDiscountSource)
        assertEquals(9000, vm.cartState.value.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `3 usar sin permiso no lo pone y avisa SinPermiso`() = runTest {
        sinPermisoDeDescuento()
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.applyOrderDiscount(cupon())
        vm.selectCustomer(cecilia)

        vm.usarDescuentoDelCliente()

        assertEquals("PROMO5", vm.cartState.value.couponCode)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", true), vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4-6 · quitar, cambiar de cliente y otro descuento encima
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `4 quitar vale para la venta - elegirla otra vez no lo pone, tras vaciar el carrito si`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        vm.quitarDescuentoDelCliente()

        assertNull(vm.cartState.value.orderDiscount)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertTrue(vm.cartState.value.descuentoDelClienteQuitado)
        assertEquals(10000, vm.cartState.value.totalCents)

        vm.selectCustomer(cecilia)
        assertNull(vm.cartState.value.orderDiscount)
        assertNull(vm.avisoDescuentoDelCliente.value)

        vm.selectCustomer(null)
        vm.selectCustomer(cecilia)
        assertNull("Quitar vale aunque la quite y la vuelva a elegir", vm.cartState.value.orderDiscount)
        assertNull(vm.avisoDescuentoDelCliente.value)

        vm.clearCart()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        assertEquals(diez.comoDescuento(), vm.cartState.value.orderDiscount)
        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        assertFalse(vm.cartState.value.descuentoDelClienteQuitado)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `5 cambiar de cliente cambia el descuento y quitar el cliente lo borra`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())

        vm.selectCustomer(cecilia)
        assertEquals(9000, vm.cartState.value.totalCents)

        vm.selectCustomer(ernesto)
        assertEquals(treinta.comoDescuento(), vm.cartState.value.orderDiscount)
        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(7000, vm.cartState.value.totalCents)

        vm.selectCustomer(null)
        assertNull(vm.cartState.value.orderDiscount)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertNull(vm.cartState.value.autorizacionDelDescuento)
        assertEquals(10000, vm.cartState.value.totalCents)

        vm.selectCustomer(ana)
        assertNull(vm.cartState.value.orderDiscount)
        assertEquals(10000, vm.cartState.value.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `5 cambiar de cliente borra la autorizacion del anterior`() = runTest {
        sinPermisoDeDescuento()
        carritoGuardadoConElDeCecilia(autorizacion = "tok-1")
        val vm = createViewModel()
        assertEquals("tok-1", vm.cartState.value.autorizacionDelDescuento)

        vm.selectCustomer(null)

        assertNull(vm.cartState.value.autorizacionDelDescuento)
        assertNull(vm.cartState.value.orderDiscount)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `6 un descuento manual encima del de Cecilia lo reemplaza y apaga la marca`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        vm.applyManualOrderDiscount(2000, "Promo")

        assertNull(vm.cartState.value.orderDiscount)
        assertEquals(2000, vm.cartState.value.manualDiscountCents)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(8000, vm.cartState.value.totalCents)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `6 un cupon o un descuento del catalogo encima del de Cecilia apagan la marca`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        vm.applyOrderDiscount(cupon())

        assertEquals("PROMO5", vm.cartState.value.couponCode)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 7 · carrito restaurado por otra cajera: el cobro vuelve a revisar el permiso
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `7 carrito restaurado sin autorizacion por una cajera sin permiso - avisa y Cobrar lo frena`() = runTest {
        sinPermisoDeDescuento()
        carritoGuardadoConElDeCecilia(autorizacion = null)
        val vm = createViewModel()

        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", true), vm.avisoDescuentoDelCliente.value)

        val resultado = vm.prepareForPayment()

        assertTrue(resultado.isFailure)
        assertEquals(TEXTO_SIN_PERMISO, resultado.exceptionOrNull()?.message)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `7 carrito restaurado con autorizacion cobra aunque la cajera no tenga permiso`() = runTest {
        sinPermisoDeDescuento()
        carritoGuardadoConElDeCecilia(autorizacion = "tok-1")
        val vm = createViewModel()

        assertNull(vm.avisoDescuentoDelCliente.value)

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("Salió $payload", payload is PaymentNavigationPayload.Order)
        coVerify(exactly = 1) { orderRepository.createOrderWithItems("venue-1", any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `7 pagar despues tambien frena el descuento sin permiso ni autorizacion`() = runTest {
        sinPermisoDeDescuento()
        carritoGuardadoConElDeCecilia(autorizacion = null)
        val vm = createViewModel()

        val resultado = vm.createPayLaterOrder()

        assertEquals(TEXTO_SIN_PERMISO, resultado.exceptionOrNull()?.message)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 8-9 · importes tecleados crean orden; un cliente sin descuento, como hoy
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `8 importes tecleados con el descuento de Cecilia crean orden, no cobro rapido`() = runTest {
        val vm = createViewModel()
        vm.addCustomAmount("Importe", 10000)
        vm.selectCustomer(cecilia)
        assertEquals(9000, vm.cartState.value.totalCents)

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("Salió $payload", payload is PaymentNavigationPayload.Order)
        coVerify(exactly = 1) { orderRepository.createOrderWithItems("venue-1", any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `9 un cliente sin descuento no cambia nada - importes van por cobro rapido`() = runTest {
        val vm = createViewModel()
        vm.addCustomAmount("Importe", 10000)

        vm.selectCustomer(ana)

        assertEquals(ana, vm.selectedCustomer.value)
        assertNull(vm.cartState.value.orderDiscount)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(10000, vm.cartState.value.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)
        assertTrue(vm.prepareForPayment().getOrThrow() is PaymentNavigationPayload.Fast)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `9 sin permiso un cliente sin descuento no avisa nada`() = runTest {
        sinPermisoDeDescuento()
        val vm = createViewModel()
        vm.addProduct(producto())

        vm.selectCustomer(ana)

        assertNull(vm.avisoDescuentoDelCliente.value)
        coVerify(exactly = 0) { autorizacion.pinPrendido(any()) }
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 10 · sin red el descuento del cliente no se cobra (diseño v5): el freno vive en ventaLocal
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `10 con permiso y sin red Cobrar se frena y no guarda venta local`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        connectionStateManager.setInternetConnected(false)

        val resultado = vm.prepareForPayment()

        assertEquals(TEXTO_SIN_RED, resultado.exceptionOrNull()?.message)
        assertTrue(aparato.filas.isEmpty())
        assertNull("No quedó carrito a medias de una venta local", aparato.carritos["venue-1"])
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        assertTrue("El carrito se conserva", vm.cartState.value.descuentoEsDelCliente)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `10 sin permiso y sin red Cobrar se frena con el texto sin red, con y sin autorizacion`() = runTest {
        sinPermisoDeDescuento()
        connectionStateManager.setInternetConnected(false)
        for (token in listOf(null, "tok-1")) {
            aparato.filas.clear()
            carritoGuardadoConElDeCecilia(autorizacion = token)
            val vm = createViewModel()

            val resultado = vm.prepareForPayment()

            assertEquals("autorización=$token", TEXTO_SIN_RED, resultado.exceptionOrNull()?.message)
            assertTrue(aparato.filas.isEmpty())
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `10 con red pero with-items falla por red - el mismo freno, sin venta local`() = runTest {
        coEvery { orderRepository.createOrderWithItems("venue-1", any()) } returns
            Result.failure(ApiException.NetworkError(java.net.SocketTimeoutException("timeout")))
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        val resultado = vm.prepareForPayment()

        assertEquals(TEXTO_SIN_RED, resultado.exceptionOrNull()?.message)
        assertTrue(aparato.filas.isEmpty())
        coVerify(exactly = 1) { orderRepository.createOrderWithItems("venue-1", any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `10 tras quitar el descuento la venta sin red se guarda como hoy`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        connectionStateManager.setInternetConnected(false)

        vm.quitarDescuentoDelCliente()
        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("Salió $payload", payload is PaymentNavigationPayload.VentaLocal)
        assertEquals("100.0", payload.amountPesosString)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `sin red pedir autorizacion avisa que no se puede`() = runTest {
        sinPermisoDeDescuento()
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        connectionStateManager.setInternetConnected(false)

        vm.pedirAutorizacionDescuentoDelCliente()

        assertEquals(AvisoDescuentoDelCliente.SinRedParaPin, vm.avisoDescuentoDelCliente.value)
        coVerify(exactly = 0) { autorizacion.paraCortesia(any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 11 · tabla compartida de redondeo (la misma que la prueba del SERVIDOR, Task 3) y el texto del valor
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `11 tabla compartida de redondeo con el descuento del cliente`() {
        data class Caso(val base: Int, val tipo: DiscountType, val valor: Double, val tope: Double?, val esperado: Int)
        val casos = listOf(
            Caso(2135, DiscountType.PERCENTAGE, 10.0, null, 214),
            Caso(375, DiscountType.PERCENTAGE, 9.2, null, 35),
            Caso(10000, DiscountType.PERCENTAGE, 10.0, null, 1000),
            Caso(999, DiscountType.PERCENTAGE, 33.33, null, 333),
            Caso(10000, DiscountType.PERCENTAGE, 30.0, 20.0, 2000),
            Caso(5000, DiscountType.FIXED, 80.0, null, 5000),
            Caso(1, DiscountType.PERCENTAGE, 50.0, null, 1),
            Caso(0, DiscountType.PERCENTAGE, 10.0, null, 0),
        )
        for (c in casos) {
            val cart = CartState(
                items = listOf(CartItem(type = CartItemType.CustomAmount, name = "Importe", unitPriceCents = c.base)),
                orderDiscount = DescuentoDelCliente("d", "D", c.tipo, c.valor, c.tope).comoDescuento(),
            )
            assertEquals("$c", c.esperado, cart.discountCents)
        }
    }

    @Test
    fun `el valor se escribe sin decimales si es entero y en pesos si es fijo`() {
        assertEquals("10 %", textoDelValor(diez))
        assertEquals("12.5 %", textoDelValor(diez.copy(valor = 12.5)))
        assertEquals("33.33 %", textoDelValor(diez.copy(valor = 33.33)))
        assertEquals("\$50.00", textoDelValor(DescuentoDelCliente("d", "D", DiscountType.FIXED, 50.0, null)))
        assertEquals("\$12.50", textoDelValor(DescuentoDelCliente("d", "D", DiscountType.FIXED, 12.5, null)))
    }
}
