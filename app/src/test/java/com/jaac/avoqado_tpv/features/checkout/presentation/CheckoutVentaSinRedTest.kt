package com.jaac.avoqado_tpv.features.checkout.presentation

import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.features.checkout.VentaSinRedEnMemoria
import com.jaac.avoqado_tpv.features.checkout.data.ActiveCartState
import com.jaac.avoqado_tpv.features.checkout.data.ImpresoraDeVentaGuardada
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * «Cobrar» sin red, C2b: la regla de `prepareForPayment` (D3 del spec), el carrito en $0 sin abrir la pantalla de cobro, el carrito a
 * medias que vuelve al abrir Cobrar y el candado de «venta en preparación». El aparato (carrito en curso + filas) es
 * [VentaSinRedEnMemoria]: un ViewModel nuevo con el mismo objeto es el proceso que murió y se reabrió.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutVentaSinRedTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var productRepository: ProductRepository
    private lateinit var orderRepository: OrderRepository
    private lateinit var customerRepository: CustomerRepository
    private lateinit var discountRepository: com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
    private lateinit var secureStorage: SecureStorage
    private lateinit var productDao: ProductDao
    private lateinit var productCategoryDao: ProductCategoryDao
    private lateinit var catalogoGuardado: CatalogoGuardado
    private lateinit var connectionStateManager: ConnectionStateManager
    private lateinit var impresora: ImpresoraDeVentaGuardada
    private lateinit var aparato: VentaSinRedEnMemoria
    private lateinit var permissions: PermissionsRepository
    private lateinit var paymentDao: PendingPaymentDao
    private val guardadas = MutableStateFlow(0)
    private val problemas = MutableStateFlow(0)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        CheckoutViewModel.despachadorDelCatalogo = Dispatchers.Unconfined

        productRepository = mockk(relaxed = true)
        orderRepository = mockk(relaxed = true)
        customerRepository = mockk(relaxed = true)
        discountRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        productDao = mockk(relaxed = true)
        productCategoryDao = mockk(relaxed = true)
        catalogoGuardado = mockk()
        connectionStateManager = ConnectionStateManager() // arranca con red
        impresora = mockk(relaxed = true)
        aparato = VentaSinRedEnMemoria()
        permissions = mockk()
        coEvery { permissions.hasPermission(any()) } returns true
        paymentDao = mockk(relaxed = true)
        io.mockk.every { paymentDao.contarVentasGuardadas("venue-1") } returns guardadas
        io.mockk.every { paymentDao.contarConProblema("venue-1") } returns problemas
        io.mockk.every { paymentDao.contarVentasGuardadas("venue-2") } returns MutableStateFlow(7)
        io.mockk.every { paymentDao.contarConProblema("venue-2") } returns MutableStateFlow(2)
        guardadas.value = 0
        problemas.value = 0

        coEvery { secureStorage.getVenueId() } returns "venue-1"
        coEvery { secureStorage.getStaffId() } returns "staff-1"
        coEvery { secureStorage.getStaffName() } returns "María"
        coEvery { productDao.getAllProducts(any()) } returns emptyList()
        coEvery { productCategoryDao.getAllCategories(any()) } returns emptyList()
        coEvery { catalogoGuardado.refrescar(any()) } returns Result.success(emptyList())
        coEvery { productRepository.getCategories(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        CheckoutViewModel.despachadorDelCatalogo = Dispatchers.Default
    }

    private fun viewModel(): CheckoutViewModel {
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
            validateReferralUseCase = mockk(relaxed = true),
            captureReferralUseCase = mockk(relaxed = true),
            productDao = productDao,
            productCategoryDao = productCategoryDao,
            catalogoGuardado = catalogoGuardado,
            connectionStateManager = connectionStateManager,
            ventaSinRed = aparato,
            impresoraDeVentaGuardada = impresora,
            pendingPaymentDao = paymentDao,
            permissionsRepository = permissions,
        )
    }

    private fun producto(id: String = "p-1", nombre: String = "Café", centavos: Int = 5000) = Product(
        id = id, name = nombre, sku = id, price = BigDecimal(centavos).divide(BigDecimal(100)), categoryId = "cat-1",
        categoryName = "Bebidas", description = null, emoji = "☕", imageUrl = null, available = true,
        catalogAt = "2026-10-20T15:00:00.000Z",
    )

    private fun orden(id: String = "ord-1", numero: String = "T-001") = Order(
        id = id, orderNumber = numero, venueId = "venue-1", tableId = null, tableName = null, covers = 1, waiterId = "staff-1",
        waiterName = "María", status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING, paymentStatus = PaymentStatus.PENDING,
        orderType = OrderType.TAKEOUT, items = emptyList(), subtotal = BigDecimal.ZERO, tax = BigDecimal.ZERO, total = BigDecimal.ZERO,
        notes = null, createdAt = java.time.Instant.now(), updatedAt = java.time.Instant.now(), version = 1,
    )

    private fun cliente(id: String = "c-1") = Customer(
        id = id, firstName = "Ana", lastName = "López", email = null, phone = "5551234567", loyaltyPoints = 0, totalVisits = 0,
        totalSpent = BigDecimal.ZERO, customerGroup = null,
    )

    private fun ventaDe(payload: PaymentNavigationPayload): VentaLocal {
        assertTrue("Se esperaba una venta local y salió $payload", payload is PaymentNavigationPayload.VentaLocal)
        return Gson().fromJson((payload as PaymentNavigationPayload.VentaLocal).ventaLocalJson, VentaLocal::class.java)
    }

    private fun pedido(venta: VentaLocal): JsonObject = JsonParser.parseString(venta.pedidoJson).asJsonObject

    /** Arranca la operación YA (hasta su primera espera) y deja seguir a la prueba con la operación en vuelo. */
    private fun <T> TestScope.arrancar(bloque: suspend () -> T) = async(start = CoroutineStart.UNDISPATCHED) { bloque() }

    private val error503 = ApiException.HttpError(503, "Error al crear orden: 503", "Error al crear orden: 503")
    private val error422 = ApiException.HttpError(422, "Producto archivado", "Producto archivado")

    // ─────────────────────────────────────────────────────────────────────
    // La regla de prepareForPayment
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `sin red con productos ⇒ VentaLocal con la llave del intento, guardada antes y sin llamar al servidor`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        vm.addProduct(producto(), quantity = 2)

        val payload = vm.prepareForPayment().getOrThrow()

        val venta = ventaDe(payload)
        assertEquals("100.0", payload.amountPesosString)
        assertEquals(10000, venta.totalCents)
        assertEquals("venue-1", venta.venueId)
        assertEquals("staff-1", venta.staffId)
        assertEquals("shift-1", venta.shiftId)
        // La llave del carrito durable es la de la venta y el externalId del pedido.
        assertEquals(venta.llave, aparato.carritos["venue-1"]?.llave)
        assertEquals(venta.llave, pedido(venta).get("externalId").asString)
        assertTrue(pedido(venta).get("isOfflineReplay").asBoolean)
        assertEquals("shift-1", pedido(venta).get("shiftIdDeLaVenta").asString)
        assertEquals(listOf("${venta.llave}:0"), venta.renglones.map { it.externalId })
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `createOrder falla con 503 ⇒ VentaLocal con la MISMA llave que el intento, guardada ANTES del POST`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        var llaveGuardadaAlLlamar: String? = null
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } coAnswers {
            llaveGuardadaAlLlamar = aparato.carritos["venue-1"]?.llave
            Result.failure(error503)
        }
        val vm = viewModel()
        vm.addProduct(producto())

        val venta = ventaDe(vm.prepareForPayment().getOrThrow())

        val externalId = enviados.single().externalId
        assertNotNull(externalId)
        assertEquals(externalId, llaveGuardadaAlLlamar) // el carrito durable ya estaba escrito cuando salió el POST
        assertEquals(externalId, venta.llave)
        assertEquals(externalId, pedido(venta).get("externalId").asString)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `createOrder con el socket cortado ⇒ VentaLocal con la misma llave`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } returns
            Result.failure(ApiException.NetworkError(java.net.SocketTimeoutException("timeout")))
        val vm = viewModel()
        vm.addProduct(producto())

        val venta = ventaDe(vm.prepareForPayment().getOrThrow())

        assertEquals(enviados.single().externalId, venta.llave)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `createOrder falla con 422 ⇒ error como hoy, sin venta local`() = runTest {
        coEvery { orderRepository.createOrderWithItems("venue-1", any()) } returns Result.failure(error422)
        val vm = viewModel()
        vm.addProduct(producto())

        val resultado = vm.prepareForPayment()

        assertTrue(resultado.isFailure)
        assertEquals(error422, resultado.exceptionOrNull())
        assertEquals(1, vm.cartState.value.items.size)
        assertTrue(aparato.filas.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `con red todo como hoy - Order con su orden y el carrito durable se borra al crearla`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        var llaveGuardadaAlLlamar: String? = null
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } coAnswers {
            llaveGuardadaAlLlamar = aparato.carritos["venue-1"]?.llave
            Result.success(orden())
        }
        val vm = viewModel()
        vm.addProduct(producto())

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue(payload is PaymentNavigationPayload.Order)
        assertEquals("ord-1", (payload as PaymentNavigationPayload.Order).orderId)
        assertEquals(enviados.single().externalId, llaveGuardadaAlLlamar)
        assertNull("La venta en línea terminó: no queda carrito a medias", aparato.carritos["venue-1"])
        vm.viewModelScope.cancel()
    }

    @Test
    fun `guardar el carrito en el aparato falla ⇒ no sale a la red y se ve un error claro`() = runTest {
        aparato.guardarCarritoFalla = true
        val vm = viewModel()
        vm.addProduct(producto())

        val resultado = vm.prepareForPayment()

        assertTrue(resultado.isFailure)
        assertEquals("No se pudo guardar la venta en la terminal. Intenta de nuevo.", resultado.exceptionOrNull()?.message)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        assertEquals(1, vm.cartState.value.items.size)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `sin red sólo importes mayores a $0 ⇒ VentaLocal con líneas libres`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        vm.addCustomAmount("Otro importe", 2500)

        val venta = ventaDe(vm.prepareForPayment().getOrThrow())

        assertEquals(2500, venta.totalCents)
        val linea = pedido(venta).getAsJsonArray("items").single().asJsonObject
        assertFalse(linea.has("productId"))
        assertEquals("Otro importe", linea.get("name").asString)
        assertEquals(2500, linea.get("unitPrice").asInt)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `con red sólo importes ⇒ Fast como hoy y no deja un carrito a medias viejo`() = runTest {
        val vm = viewModel()
        // Un intento anterior con productos dejó su carrito durable; el cajero quitó los productos y cobra sólo importes.
        aparato.carritos["venue-1"] = VentaSinRedEnMemoria.Guardado("llave-vieja", CartState(), null, System.currentTimeMillis())
        vm.addCustomAmount("Servicio", 15000)

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue(payload is PaymentNavigationPayload.Fast)
        assertEquals("150.0", payload.amountPesosString)
        assertNull(aparato.carritos["venue-1"])
        vm.viewModelScope.cancel()
    }

    @Test
    fun `sólo importes en $0 con red ⇒ venta local en $0 guardada, nunca Fast de $0`() = runTest {
        val vm = viewModel()
        vm.addCustomAmount("Servicio", 5000)
        vm.applyCortesiaToItem(vm.cartState.value.items.single().id, "Cliente frecuente")
        assertEquals(0, vm.cartState.value.totalCents)

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("Salió $payload", payload is PaymentNavigationPayload.VentaEnCeroGuardada)
        val fila = aparato.filas.single()
        assertEquals(0, fila.totalCents)
        assertEquals(fila, (payload as PaymentNavigationPayload.VentaEnCeroGuardada).venta)
        assertTrue(pedido(fila).getAsJsonArray("items").single().asJsonObject.get("isCortesia").asBoolean)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `sin red carrito en $0 ⇒ fila guardada sin navegar al cobro y el diálogo ofrece imprimir`() = runTest {
        connectionStateManager.setInternetConnected(false)
        coEvery { impresora.imprimir(any()) } coAnswers { Result.success(Unit) }
        val vm = viewModel()
        vm.addProduct(producto(id = "p-galleta", nombre = "Galleta", centavos = 3000))
        vm.applyCortesiaToItem(vm.cartState.value.items.single().id, "Cortesía de la casa")

        val payload = vm.prepareForPayment().getOrThrow()

        // No es un destino de la pantalla de cobro: la pantalla de Cobrar muestra «Venta en $0 guardada».
        assertTrue("Salió $payload", payload is PaymentNavigationPayload.VentaEnCeroGuardada)
        val venta = (payload as PaymentNavigationPayload.VentaEnCeroGuardada).venta
        assertEquals(listOf(venta), aparato.filas)
        assertEquals("Galleta", venta.renglones.single().nombre)
        assertEquals(3000, venta.renglones.single().brutoCents)
        assertNull("La fila ya quedó: no queda carrito a medias", aparato.carritos["venue-1"])
        assertTrue("La venta ya terminó: sólo su comprobante queda disponible", vm.cartState.value.isEmpty)

        // «Imprimir ticket» usa el impresor con la venta guardada; mostrar el diálogo marca la venta como vista.
        assertTrue(vm.imprimirVentaGuardada(venta).isSuccess)
        coVerify(exactly = 1) { impresora.imprimir(venta) }
        vm.ventaGuardadaVista(venta.llave)
        assertEquals(listOf(venta.llave), aparato.vistas)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `cortesía completa con red y servidor que no contesta ⇒ $0 local con la misma llave del intento`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } returns Result.failure(error503)
        val vm = viewModel()
        vm.addProduct(producto())
        vm.applyCortesiaToItem(vm.cartState.value.items.single().id, "Cortesía")

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("Salió $payload", payload is PaymentNavigationPayload.VentaEnCeroGuardada)
        assertEquals(enviados.single().externalId, aparato.filas.single().llave)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `el cliente seleccionado antes de perder red viaja en el pedido de la venta local`() = runTest {
        val vm = viewModel()
        vm.selectCustomer(cliente("c-7"))
        connectionStateManager.setInternetConnected(false)
        vm.addProduct(producto())

        val venta = ventaDe(vm.prepareForPayment().getOrThrow())

        assertEquals("c-7", pedido(venta).get("customerId").asString)
        assertEquals("c-7", aparato.carritos["venue-1"]?.cliente?.id)
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // El carrito a medias al abrir Cobrar
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `recuperar tarda ⇒ no acepta otro carrito ni sustituye la llave guardada`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val antes = viewModel()
        antes.addProduct(producto(), quantity = 3)
        val original = ventaDe(antes.prepareForPayment().getOrThrow())
        antes.viewModelScope.cancel()
        val lectura = CompletableDeferred<Unit>()
        aparato.antesDeLeerCarrito = { lectura.await() }
        val despues = viewModel()
        try {
            despues.addCustomAmount("Toque antes de recuperar", 1000)
            assertTrue("La recuperación debe terminar antes de aceptar entradas", despues.cartState.value.isEmpty)
            assertTrue(despues.prepareForPayment().isFailure)
            assertEquals(original.llave, aparato.carritos["venue-1"]?.llave)

            lectura.complete(Unit)
            assertEquals(3, despues.cartState.value.items.single().quantity)
            assertEquals("Recuperamos la venta que estaba a medias", despues.aviso.value)
            val recuperada = ventaDe(despues.prepareForPayment().getOrThrow())
            assertEquals(original.llave, recuperada.llave)
        } finally {
            lectura.complete(Unit)
            despues.viewModelScope.cancel()
        }
    }

    @Test
    fun `venta en cero guardada ⇒ perder la pantalla no permite registrar el mismo carrito otra vez`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        try {
            vm.addProduct(producto())
            vm.applyCortesiaToItem(vm.cartState.value.items.single().id, "Cliente frecuente")
            val original = vm.prepareForPayment().getOrThrow() as PaymentNavigationPayload.VentaEnCeroGuardada

            // El ViewModel sigue vivo al recrear la Activity, pero se perdió el remember del diálogo.
            val repetida = vm.prepareForPayment()

            assertTrue("El carrito que ya terminó no se vuelve a cobrar", repetida.isFailure)
            assertEquals(1, aparato.filas.size)
            assertEquals(original.venta.llave, aparato.filas.single().llave)
            // La pantalla nueva se suscribe al mismo ViewModel y recibe el comprobante que la anterior dejó.
            assertEquals(original.venta, vm.ventaEnCeroGuardada.first())
            vm.imprimirVentaGuardada(original.venta)
            coVerify(exactly = 1) { impresora.imprimir(original.venta) }
            vm.cerrarVentaEnCero()
            assertNull(vm.ventaEnCeroGuardada.value)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `muere tras guardar y antes de la respuesta ⇒ al reabrir el carrito vuelve con la MISMA llave`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        val nuncaContesta = CompletableDeferred<kotlin.Result<Order>>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } coAnswers { nuncaContesta.await() }
        val antes = viewModel()
        antes.selectCustomer(cliente("c-1"))
        antes.addProduct(producto(), quantity = 3)
        val enVuelo = arrancar { antes.prepareForPayment() }
        val llaveDelIntento = enviados.single().externalId
        // El sistema mata el proceso: la petición y el ViewModel desaparecen sin respuesta.
        enVuelo.cancelAndJoin()
        antes.viewModelScope.cancel()

        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } returns Result.success(orden(id = "ord-recuperada"))
        val despues = viewModel()

        assertEquals(3, despues.cartState.value.items.single().quantity)
        assertEquals("c-1", despues.selectedCustomer.value?.id)
        assertEquals("Recuperamos la venta que estaba a medias", despues.aviso.value)
        val payload = despues.prepareForPayment().getOrThrow()
        assertEquals("ord-recuperada", (payload as PaymentNavigationPayload.Order).orderId)
        assertEquals("El reintento debe encontrar la misma orden por externalId", llaveDelIntento, enviados.last().externalId)
        despues.viewModelScope.cancel()
    }

    @Test
    fun `con fila de pago ya insertada ⇒ no restaura y borra el carrito a medias`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val antes = viewModel()
        antes.addProduct(producto())
        val venta = ventaDe(antes.prepareForPayment().getOrThrow())
        antes.viewModelScope.cancel()
        aparato.llavesConFila += venta.llave // la pantalla de cobro insertó la fila (C3)

        val despues = viewModel()

        assertTrue(despues.cartState.value.isEmpty)
        assertNull(despues.aviso.value)
        assertNull(aparato.carritos["venue-1"])
        despues.viewModelScope.cancel()
    }

    @Test
    fun `vaciar el carrito borra el carrito a medias`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        vm.addProduct(producto())
        vm.prepareForPayment().getOrThrow()
        assertNotNull(aparato.carritos["venue-1"])

        vm.clearCart()

        assertNull(aparato.carritos["venue-1"])
        vm.viewModelScope.cancel()
    }

    @Test
    fun `el carrito restaurado queda a nombre de quien tiene la sesión ahora`() = runTest {
        // Lo empezó «staff-viejo»; lo termina quien tiene la sesión (precedente del founder del 20-oct: de quien lo termina).
        aparato.carritos["venue-1"] = VentaSinRedEnMemoria.Guardado(
            "llave-a-medias",
            CartState(selectedStaffId = "staff-viejo", selectedStaffName = "Pedro").let { c ->
                c.copy(items = listOf(com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem(
                    type = com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType.ProductItem("p-1"),
                    name = "Café", unitPriceCents = 5000,
                )))
            },
            null,
            System.currentTimeMillis(),
        )
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } returns Result.success(orden())

        val vm = viewModel()
        assertEquals("staff-1", vm.cartState.value.selectedStaffId)
        assertEquals("María", vm.cartState.value.selectedStaffName)
        vm.prepareForPayment().getOrThrow()

        assertEquals("llave-a-medias", enviados.single().externalId)
        assertEquals("staff-1", enviados.single().staffId)
        vm.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Candado de «venta en preparación»
    // ─────────────────────────────────────────────────────────────────────

    private fun intentarMutarTodo(vm: CheckoutViewModel, otro: Product) {
        val id = vm.cartState.value.items.first().id
        vm.addProduct(otro)
        vm.addCustomAmount("Otro importe", 1000)
        vm.incrementQuantity(id)
        vm.decrementQuantity(id)
        vm.removeItem(id)
        vm.setItemNote(id, "nota nueva")
        vm.setOrderNote("nota de la cuenta")
        vm.applyManualOrderDiscount(500, "descuento")
        vm.clearManualOrderDiscount()
        vm.applyOrderDiscount(null)
        vm.applyCortesiaToItem(id, "cortesía")
        vm.clearCart()
    }

    @Test
    fun `agregar mientras corre Pagar después ⇒ rechazado y nada se pierde`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        val respuesta = CompletableDeferred<kotlin.Result<Order>>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } coAnswers { respuesta.await() }
        val vm = viewModel()
        vm.selectCustomer(cliente())
        vm.addProduct(producto())
        val antes = vm.cartState.value

        val enVuelo = arrancar { vm.createPayLaterOrder() }
        assertTrue(vm.ventaEnPreparacion.value)
        intentarMutarTodo(vm, producto(id = "p-2", nombre = "Pan"))
        vm.selectCustomer(null)
        assertEquals("Nada cambia mientras se prepara la venta", antes, vm.cartState.value)
        assertEquals("c-1", vm.selectedCustomer.value?.id)

        respuesta.complete(Result.success(orden()))
        assertTrue(enVuelo.await().isSuccess)
        assertFalse(vm.ventaEnPreparacion.value)
        // Lo que queda en el carrito es exactamente lo que viajó: vaciarlo no tira nada que no se guardó.
        assertEquals(listOf("p-1"), enviados.single().items.map { it.productId })
        assertEquals(antes, vm.cartState.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `agregar mientras corre prepareForPayment ⇒ rechazado`() = runTest {
        val respuesta = CompletableDeferred<kotlin.Result<Order>>()
        coEvery { orderRepository.createOrderWithItems("venue-1", any()) } coAnswers { respuesta.await() }
        val vm = viewModel()
        vm.addProduct(producto())
        val antes = vm.cartState.value

        val enVuelo = arrancar { vm.prepareForPayment() }
        assertTrue(vm.ventaEnPreparacion.value)
        intentarMutarTodo(vm, producto(id = "p-2", nombre = "Pan"))
        assertNull("Un atajo tampoco entra (ni abre la hoja de extras)", vm.addShortcutToCart(
            com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut(id = "s-1", venueId = "venue-1", productId = "p-1", position = 0, label = "Café"),
        ))
        assertEquals(antes, vm.cartState.value)
        // Un segundo «Cobrar» encima no manda otra orden.
        assertTrue(vm.prepareForPayment().isFailure)

        respuesta.complete(Result.success(orden()))
        assertTrue(enVuelo.await().getOrThrow() is PaymentNavigationPayload.Order)
        coVerify(exactly = 1) { orderRepository.createOrderWithItems(any(), any()) }
        assertFalse(vm.ventaEnPreparacion.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `cortesía de total 0 esperando al servidor - agregar otro producto ⇒ rechazado, y al llegar CompletedFreeCart no se borra nada que no viajó`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        val respuesta = CompletableDeferred<kotlin.Result<Order>>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } coAnswers { respuesta.await() }
        val vm = viewModel()
        vm.addProduct(producto(id = "p-galleta", nombre = "Galleta", centavos = 3000))
        vm.applyCortesiaToItem(vm.cartState.value.items.single().id, "Cortesía")

        val enVuelo = arrancar { vm.prepareForPayment() }
        vm.addProduct(producto(id = "p-2", nombre = "Pan"))
        assertEquals(1, vm.cartState.value.items.size)

        respuesta.complete(Result.success(orden(id = "ord-cero", numero = "T-0")))
        val payload = enVuelo.await().getOrThrow()
        assertTrue(payload is PaymentNavigationPayload.CompletedFreeCart)
        assertEquals(enviados.single().items.map { it.productId }, vm.cartState.value.items.map { (it.type as com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType.ProductItem).productId })
        vm.clearCart() // lo que hace la pantalla al llegar CompletedFreeCart
        assertTrue(vm.cartState.value.isEmpty)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `el candado se suelta cuando la orden falla, con 4xx o sin red`() = runTest {
        val respuesta = CompletableDeferred<kotlin.Result<Order>>()
        coEvery { orderRepository.createOrderWithItems("venue-1", any()) } coAnswers { respuesta.await() }
        val vm = viewModel()
        vm.addProduct(producto())

        val enVuelo = arrancar { vm.prepareForPayment() }
        assertTrue(vm.ventaEnPreparacion.value)
        respuesta.complete(Result.failure(error422))
        assertTrue(enVuelo.await().isFailure)
        assertFalse(vm.ventaEnPreparacion.value)
        vm.addProduct(producto(id = "p-2", nombre = "Pan"))
        assertEquals(2, vm.cartState.value.items.size)

        // Y con la red caída (va a venta local) también se suelta.
        coEvery { orderRepository.createOrderWithItems("venue-1", any()) } returns
            Result.failure(ApiException.NetworkError(java.io.IOException("sin salida")))
        assertTrue(vm.prepareForPayment().getOrThrow() is PaymentNavigationPayload.VentaLocal)
        assertFalse(vm.ventaEnPreparacion.value)
        vm.addCustomAmount("Otro importe", 1000)
        assertEquals(3, vm.cartState.value.items.size)

        // Y si la pantalla se va (la corrutina se cancela) a media orden.
        val otraRespuesta = CompletableDeferred<kotlin.Result<Order>>()
        coEvery { orderRepository.createOrderWithItems("venue-1", any()) } coAnswers { otraRespuesta.await() }
        val cancelada = arrancar { vm.prepareForPayment() }
        assertTrue(vm.ventaEnPreparacion.value)
        cancelada.cancelAndJoin()
        assertFalse(vm.ventaEnPreparacion.value)
        vm.viewModelScope.cancel()
    }

    @Test fun `sin permiso propio la cortesia no cambia el carrito aunque se invoque directo y sin red`() = runTest {
        coEvery { permissions.hasPermission("tpv-orders:comp") } returns false
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        vm.addProduct(producto())
        val antes = vm.cartState.value

        vm.applyCortesiaToItem(antes.items.single().id, "Cliente frecuente")

        assertEquals(antes, vm.cartState.value)
        assertFalse(vm.cartState.value.items.single().isCortesia)
    }

    @Test fun `sin discounts apply no hay descuento manual aunque se invoque directo`() = runTest {
        coEvery { permissions.hasPermission("discounts:apply") } returns false
        val vm = viewModel()
        vm.addProduct(producto())
        val antes = vm.cartState.value

        vm.applyManualOrderDiscount(1000, "Rebaja autorizada en otro turno")

        assertEquals(antes, vm.cartState.value)
        assertEquals(0, vm.cartState.value.manualDiscountCents)
    }

    @Test fun `con permiso propio la cortesia sigue disponible sin red y conserva motivo`() = runTest {
        coEvery { permissions.hasPermission("tpv-orders:comp") } returns true
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        vm.addProduct(producto())

        vm.applyCortesiaToItem(vm.cartState.value.items.single().id, "Cliente frecuente")

        assertTrue(vm.cartState.value.items.single().isCortesia)
        assertEquals("Cliente frecuente", vm.cartState.value.items.single().cortesiaReason)
        assertEquals(0, vm.cartState.value.totalCents)
    }

    @Test fun `pagar despues sin red no llama crear orden ni consume el carrito`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        vm.addProduct(producto())
        val antes = vm.cartState.value

        val resultado = vm.createPayLaterOrder()

        assertTrue(resultado.isFailure)
        assertEquals(antes, vm.cartState.value)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
    }

    @Test fun `banner conserva pendientes y problemas al volver la red y cambia de venue`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        guardadas.value = 3
        problemas.value = 1
        assertEquals(AvisoSinConexion(true, 3, 1), vm.avisoSinConexion.value)
        connectionStateManager.setInternetConnected(true)
        assertEquals(AvisoSinConexion(false, 3, 1), vm.avisoSinConexion.value)
        coEvery { secureStorage.getVenueId() } returns "venue-2"
        vm.refreshProducts()
        assertEquals(AvisoSinConexion(false, 7, 2), vm.avisoSinConexion.value)
        guardadas.value = 9
        assertEquals(AvisoSinConexion(false, 7, 2), vm.avisoSinConexion.value)
        vm.viewModelScope.cancel()
    }

    @Test fun `banner sin motivo desaparece y fallo reconocido no se suma en VM`() = runTest {
        val vm = viewModel()
        assertNull(vm.avisoSinConexion.value)
        problemas.value = 1
        assertEquals(AvisoSinConexion(false, 0, 1), vm.avisoSinConexion.value)
        problemas.value = 0
        assertNull(vm.avisoSinConexion.value)
        vm.viewModelScope.cancel()
    }

    @Test fun `reabre la misma venta guardada desde ticket y entendido marca solo esa llave`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val anterior = viewModel()
        anterior.addProduct(producto(nombre = "Producto del ticket"))
        val venta = ventaDe(anterior.prepareForPayment().getOrThrow())
        anterior.viewModelScope.cancel()
        aparato.llavesConFila += venta.llave
        aparato.filas += venta
        aparato.filas += venta.copy(llave = "otra-venta", renglones = venta.renglones.map { it.copy(nombre = "Otro ticket") })
        val vm = viewModel()
        assertTrue(vm.cartState.value.isEmpty)
        assertEquals("otra-venta", vm.ventaSinMostrar.value?.venta?.llave)
        assertEquals("Otro ticket", vm.ventaSinMostrar.value?.venta?.renglones?.single()?.nombre)
        vm.ventaGuardadaVista("otra-venta")
        assertEquals(listOf("otra-venta"), aparato.vistas)
        assertEquals(venta, vm.ventaSinMostrar.value?.venta)
        vm.ventaGuardadaVista(venta.llave)
        assertNull(vm.ventaSinMostrar.value)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun `recuperacion ilegible bloquea el cobro y conserva el carrito guardado`() = runTest {
        val anterior = viewModel()
        anterior.addProduct(producto())
        connectionStateManager.setInternetConnected(false)
        anterior.prepareForPayment().getOrThrow()
        anterior.viewModelScope.cancel()
        aparato.antesDeLeerCarrito = { throw java.io.IOException("almacenamiento ilegible") }
        val vm = viewModel()
        vm.addProduct(producto("p-2"))
        assertNotNull(vm.errorRecuperacion.value)
        val resultado = vm.prepareForPayment()
        assertTrue(resultado.isFailure)
        assertEquals(1, aparato.carritos.size)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun `carrito restaurado con descuento no autorizado no llega a tomar efectivo`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val anterior = viewModel()
        anterior.addProduct(producto())
        anterior.applyManualOrderDiscount(1000, "Rebaja")
        val venta = ventaDe(anterior.prepareForPayment().getOrThrow())
        anterior.viewModelScope.cancel()
        coEvery { permissions.hasPermission("discounts:apply") } returns false
        val vm = viewModel()
        val antes = vm.cartState.value
        assertEquals(1000, antes.manualDiscountCents)
        assertTrue(vm.prepareForPayment().isFailure)
        assertEquals(antes, vm.cartState.value)
        assertEquals(venta.llave, aparato.carritos["venue-1"]?.llave)
        assertTrue(aparato.filas.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun `carrito restaurado con cortesia no autorizada no guarda ni siquiera venta cero`() = runTest {
        val original = CartState(items = listOf(com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem(
            id = "i", type = com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType.CustomAmount,
            name = "Servicio", unitPriceCents = 5000, isCortesia = true, cortesiaReason = "VIP")))
        aparato.guardarCarrito("venue-1", "llave-antes", original, null)
        coEvery { permissions.hasPermission("tpv-orders:comp") } returns false
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        assertTrue(vm.cartState.value.items.single().isCortesia)
        assertTrue(vm.prepareForPayment().isFailure)
        assertTrue(aparato.filas.isEmpty())
        assertNotNull(aparato.carritos["venue-1"])
        vm.viewModelScope.cancel()
    }

    @Test fun `permisos seguros mientras cargan no permiten mutaciones y el operador propio si al terminar`() = runTest {
        val permiso = CompletableDeferred<Boolean>()
        coEvery { permissions.hasPermission(any()) } coAnswers { permiso.await() }
        val vm = viewModel()
        vm.addProduct(producto())
        assertFalse(vm.canCompItems.value)
        assertFalse(vm.canApplyDiscount.value)
        vm.applyCortesiaToItem(vm.cartState.value.items.single().id, "VIP")
        vm.applyManualOrderDiscount(1000, "Rebaja")
        assertEquals(5000, vm.cartState.value.totalCents)
        permiso.complete(true)
        assertTrue(vm.canCompItems.value)
        assertTrue(vm.canApplyDiscount.value)
        vm.viewModelScope.cancel()
    }

    @Test fun `sin red cliente cupon referido no mutan ni consultan servidor`() = runTest {
        val vm = viewModel()
        vm.addProduct(producto())
        connectionStateManager.setInternetConnected(false)
        vm.selectCustomer(cliente())
        vm.onReferralCodeChange("ALGUIEN")
        vm.validateReferralCode()
        vm.updateCustomerSearchQuery("Ana")
        vm.loadRecentCustomers()
        assertNull(vm.selectedCustomer.value)
        assertEquals("", vm.referralCode.value)
        assertTrue(vm.validateAndApplyCoupon("DESC10").isFailure)
        coVerify(exactly = 0) { customerRepository.searchCustomers(any(), any(), any()) }
        coVerify(exactly = 0) { customerRepository.getRecentCustomers(any(), any()) }
        coVerify(exactly = 0) { discountRepository.validateCoupon(any(), any(), any(), any()) }
        assertEquals(5000, vm.cartState.value.totalCents)
        vm.viewModelScope.cancel()
    }

    @Test fun `cambio de sesion no reutiliza el permiso cargado de otro cajero`() = runTest {
        val vm = viewModel()
        vm.addProduct(producto())
        coEvery { secureStorage.getStaffId() } returns "staff-nuevo"
        coEvery { permissions.hasPermission(any()) } returns false
        vm.applyCortesiaToItem(vm.cartState.value.items.single().id, "VIP")
        vm.applyManualOrderDiscount(1000, "Rebaja")
        assertEquals(5000, vm.cartState.value.totalCents)
        vm.viewModelScope.cancel()
    }

    @Test fun `volver del pago con el mismo VM consume el carrito durable antes de permitir otra venta`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val vm = viewModel()
        vm.addProduct(producto())
        val venta = ventaDe(vm.prepareForPayment().getOrThrow())
        aparato.llavesConFila += venta.llave
        aparato.filas += venta
        coEvery { paymentDao.existeFilaConLlave(venta.llave) } returns true
        vm.actualizarEstadoDeCobrar()
        assertTrue("No debe quedar una copia cobrable de la venta guardada", vm.cartState.value.isEmpty)
        assertEquals(venta.llave, vm.ventaSinMostrar.value?.venta?.llave)
        vm.ventaGuardadaVista(venta.llave)
        assertTrue(vm.prepareForPayment().isFailure)
        assertEquals(1, aparato.filas.size)
        vm.viewModelScope.cancel()
    }

    @Test fun `cambio de venue mientras Entendido lee la siguiente no publica otro ticket del venue viejo`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val antes = viewModel()
        antes.addProduct(producto())
        val venta = ventaDe(antes.prepareForPayment().getOrThrow())
        antes.viewModelScope.cancel()
        aparato.llavesConFila += venta.llave
        aparato.filas += venta.copy(llave = "otra-anterior")
        aparato.filas += venta
        aparato.filas += venta.copy(llave = "venta-venue-2", venueId = "venue-2")
        val vm = viewModel()
        val leyendo = CompletableDeferred<Unit>()
        val seguir = CompletableDeferred<Unit>()
        aparato.antesDeLeerConfirmacion = { venue -> if (venue == "venue-1") { leyendo.complete(Unit); seguir.await() } }
        vm.ventaGuardadaVista(venta.llave)
        leyendo.await()
        coEvery { secureStorage.getVenueId() } returns "venue-2"
        vm.actualizarEstadoDeCobrar()
        seguir.complete(Unit)
        assertEquals("venta-venue-2", vm.ventaSinMostrar.value?.venta?.llave)
        assertEquals(listOf(venta.llave), aparato.vistas)
        vm.ventaGuardadaVista("otra-anterior")
        assertEquals(listOf(venta.llave), aparato.vistas)
        assertNull(vm.errorRecuperacion.value)
        vm.viewModelScope.cancel()
    }

    @Test fun `fallo tardio de Entendido del venue anterior no tapa la recuperacion actual`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val antes = viewModel()
        antes.addProduct(producto())
        val venta = ventaDe(antes.prepareForPayment().getOrThrow())
        antes.viewModelScope.cancel()
        aparato.filas += venta
        aparato.filas += venta.copy(llave = "venta-venue-2", venueId = "venue-2")
        val vm = viewModel()
        val seguir = CompletableDeferred<Unit>()
        aparato.antesDeMarcarVista = { seguir.await(); throw java.io.IOException("Fallo de escritura") }
        vm.ventaGuardadaVista(venta.llave)
        coEvery { secureStorage.getVenueId() } returns "venue-2"
        vm.actualizarEstadoDeCobrar()
        seguir.complete(Unit)
        assertNull(vm.errorRecuperacion.value)
        assertEquals("venta-venue-2", vm.ventaSinMostrar.value?.venta?.llave)
        assertTrue(aparato.vistas.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun `no acepta otro cobro mientras sigue leyendo la recuperacion inicial`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val continuar = CompletableDeferred<Unit>()
        aparato.antesDeLeerCarrito = { continuar.await() }
        val vm = viewModel()
        assertTrue(vm.ventaEnPreparacion.value)
        vm.addProduct(producto())
        assertTrue(vm.cartState.value.isEmpty)
        assertTrue(vm.prepareForPayment().isFailure)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        continuar.complete(Unit)
        assertFalse(vm.ventaEnPreparacion.value)
        vm.addProduct(producto())
        assertTrue(vm.prepareForPayment().isSuccess)
        vm.viewModelScope.cancel()
    }

    @Test fun `cambio de venue durante carga inicial solo restaura el borrador actual`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val otroCarrito = CartState(items = listOf(com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem(
            id = "i-2", type = com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType.CustomAmount,
            name = "Servicio de otra sucursal", unitPriceCents = 7500)))
        aparato.guardarCarrito("venue-2", "llave-2", otroCarrito, null)
        val seguir = CompletableDeferred<Unit>()
        aparato.antesDeLeerCarrito = { seguir.await() }
        val vm = viewModel()
        assertTrue(vm.ventaEnPreparacion.value)
        coEvery { secureStorage.getVenueId() } returns "venue-2"
        vm.actualizarEstadoDeCobrar()
        seguir.complete(Unit)
        assertEquals("Servicio de otra sucursal", vm.cartState.value.items.single().name)
        assertNull(vm.ventaSinMostrar.value)
        assertNull(vm.errorRecuperacion.value)
        assertEquals("llave-2", ventaDe(vm.prepareForPayment().getOrThrow()).llave)
        assertEquals(AvisoSinConexion(true, 7, 2), vm.avisoSinConexion.value)
        vm.viewModelScope.cancel()
    }

    @Test fun `fallo al leer confirmacion conserva venta y reintento muestra la misma llave y ticket`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val antes = viewModel()
        antes.addProduct(producto(nombre = "Snapshot cobrado"))
        val venta = ventaDe(antes.prepareForPayment().getOrThrow())
        antes.viewModelScope.cancel()
        aparato.llavesConFila += venta.llave
        aparato.filas += venta
        aparato.antesDeLeerConfirmacion = { throw java.io.IOException("Room temporal") }
        val vm = viewModel()
        assertNotNull(vm.errorRecuperacion.value)
        assertNull(vm.ventaSinMostrar.value)
        vm.addProduct(producto("nuevo"))
        assertTrue(vm.prepareForPayment().isFailure)
        assertEquals(listOf(venta), aparato.filas)
        aparato.antesDeLeerConfirmacion = null
        vm.actualizarEstadoDeCobrar()
        assertNull(vm.errorRecuperacion.value)
        assertEquals(venta, vm.ventaSinMostrar.value?.venta)
        assertTrue(vm.prepareForPayment().isFailure)
        vm.ventaGuardadaVista("llave-que-no-se-mostro")
        assertTrue(aparato.vistas.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun `fallo de Entendido conserva la confirmacion y permite reintentar sin marcar otra`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val antes = viewModel()
        antes.addProduct(producto())
        val venta = ventaDe(antes.prepareForPayment().getOrThrow())
        antes.viewModelScope.cancel()
        aparato.filas += venta
        val vm = viewModel()
        aparato.antesDeMarcarVista = { throw java.io.IOException("Sin escritura") }
        vm.ventaGuardadaVista(venta.llave)
        assertNotNull(vm.errorRecuperacion.value)
        assertEquals(venta, vm.ventaSinMostrar.value?.venta)
        assertTrue(aparato.vistas.isEmpty())
        aparato.antesDeMarcarVista = null
        vm.actualizarEstadoDeCobrar()
        assertNull(vm.errorRecuperacion.value)
        assertEquals(venta, vm.ventaSinMostrar.value?.venta)
        vm.ventaGuardadaVista(venta.llave)
        assertNull(vm.ventaSinMostrar.value)
        assertEquals(listOf(venta.llave), aparato.vistas)
        vm.viewModelScope.cancel()
    }

    @Test fun `permiso revocado tras restaurar se revisa otra vez antes de efectivo`() = runTest {
        connectionStateManager.setInternetConnected(false)
        val antes = viewModel()
        antes.addProduct(producto())
        antes.applyManualOrderDiscount(1000, "Motivo durable")
        val venta = ventaDe(antes.prepareForPayment().getOrThrow())
        antes.viewModelScope.cancel()
        val vm = viewModel()
        assertTrue(vm.canApplyDiscount.value)
        coEvery { permissions.hasPermission("discounts:apply") } returns false
        val resultado = vm.prepareForPayment()
        assertTrue(resultado.isFailure)
        assertTrue(resultado.exceptionOrNull()?.message.orEmpty().contains("permiso"))
        assertEquals(1000, vm.cartState.value.manualDiscountCents)
        assertEquals("Motivo durable", vm.cartState.value.manualDiscountReason)
        assertEquals(venta.llave, aparato.carritos["venue-1"]?.llave)
        assertTrue(aparato.filas.isEmpty())
        vm.viewModelScope.cancel()
    }

    // ── D5 (5-nov): el cupón baja el total, llega hasta el cable y, sin red, se dice ────────────────────────────────────────────

    private fun cuponDiez(valor: Int = 10, tope: java.math.BigDecimal? = null) = com.jaac.avoqado_tpv.features.ordering.domain.CouponCode(
        id = "cup-1",
        code = "DESC10",
        name = "Diez",
        type = com.jaac.avoqado_tpv.features.ordering.domain.DiscountType.PERCENTAGE,
        value = java.math.BigDecimal(valor),
        usesRemaining = null,
        validFrom = null,
        validUntil = null,
        isValid = true,
        invalidReason = null,
        scope = "ORDER",
        maxDiscountAmount = tope,
    )

    private suspend fun conCupon(vm: CheckoutViewModel, cupon: com.jaac.avoqado_tpv.features.ordering.domain.CouponCode = cuponDiez()) {
        coEvery { discountRepository.validateCoupon("venue-1", "DESC10", any(), any()) } returns Result.success(cupon)
        assertTrue(vm.validateAndApplyCoupon("desc10").isSuccess)
    }

    @Test fun `P1 un cupon valido baja el total y viaja con su codigo, sin id de catalogo`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } returns Result.success(orden().copy(total = java.math.BigDecimal("45.00")))
        val vm = viewModel()
        vm.addProduct(producto())
        conCupon(vm)
        assertEquals(4500, vm.cartState.value.totalCents)
        assertEquals("Diez (DESC10)", vm.cartState.value.orderDiscount?.name)

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("$payload", payload is PaymentNavigationPayload.Order)
        assertEquals("se cobra el total del servidor", "45.00", (payload as PaymentNavigationPayload.Order).amountPesosString)
        val pedido = enviados.single()
        assertEquals("DESC10", pedido.couponCode)
        assertNull(pedido.orderDiscountId)
        assertEquals(0, java.math.BigDecimal(5).compareTo(pedido.discount))
        assertEquals(0, java.math.BigDecimal(45).compareTo(pedido.total))
        vm.viewModelScope.cancel()
    }

    @Test fun `P1 validar manda lo que vale la venta y su cliente`() = runTest {
        val vm = viewModel()
        vm.addProduct(producto())
        vm.selectCustomer(cliente())
        conCupon(vm)
        coVerify { discountRepository.validateCoupon("venue-1", "DESC10", 50.0, cliente().id) }
        vm.viewModelScope.cancel()
    }

    @Test fun `el tope y el redondeo son los del servidor`() = runTest {
        val vm = viewModel()
        vm.addProduct(producto(centavos = 2135))
        conCupon(vm)
        assertEquals("10 % de \$21.35 son \$2.14", 214, vm.cartState.value.discountCents)
        vm.clearCart()
        vm.addProduct(producto(centavos = 100000))
        conCupon(vm, cuponDiez(valor = 50, tope = java.math.BigDecimal(20)))
        assertEquals(2000, vm.cartState.value.discountCents)
        vm.viewModelScope.cancel()
    }

    @Test fun `P1 puros importes con cupon no salen como cobro rapido, porque ahi nunca contaria`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } returns Result.success(orden().copy(total = java.math.BigDecimal("45.00")))
        val vm = viewModel()
        vm.addCustomAmount("Otro importe", 5000)
        conCupon(vm)

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("$payload", payload is PaymentNavigationPayload.Order)
        assertEquals("DESC10", enviados.single().couponCode)
        vm.viewModelScope.cancel()
    }

    @Test fun `P1 sin red con cupon en el carrito se dice y no se guarda como venta local`() = runTest {
        val vm = viewModel()
        vm.addProduct(producto())
        conCupon(vm)
        connectionStateManager.setInternetConnected(false)

        val fallo = vm.prepareForPayment().exceptionOrNull()

        assertEquals(TEXTO_CUPON_SIN_RED, fallo?.message)
        coVerify(exactly = 0) { orderRepository.createOrderWithItems(any(), any()) }
        assertEquals("el carrito se queda con su cupón", "DESC10", vm.cartState.value.couponCode)
        vm.viewModelScope.cancel()
    }

    @Test fun `P1 el servidor no contesto con cupon se dice, sin venta local, y el reintento usa la misma llave`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } returns Result.failure(error503)
        val vm = viewModel()
        vm.addProduct(producto())
        conCupon(vm)

        assertEquals(TEXTO_CUPON_SIN_RED, vm.prepareForPayment().exceptionOrNull()?.message)
        assertEquals(TEXTO_CUPON_SIN_RED, vm.prepareForPayment().exceptionOrNull()?.message)

        assertEquals(2, enviados.size)
        assertEquals(enviados[0].externalId, enviados[1].externalId)
        assertEquals("DESC10", vm.cartState.value.couponCode)
        vm.viewModelScope.cancel()
    }

    @Test fun `sin red al validar dice Sin conexion, no cupon invalido`() = runTest {
        val vm = viewModel()
        vm.addProduct(producto())
        connectionStateManager.setInternetConnected(false)
        assertEquals(
            com.jaac.avoqado_tpv.features.ordering.data.repository.TEXTO_CUPON_SIN_RED_AL_VALIDAR,
            vm.validateAndApplyCoupon("DESC10").exceptionOrNull()?.message,
        )
        vm.viewModelScope.cancel()
    }

    @Test fun `un cupon por producto se rechaza con su razon`() = runTest {
        val vm = viewModel()
        vm.addProduct(producto())
        coEvery { discountRepository.validateCoupon("venue-1", "CAFE10", any(), any()) } returns Result.success(cuponDiez().copy(scope = "ITEM"))
        assertTrue(vm.validateAndApplyCoupon("CAFE10").exceptionOrNull()!!.message!!.contains("ciertos productos"))
        assertEquals(5000, vm.cartState.value.totalCents)
        vm.viewModelScope.cancel()
    }

    @Test fun `sin el permiso se dice antes de preguntar`() = runTest {
        coEvery { permissions.hasPermission("coupons:redeem") } returns false
        val vm = viewModel()
        vm.addProduct(producto())
        assertTrue(vm.validateAndApplyCoupon("DESC10").exceptionOrNull()!!.message!!.contains("permiso"))
        coVerify(exactly = 0) { discountRepository.validateCoupon(any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun `quitar el cupon regresa el total y ya no viaja`() = runTest {
        val enviados = mutableListOf<TpvCreateOrderWithItemsRequest>()
        coEvery { orderRepository.createOrderWithItems("venue-1", capture(enviados)) } returns Result.success(orden())
        val vm = viewModel()
        vm.addProduct(producto())
        conCupon(vm)

        vm.clearManualOrderDiscount()
        vm.applyOrderDiscount(null)

        assertEquals(5000, vm.cartState.value.totalCents)
        vm.prepareForPayment().getOrThrow()
        assertNull(enviados.single().couponCode)
        vm.viewModelScope.cancel()
    }

    @Test fun `Codex r1 7 con cupon la cortesia la decide el total del servidor, no el de la pantalla`() = runTest {
        coEvery { orderRepository.createOrderWithItems("venue-1", any()) } returns
            Result.success(orden().copy(total = java.math.BigDecimal("0.01")))
        val vm = viewModel()
        vm.addProduct(producto(centavos = 100))
        conCupon(vm, cuponDiez(valor = 100))
        assertEquals(0, vm.cartState.value.totalCents)

        val payload = vm.prepareForPayment().getOrThrow()

        assertTrue("una cuenta de \$0.01 se cobra, no se anuncia como cortesía: $payload", payload is PaymentNavigationPayload.Order)
        assertEquals("0.01", (payload as PaymentNavigationPayload.Order).amountPesosString)
        vm.viewModelScope.cancel()
    }
}
