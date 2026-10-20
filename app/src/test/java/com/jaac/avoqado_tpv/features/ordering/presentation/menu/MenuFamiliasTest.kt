package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntity
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductDisplayMode
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.ordering.presentation.components.visiblesEnCuadricula
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Variantes de producto, bloque 4 (TPV), Órdenes: las tallas de una familia salen en UN botón, también sin red (la familia sale de la
 * caché de Room), y la variante elegida sigue el camino de hoy (extras o agregar) contra el catálogo VIGENTE.
 *
 * Los casos de búsqueda y de categoría usan las mismas piezas puras que la pantalla (`visiblesEnCuadricula` +
 * `reglasDeOrdenes.entradasParaMostrar`), así que lo que se prueba aquí es lo que ve la cajera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuFamiliasTest {

    private val venueId = "venue-1"
    private val grupo = "g-playera"
    private val testDispatcher = UnconfinedTestDispatcher()
    private val creados = mutableListOf<MenuViewModel>()

    private lateinit var productRepository: ProductRepository
    private lateinit var productDao: ProductDao
    private lateinit var productCategoryDao: ProductCategoryDao
    private lateinit var secureStorage: SecureStorage
    private lateinit var deviceInfoManager: DeviceInfoManager
    private lateinit var socketManager: SocketManager
    private lateinit var connectionEventManager: ConnectionEventManager
    private lateinit var orderSyncCoordinator: OrderSyncCoordinator

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
        productRepository = mockk(relaxed = true)
        productDao = mockk(relaxed = true)
        productCategoryDao = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        deviceInfoManager = mockk(relaxed = true)
        socketManager = mockk(relaxed = true)
        connectionEventManager = mockk(relaxed = true)
        orderSyncCoordinator = mockk(relaxed = true)
        every { secureStorage.getOrderingProductDisplayMode() } returns ProductDisplayMode.GRID_3
        every { deviceInfoManager.getVenueId() } returns venueId
        every { socketManager.events } returns MutableSharedFlow<SocketEvent>()
        every { connectionEventManager.connectionRestoredEvents } returns MutableSharedFlow<ConnectionRestoredEvent>()
        every { orderSyncCoordinator.syncEvents } returns MutableSharedFlow<OrderSyncCoordinator.SyncEvent>()
        coEvery { productCategoryDao.getAllCategories(any()) } returns emptyList()
        coEvery { productRepository.getCategories(venueId) } returns Result.success(emptyList())
        // Sin red: el server no contesta; lo que hay es la caché de Room.
        coEvery { productRepository.getProducts(venueId) } returns Result.failure(java.io.IOException("sin red"))
    }

    @After fun limpiar() {
        try {
            runBlocking {
                creados.forEach { it.viewModelScope.cancel() }
                creados.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally {
            creados.clear()
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    @Test fun `sin red la familia sale de la cache`() = runBlocking<Unit> {
        val vm = crearViewModelConCache(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"), taza())

        val familias = withTimeout(5_000) { vm.familias.first { it.isNotEmpty() } }

        assertThat(familias.keys).containsExactly(grupo)
        assertThat(familias.getValue(grupo).variantes.map { it.id }).containsExactly("p-s", "p-m", "p-l")
        // La taza no tiene familia: sale suelta, y las tres playeras salen en UN botón.
        val entradas = reglasDeOrdenes.entradasParaMostrar(visiblesEnCuadricula(vm.products.value, null), familias, "")
        assertThat(entradas).hasSize(2)
        assertThat(entradas.filterIsInstance<EntradaDeCatalogo.DeFamilia<Product>>().single().familia.nombre).isEqualTo("Playera básica")
        assertThat(entradas.filterIsInstance<EntradaDeCatalogo.Suelto<Product>>().single().producto.id).isEqualTo("p-taza")
    }

    @Test fun `una talla no disponible no entra a la familia`() = runBlocking<Unit> {
        val vm = crearViewModelConCache(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L", disponible = false))

        val familias = withTimeout(5_000) { vm.familias.first { it.isNotEmpty() } }

        assertThat(familias.getValue(grupo).variantes.map { it.id }).containsExactly("p-s", "p-m")
        // Y la cuadrícula tampoco la pinta suelta: Órdenes oculta lo no disponible, como hoy.
        assertThat(visiblesEnCuadricula(vm.products.value, null).map { it.id }).containsExactly("p-s", "p-m")
    }

    @Test fun `una familia con una sola talla disponible deja de ser familia y sale suelta`() = runBlocking<Unit> {
        val vm = crearViewModelConCache(playera("p-s", "S"), playera("p-m", "M", disponible = false), playera("p-l", "L", disponible = false))
        withTimeout(5_000) { vm.products.first { it.isNotEmpty() } }

        assertThat(vm.familias.value).isEmpty()
        val entradas = reglasDeOrdenes.entradasParaMostrar(visiblesEnCuadricula(vm.products.value, null), vm.familias.value, "")
        assertThat(entradas).containsExactly(EntradaDeCatalogo.Suelto(vm.products.value.single { it.id == "p-s" }))
    }

    @Test fun `buscar playera devuelve la familia y buscar el sku devuelve la talla`() = runBlocking<Unit> {
        val vm = crearViewModelConCache(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"), taza())
        val familias = withTimeout(5_000) { vm.familias.first { it.isNotEmpty() } }
        val visibles = visiblesEnCuadricula(vm.products.value, null)

        // Por nombre (sin importar mayúsculas) o por descripción: la familia, UNA vez.
        for (texto in listOf("playera", "PLAYERA", "algodón")) {
            val porTexto = reglasDeOrdenes.entradasParaMostrar(visibles, familias, texto)
            assertThat(porTexto).containsExactly(EntradaDeCatalogo.DeFamilia(familias.getValue(grupo)))
        }
        // Por SKU: la talla suelta, nunca el botón de la familia (quien teclea o escanea un código quiere ESA talla).
        val porSku = reglasDeOrdenes.entradasParaMostrar(visibles, familias, "sku-p-m")
        assertThat(porSku).hasSize(1)
        assertThat((porSku.single() as EntradaDeCatalogo.Suelto<Product>).producto.id).isEqualTo("p-m")
        // Un producto suelto se sigue encontrando como hoy.
        val porTaza = reglasDeOrdenes.entradasParaMostrar(visibles, familias, "taza")
        assertThat(porTaza.map { (it as EntradaDeCatalogo.Suelto<Product>).producto.id }).containsExactly("p-taza")
        // Teclear no recalcula las familias: el mapa es el mismo objeto.
        vm.setSearchQuery("playera")
        assertThat(vm.familias.value).isSameInstanceAs(familias)
    }

    @Test fun `una categoria con una sola variante visible igual muestra el boton de la familia`() = runBlocking<Unit> {
        // S y M viven en «c-1»; L vive en «c-2». Las familias se calculan sobre el catálogo completo y se colocan en lo ya filtrado.
        val vm = crearViewModelConCache(
            playera("p-s", "S", categoria = "c-1"), playera("p-m", "M", categoria = "c-1"), playera("p-l", "L", categoria = "c-2"),
        )
        val familias = withTimeout(5_000) { vm.familias.first { it.isNotEmpty() } }
        val soloC2 = ProductCategory(id = "c-2", name = "Otra", displayOrder = 1, productCount = 1)

        val entradas = reglasDeOrdenes.entradasParaMostrar(visiblesEnCuadricula(vm.products.value, soloC2), familias, "")

        assertThat(entradas).hasSize(1)
        val familia = (entradas.single() as EntradaDeCatalogo.DeFamilia<Product>).familia
        assertThat(familia.variantes.map { it.id }).containsExactly("p-s", "p-m", "p-l")
    }

    @Test fun `la variante elegida con extras abre su propia hoja`() {
        val sinExtras = playera("p-s", "S")
        val conExtras = playera("p-m", "M", extras = true)

        assertThat(accionAlElegir(conExtras)).isEqualTo(AccionAlElegir.AbrirExtras(conExtras))
        assertThat(accionAlElegir(sinExtras)).isEqualTo(AccionAlElegir.Agregar(sinExtras))
        // Sin variante en el catálogo vigente no hay nada que hacer.
        assertThat(accionAlElegir(null)).isEqualTo(AccionAlElegir.Cerrar)
    }

    @Test fun `la hoja no puede estar abierta mientras se prepara el pago ni con el escaner abierto`() {
        // Con «Preparando pago…» un artículo nuevo entraría mientras se sincroniza la orden y el cobro saldría corto.
        assertThat(hojaDeVariantesPermitida(preparandoPago = false, escanerAbierto = false)).isTrue()
        assertThat(hojaDeVariantesPermitida(preparandoPago = true, escanerAbierto = false)).isFalse()
        assertThat(hojaDeVariantesPermitida(preparandoPago = false, escanerAbierto = true)).isFalse()
        assertThat(hojaDeVariantesPermitida(preparandoPago = true, escanerAbierto = true)).isFalse()
    }

    @Test fun `la variante se resuelve contra el catalogo vigente`() = runBlocking<Unit> {
        val vm = crearViewModelConCache(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"))
        val familias = withTimeout(5_000) { vm.familias.first { it.isNotEmpty() } }
        // La hoja se abrió con esta talla M: sin extras.
        val mAlAbrir = familias.getValue(grupo).variantes.single { it.id == "p-m" }
        assertThat(accionAlElegir(vm.productoVigente(mAlAbrir.id))).isEqualTo(AccionAlElegir.Agregar(mAlAbrir))

        // El catálogo se refresca con la hoja abierta: ahora M lleva extras. Se usan los extras NUEVOS.
        servidorResponde(playera("p-s", "S"), playera("p-m", "M", extras = true), playera("p-l", "L"))
        vm.refreshProducts()
        withTimeout(5_000) { vm.products.first { lista -> lista.any { it.id == "p-m" && it.hasModifiers } } }
        val accion = accionAlElegir(vm.productoVigente("p-m"))
        assertThat(accion).isInstanceOf(AccionAlElegir.AbrirExtras::class.java)
        assertThat((accion as AccionAlElegir.AbrirExtras).variante.modifierGroups).isNotEmpty()

        // Y si M ya no está (o ya no se puede vender), la hoja sólo se cierra.
        servidorResponde(playera("p-s", "S"), playera("p-l", "L"))
        vm.refreshProducts()
        withTimeout(5_000) { vm.products.first { lista -> lista.none { it.id == "p-m" } } }
        assertThat(vm.productoVigente("p-m")).isNull()
        assertThat(accionAlElegir(vm.productoVigente("p-m"))).isEqualTo(AccionAlElegir.Cerrar)

        servidorResponde(playera("p-s", "S"), playera("p-m", "M", disponible = false), playera("p-l", "L"))
        vm.refreshProducts()
        withTimeout(5_000) { vm.products.first { lista -> lista.any { it.id == "p-m" && !it.available } } }
        assertThat(vm.productoVigente("p-m")).isNull()
    }

    // ---- Montaje ----

    private fun servidorResponde(vararg productos: Product) {
        coEvery { productRepository.getProducts(venueId) } returns Result.success(productos.toList())
    }

    private fun crearViewModelConCache(vararg cache: Product): MenuViewModel {
        coEvery { productDao.getAllProducts(venueId) } returns cache.map { it.toEntity(venueId, 100L) }
        return MenuViewModel(
            context = mockk(relaxed = true),
            secureStorage = secureStorage,
            deviceInfoManager = deviceInfoManager,
            productRepository = productRepository,
            orderRepository = mockk(relaxed = true),
            tableRepository = mockk(relaxed = true),
            discountRepository = mockk(relaxed = true),
            customerRepository = mockk(relaxed = true),
            socketManager = socketManager,
            orderSyncCoordinator = orderSyncCoordinator,
            connectionEventManager = connectionEventManager,
            productDao = productDao,
            catalogoGuardado = CatalogoGuardado(productRepository, productDao),
            productCategoryDao = productCategoryDao,
            printerManager = mockk(relaxed = true),
            paymentApiService = mockk(relaxed = true),
            permissionsRepository = mockk(relaxed = true),
        ).also { creados.add(it) }
    }

    private fun playera(
        id: String,
        talla: String,
        disponible: Boolean = true,
        extras: Boolean = false,
        categoria: String = "c-1",
    ) = Product(
        id = id, name = "Playera básica $talla Negro", sku = "sku-$id", price = BigDecimal("199.00"), categoryId = categoria,
        categoryName = "Ropa", description = "Algodón", emoji = "👕", imageUrl = null, available = disponible,
        modifierGroups = if (extras) listOf(grupoDeExtras()) else emptyList(),
        familia = DatosDeFamilia(
            groupId = grupo, groupName = "Playera básica", groupImageUrl = null,
            opciones = listOf(
                OpcionDeVariante("Talla", talla, 0, listOf("S", "M", "L").indexOf(talla)),
                OpcionDeVariante("Color", "Negro", 1, 0),
            ),
        ),
    )

    private fun taza() = Product(
        id = "p-taza", name = "Taza", sku = "sku-taza", price = BigDecimal("80.00"), categoryId = "c-1", categoryName = "Hogar",
        description = null, emoji = "☕", imageUrl = null, available = true,
    )

    private fun grupoDeExtras() = ModifierGroup(
        id = "mg-1", name = "Extras", type = ModifierType.MULTIPLE_CHOICE,
        modifiers = listOf(ProductModifier("m-1", "Bordado", BigDecimal("30.00"), ModifierType.MULTIPLE_CHOICE)),
    )
}
