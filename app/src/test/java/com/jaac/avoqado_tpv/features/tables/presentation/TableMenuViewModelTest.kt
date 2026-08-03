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
}
