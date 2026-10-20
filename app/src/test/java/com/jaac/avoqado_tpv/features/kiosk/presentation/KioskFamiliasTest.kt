package com.jaac.avoqado_tpv.features.kiosk.presentation

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskProduct
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskState
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Variantes de producto, bloque 4 (TPV), Kiosco: las tallas de una familia salen en UN botón y la variante elegida sigue el camino de
 * HOY del kiosco (con extras ⇒ `KioskModifierDialog` de ESA variante; si no ⇒ `addToCart`). El kiosco sólo muestra lo disponible, así
 * que las familias se arman sobre eso; la búsqueda sigue siendo la de hoy (nombre, `ignoreCase`, sin `trim` ni acentos, en blanco = nada).
 *
 * Usa el `KioskViewModel` real y las mismas piezas puras que la pantalla (`reglasDeKiosco`, `accionAlElegirEnKiosco`): lo que se
 * prueba aquí es lo que ve el cliente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KioskFamiliasTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val grupo = "g-playera"

    private lateinit var productRepository: ProductRepository
    private lateinit var secureStorage: SecureStorage

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
        productRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        coEvery { secureStorage.getKioskVenueId() } returns "venue-1"
        coEvery { productRepository.getCategories(any()) } returns Result.success(emptyList())
    }

    @After fun limpiar() {
        Dispatchers.resetMain()
    }

    // ---- Familias: sobre lo que el kiosco muestra (disponible) ----

    @Test fun `las familias salen de loadProducts y cambiar de categoria no las recalcula`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"), taza())

        val familias = vm.state.value.familias

        assertThat(familias.keys).containsExactly(grupo)
        assertThat(familias.getValue(grupo).variantes.map { it.id }).containsExactly("p-s", "p-m", "p-l")
        // La taza no tiene familia: sale suelta; las tres playeras salen en UN botón.
        val entradas = reglasDeKiosco.agruparCatalogo(vm.state.value.filteredProducts, familias)
        assertThat(entradas).hasSize(2)
        assertThat(entradas.filterIsInstance<EntradaDeCatalogo.DeFamilia<KioskProduct>>().single().familia.nombre).isEqualTo("Playera básica")
        // Cambiar de categoría no recalcula las familias: el mapa es el mismo objeto (sólo cambia cuando cambia el catálogo).
        vm.selectCategory("c-1")
        assertThat(vm.state.value.familias).isSameInstanceAs(familias)
        vm.viewModelScope.cancel()
    }

    @Test fun `una talla agotada ni entra a la familia ni se ve en el kiosco`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L", disponible = false))

        val familia = vm.state.value.familias.getValue(grupo)

        // El kiosco no muestra lo agotado: tampoco dentro de la hoja.
        assertThat(familia.variantes.map { it.id }).containsExactly("p-s", "p-m")
        assertThat(vm.state.value.allProducts.map { it.id }).containsExactly("p-s", "p-m")
        assertThat(reglasDeKiosco.agotada(familia)).isFalse()
        vm.viewModelScope.cancel()
    }

    @Test fun `si solo queda una talla disponible ya no hay familia y esa talla sale suelta`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M", disponible = false), playera("p-l", "L", disponible = false))

        assertThat(vm.state.value.familias).isEmpty()
        val entradas = reglasDeKiosco.agruparCatalogo(vm.state.value.filteredProducts, vm.state.value.familias)
        assertThat(entradas.map { (it as EntradaDeCatalogo.Suelto<KioskProduct>).producto.id }).containsExactly("p-s")
        vm.viewModelScope.cancel()
    }

    @Test fun `una categoria con una sola variante visible igual muestra el boton de la familia`() = runTest {
        // S y M viven en «c-1»; L vive en «c-2». Las familias se calculan sobre todo lo disponible y se colocan en la lista ya filtrada.
        val vm = viewModelConCatalogo(
            playera("p-s", "S", categoria = "c-1"), playera("p-m", "M", categoria = "c-1"), playera("p-l", "L", categoria = "c-2"),
        )
        vm.selectCategory("c-2")

        val entradas = reglasDeKiosco.agruparCatalogo(vm.state.value.filteredProducts, vm.state.value.familias)

        assertThat(entradas).hasSize(1)
        assertThat((entradas.single() as EntradaDeCatalogo.DeFamilia<KioskProduct>).familia.variantes.map { it.id })
            .containsExactly("p-s", "p-m", "p-l")
        vm.viewModelScope.cancel()
    }

    @Test fun `las llaves de la cuadricula no chocan aunque un producto se llame como el grupo`() {
        val suelto = EntradaDeCatalogo.Suelto(playera(grupo, "S").toKioskProduct())
        val familia = reglasDeKiosco.familiasDe(listOf(playera("p-s", "S").toKioskProduct(), playera("p-m", "M").toKioskProduct()))
            .getValue(grupo)

        assertThat(claveDeEntradaDeKiosco(suelto)).isNotEqualTo(claveDeEntradaDeKiosco(EntradaDeCatalogo.DeFamilia(familia)))
    }

    // ---- Elegir una variante ----

    @Test fun `la variante elegida con extras abre sus propios extras y sin extras se agrega`() {
        val sinExtras = playera("p-s", "S").toKioskProduct()
        val conExtras = playera("p-m", "M", extras = true).toKioskProduct()

        assertThat(accionAlElegirEnKiosco(conExtras)).isEqualTo(AccionAlElegir.AbrirExtras(conExtras))
        assertThat(accionAlElegirEnKiosco(sinExtras)).isEqualTo(AccionAlElegir.Agregar(sinExtras))
    }

    @Test fun `una variante que ya no esta o no esta disponible solo cierra la hoja`() {
        // El kiosco no tiene un aviso de «no disponible» y no se inventa: lo agotado no se muestra, y si se agotó con la hoja abierta, se cierra.
        assertThat(accionAlElegirEnKiosco(null)).isEqualTo(AccionAlElegir.Cerrar)
        assertThat(accionAlElegirEnKiosco(playera("p-s", "S", disponible = false).toKioskProduct())).isEqualTo(AccionAlElegir.Cerrar)
        // Agotada y con extras: igual se cierra (primero lo agotado).
        assertThat(accionAlElegirEnKiosco(playera("p-m", "M", disponible = false, extras = true).toKioskProduct()))
            .isEqualTo(AccionAlElegir.Cerrar)
    }

    @Test fun `la variante se resuelve contra el catalogo vigente`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"))
        assertThat(accionAlElegirEnKiosco(vm.productoVigente("p-m"))).isInstanceOf(AccionAlElegir.Agregar::class.java)

        // El catálogo se refresca con la hoja abierta: M gana extras ⇒ cuentan los extras NUEVOS, de esa variante.
        servidorResponde(playera("p-s", "S"), playera("p-m", "M", extras = true), playera("p-l", "L"))
        vm.loadProducts()
        val conExtras = accionAlElegirEnKiosco(vm.productoVigente("p-m"))
        assertThat(conExtras).isInstanceOf(AccionAlElegir.AbrirExtras::class.java)
        assertThat((conExtras as AccionAlElegir.AbrirExtras).variante.id).isEqualTo("p-m")
        assertThat(conExtras.variante.modifierGroups).isNotEmpty()

        // M se agota con la hoja abierta: el kiosco ya no la vende ⇒ la hoja sólo se cierra.
        servidorResponde(playera("p-s", "S"), playera("p-m", "M", disponible = false), playera("p-l", "L"))
        vm.loadProducts()
        assertThat(accionAlElegirEnKiosco(vm.productoVigente("p-m"))).isEqualTo(AccionAlElegir.Cerrar)

        // M desaparece del catálogo: igual.
        servidorResponde(playera("p-s", "S"), playera("p-l", "L"))
        vm.loadProducts()
        assertThat(vm.productoVigente("p-m")).isNull()
        assertThat(accionAlElegirEnKiosco(vm.productoVigente("p-m"))).isEqualTo(AccionAlElegir.Cerrar)
        vm.viewModelScope.cancel()
    }

    @Test fun `dos variantes distintas de la misma familia quedan como dos renglones del carrito`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"))

        // El camino de la pantalla: resolver por id en el catálogo vigente, decidir (accionAlElegirEnKiosco) y sólo entonces agregar.
        vm.elegirEnLaHoja("p-s")
        vm.elegirEnLaHoja("p-m")

        assertThat(vm.cartItems.value.map { it.productId }).containsExactly("p-s", "p-m").inOrder()
        assertThat(vm.cartItems.value.map { it.quantity }).containsExactly(1, 1)
        // La misma talla otra vez suma a SU renglón (agrupa por productId), sin tocar el de la otra.
        vm.elegirEnLaHoja("p-s")
        assertThat(vm.cartItems.value.map { it.productId to it.quantity }).containsExactly("p-s" to 2, "p-m" to 1).inOrder()
        vm.viewModelScope.cancel()
    }

    @Test fun `una variante con extras no entra por addToCart sino por sus extras y queda en su propio renglon`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M", extras = true))
        val m = vm.productoVigente("p-m")!!

        // addToCart rechaza lo que tiene extras: por eso la pantalla abre KioskModifierDialog (AbrirExtras) y no llama a addToCart.
        vm.addToCart(m)
        assertThat(vm.cartItems.value).isEmpty()

        // El camino de la pantalla: la talla sin extras se agrega; la que tiene extras abre los suyos y entra con ellos.
        vm.elegirEnLaHoja("p-s")
        val accion = accionAlElegirEnKiosco(vm.productoVigente("p-m"))
        assertThat(accion).isInstanceOf(AccionAlElegir.AbrirExtras::class.java)
        val variante = (accion as AccionAlElegir.AbrirExtras).variante
        vm.addToCartWithModifiers(variante, variante.modifierGroups.first().modifiers, BigDecimal("229.00"))
        assertThat(vm.cartItems.value.map { it.productId }).containsExactly("p-s", "p-m").inOrder()
        assertThat(vm.cartItems.value.last().unitPrice).isEqualTo(BigDecimal("229.00"))
        vm.viewModelScope.cancel()
    }

    // ---- Búsqueda: la del kiosco de hoy (nombre, ignoreCase, sin trim, sin acentos; en blanco = nada) ----

    @Test fun `un texto en blanco no busca nada`() {
        val catalogo = listOf(playera("p-s", "S"), playera("p-m", "M"), taza()).map { it.toKioskProduct() }
        val familias = reglasDeKiosco.familiasDe(catalogo)

        // Hoy un texto en blanco muestra «Escribe para buscar», no el catálogo: ni suelto ni agrupado.
        assertThat(entradasDeBusquedaEnKiosco(catalogo, familias, "")).isEmpty()
        assertThat(entradasDeBusquedaEnKiosco(catalogo, familias, "   ")).isEmpty()
    }

    @Test fun `buscar por nombre da la familia una vez y un producto suelto como hoy`() {
        val catalogo = listOf(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"), taza()).map { it.toKioskProduct() }
        val familias = reglasDeKiosco.familiasDe(catalogo)

        val porNombre = entradasDeBusquedaEnKiosco(catalogo, familias, "PLAYERA básica")
        assertThat(porNombre).hasSize(1)
        assertThat((porNombre.single() as EntradaDeCatalogo.DeFamilia<KioskProduct>).familia.groupId).isEqualTo(grupo)

        val suelto = entradasDeBusquedaEnKiosco(catalogo, familias, "taza")
        assertThat(suelto.map { (it as EntradaDeCatalogo.Suelto<KioskProduct>).producto.id }).containsExactly("p-taza")
    }

    @Test fun `la busqueda no mira el sku ni la descripcion como hoy`() {
        // KioskProduct no tiene SKU ni descripción: la búsqueda de hoy sólo mira el nombre.
        val catalogo = listOf(playera("p-s", "S"), playera("p-m", "M")).map { it.toKioskProduct() }
        val familias = reglasDeKiosco.familiasDe(catalogo)

        assertThat(entradasDeBusquedaEnKiosco(catalogo, familias, "sku-p-s")).isEmpty()
        assertThat(entradasDeBusquedaEnKiosco(catalogo, familias, "algodón")).isEmpty()
    }

    @Test fun `la busqueda de productos sueltos da lo mismo que el contains ignoreCase de hoy`() {
        // «Istanbul» con «İstan» es el caso donde `lowercase()` difiere de `ignoreCase` (la «İ» con punto): la regla copia a `ignoreCase`.
        val nombres = listOf(
            "Café", "CAFÉ americano", "Cafe latte", "Taza grande", "Piña colada", "PIÑA", "Ñoño", "Té verde", "Pan de elote", "Istanbul",
        )
        val catalogo = nombres.mapIndexed { i, n -> producto("p-$i", n).toKioskProduct() }
        val consultas = listOf(
            "café", "CAFÉ", "cafe", "Cafe", "taza", "taza ", " taza", " ", "piña", "PIÑA", "pina", "ñ", "Ñ", "té", "te", "TÉ", "pan de",
            "pan  de", "x", "ELOTE", "İstan", "ISTAN",
        )

        for (consulta in consultas) {
            // Lo de hoy (KioskSearchDialog): vacío si está en blanco; si no, `name.contains(texto, ignoreCase = true)`.
            val hoy = if (consulta.isBlank()) emptyList() else catalogo.filter { it.name.contains(consulta, ignoreCase = true) }.map { it.id }
            val ahora = entradasDeBusquedaEnKiosco(catalogo, emptyMap(), consulta)
                .map { (it as EntradaDeCatalogo.Suelto<KioskProduct>).producto.id }
            assertThat(ahora).containsExactlyElementsIn(hoy).inOrder()
        }
    }

    // ---- La hoja y el pago ----

    @Test fun `la hoja no se pinta mientras el sistema de pagos no esta listo`() {
        assertThat(hojaDeVariantesPermitidaEnKiosco(KioskState(isLoading = false, isBlumonInitializing = false, isBlumonReady = true))).isTrue()
        // Arrancando, reintentando o con error: el aviso de «Preparando sistema de pagos…» manda y la hoja no sigue abierta debajo.
        assertThat(hojaDeVariantesPermitidaEnKiosco(KioskState(isBlumonInitializing = true, isBlumonReady = false))).isFalse()
        assertThat(hojaDeVariantesPermitidaEnKiosco(KioskState(isBlumonInitializing = false, isBlumonReady = false))).isFalse()
        assertThat(hojaDeVariantesPermitidaEnKiosco(KioskState(isBlumonInitializing = false, isBlumonReady = true, blumonInitError = "x")))
            .isFalse()
    }

    // ---- Montaje ----

    /** Lo que hace la pantalla al elegir una talla sin extras: la resuelve por id en el catálogo vigente, decide y agrega. */
    private fun KioskViewModel.elegirEnLaHoja(id: String) {
        val accion = accionAlElegirEnKiosco(productoVigente(id))
        assertThat(accion).isInstanceOf(AccionAlElegir.Agregar::class.java)
        addToCart((accion as AccionAlElegir.Agregar).variante)
    }

    private fun servidorResponde(vararg productos: Product) {
        coEvery { productRepository.getProducts(any(), any()) } returns Result.success(productos.toList())
    }

    private fun viewModelConCatalogo(vararg productos: Product): KioskViewModel {
        servidorResponde(*productos)
        val getMerchants = mockk<com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase>()
        // El arranque del SDK de pagos corre aparte (hilo de IO) y no interviene aquí: si truena, el VM lo guarda como error de SDK.
        every { getMerchants() } throws IllegalStateException("sin SDK en la prueba")
        val vm = KioskViewModel(
            productRepository = productRepository,
            orderRepository = mockk(relaxed = true),
            customerRepository = mockk(relaxed = true),
            secureStorage = secureStorage,
            initializationManager = mockk(relaxed = true),
            getMerchantsUseCase = getMerchants,
            multiMerchantSDKManager = mockk(relaxed = true),
            tpvSettingsRepository = mockk(relaxed = true),
        )
        vm.loadProducts()
        return vm
    }

    private fun producto(id: String, nombre: String) = Product(
        id = id, name = nombre, sku = id, price = BigDecimal("50.00"), categoryId = "c-1", categoryName = "Bebidas",
        description = null, emoji = "☕", imageUrl = null, available = true,
    )

    private fun taza() = producto("p-taza", "Taza")

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

    private fun grupoDeExtras() = ModifierGroup(
        id = "mg-1", name = "Extras", type = ModifierType.MULTIPLE_CHOICE,
        modifiers = listOf(ProductModifier("m-1", "Bordado", BigDecimal("30.00"), ModifierType.MULTIPLE_CHOICE)),
    )
}
