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
import com.jaac.avoqado_tpv.features.ordering.domain.TpvCreateOrderWithItemsRequest
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
        assertEquals(setOf("c-cecilia"), vm.cartState.value.descuentosDelClienteQuitados)
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
        assertEquals(emptySet<String>(), vm.cartState.value.descuentosDelClienteQuitados)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `4 quitar es por cliente - Ernesto lleva el suyo y de regreso Cecilia sigue sin el suyo`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.quitarDescuentoDelCliente()

        vm.selectCustomer(ernesto)
        assertEquals(treinta.comoDescuento(), vm.cartState.value.orderDiscount)
        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(7000, vm.cartState.value.totalCents)

        vm.selectCustomer(cecilia)
        assertNull(vm.cartState.value.orderDiscount)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(10000, vm.cartState.value.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `4 quitar recuerda a cada cliente - Cecilia, Ernesto y de regreso Cecilia no vuelve a ponerse`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.quitarDescuentoDelCliente()
        vm.selectCustomer(ernesto)
        vm.quitarDescuentoDelCliente()
        assertEquals(setOf("c-cecilia", "c-ernesto"), vm.cartState.value.descuentosDelClienteQuitados)

        vm.selectCustomer(cecilia)
        assertNull(vm.cartState.value.orderDiscount)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.selectCustomer(ernesto)
        assertNull(vm.cartState.value.orderDiscount)
        assertNull(vm.avisoDescuentoDelCliente.value)
        assertEquals(10000, vm.cartState.value.totalCents)
        vm.viewModelScope.cancel()
    }

    // Codex r7 #2: la revisión espera el permiso (puede ir a la red); un «Quitar» tocado mientras tanto no se deshace al volver.
    @Test
    fun `4 quitar mientras se revisa el permiso no se deshace al llegar la respuesta`() = runTest {
        val permiso = kotlinx.coroutines.CompletableDeferred<Boolean>()
        coEvery { permisos.hasPermission("discounts:apply") } coAnswers { permiso.await() }
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.quitarDescuentoDelCliente()

        permiso.complete(true)

        assertNull(vm.cartState.value.orderDiscount)
        assertEquals(setOf("c-cecilia"), vm.cartState.value.descuentosDelClienteQuitados)
        assertNull(vm.avisoDescuentoDelCliente.value)
        assertEquals(10000, vm.cartState.value.totalCents)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `4 quitar mientras se revisa el permiso no deja volver su aviso`() = runTest {
        val permiso = kotlinx.coroutines.CompletableDeferred<Boolean>()
        coEvery { permisos.hasPermission("discounts:apply") } coAnswers { permiso.await() }
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.quitarDescuentoDelCliente()

        permiso.complete(false)

        assertNull(vm.cartState.value.orderDiscount)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `4 Usar el de Cecilia tras quitar lo pone y la saca de las quitadas`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.quitarDescuentoDelCliente()
        vm.selectCustomer(ernesto)
        vm.quitarDescuentoDelCliente()
        vm.selectCustomer(cecilia)

        vm.usarDescuentoDelCliente()

        assertEquals(diez.comoDescuento(), vm.cartState.value.orderDiscount)
        assertEquals(setOf("c-ernesto"), vm.cartState.value.descuentosDelClienteQuitados)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `4 el Quitar descuento de los atajos cuenta como quitar el de Cecilia`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        // Lo que hace «Quitar descuento» de los atajos (CheckoutScreen, onClearManualDiscount).
        vm.clearManualOrderDiscount()
        vm.applyOrderDiscount(null)
        vm.selectCustomer(cecilia)

        assertNull(vm.cartState.value.orderDiscount)
        assertEquals(setOf("c-cecilia"), vm.cartState.value.descuentosDelClienteQuitados)
        assertEquals(10000, vm.cartState.value.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `4 quitar un descuento manual en los atajos no marca al cliente`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.applyManualOrderDiscount(500, "5 %")
        vm.selectCustomer(cecilia) // YaHayOtro

        vm.clearManualOrderDiscount()
        vm.applyOrderDiscount(null)

        assertEquals(emptySet<String>(), vm.cartState.value.descuentosDelClienteQuitados)
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tras limpiar el carrito con el cliente todavía elegido, la venta que sigue trae su descuento
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `tras limpiar el carrito con Cecilia elegida su descuento vuelve para la venta que sigue`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        vm.clearCart()
        vm.addCustomAmount("Importe", 10000)

        assertEquals(cecilia, vm.selectedCustomer.value)
        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(9000, vm.cartState.value.totalCents)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `tras limpiar el carrito sin permiso vuelve su aviso`() = runTest {
        sinPermisoDeDescuento()
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        vm.clearCart()
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", true), vm.avisoDescuentoDelCliente.value)
        vm.addCustomAmount("Importe", 10000)

        assertEquals(10000, vm.cartState.value.totalCents)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `tras limpiar el carrito sin red no se pone solo`() = runTest {
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        connectionStateManager.setInternetConnected(false)

        vm.clearCart()

        assertNull(vm.cartState.value.orderDiscount)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `tras guardar una venta en 0 pesos con Cecilia elegida su descuento vuelve al soltarse la venta`() = runTest {
        val vm = createViewModel()
        vm.selectCustomer(cecilia)
        vm.quitarDescuentoDelCliente()
        vm.addCustomAmount("Servicio", 5000)
        vm.applyCortesiaToItem(vm.cartState.value.items.single().id, "Cortesía")

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("Salió $payload", payload is PaymentNavigationPayload.VentaEnCeroGuardada)
        assertTrue(vm.cartState.value.isEmpty)
        assertEquals(diez.comoDescuento(), vm.cartState.value.orderDiscount)
        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `sin nombre de pila los textos usan su apellido o El cliente`() = runTest {
        sinPermisoDeDescuento()
        val vm = createViewModel()
        vm.addProduct(producto())

        vm.selectCustomer(cecilia.copy(id = "c-v", firstName = "  ", lastName = "Valdivia"))
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Valdivia", "10 %", true), vm.avisoDescuentoDelCliente.value)

        vm.selectCustomer(cecilia.copy(id = "c-x", firstName = "", lastName = null, phone = " ", email = null))
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("El cliente", "10 %", true), vm.avisoDescuentoDelCliente.value)
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

    // Codex r8 #4: el «Quitar descuento» de los atajos quita el del cliente; su aviso (y «Pedir autorización») se va con él.
    @Test
    fun `7 carrito restaurado sin permiso - el Quitar de los atajos quita el descuento y su aviso`() = runTest {
        sinPermisoDeDescuento()
        carritoGuardadoConElDeCecilia(autorizacion = null)
        val vm = createViewModel()
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", true), vm.avisoDescuentoDelCliente.value)

        vm.applyOrderDiscount(null)

        assertNull(vm.cartState.value.orderDiscount)
        assertTrue("c-cecilia" in vm.cartState.value.descuentosDelClienteQuitados)
        assertNull(vm.avisoDescuentoDelCliente.value)
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
        coVerify(exactly = 0) { autorizacion.paraPermiso(any(), any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Task 6 · «Pedir autorización»: PIN de gerente para `discounts:apply`, atado a Cecilia y a su descuento
    // ─────────────────────────────────────────────────────────────────────

    private val LLAVE_CECILIA = "descuento-cliente:c-cecilia:d-10"

    private fun pinDeCecilia(r: Autorizacion) {
        coEvery {
            autorizacion.paraPermiso("venue-1", "discounts:apply", "discounts:apply", "Descuento de Cecilia", LLAVE_CECILIA, false)
        } returns r
    }

    @Test
    fun `PIN correcto pone el descuento de Cecilia con su autorizacion, que sigue al agregar y se va al cambiar de cliente`() = runTest {
        sinPermisoDeDescuento()
        pinDeCecilia(Autorizacion.ConPin("tok-d"))
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", true), vm.avisoDescuentoDelCliente.value)

        vm.pedirAutorizacionDescuentoDelCliente()

        coVerify(exactly = 1) {
            autorizacion.paraPermiso("venue-1", "discounts:apply", "discounts:apply", "Descuento de Cecilia", LLAVE_CECILIA, false)
        }
        assertEquals(diez.comoDescuento(), vm.cartState.value.orderDiscount)
        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        assertEquals("tok-d", vm.cartState.value.autorizacionDelDescuento)
        assertEquals(9000, vm.cartState.value.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)

        vm.addCustomAmount("Importe", 5000)
        assertEquals("Agregar otro importe no borra la autorización", "tok-d", vm.cartState.value.autorizacionDelDescuento)
        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(13500, vm.cartState.value.totalCents)

        vm.selectCustomer(ernesto)
        assertNull(vm.cartState.value.autorizacionDelDescuento)
        assertNull(vm.cartState.value.orderDiscount)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Ernesto", "30 %", true), vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `con la autorizacion del PIN Cobrar pasa aunque la cajera no tenga permiso`() = runTest {
        sinPermisoDeDescuento()
        pinDeCecilia(Autorizacion.ConPin("tok-d"))
        val vm = createViewModel()
        vm.addCustomAmount("Importe", 10000)
        vm.selectCustomer(cecilia)
        vm.pedirAutorizacionDescuentoDelCliente()

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("Salió $payload", payload is PaymentNavigationPayload.Order)
        coVerify(exactly = 1) { orderRepository.createOrderWithItems("venue-1", any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `PIN cancelado o apagado sigue el aviso y no pone nada`() = runTest {
        sinPermisoDeDescuento()
        pinDeCecilia(Autorizacion.Cancelada)
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        vm.pedirAutorizacionDescuentoDelCliente()

        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", true), vm.avisoDescuentoDelCliente.value)
        assertNull(vm.cartState.value.orderDiscount)
        assertNull(vm.cartState.value.autorizacionDelDescuento)
        assertEquals(10000, vm.cartState.value.totalCents)

        // El negocio apagó el PIN: el aviso se queda, ya sin «Pedir autorización».
        pinDeCecilia(Autorizacion.Negada("Lo autoriza un gerente con su sesión"))
        coEvery { autorizacion.pinPrendido("venue-1") } returns false
        vm.pedirAutorizacionDescuentoDelCliente()

        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", false), vm.avisoDescuentoDelCliente.value)
        assertNull(vm.cartState.value.orderDiscount)
        assertNull(vm.cartState.value.autorizacionDelDescuento)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `si pedir el PIN falla se dice y sigue el aviso`() = runTest {
        sinPermisoDeDescuento()
        coEvery { autorizacion.paraPermiso(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("boom")
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        vm.pedirAutorizacionDescuentoDelCliente()

        assertEquals("No se pudo pedir la autorización. Intenta otra vez", vm.aviso.value)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", true), vm.avisoDescuentoDelCliente.value)
        assertNull(vm.cartState.value.autorizacionDelDescuento)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `si cambia de cliente mientras el gerente teclea el token no se pone a nadie`() = runTest {
        sinPermisoDeDescuento()
        val gerente = kotlinx.coroutines.CompletableDeferred<Autorizacion>()
        coEvery { autorizacion.paraPermiso(any(), any(), any(), any(), any(), any()) } coAnswers { gerente.await() }
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.pedirAutorizacionDescuentoDelCliente()

        vm.selectCustomer(ernesto)
        gerente.complete(Autorizacion.ConPin("tok-d"))

        assertNull(vm.cartState.value.autorizacionDelDescuento)
        assertNull(vm.cartState.value.orderDiscount)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Ernesto", "30 %", true), vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `doble toque en Pedir autorizacion abre un solo PIN`() = runTest {
        sinPermisoDeDescuento()
        val gerente = kotlinx.coroutines.CompletableDeferred<Autorizacion>()
        coEvery { autorizacion.paraPermiso(any(), any(), any(), any(), any(), any()) } coAnswers { gerente.await() }
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        vm.pedirAutorizacionDescuentoDelCliente()
        vm.pedirAutorizacionDescuentoDelCliente()
        gerente.complete(Autorizacion.ConPin("tok-d"))

        coVerify(exactly = 1) { autorizacion.paraPermiso(any(), any(), any(), any(), any(), any()) }
        assertEquals("tok-d", vm.cartState.value.autorizacionDelDescuento)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `SinRedParaPin no atrapa - con la red de vuelta pedir otra vez abre el PIN`() = runTest {
        sinPermisoDeDescuento()
        pinDeCecilia(Autorizacion.ConPin("tok-d"))
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        connectionStateManager.setInternetConnected(false)
        vm.pedirAutorizacionDescuentoDelCliente()
        assertEquals(AvisoDescuentoDelCliente.SinRedParaPin, vm.avisoDescuentoDelCliente.value)

        connectionStateManager.setInternetConnected(true)
        vm.pedirAutorizacionDescuentoDelCliente()

        assertEquals("tok-d", vm.cartState.value.autorizacionDelDescuento)
        assertTrue(vm.cartState.value.descuentoEsDelCliente)
        assertNull(vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `SinRedParaPin - elegirla otra vez o limpiar el carrito regresa al aviso normal`() = runTest {
        sinPermisoDeDescuento()
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        val normal = AvisoDescuentoDelCliente.SinPermiso("Cecilia", "10 %", true)

        connectionStateManager.setInternetConnected(false)
        vm.pedirAutorizacionDescuentoDelCliente()
        connectionStateManager.setInternetConnected(true)
        vm.selectCustomer(cecilia)
        assertEquals(normal, vm.avisoDescuentoDelCliente.value)

        connectionStateManager.setInternetConnected(false)
        vm.pedirAutorizacionDescuentoDelCliente()
        connectionStateManager.setInternetConnected(true)
        vm.clearCart()
        assertEquals(normal, vm.avisoDescuentoDelCliente.value)
        coVerify(exactly = 0) { autorizacion.paraPermiso(any(), any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `elegir otra vez a Cecilia con el mismo descuento conserva la autorizacion, con otro la borra y se decide de nuevo`() = runTest {
        sinPermisoDeDescuento()
        pinDeCecilia(Autorizacion.ConPin("tok-d"))
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.pedirAutorizacionDescuentoDelCliente()
        assertEquals("tok-d", vm.cartState.value.autorizacionDelDescuento)

        vm.selectCustomer(cecilia)
        assertEquals("mismo cliente y mismo descuento", "tok-d", vm.cartState.value.autorizacionDelDescuento)
        assertEquals(diez.comoDescuento(), vm.cartState.value.orderDiscount)

        // El negocio le cambió el descuento a Cecilia: la autorización era del de 10 %, no del de 30 %.
        vm.selectCustomer(cecilia.copy(descuentoDelCliente = treinta))
        assertNull(vm.cartState.value.autorizacionDelDescuento)
        assertNull(vm.cartState.value.orderDiscount)
        assertFalse(vm.cartState.value.descuentoEsDelCliente)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "30 %", true), vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `si a Cecilia le cambian el descuento mientras el gerente teclea el token no se pone`() = runTest {
        sinPermisoDeDescuento()
        val gerente = kotlinx.coroutines.CompletableDeferred<Autorizacion>()
        coEvery { autorizacion.paraPermiso(any(), any(), any(), any(), any(), any()) } coAnswers { gerente.await() }
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.pedirAutorizacionDescuentoDelCliente()

        vm.selectCustomer(cecilia.copy(descuentoDelCliente = treinta))
        gerente.complete(Autorizacion.ConPin("tok-d"))

        assertNull(vm.cartState.value.autorizacionDelDescuento)
        assertNull(vm.cartState.value.orderDiscount)
        assertEquals(AvisoDescuentoDelCliente.SinPermiso("Cecilia", "30 %", true), vm.avisoDescuentoDelCliente.value)
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Task 7 · lo que viaja en `with-items`: el id, el monto exacto y, con PIN, la autorización (en el cuerpo, nunca en el encabezado)
    // ─────────────────────────────────────────────────────────────────────

    private val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
    private val encabezados = mutableListOf<String?>()

    private fun capturarPedidos(respuesta: Result<Order> = Result.success(orden())) {
        coEvery { orderRepository.createOrderWithItems(any(), capture(enviados), captureNullable(encabezados)) } returns respuesta
    }

    private fun assertPesos(esperado: String, real: BigDecimal) =
        assertEquals("esperaba $esperado, salió $real", 0, BigDecimal(esperado).compareTo(real))

    @Test
    fun `cobro en linea con el de Cecilia manda su id, su monto y el total`() = runTest {
        capturarPedidos()
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)

        vm.prepareForPayment().getOrThrow()

        val pedido = enviados.single()
        assertEquals("d-10", pedido.orderDiscountId)
        assertPesos("10.00", pedido.discount)
        assertPesos("100.00", pedido.subtotal)
        assertPesos("90.00", pedido.total)
        assertEquals("c-cecilia", pedido.customerId)
        assertNull(pedido.couponCode)
        assertNull("con permiso no hay autorización", pedido.autorizacionDelDescuento)
        assertNull(encabezados.single())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `con el PIN el cuerpo lleva ademas la autorizacion y el encabezado sigue vacio`() = runTest {
        sinPermisoDeDescuento()
        pinDeCecilia(Autorizacion.ConPin("tok-d"))
        capturarPedidos()
        val vm = createViewModel()
        vm.addCustomAmount("Importe", 10000)
        vm.selectCustomer(cecilia)
        vm.pedirAutorizacionDescuentoDelCliente()

        vm.prepareForPayment().getOrThrow()

        val pedido = enviados.single()
        assertEquals("tok-d", pedido.autorizacionDelDescuento)
        assertEquals("d-10", pedido.orderDiscountId)
        assertPesos("10.00", pedido.discount)
        assertPesos("90.00", pedido.total)
        assertNull("X-Permission-Override es sólo de la cortesía", encabezados.single())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `cortesia con PIN y descuento del cliente - encabezado de cortesia y autorizacion en el cuerpo`() = runTest {
        coEvery { permisos.hasPermission(any()) } returns false // ni tpv-orders:comp ni discounts:apply
        coEvery { autorizacion.paraCortesia("venue-1", "Para cobrar con cortesía", false) } returns Autorizacion.ConPin("tok-c")
        pinDeCecilia(Autorizacion.ConPin("tok-d"))
        capturarPedidos()
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.applyCortesiaToItem(vm.cartState.value.items.first().id, "Cliente frecuente")
        vm.addCustomAmount("Importe", 10000)
        vm.selectCustomer(cecilia)
        vm.pedirAutorizacionDescuentoDelCliente()

        vm.prepareForPayment().getOrThrow()

        val pedido = enviados.single()
        assertEquals("tok-c", encabezados.single())
        assertEquals("tok-d", pedido.autorizacionDelDescuento)
        assertTrue(pedido.items.first().isCortesia)
        assertPesos("10.00", pedido.discount)
        assertPesos("90.00", pedido.total)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `misma venta con y sin autorizacion - misma llave al restaurar y reintentar tras morir la app`() = runTest {
        // La cajera con permiso tocó Cobrar y la app murió: el carrito quedó con la llave-1 y SIN autorización. Lo retoma otra sin
        // permiso, pide el PIN y cobra: la orden es la misma (si la primera sí llegó, el servidor la devuelve por `externalId`).
        sinPermisoDeDescuento()
        pinDeCecilia(Autorizacion.ConPin("tok-d"))
        carritoGuardadoConElDeCecilia(autorizacion = null)
        capturarPedidos(Result.failure(ApiException.HttpError(500, "boom", "Error al crear orden: 500")))
        val vm = createViewModel()
        vm.pedirAutorizacionDescuentoDelCliente()
        assertEquals("tok-d", vm.cartState.value.autorizacionDelDescuento)

        assertTrue(vm.prepareForPayment().isFailure)
        assertEquals("el carrito durable conserva su llave", "llave-1", aparato.carritos["venue-1"]?.llave)
        capturarPedidos()
        vm.prepareForPayment().getOrThrow()

        assertEquals(listOf("llave-1", "llave-1"), enviados.map { it.externalId })
        assertEquals(listOf("tok-d", "tok-d"), enviados.map { it.autorizacionDelDescuento })
        assertPesos("10.00", enviados.last().discount)
        assertPesos("90.00", enviados.last().total)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `carrito restaurado con autorizacion manda el mismo cuerpo con su llave`() = runTest {
        sinPermisoDeDescuento()
        carritoGuardadoConElDeCecilia(autorizacion = "tok-1")
        capturarPedidos()
        val vm = createViewModel()

        vm.prepareForPayment().getOrThrow()

        val pedido = enviados.single()
        assertEquals("llave-1", pedido.externalId)
        assertEquals("tok-1", pedido.autorizacionDelDescuento)
        assertEquals("d-10", pedido.orderDiscountId)
        assertEquals("c-cecilia", pedido.customerId)
        assertPesos("10.00", pedido.discount)
        assertPesos("90.00", pedido.total)
        assertNull(encabezados.single())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `pagar despues con el de Cecilia y PIN manda el monto y la autorizacion`() = runTest {
        sinPermisoDeDescuento()
        pinDeCecilia(Autorizacion.ConPin("tok-d"))
        capturarPedidos()
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.pedirAutorizacionDescuentoDelCliente()

        assertTrue(vm.createPayLaterOrder().isSuccess)

        val pedido = enviados.single()
        assertEquals("tok-d", pedido.autorizacionDelDescuento)
        assertEquals("d-10", pedido.orderDiscountId)
        assertPesos("10.00", pedido.discount)
        assertPesos("90.00", pedido.total)
        assertNull(encabezados.single())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `tras Quitar o con otro descuento del catalogo la autorizacion no viaja y el catalogo manda su monto`() = runTest {
        sinPermisoDeDescuento()
        pinDeCecilia(Autorizacion.ConPin("tok-d"))
        capturarPedidos(Result.failure(ApiException.HttpError(500, "boom", "Error al crear orden: 500")))
        val vm = createViewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cecilia)
        vm.pedirAutorizacionDescuentoDelCliente()

        vm.quitarDescuentoDelCliente()
        vm.prepareForPayment()

        val sinDescuento = enviados.last()
        assertNull(sinDescuento.autorizacionDelDescuento)
        assertNull(sinDescuento.orderDiscountId)
        assertPesos("0", sinDescuento.discount)

        // Un descuento del catálogo que pone la cajera (no es del cliente): su monto viaja (antes 0) y la autorización no.
        val delCatalogo = Discount(
            id = "d-cat", name = "Lunes 20 %", type = DiscountType.PERCENTAGE, value = BigDecimal(20), scope = DiscountScope.ORDER,
            conditions = null, active = true, requiresAuthorization = false,
        )
        vm.applyOrderDiscount(delCatalogo)
        vm.prepareForPayment()

        val conCatalogo = enviados.last()
        assertEquals("d-cat", conCatalogo.orderDiscountId)
        assertPesos("20.00", conCatalogo.discount)
        assertPesos("80.00", conCatalogo.total)
        assertNull(conCatalogo.autorizacionDelDescuento)
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
