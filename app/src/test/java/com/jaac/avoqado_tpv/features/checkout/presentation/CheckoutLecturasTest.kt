package com.jaac.avoqado_tpv.features.checkout.presentation

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntities
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.features.checkout.data.ActiveCartState
import com.jaac.avoqado_tpv.features.checkout.data.CarritoEnCurso
import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalRepository
import com.jaac.avoqado_tpv.features.checkout.data.repository.VentaSinRedRepositoryImpl
import com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.Executor

/** C8: fronteras reales VM -> repositorio -> Room; sólo falla el DAO/HTTP elegido, nunca se fabrica una guarda exitosa. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutLecturasTest {
    private val contexto get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AvoqadoDatabase
    private lateinit var storage: SecureStorage
    private lateinit var productos: ProductDao
    private lateinit var red: ProductRepository
    private lateinit var conexion: ConnectionStateManager
    private lateinit var carritos: CarritoEnCurso
    private lateinit var turnos: ShiftRepository
    private lateinit var ventas: VentaSinRedRepositoryImpl
    private val modelos = mutableListOf<CheckoutViewModel>()
    private var venue = "venue-c8"
    private var staff = "staff-c8"
    private var lecturasDeTurno = 0
    private var falloTurno: Throwable? = null

    @Before fun preparar() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        CheckoutViewModel.despachadorDelCatalogo = Dispatchers.Unconfined
        db = Room.inMemoryDatabaseBuilder(contexto, AvoqadoDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(Executor { it.run() }).setTransactionExecutor(Executor { it.run() }).build()
        storage = mockk(relaxed = true)
        every { storage.getVenueId() } answers { venue }
        every { storage.getStaffId() } answers { staff }
        every { storage.getStaffName() } returns "Cajera"
        every { storage.isShiftSystemEnabled() } returns true
        productos = spyk(db.productDao())
        val cached = spyk(db.cachedShiftDao())
        coEvery { cached.getCachedShift(any()) } coAnswers {
            lecturasDeTurno++
            falloTurno?.let { falloTurno = null; throw it }
            db.cachedShiftDao().getCachedShift(firstArg())
        }
        turnos = ShiftRepository(mockk(relaxed = true), db, storage, cached, mockk(relaxed = true), appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext())
        carritos = CarritoEnCurso(contexto)
        ventas = VentaSinRedRepositoryImpl(carritos, db.pendingPaymentDao(), VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture()), turnos, storage, contexto)
        red = mockk()
        coEvery { red.getProducts(any(), any()) } returns Result.failure(IOException("Sin transporte"))
        coEvery { red.getCategories(any()) } returns Result.failure(IOException("Sin transporte"))
        coEvery { red.getProductByBarcode(any(), any()) } returns Result.failure(IOException("Sin transporte"))
        conexion = ConnectionStateManager().also { it.setInternetConnected(false) }
        kotlinx.coroutines.runBlocking {
            carritos.borrar("venue-c8")
            db.cachedShiftDao().cacheShift(CachedShiftEntity("T1", "venue-c8", "OPEN", "staff-c8", "Cajera", "2026-10-24T10:00:00Z", "0", 0, null, 0))
        }
    }

    @After fun cerrar() {
        modelos.forEach { it.viewModelScope.cancel() }
        kotlinx.coroutines.runBlocking { carritos.borrar("venue-c8") }
        db.close()
        CheckoutViewModel.despachadorDelCatalogo = Dispatchers.Default
        Dispatchers.resetMain()
    }

    private fun producto(id: String = "p-c8") = Product(id, "Camisa", "SKU-C8", BigDecimal("40.00"), "cat-c8", "Ropa", null, "", null, true)

    private suspend fun vm(): CheckoutViewModel {
        val guardados = mockk<SavedCartsRepository> { coEvery { observeAll() } returns MutableStateFlow(emptyList()) }
        val mosaico = mockk<MosaicRepository> { coEvery { observe(any()) } returns MutableStateFlow(emptyList()) }
        val modelo = CheckoutViewModel(red, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), guardados,
            mosaico, ActiveCartState(), storage, mockk(relaxed = true), mockk(relaxed = true), productos, db.productCategoryDao(),
            CatalogoGuardado(red, productos), conexion, ventas, mockk(relaxed = true), db.pendingPaymentDao(), mockk {
                coEvery { hasPermission(any()) } returns true
            }, mockk(relaxed = true))
        modelos += modelo
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.ventaEnPreparacion.first { !it } } }
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.isLoading.first { !it } } }
        return modelo
    }

    private suspend fun primeraLecturaFalla(tipo: String, cero: Boolean) {
        val modelo = vm()
        if (tipo != "manual") modelo.addProduct(producto())
        if (tipo != "catalogo") modelo.addCustomAmount("Servicio", 1000)
        if (cero) modelo.cartState.value.items.forEach { modelo.applyCortesiaToItem(it.id, "Cortesía C8") }
        val antes = modelo.cartState.value
        falloTurno = IOException("Primera lectura del turno")
        val fallo = modelo.prepareForPayment()
        assertTrue("No debe preparar ni navegar con identidad desconocida: $fallo", fallo.isFailure)
        assertTrue(fallo.exceptionOrNull()?.message.orEmpty().contains("leer", ignoreCase = true))
        assertEquals(antes, modelo.cartState.value)
        assertEquals(0, db.pendingPaymentDao().getPendingCount())
        val borrador = carritos.leer("venue-c8")!!
        assertEquals(antes, borrador.second)
        assertFalse(modelo.ventaEnPreparacion.value)
        // Otra lectura sana de la guarda NO rellena el VentaLocal anterior: se vuelve a preparar con la MISMA llave.
        assertEquals("T1", turnos.getCachedOpenShift("venue-c8")?.id)
        val reabierto = vm()
        assertEquals(antes.items, reabierto.cartState.value.items)
        val resultado = reabierto.prepareForPayment().getOrThrow()
        val venta = when (resultado) {
            is PaymentNavigationPayload.VentaEnCeroGuardada -> resultado.venta
            is PaymentNavigationPayload.VentaLocal -> Gson().fromJson(resultado.ventaLocalJson, VentaLocal::class.java)
            else -> error("Navegación incorrecta: $resultado")
        }
        assertEquals(borrador.first, venta.llave)
        assertEquals("T1", venta.shiftId)
        assertEquals("T1", JsonParser.parseString(venta.pedidoJson).asJsonObject["shiftIdDeLaVenta"].asString)
        if (!cero) VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture()).guardar(venta, "ANGELPAY", "", "N86-C8").getOrThrow()
        val fila = db.pendingPaymentDao().findByReference(referenciaDeEfectivo(venta.llave), "venue-c8")!!
        assertEquals("T1", fila.shiftId)
        assertEquals(venta.pedidoJson, fila.orderRequestJson)
        assertEquals(1, db.pendingPaymentDao().getPendingCount())
        assertEquals(if (cero) 0 else antes.totalCents, venta.totalCents)
    }

    @Test fun `turno falla solo una vez - catalogo positivo conserva identidad y llave`() = runTest { primeraLecturaFalla("catalogo", false) }
    @Test fun `turno falla solo una vez - importe positivo conserva identidad y llave`() = runTest { primeraLecturaFalla("manual", false) }
    @Test fun `turno falla solo una vez - mixto positivo conserva identidad y llave`() = runTest { primeraLecturaFalla("mixto", false) }
    @Test fun `turno falla solo una vez - catalogo en cero no inserta`() = runTest { primeraLecturaFalla("catalogo", true) }
    @Test fun `turno falla solo una vez - importe en cero no inserta`() = runTest { primeraLecturaFalla("manual", true) }
    @Test fun `turno falla solo una vez - mixto en cero no inserta`() = runTest { primeraLecturaFalla("mixto", true) }

    @Test fun `sin sistema de turnos null es legitimo sin leer la caja`() = runTest {
        every { storage.isShiftSystemEnabled() } returns false
        val modelo = vm(); modelo.addProduct(producto())
        val payload = modelo.prepareForPayment().getOrThrow() as PaymentNavigationPayload.VentaLocal
        assertNull(Gson().fromJson(payload.ventaLocalJson, VentaLocal::class.java).shiftId)
        assertEquals(0, lecturasDeTurno)
    }

    @Test fun `sin caja guardada null sigue siendo ausencia legitima`() = runTest {
        db.cachedShiftDao().clearCache("venue-c8")
        val modelo = vm(); modelo.addProduct(producto())
        val payload = modelo.prepareForPayment().getOrThrow() as PaymentNavigationPayload.VentaLocal
        assertNull(Gson().fromJson(payload.ventaLocalJson, VentaLocal::class.java).shiftId)
    }

    @Test fun `cancelar lectura de turno propaga y no inserta ni cambia carrito`() = runTest {
        val modelo = vm(); modelo.addProduct(producto())
        val antes = modelo.cartState.value
        falloTurno = CancellationException("Cancelado")
        var cancelada = false
        try { modelo.prepareForPayment() } catch (e: CancellationException) { cancelada = true }
        assertTrue(cancelada)
        assertEquals(antes, modelo.cartState.value)
        assertFalse(modelo.ventaEnPreparacion.value)
        assertEquals(0, db.pendingPaymentDao().getPendingCount())
    }

    @Test fun `Room falla pero online recupera el catalogo real`() = runTest {
        coEvery { productos.getAllProducts("venue-c8") } throws IOException("Lectura fallida")
        coEvery { red.getProducts("venue-c8", any()) } returns Result.success(listOf(producto("p-red")))
        conexion.setInternetConnected(true)
        val modelo = vm()
        assertEquals(listOf("p-red"), modelo.products.value.map { it.id })
        assertEquals(listOf("p-red"), db.productDao().getAllProducts("venue-c8").map { it.productId })
    }

    @Test fun `mapeo de precio falla pero online recupera sin borrar antes`() = runTest {
        db.productDao().upsertProducts(listOf(producto()).toEntities("venue-c8", 0).map { it.copy(price = "ilegible") })
        coEvery { red.getProducts("venue-c8", any()) } returns Result.success(listOf(producto("p-red")))
        conexion.setInternetConnected(true)
        assertEquals(listOf("p-red"), vm().products.value.map { it.id })
    }

    @Test fun `Room falla sin red avisa lectura y reintenta de verdad`() = runTest {
        coEvery { productos.getAllProducts("venue-c8") } throws IOException("Lectura fallida")
        val modelo = vm()
        assertTrue(modelo.aviso.value.orEmpty().contains("leer", ignoreCase = true))
        assertTrue(modelo.products.value.isEmpty())
        coEvery { productos.getAllProducts("venue-c8") } coAnswers { db.productDao().getAllProducts(firstArg()) }
        db.productDao().upsertProducts(listOf(producto()).toEntities("venue-c8", 0))
        modelo.refreshProducts()
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.isLoading.first { !it } } }
        assertEquals(listOf("p-c8"), modelo.products.value.map { it.id })
    }

    @Test fun `cache ilegible y red mala avisa y no borra las filas`() = runTest {
        db.productDao().upsertProducts(listOf(producto()).toEntities("venue-c8", 0).map { it.copy(price = "ilegible") })
        conexion.setInternetConnected(true)
        val modelo = vm()
        assertTrue(modelo.aviso.value.orEmpty().contains("leer", ignoreCase = true))
        assertEquals("ilegible", db.productDao().getAllProducts("venue-c8").single().price)
    }

    @Test fun `cache vacia correcta sin red no publica error`() = runTest {
        val modelo = vm()
        assertTrue(modelo.products.value.isEmpty())
        assertNull(modelo.aviso.value)
    }

    @Test fun `cache buena y red mala conserva catalogo y no confunde offline con error`() = runTest {
        db.productDao().upsertProducts(listOf(producto()).toEntities("venue-c8", 0))
        conexion.setInternetConnected(true)
        val modelo = vm()
        assertEquals(listOf("p-c8"), modelo.products.value.map { it.id })
        assertNull(modelo.aviso.value)
        coEvery { productos.getAllProducts("venue-c8") } throws IOException("Lectura siguiente")
        modelo.refreshProducts()
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.isLoading.first { !it } } }
        assertEquals(listOf("p-c8"), modelo.products.value.map { it.id })
        assertTrue(modelo.aviso.value.orEmpty().contains("leer", ignoreCase = true))
    }

    @Test fun `escaner distingue fallo de lectura de ausencia`() = runTest {
        val modelo = vm()
        coEvery { productos.getProductByCodigo("venue-c8", "SKU-C8") } throws IOException("Room falló")
        var callbacks = 0
        modelo.findProductByBarcode("SKU-C8") { _, _ -> callbacks++ }
        assertEquals(0, callbacks)
        assertTrue(modelo.aviso.value.orEmpty().contains("leer", ignoreCase = true))
    }

    @Test fun `escaner ausencia local correcta si notifica no encontrado`() = runTest {
        val modelo = vm()
        val respuesta = CompletableDeferred<Pair<Product?, Boolean>>()
        modelo.findProductByBarcode("NO-EXISTE") { p, local -> respuesta.complete(p to local) }
        assertEquals(null to true, withContext(Dispatchers.Default) { withTimeout(10000) { respuesta.await() } })
        assertNull(modelo.aviso.value)
    }

    @Test fun `escaner cancelacion en Result no cae al catalogo ni notifica ausencia`() = runTest {
        val modelo = vm()
        conexion.setInternetConnected(true)
        coEvery { red.getProductByBarcode(any(), any()) } returns Result.failure(CancellationException("Cancelado"))
        var callbacks = 0
        modelo.findProductByBarcode("SKU-C8") { _, _ -> callbacks++ }
        assertEquals(0, callbacks)
        assertNull(modelo.aviso.value)
    }

    @Test fun `escaner local cancelado no notifica ausencia`() = runTest {
        val modelo = vm()
        coEvery { productos.getProductByCodigo(any(), any()) } throws CancellationException("Cancelado")
        var callbacks = 0
        modelo.findProductByBarcode("SKU-C8") { _, _ -> callbacks++ }
        assertEquals(0, callbacks)
        assertNull(modelo.aviso.value)
    }

    @Test fun `escaner respuesta tardia tras relevo no toca la nueva sesion`() = runTest {
        val modelo = vm()
        conexion.setInternetConnected(true)
        val respuesta = CompletableDeferred<Result<Product?>>()
        coEvery { red.getProductByBarcode(any(), any()) } coAnswers { withContext(NonCancellable) { respuesta.await() } }
        var callbacks = 0
        modelo.findProductByBarcode("SKU-C8") { _, _ -> callbacks++ }
        staff = "otra-cajera"
        modelo.actualizarEstadoDeCobrar()
        respuesta.complete(Result.success(producto()))
        assertEquals(0, callbacks)
    }

    @Test fun `catalogo tardio tras cambiar venue sin otro refresh no se publica`() = runTest {
        val modelo = vm()
        conexion.setInternetConnected(true)
        val respuesta = CompletableDeferred<Result<List<Product>>>()
        coEvery { red.getProducts(any(), any()) } coAnswers { withContext(NonCancellable) { respuesta.await() } }
        modelo.refreshProducts()
        venue = "otro-venue"
        modelo.actualizarEstadoDeCobrar()
        respuesta.complete(Result.success(listOf(producto("tarde"))))
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.isLoading.first { !it } } }
        assertTrue(modelo.products.value.isEmpty())
    }
    @Test fun `dos escaneos admitidos conservan ambos productos aunque B llegue antes que A`() = runTest {
        val modelo = vm()
        conexion.setInternetConnected(true)
        val a = CompletableDeferred<Result<Product?>>()
        val b = CompletableDeferred<Result<Product?>>()
        coEvery { red.getProductByBarcode("venue-c8", "A") } coAnswers { a.await() }
        coEvery { red.getProductByBarcode("venue-c8", "B") } coAnswers { b.await() }
        modelo.findProductByBarcode("A") { p, _ -> p?.let(modelo::addProduct) }
        modelo.findProductByBarcode("B") { p, _ -> p?.let(modelo::addProduct) }
        b.complete(Result.success(producto("B")))
        a.complete(Result.success(producto("A")))
        assertEquals(listOf("B", "A"), modelo.cartState.value.items.map { (it.type as com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType.ProductItem).productId })
    }

    @Test fun `fallo local tardio de escaner tras sesion A B A no publica error viejo`() = runTest {
        val modelo = vm()
        var pendiente: kotlin.coroutines.Continuation<com.jaac.avoqado_tpv.core.data.local.entities.ProductEntity?>? = null
        coEvery { productos.getProductByCodigo(any(), any()) } coAnswers { kotlin.coroutines.suspendCoroutine { pendiente = it } }
        var callbacks = 0
        modelo.findProductByBarcode("A") { _, _ -> callbacks++ }
        assertNotNull(pendiente)
        staff = "staff-B"; modelo.actualizarEstadoDeCobrar()
        staff = "staff-c8"; modelo.actualizarEstadoDeCobrar()
        pendiente!!.resumeWith(Result.failure(IOException("Error de A anterior")))
        assertEquals(0, callbacks)
        assertNull(modelo.aviso.value)
    }

    @Test fun `fallo remoto tardio del catalogo tras venue A B A no publica error viejo`() = runTest {
        val modelo = vm()
        conexion.setInternetConnected(true)
        var pendiente: kotlin.coroutines.Continuation<Result<List<Product>>>? = null
        coEvery { red.getProducts(any(), any()) } coAnswers { kotlin.coroutines.suspendCoroutine { pendiente = it } }
        modelo.refreshProducts()
        assertNotNull(pendiente)
        venue = "venue-B"; modelo.actualizarEstadoDeCobrar()
        venue = "venue-c8"; modelo.actualizarEstadoDeCobrar()
        pendiente!!.resumeWith(Result.success(Result.failure(IOException("Red de A anterior"))))
        assertNull(modelo.errorCatalogo.value)
        assertNull(modelo.aviso.value)
    }

    @Test fun `buscar tras error real de Room recupera productos y resultados con el mismo reintento`() = runTest {
        db.productDao().upsertProducts(listOf(producto()).toEntities("venue-c8", 0).map { it.copy(price = "ilegible") })
        val modelo = vm()
        modelo.updateSearchQuery("cam")
        assertNotNull(modelo.errorCatalogo.value)
        assertTrue(modelo.products.value.isEmpty())
        assertTrue(modelo.searchResults.value.isEmpty())
        db.productDao().upsertProducts(listOf(producto()).toEntities("venue-c8", 0))
        modelo.refreshProducts()
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.isLoading.first { !it } } }
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.searchResults.first { it.isNotEmpty() } } }
        assertNull(modelo.errorCatalogo.value)
        val resultado = modelo.searchResults.value.single() as com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo.Suelto<Product>
        assertEquals("p-c8", resultado.producto.id)
        modelo.addProduct(resultado.producto)
        assertEquals(4000, modelo.cartState.value.totalCents)
    }

    @Test fun `catalogo bueno con lectura posterior fallida conserva busqueda y atajo mientras avisa`() = runTest {
        db.productDao().upsertProducts(listOf(producto()).toEntities("venue-c8", 0))
        val modelo = vm()
        coEvery { productos.getAllProducts("venue-c8") } throws IOException("Lectura siguiente")
        modelo.refreshProducts()
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.isLoading.first { !it } } }
        modelo.updateSearchQuery("cam")
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.searchResults.first { it.isNotEmpty() } } }
        assertNotNull(modelo.errorCatalogo.value)
        assertEquals(1, modelo.products.value.size)
        modelo.addShortcutToCart(com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut("s-c8", "venue-c8", "p-c8", 0, "Camisa"))
        assertEquals(4000, modelo.cartState.value.totalCents)
        coEvery { productos.getAllProducts("venue-c8") } coAnswers { db.productDao().getAllProducts(firstArg()) }
        modelo.refreshProducts()
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.isLoading.first { !it } } }
        assertNull(modelo.errorCatalogo.value)
        modelo.updateSearchQuery("sin-coincidencia")
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.searchResults.first { it.isEmpty() } } }
        assertFalse(modelo.products.value.isEmpty())
        assertNull(modelo.errorCatalogo.value)
    }

    @Test fun `carga remota vacia gira sin error y despues entrega resultados reales`() = runTest {
        val modelo = vm()
        conexion.setInternetConnected(true)
        val pendiente = CompletableDeferred<Result<List<Product>>>()
        coEvery { red.getProducts(any(), any()) } coAnswers { pendiente.await() }
        modelo.refreshProducts()
        assertTrue(modelo.isLoading.value)
        assertNull(modelo.errorCatalogo.value)
        assertTrue(modelo.products.value.isEmpty())
        pendiente.complete(Result.success(listOf(producto())))
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.isLoading.first { !it } } }
        modelo.updateSearchQuery("cam")
        withContext(Dispatchers.Default) { withTimeout(10000) { modelo.searchResults.first { it.isNotEmpty() } } }
        assertEquals(1, modelo.searchResults.value.size)
        assertNull(modelo.errorCatalogo.value)
    }

}
