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
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductDisplayMode
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * Regresión del candado de `CatalogoGuardado` (Codex r3): con un solo refresco a la vez, Órdenes podía quedarse con el aviso
 * «Cargando productos y categorías…» (`MenuScreen`, mandado sólo por `isLoadingProducts`) mientras Inicio tenía el candado, aunque
 * el catálogo guardado ya se podía pintar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelCatalogoTest {

    private val venueId = "venue-1"
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

    @Test fun `con cache el indicador de carga se apaga aunque otro refresco tenga el candado`() = runBlocking<Unit> {
        val cache = listOf(producto("p-1", "Taza"), producto("p-2", "Playera"))
        coEvery { productDao.getAllProducts(venueId) } returns cache.map { it.toEntity(venueId, 100L) }
        val catalogo = CatalogoGuardado(productRepository, productDao)

        // "Inicio" ya está refrescando: tiene el candado y espera la respuesta del server.
        val inicioPregunta = CompletableDeferred<Unit>()
        val respuestaDeInicio = CompletableDeferred<Result<List<Product>>>()
        coEvery { productRepository.getProducts(venueId) } coAnswers {
            inicioPregunta.complete(Unit)
            respuestaDeInicio.await()
        }
        val inicio = launch(Dispatchers.IO) { catalogo.refrescar(venueId) }
        withTimeout(5_000) { inicioPregunta.await() }

        val vm = crearViewModel(catalogo)

        withTimeout(5_000) { vm.isLoadingProducts.first { !it } }
        assertThat(vm.isLoadingProducts.value).isFalse()
        assertThat(vm.products.value.map { it.id }).containsExactly("p-1", "p-2")

        respuestaDeInicio.complete(Result.failure(java.io.IOException("sin red")))
        inicio.join()
    }

    @Test fun `con la cache vacia el indicador de carga sigue hasta que llega el catalogo`() = runBlocking<Unit> {
        coEvery { productDao.getAllProducts(venueId) } returns emptyList()
        val catalogo = CatalogoGuardado(productRepository, productDao)
        val llegada = CompletableDeferred<Result<List<Product>>>()
        val preguntando = CompletableDeferred<Unit>()
        coEvery { productRepository.getProducts(venueId) } coAnswers {
            preguntando.complete(Unit)
            llegada.await()
        }

        val vm = crearViewModel(catalogo)
        withTimeout(5_000) { preguntando.await() }

        assertThat(vm.isLoadingProducts.value).isTrue()

        llegada.complete(Result.success(listOf(producto("p-1", "Taza"))))
        withTimeout(5_000) { vm.isLoadingProducts.first { !it } }
        assertThat(vm.products.value.map { it.id }).containsExactly("p-1")
    }

    private fun crearViewModel(catalogo: CatalogoGuardado) = MenuViewModel(
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
        catalogoGuardado = catalogo,
        productCategoryDao = productCategoryDao,
        printerManager = mockk(relaxed = true),
        paymentApiService = mockk(relaxed = true),
        permissionsRepository = mockk(relaxed = true),
    ).also { creados.add(it) }

    private fun producto(id: String, nombre: String) = Product(
        id = id, name = nombre, sku = "sku-$id", price = BigDecimal("50.00"), categoryId = "c-1", categoryName = "General",
        description = null, emoji = "🛍", imageUrl = null, available = true,
    )
}
