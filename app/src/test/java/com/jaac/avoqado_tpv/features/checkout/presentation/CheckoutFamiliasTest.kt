package com.jaac.avoqado_tpv.features.checkout.presentation

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.features.checkout.data.ActiveCartState
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import io.mockk.coEvery
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
 * Variantes de producto, bloque 4 (TPV), Cobro rápido: las tallas de una familia salen en UN botón y la variante elegida sigue el
 * camino de HOY de esta pantalla (agotada ⇒ aviso de no disponible; con extras ⇒ su hoja de extras; si no ⇒ agregar), contra el
 * catálogo vigente. La búsqueda sigue siendo la de Cobro rápido: sin acentos, con `trim`, sólo nombre y SKU (no descripción).
 *
 * Usa el `CheckoutViewModel` real y las mismas piezas puras que la pantalla (`reglasDeCobro`, `accionAlElegirEnCobro`): lo que se prueba aquí es lo que ve la cajera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutFamiliasTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val grupo = "g-playera"

    private lateinit var productRepository: ProductRepository
    private lateinit var savedCartsRepository: SavedCartsRepository
    private lateinit var mosaicRepository: MosaicRepository
    private lateinit var secureStorage: SecureStorage

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
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
    }

    @Test fun `las familias salen de refreshProducts y teclear no las recalcula`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"), taza())

        val familias = vm.familias.first { it.isNotEmpty() }

        assertThat(familias.keys).containsExactly(grupo)
        assertThat(familias.getValue(grupo).variantes.map { it.id }).containsExactly("p-s", "p-m", "p-l")
        // La taza no tiene familia: sale suelta; las tres playeras salen en UN botón.
        val entradas = reglasDeCobro.agruparCatalogo(vm.products.value, familias)
        assertThat(entradas).hasSize(2)
        assertThat(entradas.filterIsInstance<EntradaDeCatalogo.DeFamilia<Product>>().single().familia.nombre).isEqualTo("Playera básica")
        // Teclear en la búsqueda no recalcula las familias: el mapa es el mismo objeto (sólo cambia cuando cambia el catálogo).
        vm.updateSearchQuery("playera")
        assertThat(vm.familias.value).isSameInstanceAs(familias)
        vm.viewModelScope.cancel()
    }

    @Test fun `una talla agotada sigue en la familia y el boton dice Agotado solo si todas lo estan`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M", disponible = false))
        val familia = vm.familias.first { it.isNotEmpty() }.getValue(grupo)

        // Cobro rápido pinta hoy lo agotado (con su velo): la talla agotada NO sale de la familia.
        assertThat(familia.variantes.map { it.id }).containsExactly("p-s", "p-m")
        assertThat(reglasDeCobro.agotada(familia)).isFalse()

        // Con TODAS agotadas, el botón de la familia dice «Agotado» (y sigue siendo tocable).
        servidorResponde(playera("p-s", "S", disponible = false), playera("p-m", "M", disponible = false))
        vm.refreshProducts()
        val todasAgotadas = vm.familias.first { f -> f[grupo]?.variantes?.all { !it.available } == true }.getValue(grupo)
        assertThat(reglasDeCobro.agotada(todasAgotadas)).isTrue()
        vm.viewModelScope.cancel()
    }

    @Test fun `variante agotada elegida va al dialogo de no disponible`() {
        val agotada = playera("p-m", "M", disponible = false)
        // El orden de hoy: lo agotado primero, aunque además tenga extras.
        assertThat(accionAlElegirEnCobro(agotada)).isEqualTo(AccionAlElegir.NoDisponible(agotada))
        val agotadaConExtras = playera("p-l", "L", disponible = false, extras = true)
        assertThat(accionAlElegirEnCobro(agotadaConExtras)).isEqualTo(AccionAlElegir.NoDisponible(agotadaConExtras))
    }

    @Test fun `la hoja dice Agotado si no esta disponible y si no cuantas quedan`() {
        val base = playera("p-m", "M")
        // Agotada: se ve marcada (como hoy un agotado se muestra), con la palabra corta y no con «se puede vender igual».
        assertThat(avisoDeExistenciaEnCobro(base.copy(available = false))).isEqualTo("Agotado")
        assertThat(avisoDeExistenciaEnCobro(base.copy(available = false, trackInventory = true, availableQuantity = 0))).isEqualTo("Agotado")
        // Disponible: la misma regla que Órdenes (la existencia que hoy enseña cada talla suelta).
        assertThat(avisoDeExistenciaEnCobro(base.copy(trackInventory = true, availableQuantity = 5))).isEqualTo("5 disponibles")
        assertThat(avisoDeExistenciaEnCobro(base.copy(trackInventory = true, availableQuantity = 1))).isEqualTo("1 disponible")
        assertThat(avisoDeExistenciaEnCobro(base.copy(trackInventory = false, availableQuantity = 5))).isNull()
        assertThat(avisoDeExistenciaEnCobro(base.copy(trackInventory = true, availableQuantity = null))).isNull()
    }

    @Test fun `la variante elegida con extras abre sus propios extras y sin extras se agrega`() {
        val sinExtras = playera("p-s", "S")
        val conExtras = playera("p-m", "M", extras = true)

        assertThat(accionAlElegirEnCobro(conExtras)).isEqualTo(AccionAlElegir.AbrirExtras(conExtras))
        assertThat(accionAlElegirEnCobro(sinExtras)).isEqualTo(AccionAlElegir.Agregar(sinExtras))
        // La variante ya no está en el catálogo vigente: la hoja sólo se cierra.
        assertThat(accionAlElegirEnCobro(null)).isEqualTo(AccionAlElegir.Cerrar)
    }

    @Test fun `la variante se resuelve contra el catalogo vigente`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"))
        vm.familias.first { it.isNotEmpty() }
        assertThat(accionAlElegirEnCobro(vm.productoVigente("p-m"))).isInstanceOf(AccionAlElegir.Agregar::class.java)

        // El catálogo se refresca con la hoja abierta: M gana extras ⇒ cuentan los extras NUEVOS, de esa variante.
        servidorResponde(playera("p-s", "S"), playera("p-m", "M", extras = true), playera("p-l", "L"))
        vm.refreshProducts()
        vm.products.first { lista -> lista.any { it.id == "p-m" && it.hasModifiers } }
        val conExtras = accionAlElegirEnCobro(vm.productoVigente("p-m"))
        assertThat(conExtras).isInstanceOf(AccionAlElegir.AbrirExtras::class.java)
        assertThat((conExtras as AccionAlElegir.AbrirExtras).variante.id).isEqualTo("p-m")
        assertThat(conExtras.variante.modifierGroups).isNotEmpty()

        // M se agota con la hoja abierta: la terminal la tiene que seguir viendo (hoy un agotado se muestra) y avisa.
        servidorResponde(playera("p-s", "S"), playera("p-m", "M", disponible = false), playera("p-l", "L"))
        vm.refreshProducts()
        vm.products.first { lista -> lista.any { it.id == "p-m" && !it.available } }
        assertThat(accionAlElegirEnCobro(vm.productoVigente("p-m"))).isInstanceOf(AccionAlElegir.NoDisponible::class.java)

        // M desaparece del catálogo: la hoja sólo se cierra.
        servidorResponde(playera("p-s", "S"), playera("p-l", "L"))
        vm.refreshProducts()
        vm.products.first { lista -> lista.none { it.id == "p-m" } }
        assertThat(vm.productoVigente("p-m")).isNull()
        assertThat(accionAlElegirEnCobro(vm.productoVigente("p-m"))).isEqualTo(AccionAlElegir.Cerrar)
        vm.viewModelScope.cancel()
    }

    @Test fun `una categoria con una sola variante visible igual muestra el boton de la familia`() = runTest {
        // S y M viven en «c-1»; L vive en «c-2». Las familias se calculan sobre el catálogo completo y se colocan en la lista ya filtrada.
        val vm = viewModelConCatalogo(
            playera("p-s", "S", categoria = "c-1"), playera("p-m", "M", categoria = "c-1"), playera("p-l", "L", categoria = "c-2"),
        )
        val familias = vm.familias.first { it.isNotEmpty() }

        val soloC2 = vm.products.value.filter { it.categoryId == "c-2" }
        val entradas = reglasDeCobro.agruparCatalogo(soloC2, familias)

        assertThat(entradas).hasSize(1)
        assertThat((entradas.single() as EntradaDeCatalogo.DeFamilia<Product>).familia.variantes.map { it.id })
            .containsExactly("p-s", "p-m", "p-l")
        vm.viewModelScope.cancel()
    }

    // ---- Búsqueda: la de Cobro rápido de hoy ----

    @Test fun `una busqueda con espacios y sin acento encuentra el producto con acento`() = runTest {
        // «  cafe » (espacios alrededor, sin acento) encuentra «Café»; «Taza» sólo lo tiene en la DESCRIPCIÓN y no entra.
        val vm = viewModelConCatalogo(
            producto("p-cafe", "Café", sku = "CF-1"),
            producto("p-taza", "Taza", sku = "TZ-1", descripcion = "para tu cafe"),
        )
        vm.updateSearchQuery("  cafe ")

        val resultados = vm.searchResults.first { it.isNotEmpty() }

        assertThat(resultados.map { (it as EntradaDeCatalogo.Suelto<Product>).producto.id }).containsExactly("p-cafe")
        vm.viewModelScope.cancel()
    }

    @Test fun `un acierto solo en la descripcion no coincide en Cobro rapido`() {
        val productos = listOf(producto("p-taza", "Taza", sku = "TZ-1", descripcion = "para tu cafe"))

        assertThat(reglasDeCobro.entradasParaMostrar(productos, emptyMap(), "cafe")).isEmpty()
        assertThat(reglasDeCobro.entradasParaMostrar(productos, emptyMap(), "  TAZA ")).hasSize(1)
        // El SKU también se busca sin acentos ni mayúsculas, como hoy.
        assertThat(reglasDeCobro.entradasParaMostrar(productos, emptyMap(), "tz-1")).hasSize(1)
    }

    @Test fun `texto en blanco enseña todo el catalogo agrupado`() {
        val catalogo = listOf(playera("p-s", "S"), playera("p-m", "M"), taza())
        val familias = reglasDeCobro.familiasDe(catalogo)

        val entradas = reglasDeCobro.entradasParaMostrar(catalogo, familias, "   ")

        // Hoy un texto en blanco lista todos los productos; con familias, la familia sale UNA vez (no cada talla suelta) y la taza suelta.
        assertThat(entradas).hasSize(2)
        assertThat((entradas[0] as EntradaDeCatalogo.DeFamilia<Product>).familia.groupId).isEqualTo(grupo)
        assertThat((entradas[1] as EntradaDeCatalogo.Suelto<Product>).producto.id).isEqualTo("p-taza")
        // Sin familias, cada producto sale suelto y en su orden (lo de hoy).
        val sinFamilias = reglasDeCobro.entradasParaMostrar(listOf(taza()), emptyMap(), "")
        assertThat(sinFamilias.map { (it as EntradaDeCatalogo.Suelto<Product>).producto.id }).containsExactly("p-taza")
    }

    @Test fun `la busqueda en blanco del VM enseña la familia una vez y no cada talla`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"), taza())

        val entradas = vm.searchResults.first { it.isNotEmpty() }

        // Lo que ve la cajera al abrir la búsqueda: la familia en UNA fila (no tres tallas sueltas) y la taza.
        assertThat(entradas).hasSize(2)
        assertThat((entradas[0] as EntradaDeCatalogo.DeFamilia<Product>).familia.groupId).isEqualTo(grupo)
        assertThat((entradas[1] as EntradaDeCatalogo.Suelto<Product>).producto.id).isEqualTo("p-taza")
        vm.viewModelScope.cancel()
    }

    @Test fun `buscar por nombre da la familia una vez y buscar por sku da la talla`() {
        val catalogo = listOf(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"), taza())
        val familias = reglasDeCobro.familiasDe(catalogo)

        val porNombre = reglasDeCobro.entradasParaMostrar(catalogo, familias, "PLAYERA básica")
        assertThat(porNombre).hasSize(1)
        assertThat((porNombre.single() as EntradaDeCatalogo.DeFamilia<Product>).familia.groupId).isEqualTo(grupo)
        // También sin acento (la búsqueda de Cobro rápido ignora acentos): «basica» encuentra «Playera básica».
        assertThat(reglasDeCobro.entradasParaMostrar(catalogo, familias, "basica")).hasSize(1)

        val porSku = reglasDeCobro.entradasParaMostrar(catalogo, familias, "sku-p-m")
        assertThat(porSku).hasSize(1)
        assertThat((porSku.single() as EntradaDeCatalogo.Suelto<Product>).producto.id).isEqualTo("p-m")
        // Un producto suelto se sigue encontrando como hoy.
        assertThat(reglasDeCobro.entradasParaMostrar(catalogo, familias, "taza")).hasSize(1)
    }

    // ---- Montaje ----

    private fun servidorResponde(vararg productos: Product) {
        coEvery { productRepository.getProducts(any(), any()) } returns Result.success(productos.toList())
    }

    private fun viewModelConCatalogo(vararg productos: Product): CheckoutViewModel {
        servidorResponde(*productos)
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
        )
    }

    private fun producto(id: String, nombre: String, sku: String = id, descripcion: String? = null) = Product(
        id = id, name = nombre, sku = sku, price = BigDecimal("50.00"), categoryId = "c-1", categoryName = "Bebidas",
        description = descripcion, emoji = "☕", imageUrl = null, available = true,
    )

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

    private fun taza() = producto("p-taza", "Taza", sku = "sku-taza")

    private fun grupoDeExtras() = ModifierGroup(
        id = "mg-1", name = "Extras", type = ModifierType.MULTIPLE_CHOICE,
        modifiers = listOf(ProductModifier("m-1", "Bordado", BigDecimal("30.00"), ModifierType.MULTIPLE_CHOICE)),
    )
}
