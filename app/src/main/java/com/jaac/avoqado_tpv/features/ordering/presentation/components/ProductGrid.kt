package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * 4-column grid (portrait) showing all available products.
 * Products can be filtered by category.
 *
 * Design:
 * - GridCells.Fixed(4) for portrait handheld devices (compact POS layout)
 * - Responsive spacing using LocalResponsiveSizes
 * - Bottom padding for collapsed top panel (48dp + 16dp margin)
 * - Scrollable (LazyVerticalGrid for performance)
 *
 * Features:
 * - Filter by category
 * - Tap product → Opens quantity selector
 * - Only shows available products
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),  // Portrait: 4 columns (compact POS style)
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,  // Minimal padding
            end = 8.dp,
            top = 4.dp,
            bottom = 80.dp  // Space for collapsed top panel + margin
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),  // Ultra tight spacing for 4 columns
        verticalArrangement = Arrangement.spacedBy(4.dp)  // Ultra tight vertical spacing
    ) {
        items(availableProducts, key = { it.id }) { product ->
            ProductCard(
                product = product,
                onClick = { onProductClick(product) }
            )
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
