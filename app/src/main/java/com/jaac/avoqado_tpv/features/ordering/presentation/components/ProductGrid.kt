package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory

/**
 * Product grid for menu display
 *
 * 3-column grid (portrait) with pagination for better performance.
 * Products can be filtered by category.
 *
 * Design:
 * - GridCells.Fixed(3) for portrait handheld devices (larger touch targets)
 * - Responsive spacing using LocalResponsiveSizes
 * - Bottom padding for collapsed top panel (48dp + 16dp margin)
 * - Scrollable (LazyVerticalGrid with pagination for performance)
 *
 * Features:
 * - Filter by category
 * - Tap product → Opens quantity selector
 * - Only shows available products
 * - Pagination: Shows 15 products initially, loads more on scroll
 *
 * @param products List of all products
 * @param selectedCategory Filter by this category (null = show all)
 * @param onProductClick Callback when product is tapped
 * @param modifier Modifier for customization
 */
@Composable
fun ProductGrid(
    products: List<Product>,
    selectedCategory: ProductCategory?,
    onProductClick: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

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

    // 🔢 PAGINATION: State management (reset when availableProducts changes)
    var displayedItemCount by remember(availableProducts) { mutableStateOf(15) }
    val gridState = rememberLazyGridState()

    // 📜 SCROLL RESET: Scroll to top when products change (category switch, search)
    LaunchedEffect(availableProducts) {
        gridState.scrollToItem(0)  // Always start at the top
    }

    // 🔢 PAGINATION: Only show first N products
    val displayedProducts = remember(availableProducts, displayedItemCount) {
        availableProducts.take(displayedItemCount)
    }

    // 🔄 LAZY LOADING: Load more when scrolling near end
    LaunchedEffect(gridState, availableProducts) {
        snapshotFlow { gridState.layoutInfo }
            .collect { layoutInfo ->
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0

                // Load more when within 3 items of the end (less aggressive)
                if (totalItems > 0 &&
                    lastVisibleItem >= totalItems - 3 &&
                    displayedProducts.size < availableProducts.size) {
                    displayedItemCount = minOf(displayedItemCount + 15, availableProducts.size)
                }
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),  // Portrait: 3 columns (larger touch targets, fewer products)
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 4.dp,
            bottom = 80.dp  // Space for collapsed top panel + margin
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),  // Slightly wider spacing for 3 columns
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(
            items = displayedProducts,
            key = { it.id },
            contentType = { it.categoryId }  // ⚡ Reuse slots for products in same category
        ) { product ->
            ProductCard(
                product = product,
                onClick = { onProductClick(product) }
            )
        }

        // ⏳ LOADING INDICATOR: Show when more products are available
        if (displayedProducts.size < availableProducts.size) {
            item(span = { GridItemSpan(3) }) {  // Span all 3 columns
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
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
            onProductClick = {}
        )
    }
}
