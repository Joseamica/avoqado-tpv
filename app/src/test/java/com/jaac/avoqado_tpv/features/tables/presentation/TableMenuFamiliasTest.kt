package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.core.presentation.components.variantes.textoDePrecio
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuModifier
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuModifierGroup
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuProduct
import com.jaac.avoqado_tpv.features.tables.domain.model.precioDeMesas
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
import java.io.File
import java.io.IOException
import java.math.BigDecimal

/**
 * Variantes de producto, bloque 4 (TPV), Mesas: las tallas de una familia salen en UN botón y la variante elegida sigue el camino de
 * HOY de Mesas (con extras ⇒ `ModifierPickerSheet` de ESA variante; si no ⇒ `addSimple` ⇒ `PendingRoundCart`, que es lo que lee el
 * envío de la ronda). Mesas no conoce existencias (nada sale «Agotado») y NO importa nada de `features/ordering`.
 *
 * Usa el `TableMenuViewModel` y el `PendingRoundCart` reales con las mismas piezas puras que la pantalla (`reglasDeMesas`,
 * `accionAlElegirEnMesas`): lo que se prueba aquí es lo que ve el mesero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableMenuFamiliasTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val venueId = "venue-1"
    private val grupo = "g-playera"

    private val repository = mockk<TablesRepository>()
    private val deviceInfoManager = mockk<DeviceInfoManager>()
    private lateinit var pendingCart: PendingRoundCart

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
        pendingCart = PendingRoundCart()
        every { deviceInfoManager.getVenueId() } returns venueId
        coEvery { repository.getCategories(venueId) } returns Result.success(emptyList())
    }

    @After fun limpiar() {
        Dispatchers.resetMain()
    }

    // ---- Familias: sobre el catálogo completo de Mesas ----

    @Test fun `las familias salen de load y cambiar de categoria no las recalcula`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"), taza())

        val familias = vm.uiState.value.familias

        assertThat(familias.keys).containsExactly(grupo)
        assertThat(familias.getValue(grupo).variantes.map { it.id }).containsExactly("p-s", "p-m", "p-l")
        // La taza no tiene familia: sale suelta; las tres playeras salen en UN botón.
        val entradas = reglasDeMesas.agruparCatalogo(vm.uiState.value.productsForSelectedCategory(), familias)
        assertThat(entradas).hasSize(2)
        assertThat(entradas.filterIsInstance<EntradaDeCatalogo.DeFamilia<MenuProduct>>().single().familia.nombre).isEqualTo("Playera básica")
        // Cambiar de categoría no recalcula las familias: el mapa es el mismo objeto (sólo cambia cuando llega un catálogo nuevo).
        vm.selectCategory("c-1")
        assertThat(vm.uiState.value.familias).isSameInstanceAs(familias)
    }

    @Test fun `una categoria con una sola variante visible igual muestra el boton de la familia`() = runTest {
        // S y M viven en «c-1»; L vive en «c-2». Las familias se calculan sobre TODO el catálogo y se colocan en la lista ya filtrada.
        val vm = viewModelConCatalogo(
            playera("p-s", "S", categoria = "c-1"), playera("p-m", "M", categoria = "c-1"), playera("p-l", "L", categoria = "c-2"),
        )
        vm.selectCategory("c-2")

        val entradas = reglasDeMesas.agruparCatalogo(vm.uiState.value.productsForSelectedCategory(), vm.uiState.value.familias)

        assertThat(entradas).hasSize(1)
        // El botón enseña la familia COMPLETA, aunque sólo una de sus tallas viva en la categoría elegida.
        assertThat((entradas.single() as EntradaDeCatalogo.DeFamilia<MenuProduct>).familia.variantes.map { it.id })
            .containsExactly("p-s", "p-m", "p-l")
    }

    @Test fun `la familia sale una sola vez, en el lugar de su primera variante`() = runTest {
        val vm = viewModelConCatalogo(taza("t-1"), playera("p-s", "S"), taza("t-2"), playera("p-m", "M"), playera("p-l", "L"))

        val entradas = reglasDeMesas.agruparCatalogo(vm.uiState.value.products, vm.uiState.value.familias)

        assertThat(entradas.map { claveDeEntradaDeMesas(it) }).containsExactly("prod_t-1", "familia_$grupo", "prod_t-2").inOrder()
    }

    @Test fun `una variante sola no es familia y sale suelta`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), taza())

        assertThat(vm.uiState.value.familias).isEmpty()
        val entradas = reglasDeMesas.agruparCatalogo(vm.uiState.value.products, vm.uiState.value.familias)
        assertThat(entradas.map { (it as EntradaDeCatalogo.Suelto<MenuProduct>).producto.id }).containsExactly("p-s", "t-1").inOrder()
    }

    @Test fun `Mesas no conoce existencias, asi que una familia nunca sale agotada`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"))

        val familia = vm.uiState.value.familias.getValue(grupo)

        assertThat(reglasDeMesas.agotada(familia)).isFalse()
        assertThat(familia.variantes.none(reglasDeMesas.agotado)).isTrue()
    }

    @Test fun `las llaves de la cuadricula no chocan aunque un producto se llame como el grupo`() {
        val suelto = EntradaDeCatalogo.Suelto(playera(grupo, "S"))
        val familia = reglasDeMesas.familiasDe(listOf(playera("p-s", "S"), playera("p-m", "M"))).getValue(grupo)

        assertThat(claveDeEntradaDeMesas(suelto)).isNotEqualTo(claveDeEntradaDeMesas(EntradaDeCatalogo.DeFamilia(familia)))
    }

    // ---- El botón de la familia: precio y cantidad en la ronda ----

    @Test fun `el boton dice desde con el precio mas bajo si cuestan distinto, y el precio solo si cuestan igual`() {
        val distintas = reglasDeMesas.familiasDe(listOf(playera("p-s", "S", precio = "199.00"), playera("p-m", "M", precio = "249.00")))
            .getValue(grupo)
        val iguales = reglasDeMesas.familiasDe(listOf(playera("p-s", "S", precio = "199.00"), playera("p-m", "M", precio = "199")))
            .getValue(grupo)

        assertThat(reglasDeMesas.textoDePrecio(distintas, ::precioDeMesas)).isEqualTo("desde $199.00")
        assertThat(reglasDeMesas.textoDePrecio(iguales, ::precioDeMesas)).isEqualTo("$199.00")
        // El mismo formato que el precio de un producto suelto de Mesas (la hoja y la cuadrícula escriben lo mismo).
        assertThat(precioDeMesas(BigDecimal("1299"))).isEqualTo(taza(precio = "1299").priceDisplay)
    }

    @Test fun `el boton cuenta lo que ya hay en la ronda de todas sus tallas`() {
        val familia = reglasDeMesas.familiasDe(listOf(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"))).getValue(grupo)

        assertThat(cantidadEnLaRondaDeFamilia(familia, mapOf("p-s" to 2, "p-l" to 1, "t-1" to 5))).isEqualTo(3)
        assertThat(cantidadEnLaRondaDeFamilia(familia, emptyMap())).isEqualTo(0)
    }

    // ---- Elegir una variante ----

    @Test fun `la variante elegida con extras abre sus propios extras y sin extras se agrega`() {
        val sinExtras = playera("p-s", "S")
        val conExtras = playera("p-m", "M", extras = true)

        assertThat(accionAlElegirEnMesas(conExtras)).isEqualTo(AccionAlElegir.AbrirExtras(conExtras))
        assertThat(accionAlElegirEnMesas(sinExtras)).isEqualTo(AccionAlElegir.Agregar(sinExtras))
    }

    @Test fun `una variante que ya no esta solo cierra la hoja`() {
        assertThat(accionAlElegirEnMesas(null)).isEqualTo(AccionAlElegir.Cerrar)
    }

    @Test fun `la variante elegida llega a la ronda con SU productId y SU precio, y dos tallas son dos renglones`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S", precio = "199.00"), playera("p-m", "M", precio = "249.00"))

        // El camino de la pantalla: resolver por id en el catálogo vigente, decidir (accionAlElegirEnMesas) y sólo entonces agregar.
        vm.elegirEnLaHoja("p-m")
        vm.elegirEnLaHoja("p-s")

        assertThat(pendingCart.lines.value.map { it.productId }).containsExactly("p-m", "p-s").inOrder()
        assertThat(pendingCart.lines.value.map { it.unitPrice }).containsExactly(BigDecimal("249.00"), BigDecimal("199.00")).inOrder()
        assertThat(pendingCart.lines.value.map { it.name }).containsExactly("Playera M", "Playera S").inOrder()
        // Lo que se manda al servidor es lo mismo: el id de cada variante.
        assertThat(pendingCart.toAddItemsRequests().map { it.productId }).containsExactly("p-m", "p-s").inOrder()
        // La misma talla otra vez suma a SU renglón (agrupa por productId), sin tocar el de la otra.
        vm.elegirEnLaHoja("p-m")
        assertThat(pendingCart.lines.value.map { it.productId to it.quantity }).containsExactly("p-m" to 2, "p-s" to 1).inOrder()
    }

    @Test fun `una variante con extras entra por sus extras con SU productId y SU precio, en su propio renglon`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S", precio = "199.00"), playera("p-m", "M", precio = "249.00", extras = true))

        vm.elegirEnLaHoja("p-s")
        val accion = accionAlElegirEnMesas(vm.productoVigente("p-m"))
        assertThat(accion).isInstanceOf(AccionAlElegir.AbrirExtras::class.java)
        // Se abre el selector de extras de ESA variante (la talla M), no el de la familia ni el de la primera talla.
        val variante = (accion as AccionAlElegir.AbrirExtras).variante
        assertThat(variante.id).isEqualTo("p-m")
        assertThat(variante.modifierGroups.single().modifiers.single().name).isEqualTo("Extra queso")
        vm.addWithModifiers(variante, 2, listOf(PendingRoundCart.Modifier("m-1", "Extra queso", BigDecimal("15.00"))))

        assertThat(pendingCart.lines.value.map { it.productId }).containsExactly("p-s", "p-m").inOrder()
        val conExtras = pendingCart.lines.value.last()
        assertThat(conExtras.unitPrice).isEqualTo(BigDecimal("249.00"))
        assertThat(conExtras.effectiveUnitPrice).isEqualTo(BigDecimal("264.00"))
        assertThat(conExtras.quantity).isEqualTo(2)
        assertThat(pendingCart.toAddItemsRequests().last().modifierIds).containsExactly("m-1")
    }

    @Test fun `la variante se resuelve contra el catalogo vigente`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"), playera("p-l", "L"))
        assertThat(accionAlElegirEnMesas(vm.productoVigente("p-m"))).isInstanceOf(AccionAlElegir.Agregar::class.java)

        // El catálogo se refresca con la hoja abierta: M gana extras ⇒ cuentan los extras NUEVOS, de esa variante.
        servidorResponde(playera("p-s", "S"), playera("p-m", "M", extras = true), playera("p-l", "L"))
        vm.load()
        val conExtras = accionAlElegirEnMesas(vm.productoVigente("p-m"))
        assertThat(conExtras).isInstanceOf(AccionAlElegir.AbrirExtras::class.java)
        assertThat((conExtras as AccionAlElegir.AbrirExtras).variante.modifierGroups).isNotEmpty()

        // M cambia de precio: se agrega con el precio de AHORA, no con el de la copia con la que se abrió la hoja.
        servidorResponde(playera("p-s", "S"), playera("p-m", "M", precio = "259.00"), playera("p-l", "L"))
        vm.load()
        vm.elegirEnLaHoja("p-m")
        assertThat(pendingCart.lines.value.single().unitPrice).isEqualTo(BigDecimal("259.00"))

        // M desaparece del catálogo: la hoja sólo se cierra y no se agrega nada.
        servidorResponde(playera("p-s", "S"), playera("p-l", "L"))
        vm.load()
        assertThat(vm.productoVigente("p-m")).isNull()
        assertThat(accionAlElegirEnMesas(vm.productoVigente("p-m"))).isEqualTo(AccionAlElegir.Cerrar)
        assertThat(vm.uiState.value.familias.getValue(grupo).variantes.map { it.id }).containsExactly("p-s", "p-l")
    }

    // ---- Sin red ----

    @Test fun `con el catalogo ya cargado y sin red las familias se siguen mostrando y se pueden agregar`() = runTest {
        val vm = viewModelConCatalogo(playera("p-s", "S"), playera("p-m", "M"))
        val familias = vm.uiState.value.familias

        coEvery { repository.getProducts(venueId) } returns Result.failure(IOException("sin red"))
        vm.load()

        assertThat(vm.uiState.value.errorMessage).isNull()
        assertThat(vm.uiState.value.familias).isSameInstanceAs(familias)
        vm.elegirEnLaHoja("p-m")
        assertThat(pendingCart.lines.value.single().productId).isEqualTo("p-m")
    }

    @Test fun `sin red en frio no hay catalogo ni familias`() = runTest {
        coEvery { repository.getProducts(venueId) } returns Result.failure(IOException("sin red"))

        val vm = viewModel()

        assertThat(vm.uiState.value.products).isEmpty()
        assertThat(vm.uiState.value.familias).isEmpty()
        assertThat(vm.productoVigente("p-m")).isNull()
    }

    // ---- La regla dura: Mesas no importa nada de ordering ----

    @Test fun `ningun fuente de features-tables importa features-ordering`() {
        val raiz = File("src/main/java/com/jaac/avoqado_tpv/features/tables")
        assertThat(raiz.isDirectory).isTrue()
        val fuentes = raiz.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // Que la prueba no pase en vacío: los fuentes de esta tarea están entre los leídos.
        assertThat(fuentes.map { it.name }).containsAtLeast("TableMenuScreen.kt", "TableMenuViewModel.kt", "ReglasDeMesas.kt")

        val ofensores = fuentes.flatMap { archivo ->
            archivo.readLines()
                .map { it.trim() }
                // Los comentarios pueden nombrar la regla («features/ordering/»); lo que cuenta es el código: un import o un nombre completo.
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { it.contains("features.ordering") }
                .map { "${archivo.name}: $it" }
        }

        assertThat(ofensores).isEmpty()
    }

    // ---- Ayudas ----

    private fun viewModel() = TableMenuViewModel(
        repository = repository,
        tableSession = TableSession(),
        pendingCart = pendingCart,
        deviceInfoManager = deviceInfoManager,
    )

    private fun servidorResponde(vararg productos: MenuProduct) {
        coEvery { repository.getProducts(venueId) } returns Result.success(productos.toList())
    }

    private fun viewModelConCatalogo(vararg productos: MenuProduct): TableMenuViewModel {
        servidorResponde(*productos)
        return viewModel()
    }

    /** Lo que hace la pantalla al elegir en la hoja: resolver por id en el catálogo vigente, decidir y actuar. */
    private fun TableMenuViewModel.elegirEnLaHoja(id: String) {
        when (val accion = accionAlElegirEnMesas(productoVigente(id))) {
            is AccionAlElegir.Agregar -> addSimple(accion.variante)
            is AccionAlElegir.AbrirExtras, is AccionAlElegir.NoDisponible, AccionAlElegir.Cerrar -> Unit
        }
    }

    private fun playera(id: String, talla: String, precio: String = "199.00", categoria: String? = null, extras: Boolean = false) = MenuProduct(
        id = id,
        name = "Playera $talla",
        price = BigDecimal(precio),
        categoryId = categoria,
        modifierGroups = if (extras) {
            listOf(MenuModifierGroup(id = "g-1", name = "Extras", modifiers = listOf(MenuModifier(id = "m-1", name = "Extra queso", price = BigDecimal("15.00")))))
        } else {
            emptyList()
        },
        familia = DatosDeFamilia(
            groupId = grupo,
            groupName = "Playera básica",
            groupImageUrl = null,
            opciones = listOf(OpcionDeVariante("Talla", talla, 0, listOf("S", "M", "L").indexOf(talla))),
        ),
    )

    private fun taza(id: String = "t-1", precio: String = "89.00") = MenuProduct(id = id, name = "Taza", price = BigDecimal(precio))
}
