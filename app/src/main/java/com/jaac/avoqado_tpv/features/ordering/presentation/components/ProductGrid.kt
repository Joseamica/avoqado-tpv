package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.core.presentation.components.variantes.TileDeFamilia
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductDisplayMode
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.formatoDePrecio
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.reglasDeOrdenes
import java.math.BigDecimal

private const val PAGE_SIZE = 15
private const val PAGINATION_THRESHOLD = 3

/**
 * Product catalog view for menu display.
 *
 * Supports configurable view modes:
 * - 3-column grid (dense)
 * - 2-column grid (readable)
 * - Detailed list (name, price, SKU, stock)
 *
 * Uses incremental pagination for all modes to keep memory usage low
 * on constrained terminal devices.
 *
 * Products can be filtered by category and by [searchQuery].
 *
 * Las tallas/colores de una misma familia salen en UN botón ([TileDeFamilia], o una fila en la lista): tocarlo llama a
 * [onFamiliaClick] y nunca agrega nada por sí solo. Qué se pinta lo decide `reglasDeOrdenes.entradasParaMostrar` (sin texto: el
 * catálogo agrupado; con texto: un nombre o descripción da la familia una vez, un SKU da la talla).
 *
 * @param products List of all products (la categoría, lo disponible y la búsqueda se aplican aquí)
 * @param selectedCategory Filter by this category (null = show all)
 * @param displayMode Visual mode for the product catalog
 * @param onProductClick Callback when product is tapped
 * @param modifier Modifier for customization
 * @param scrollResetKey Key that resets pagination + scroll
 * @param searchQuery Texto de la búsqueda de Órdenes (vacío = sin búsqueda)
 * @param familias Familias válidas del catálogo completo, por `groupId` (`MenuViewModel.familias`)
 * @param onFamiliaClick Callback cuando se toca el botón de una familia
 */
@Composable
fun ProductGrid(
    products: List<Product>,
    selectedCategory: ProductCategory?,
    displayMode: ProductDisplayMode = ProductDisplayMode.GRID_3,
    onProductClick: (Product) -> Unit,
    headerContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    scrollResetKey: Any? = selectedCategory?.id,
    searchQuery: String = "",
    familias: Map<String, Familia<Product>> = emptyMap(),
    onFamiliaClick: (Familia<Product>) -> Unit = {}
) {
    // ⚡ PERFORMANCE: Use remember to avoid filtering on every recomposition
    // El filtro de categoría/disponible sólo se rehace si cambia el catálogo o la categoría, no con cada letra de la búsqueda.
    val visibles = remember(products, selectedCategory) { visiblesEnCuadricula(products, selectedCategory) }
    val entradas = remember(visibles, familias, searchQuery) {
        reglasDeOrdenes.entradasParaMostrar(visibles, familias, searchQuery)
    }

    val resetKey = "${scrollResetKey ?: selectedCategory?.id ?: "all"}|${displayMode.preferenceValue}"

    // 🔢 PAGINATION: reset on filter/search/layout changes
    var displayedItemCount by remember(resetKey) { mutableStateOf(PAGE_SIZE) }

    // 🔢 PAGINATION: Only show first N products
    val entradasVisibles = remember(entradas, displayedItemCount) {
        entradas.take(displayedItemCount)
    }

    if (displayMode == ProductDisplayMode.LIST) {
        val listState = rememberLazyListState()

        LaunchedEffect(resetKey) {
            listState.scrollToItem(0)
        }

        LaunchedEffect(listState, entradas) {
            snapshotFlow { listState.layoutInfo }
                .collect { layoutInfo ->
                    val totalItems = layoutInfo.totalItemsCount
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (totalItems > 0 &&
                        lastVisibleItem >= totalItems - PAGINATION_THRESHOLD &&
                        entradasVisibles.size < entradas.size) {
                        displayedItemCount = minOf(displayedItemCount + PAGE_SIZE, entradas.size)
                    }
                }
        }

        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 80.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (headerContent != null) {
                item(
                    key = "catalog_header",
                    contentType = "catalog_header"
                ) {
                    headerContent()
                }
            }

            items(
                items = entradasVisibles,
                key = ::claveDeEntrada,
                contentType = ::tipoDeEntrada
            ) { entrada ->
                when (entrada) {
                    is EntradaDeCatalogo.Suelto -> ProductListItem(
                        product = entrada.producto,
                        onClick = { onProductClick(entrada.producto) }
                    )
                    is EntradaDeCatalogo.DeFamilia -> FamiliaListItem(
                        familia = entrada.familia,
                        reglas = reglasDeOrdenes,
                        precioTexto = formatoDePrecio,
                        onClick = { onFamiliaClick(entrada.familia) }
                    )
                }
            }

            if (entradasVisibles.size < entradas.size) {
                item {
                    LoadingFooter()
                }
            }
        }
        return
    }

    val columns = if (displayMode == ProductDisplayMode.GRID_2) 2 else 3
    val gridState = rememberLazyGridState()

    // 📜 SCROLL RESET: Scroll to top only on filter/search/layout changes
    LaunchedEffect(resetKey) {
        gridState.scrollToItem(0)
    }

    // 🔄 LAZY LOADING: Load more when scrolling near end
    LaunchedEffect(gridState, entradas) {
        snapshotFlow { gridState.layoutInfo }
            .collect { layoutInfo ->
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0

                if (totalItems > 0 &&
                    lastVisibleItem >= totalItems - PAGINATION_THRESHOLD &&
                    entradasVisibles.size < entradas.size) {
                    displayedItemCount = minOf(displayedItemCount + PAGE_SIZE, entradas.size)
                }
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 8.dp,
            bottom = 80.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (headerContent != null) {
            item(
                span = { GridItemSpan(columns) },
                key = "catalog_header",
                contentType = "catalog_header"
            ) {
                headerContent()
            }
        }

        gridItems(
            items = entradasVisibles,
            key = ::claveDeEntrada,
            contentType = ::tipoDeEntrada
        ) { entrada ->
            when (entrada) {
                is EntradaDeCatalogo.Suelto -> ProductCard(
                    product = entrada.producto,
                    onClick = { onProductClick(entrada.producto) }
                )
                is EntradaDeCatalogo.DeFamilia -> TileDeFamilia(
                    familia = entrada.familia,
                    reglas = reglasDeOrdenes,
                    precioTexto = formatoDePrecio,
                    onClick = { onFamiliaClick(entrada.familia) },
                    // Misma altura que ProductCard (1.45, el valor por defecto). En 2 columnas el contenido completo cabe; en 3 (~107 dp
                    // de ancho, 74 dp de alto) no, y se usa la versión compacta. Ver `TileDeFamilia` y el cálculo en el informe de la tarea 6.
                    compacto = columns == 3
                )
            }
        }

        if (entradasVisibles.size < entradas.size) {
            item(span = { GridItemSpan(columns) }) {
                LoadingFooter()
            }
        }
    }
}

/**
 * Lo que la cuadrícula puede mostrar de [products]: la categoría elegida (null = todas) y sólo lo disponible (Órdenes no pinta un
 * producto agotado ni desactivado). Es el filtro que antes vivía dentro de [ProductGrid], sacado para probarlo; las familias y la
 * búsqueda se aplican DESPUÉS, sobre este resultado.
 */
fun visiblesEnCuadricula(products: List<Product>, selectedCategory: ProductCategory?): List<Product> {
    val deLaCategoria = if (selectedCategory == null) {
        products
    } else {
        products.filter { it.categoryId == selectedCategory.id }
    }
    return deLaCategoria.filter { it.available }
}

// La llave de una entrada: el id del producto, o «familia:<groupId>» (el prefijo evita chocar con un id de producto).
private fun claveDeEntrada(entrada: EntradaDeCatalogo<Product>): String = when (entrada) {
    is EntradaDeCatalogo.Suelto -> entrada.producto.id
    is EntradaDeCatalogo.DeFamilia -> "familia:${entrada.familia.groupId}"
}

private fun tipoDeEntrada(entrada: EntradaDeCatalogo<Product>): String = when (entrada) {
    is EntradaDeCatalogo.Suelto -> entrada.producto.categoryId
    is EntradaDeCatalogo.DeFamilia -> "familia"
}

@Composable
private fun LoadingFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(name = "All Products", showBackground = true, heightDp = 800, widthDp = 400)
@Composable
private fun ProductGridAllPreview() {
    AvoqadoTheme {
        ProductGrid(
            products = MockProducts.allProducts,
            selectedCategory = null,  // Show all
            displayMode = ProductDisplayMode.GRID_3,
            onProductClick = {}
        )
    }
}

@Preview(name = "Bebidas Only", showBackground = true, heightDp = 600, widthDp = 400)
@Composable
private fun ProductGridBebidasPreview() {
    AvoqadoTheme {
        ProductGrid(
            products = MockProducts.allProducts,
            selectedCategory = MockProducts.categories[0],  // Bebidas
            displayMode = ProductDisplayMode.GRID_2,
            onProductClick = {}
        )
    }
}

@Preview(name = "Comidas Only", showBackground = true, heightDp = 600, widthDp = 400)
@Composable
private fun ProductGridComidasPreview() {
    AvoqadoTheme {
        ProductGrid(
            products = MockProducts.allProducts,
            selectedCategory = MockProducts.categories[1],  // Comidas
            displayMode = ProductDisplayMode.LIST,
            onProductClick = {}
        )
    }
}

@Preview(name = "Postres Only", showBackground = true, heightDp = 600, widthDp = 400)
@Composable
private fun ProductGridPostresPreview() {
    AvoqadoTheme {
        ProductGrid(
            products = MockProducts.allProducts,
            selectedCategory = MockProducts.categories[2],  // Postres
            displayMode = ProductDisplayMode.GRID_3,
            onProductClick = {}
        )
    }
}

@Preview(name = "Empty Category", showBackground = true, heightDp = 400, widthDp = 400)
@Composable
private fun ProductGridEmptyPreview() {
    AvoqadoTheme {
        ProductGrid(
            products = emptyList(),
            selectedCategory = null,
            displayMode = ProductDisplayMode.GRID_3,
            onProductClick = {}
        )
    }
}

// Celdas de 3 columnas: (360 − 24 de margen − 16 de separación) / 3 = 106.7 dp de ancho y 106.7 / 1.45 = 73.6 dp de alto (el de ProductCard).
// El botón de familia compacto necesita 6 + 20 (nombre en 1 renglón) + 18 (insignia) + 2 + 16 (precio, labelSmall) + 6 = 68 dp ≤ 73.6, y de
// ancho 90.7 dp de contenido (106.7 − 4 de acento − 2×6): «3 variantes» 61.2 + 2×4 = 69.2 dp, «desde $199.00» 78.0 dp, «desde $9,999.00»
// 90.4 dp (anchos medidos con las fuentes de la app, ver TileDeFamilia).
@Preview(name = "3 columnas con una familia", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ProductGridFamiliasPreview() {
    val variantes = listOf("S" to "19900", "M" to "19900", "L" to "24900").map { (talla, centavos) ->
        Product(
            id = "pl-$talla", name = "Playera básica $talla", sku = "PL-$talla", price = BigDecimal(centavos).movePointLeft(2),
            categoryId = "cat_ropa", categoryName = "Ropa", description = null, emoji = "👕", imageUrl = null, available = true,
            familia = DatosDeFamilia(
                groupId = "playera", groupName = "Playera básica", groupImageUrl = null,
                opciones = listOf(OpcionDeVariante("Talla", talla, 0, listOf("S", "M", "L").indexOf(talla))),
            ),
        )
    }
    // Dos productos sueltos, la familia (ocupa el lugar de su primera talla) y dos sueltos más: así comparte fila con tarjetas normales.
    val productos = MockProducts.allProducts.take(2) + variantes + MockProducts.allProducts.drop(2).take(3)
    AvoqadoTheme {
        ProductGrid(
            products = productos,
            selectedCategory = null,
            displayMode = ProductDisplayMode.GRID_3,
            onProductClick = {},
            familias = reglasDeOrdenes.familiasDe(productos),
        )
    }
}
