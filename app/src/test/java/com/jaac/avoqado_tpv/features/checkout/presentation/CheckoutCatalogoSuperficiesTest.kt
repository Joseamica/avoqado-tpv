package com.jaac.avoqado_tpv.features.checkout.presentation

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Cableado y guardas, NO render UI: Compose test sólo está en androidTest. El estado/retry real se prueba con Room en CheckoutLecturasTest. */
class CheckoutCatalogoSuperficiesTest {
    private val base = "src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/"
    private fun fuente(nombre: String) = File(base + nombre).readText().replace("\r\n", "\n")
    private val screen = fuente("CheckoutScreen.kt")
    private val buscar = fuente("components/SearchOverlayView.kt")
    private val mosaico = fuente("components/MosaicConfigView.kt")
    private val atajos = fuente("components/ShortcutsGridView.kt")
    private val grid = fuente("components/ProductGridView.kt")
    private fun contiene(texto: String, patron: String) = Regex(patron, RegexOption.DOT_MATCHES_ALL).containsMatchIn(texto)

    @Test fun `buscar observa error persistente y refresca el VM desde el overlay`() {
        assertTrue(buscar.contains("viewModel.errorCatalogo.collectAsState()"))
        assertTrue(buscar.contains("viewModel.isLoading.collectAsState()"))
        assertTrue(contiene(buscar, "AvisoDeCatalogo\\(.*?errorCatalogo = errorCatalogo,.*?isLoading = isLoading,.*?onRetry = viewModel::refreshProducts"))
    }

    @Test fun `buscar protege ausencia tras carga y error sin esconder lista buena`() {
        assertTrue(contiene(buscar, "isLoading && products.isEmpty\\(\\).*?AvoqadoFullScreenLoading\\(.*?errorCatalogo != null && results.isEmpty\\(\\).*?Unit.*?results.isEmpty\\(\\).*?EmptyState\\(.*?else.*?LazyColumn"))
    }

    @Test fun `configurar recibe estado y reintento y los entrega al selector modal`() {
        val caller = screen.substringAfter("InputTab.MOSAIC ->").substringBefore("if (ventaEnPreparacion)")
        assertTrue(contiene(caller, "MosaicConfigView\\(.*?errorCatalogo = errorCatalogo,.*?isLoading = isLoading,.*?onRetry = viewModel::refreshProducts"))
        assertTrue(contiene(mosaico, "ProductPickerSheet\\(.*?errorCatalogo = errorCatalogo,.*?isLoading = isLoading,.*?onRetry = onRetry"))
    }

    @Test fun `selector modal muestra aviso y prioriza carga y fallo sobre ausencia`() {
        val selector = mosaico.substringAfter("private fun ProductPickerSheet(")
        assertTrue(contiene(selector, "AvisoDeCatalogo\\(.*?errorCatalogo = errorCatalogo,.*?isLoading = isLoading,.*?onRetry = onRetry"))
        assertTrue(contiene(selector, "isLoading && products.isEmpty\\(\\).*?AvoqadoFullScreenLoading\\(.*?errorCatalogo != null && products.isEmpty\\(\\).*?Unit.*?products.isEmpty\\(\\).*?No hay productos disponibles.*?else.*?LazyColumn"))
    }

    @Test fun `atajos guardados mantienen error y reintento visibles en su propia pestana`() {
        val caller = screen.substringAfter("InputTab.SHORTCUTS ->").substringBefore("InputTab.PRODUCTS ->")
        assertTrue(contiene(caller, "ShortcutsGridView\\(.*?errorCatalogo = errorCatalogo,.*?isLoading = isLoading,.*?onRetry = viewModel::refreshProducts"))
        assertTrue(contiene(atajos, "ShortcutsScreen.MAIN ->.*?AvisoDeCatalogo\\(.*?errorCatalogo = errorCatalogo,.*?isLoading = isLoading,.*?onRetry = onRetry.*?ShortcutsMainGrid\\("))
    }

    @Test fun `grid comparte aviso y no anuncia categoria vacia tras error de lectura`() {
        assertTrue(grid.contains("AvisoDeCatalogo("))
        assertTrue(contiene(grid, "if \\(categoryProducts.isEmpty\\(\\)\\) \\{\\s*if \\(errorCatalogo == null\\)"))
    }

    @Test fun `el mismo aviso concreto conserva su accion real deshabilitada durante lectura`() {
        val archivo = File(base + "components/AvisoDeCatalogo.kt")
        assertTrue("Falta el aviso concreto compartido por las superficies", archivo.exists())
        val aviso = archivo.readText()
        assertTrue(contiene(aviso, "if \\(errorCatalogo != null\\).*?Text\\(errorCatalogo.*?TextButton\\(onClick = onRetry, enabled = !isLoading\\).*?Reintentar"))
    }
}
