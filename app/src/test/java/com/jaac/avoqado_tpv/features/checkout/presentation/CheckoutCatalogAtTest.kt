package com.jaac.avoqado_tpv.features.checkout.presentation

import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.features.checkout.data.ActiveCartState
import com.jaac.avoqado_tpv.features.checkout.domain.armarPedidoParaMobile
import com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * «Cobrar» sin red, C2a: el `catalogAt` del renglón se CONGELA al agregar el producto. Un refresco posterior del catálogo no lo mueve:
 * lo que viaja al servidor es el precio y la hora del catálogo con que el cajero VIO el producto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutCatalogAtTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val viejo = "2026-10-20T10:00:00.000Z"
    private val nuevo = "2026-10-20T11:30:00.000Z"

    private lateinit var productRepository: ProductRepository
    private lateinit var mosaicRepository: MosaicRepository
    private lateinit var savedCartsRepository: SavedCartsRepository
    private lateinit var secureStorage: SecureStorage

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
        CheckoutViewModel.despachadorDelCatalogo = Dispatchers.Unconfined
        productRepository = mockk(relaxed = true)
        savedCartsRepository = mockk(relaxed = true)
        mosaicRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        coEvery { secureStorage.getVenueId() } returns "venue-1"
        coEvery { secureStorage.getStaffId() } returns "staff-1"
        coEvery { secureStorage.getStaffName() } returns "María"
        coEvery { productRepository.getCategories(any()) } returns Result.success(emptyList())
        coEvery { savedCartsRepository.observeAll() } returns MutableStateFlow(emptyList())
        coEvery { mosaicRepository.observe(any()) } returns MutableStateFlow(emptyList())
    }

    @After fun limpiar() {
        Dispatchers.resetMain()
        CheckoutViewModel.despachadorDelCatalogo = Dispatchers.Default
    }

    private fun cafe(catalogAt: String?, precio: String = "50.00") = Product(
        id = "p-1", name = "Café", sku = "CF", price = BigDecimal(precio), categoryId = "c-1", categoryName = "Bebidas",
        description = null, emoji = "☕", imageUrl = null, available = true, catalogAt = catalogAt,
    )

    private fun viewModel(vararg productos: Product, conexion: ConnectionStateManager = ConnectionStateManager()): CheckoutViewModel {
        coEvery { productRepository.getProducts(any(), any()) } returns Result.success(productos.toList())
        return CheckoutViewModel(
            productRepository = productRepository,
            customerRepository = mockk(relaxed = true),
            orderRepository = mockk(relaxed = true),
            discountRepository = mockk(relaxed = true),
            savedCartsRepository = savedCartsRepository,
            mosaicRepository = mosaicRepository,
            activeCartState = ActiveCartState(),
            secureStorage = secureStorage,
            validateReferralUseCase = mockk(relaxed = true),
            captureReferralUseCase = mockk(relaxed = true),
            productDao = mockk(relaxed = true) { coEvery { getAllProducts(any()) } returns emptyList() },
            productCategoryDao = mockk(relaxed = true) { coEvery { getAllCategories(any()) } returns emptyList() },
            catalogoGuardado = mockk { coEvery { refrescar(any()) } coAnswers { productRepository.getProducts(firstArg()) } },
            connectionStateManager = conexion,
            ventaSinRed = com.jaac.avoqado_tpv.features.checkout.VentaSinRedEnMemoria(),
            impresoraDeVentaGuardada = mockk(relaxed = true),
            pendingPaymentDao = mockk(relaxed = true) {
                io.mockk.every { contarVentasGuardadas(any()) } returns kotlinx.coroutines.flow.MutableStateFlow(0)
                io.mockk.every { contarConProblema(any()) } returns kotlinx.coroutines.flow.MutableStateFlow(0)
            },
            autorizacionDeGerente = mockk {
                coEvery { paraCortesia(any(), any(), any()) } returns com.jaac.avoqado_tpv.features.permissions.presentation.override.Autorizacion.NoHaceFalta
            },
            permissionsRepository = mockk(relaxed = true) {
                io.mockk.coEvery { hasPermission(any()) } returns true
            },
        )
    }

    @Test fun `catalogAt del renglon es el de cuando se agrego aunque el catalogo se refresque despues`() = runTest {
        val vm = viewModel(cafe(viejo))
        vm.products.first { it.isNotEmpty() }
        vm.addProduct(vm.products.value.single())

        coEvery { productRepository.getProducts(any(), any()) } returns Result.success(listOf(cafe(nuevo, precio = "80.00")))
        vm.refreshProducts()
        vm.products.first { lista -> lista.any { it.catalogAt == nuevo } }

        val renglon = vm.cartState.value.items.single()
        assertThat(renglon.catalogAt).isEqualTo(viejo)
        assertThat(renglon.unitPriceCents).isEqualTo(5000)
        // Lo que viaja: el precio y la hora que VIO el cajero, no los del catálogo vigente.
        val linea = JsonParser.parseString(
            armarPedidoParaMobile(vm.cartState.value, "llave-1", "staff-1", null, 1L, vm.products.value.associateBy { it.id }),
        ).asJsonObject["items"].asJsonArray[0].asJsonObject
        assertThat(linea["catalogAt"].asString).isEqualTo(viejo)
        assertThat(linea["displayedUnitPrice"].asInt).isEqualTo(5000)
        vm.viewModelScope.cancel()
    }

    @Test fun `el producto escaneado con red lleva el catalogAt de la respuesta a la linea del pedido`() = runTest {
        val conRed = mockk<ConnectionStateManager>(relaxed = true) { every { hasInternet() } returns true }
        val vm = viewModel(conexion = conRed)
        coEvery { productRepository.getProductByBarcode("venue-1", "750100") } returns Result.success(cafe(viejo))

        // El camino real del escáner: `findProductByBarcode` pregunta al servidor y entrega el producto (con el catalogAt de la
        // raíz de la respuesta) por su callback; la pantalla lo agrega con addProduct.
        var encontrado: Product? = null
        var delGuardado: Boolean? = null
        vm.findProductByBarcode("750100") { producto, guardado -> encontrado = producto; delGuardado = guardado }
        assertThat(delGuardado).isFalse()
        vm.addProduct(encontrado!!)

        val linea = JsonParser.parseString(
            armarPedidoParaMobile(vm.cartState.value, "llave-1", "staff-1", null, 1L, emptyMap()),
        ).asJsonObject["items"].asJsonArray[0].asJsonObject

        assertThat(linea["displayedUnitPrice"].asInt).isEqualTo(5000)
        assertThat(linea["catalogAt"].asString).isEqualTo(viejo)
        vm.viewModelScope.cancel()
    }

    @Test fun `el atajo congela el catalogAt del catalogo pintado y un importe tecleado no lleva ninguno`() = runTest {
        val vm = viewModel(cafe(viejo))
        vm.products.first { it.isNotEmpty() }

        vm.addShortcutToCart(MosaicShortcut(id = "s-1", venueId = "venue-1", productId = "p-1", position = 0, label = "Café"))
        vm.addCustomAmount("Propina extra", 1500)

        val (producto, importe) = vm.cartState.value.items
        assertThat(producto.catalogAt).isEqualTo(viejo)
        assertThat(importe.catalogAt).isNull()
        vm.viewModelScope.cancel()
    }

    @Test fun `un producto sin catalogAt (servidor viejo) queda sin precio visto`() = runTest {
        val vm = viewModel()
        vm.addProduct(cafe(null))

        val linea = JsonParser.parseString(
            armarPedidoParaMobile(vm.cartState.value, "llave-1", "staff-1", null, 1L, emptyMap()),
        ).asJsonObject["items"].asJsonArray[0].asJsonObject

        assertThat(vm.cartState.value.items.single().catalogAt).isNull()
        assertThat(linea.has("displayedUnitPrice")).isFalse()
        assertThat(linea.has("catalogAt")).isFalse()
        vm.viewModelScope.cancel()
    }
}
