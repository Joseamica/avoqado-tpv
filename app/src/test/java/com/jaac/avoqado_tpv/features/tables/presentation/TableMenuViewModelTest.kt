package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuCategory
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuProduct
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/** `TableMenuScreen` (Plan C, Task 7) — catálogo + alta al [PendingRoundCart]. */
@OptIn(ExperimentalCoroutinesApi::class)
class TableMenuViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<TablesRepository>()
    private val deviceInfoManager = mockk<DeviceInfoManager>()
    private lateinit var tableSession: TableSession
    private lateinit var pendingCart: PendingRoundCart

    private val venueId = "venue-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearMocks(repository, deviceInfoManager)
        tableSession = TableSession()
        pendingCart = PendingRoundCart()
        every { deviceInfoManager.getVenueId() } returns venueId
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TableMenuViewModel = TableMenuViewModel(
        repository = repository,
        tableSession = tableSession,
        pendingCart = pendingCart,
        deviceInfoManager = deviceInfoManager,
        productDao = io.mockk.mockk<com.jaac.avoqado_tpv.core.data.local.dao.ProductDao>().also { io.mockk.coEvery { it.getAvailableProducts(any()) } returns emptyList() },
    )

    @Test
    fun load_exitoso_llena_productos_y_categorias() = runTest {
        coEvery { repository.getProducts(venueId) } returns Result.success(
            listOf(MenuProduct(id = "p1", name = "Café", price = BigDecimal("45.00"), categoryId = "c1")),
        )
        coEvery { repository.getCategories(venueId) } returns Result.success(listOf(MenuCategory(id = "c1", name = "Bebidas")))

        val viewModel = createViewModel()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.products).hasSize(1)
        assertThat(viewModel.uiState.value.categories).hasSize(1)
        assertThat(viewModel.uiState.value.errorMessage).isNull()
    }

    @Test
    fun sin_red_en_frio_sin_catalogo_previo_marca_error() = runTest {
        coEvery { repository.getProducts(venueId) } returns Result.failure(IOException("sin red"))
        coEvery { repository.getCategories(venueId) } returns Result.failure(IOException("sin red"))

        val viewModel = createViewModel()

        assertThat(viewModel.uiState.value.products).isEmpty()
        assertThat(viewModel.uiState.value.errorMessage).isNotNull()
    }

    @Test
    fun catalogo_ya_cargado_se_sigue_mostrando_si_un_refresco_posterior_falla() = runTest {
        // Offline-first de lectura (mismo criterio que TablesViewModel): un
        // catálogo YA cargado nunca se tapa con una pantalla de error solo
        // porque el siguiente refresco (p.ej. load() llamado de nuevo) no
        // tuvo red — el grid sigue mostrando lo último bueno.
        coEvery { repository.getProducts(venueId) } returns Result.success(
            listOf(MenuProduct(id = "p1", name = "Café", price = BigDecimal("45.00"))),
        )
        coEvery { repository.getCategories(venueId) } returns Result.success(emptyList())
        val viewModel = createViewModel()
        assertThat(viewModel.uiState.value.products).hasSize(1)

        coEvery { repository.getProducts(venueId) } returns Result.failure(IOException("sin red"))
        viewModel.load()

        assertThat(viewModel.uiState.value.products).hasSize(1)
        assertThat(viewModel.uiState.value.errorMessage).isNull()
    }

    @Test
    fun productsForSelectedCategory_filtra_por_categoria() = runTest {
        coEvery { repository.getProducts(venueId) } returns Result.success(
            listOf(
                MenuProduct(id = "p1", name = "Café", price = BigDecimal("45.00"), categoryId = "c1"),
                MenuProduct(id = "p2", name = "Sandwich", price = BigDecimal("80.00"), categoryId = "c2"),
            ),
        )
        coEvery { repository.getCategories(venueId) } returns Result.success(emptyList())
        val viewModel = createViewModel()

        viewModel.selectCategory("c1")

        assertThat(viewModel.uiState.value.productsForSelectedCategory()).hasSize(1)
        assertThat(viewModel.uiState.value.productsForSelectedCategory().first().id).isEqualTo("p1")
    }

    @Test
    fun addSimple_escribe_en_el_carrito_compartido() = runTest {
        coEvery { repository.getProducts(venueId) } returns Result.success(emptyList())
        coEvery { repository.getCategories(venueId) } returns Result.success(emptyList())
        val viewModel = createViewModel()
        val product = MenuProduct(id = "p1", name = "Café", price = BigDecimal("45.00"))

        viewModel.addSimple(product)

        assertThat(pendingCart.lines.value).hasSize(1)
        assertThat(pendingCart.lines.value.first().productId).isEqualTo("p1")
    }

    // region — D86: sin red, el menú guardado en la terminal

    private fun guardado(id: String, available: Boolean = true, categoriaActiva: Boolean = true) =
        com.jaac.avoqado_tpv.core.data.local.entities.ProductEntity(
            productId = id, venueId = "venue-1", name = "Producto $id", sku = "sku-$id", price = "45.00",
            categoryId = "cat-1", categoryName = "Bebidas", description = null, emoji = "", imageUrl = null,
            available = available, displayOrder = 0, trackInventory = false, inventoryMethod = null, availableQuantity = null,
            modifierGroupsJson = "[]", categoryIsActive = categoriaActiva, cachedAt = 0L,
        )

    @Test
    fun D86_sin_red_y_sin_menu_en_memoria_usa_el_guardado_sólo_con_lo_disponible() = runTest {
        val dao = mockk<com.jaac.avoqado_tpv.core.data.local.dao.ProductDao>()
        coEvery { dao.getAvailableProducts("venue-1") } returns listOf(guardado("p1"), guardado("p2", categoriaActiva = false))
        coEvery { repository.getProducts(any()) } returns Result.failure(IOException("sin red"))
        coEvery { repository.getCategories(any()) } returns Result.failure(IOException("sin red"))
        every { deviceInfoManager.getVenueId() } returns "venue-1"

        val vm = TableMenuViewModel(repository = repository, tableSession = tableSession, pendingCart = pendingCart, deviceInfoManager = deviceInfoManager, productDao = dao)

        val estado = vm.uiState.value
        assertThat(estado.products.map { it.id }).containsExactly("p1")
        assertThat(estado.products.single().price).isEqualTo(BigDecimal("45.00"))
        assertThat(estado.categories.map { it.name }).containsExactly("Bebidas")
        assertThat(estado.avisoCatalogo).isEqualTo(AVISO_CATALOGO_GUARDADO)
        assertThat(estado.errorMessage).isNull()
    }

    @Test
    fun D86_sin_red_y_sin_menu_guardado_lo_dice() = runTest {
        coEvery { repository.getProducts(any()) } returns Result.failure(IOException("sin red"))
        coEvery { repository.getCategories(any()) } returns Result.failure(IOException("sin red"))

        val vm = createViewModel()

        assertThat(vm.uiState.value.products).isEmpty()
        assertThat(vm.uiState.value.errorMessage).contains("sin menú guardado")
    }

    // endregion
}
