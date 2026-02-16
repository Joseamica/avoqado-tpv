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
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductDisplayMode

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
 * Products can be filtered by category.
 *
 * @param products List of all products
 * @param selectedCategory Filter by this category (null = show all)
 * @param displayMode Visual mode for the product catalog
 * @param onProductClick Callback when product is tapped
 * @param modifier Modifier for customization
 * @param scrollResetKey Key that resets pagination + scroll
 */
@Composable
fun ProductGrid(
    products: List<Product>,
    selectedCategory: ProductCategory?,
    displayMode: ProductDisplayMode = ProductDisplayMode.GRID_3,
    onProductClick: (Product) -> Unit,
    headerContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    scrollResetKey: Any? = selectedCategory?.id
) {
    // ⚡ PERFORMANCE: Use remember to avoid filtering on every recomposition
    val availableProducts = remember(products, selectedCategory) {
        // Filter by category first
        val categoryFiltered = if (selectedCategory == null) {
            products
        } else {
            products.filter { it.categoryId == selectedCategory.id }
        }

        // Then filter by availability
        categoryFiltered.filter { it.available }
    }

    val resetKey = "${scrollResetKey ?: selectedCategory?.id ?: "all"}|${displayMode.preferenceValue}"

    // 🔢 PAGINATION: reset on filter/search/layout changes
    var displayedItemCount by remember(resetKey) { mutableStateOf(PAGE_SIZE) }

    // 🔢 PAGINATION: Only show first N products
    val displayedProducts = remember(availableProducts, displayedItemCount) {
        availableProducts.take(displayedItemCount)
    }

    if (displayMode == ProductDisplayMode.LIST) {
        val listState = rememberLazyListState()

        LaunchedEffect(resetKey) {
            listState.scrollToItem(0)
        }

        LaunchedEffect(listState, availableProducts) {
            snapshotFlow { listState.layoutInfo }
                .collect { layoutInfo ->
                    val totalItems = layoutInfo.totalItemsCount
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (totalItems > 0 &&
                        lastVisibleItem >= totalItems - PAGINATION_THRESHOLD &&
                        displayedProducts.size < availableProducts.size) {
                        displayedItemCount = minOf(displayedItemCount + PAGE_SIZE, availableProducts.size)
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
                items = displayedProducts,
                key = { it.id },
                contentType = { it.categoryId }
            ) { product ->
                ProductListItem(
                    product = product,
                    onClick = { onProductClick(product) }
                )
            }

            if (displayedProducts.size < availableProducts.size) {
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
    LaunchedEffect(gridState, availableProducts) {
        snapshotFlow { gridState.layoutInfo }
            .collect { layoutInfo ->
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0

                if (totalItems > 0 &&
                    lastVisibleItem >= totalItems - PAGINATION_THRESHOLD &&
                    displayedProducts.size < availableProducts.size) {
                    displayedItemCount = minOf(displayedItemCount + PAGE_SIZE, availableProducts.size)
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
            items = displayedProducts,
            key = { it.id },
            contentType = { it.categoryId }
        ) { product ->
            ProductCard(
                product = product,
                onClick = { onProductClick(product) }
            )
        }

        if (displayedProducts.size < availableProducts.size) {
            item(span = { GridItemSpan(columns) }) {
                LoadingFooter()
            }
        }
    }
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
