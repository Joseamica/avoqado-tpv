package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoPullToRefresh
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.presentation.components.CategoryTabs
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductGrid

/**
 * MenuTab - Product browsing and adding to order
 *
 * Extracted from MenuScreen as part of 4-tab interface redesign.
 * This tab shows the product catalog with category filtering and inline search.
 *
 * ## Layout (Collapsed Mode)
 * ```
 * ┌─────────────────────────────────────┐
 * │ [🔍] [Bebidas] [Comida] [Postres]   │ ← Search icon + Category tabs
 * ├─────────────────────────────────────┤
 * │                                     │
 * │   [Product Grid]                    │ ← Products
 * │   ┌─────┐ ┌─────┐ ┌─────┐          │
 * │   │ Img │ │ Img │ │ Img │          │
 * │   │Name │ │Name │ │Name │          │
 * │   │Price│ │Price│ │Price│          │
 * │   └─────┘ └─────┘ └─────┘          │
 * │                                     │
 * └─────────────────────────────────────┘
 * ```
 *
 * ## Layout (Expanded Search Mode)
 * ```
 * ┌─────────────────────────────────────┐
 * │ [✕] [🔍 Search field...]            │ ← Close + TextField (fullwidth)
 * ├─────────────────────────────────────┤
 * │                                     │
 * │   [Product Grid - Filtered]         │ ← Filtered products
 * │   ┌─────┐ ┌─────┐                  │
 * │   │ Res │ │ Res │                  │
 * │   └─────┘ └─────┘                  │
 * │                                     │
 * └─────────────────────────────────────┘
 * ```
 *
 * ## Features
 * - **Inline Search**: Click search icon to expand search field (Google/Twitter pattern)
 * - **Categories**: Horizontal scrollable tabs from backend (hidden when search expanded)
 * - **Products**: Grid of products with quick-add or modal
 * - **Modifiers**: Product click triggers callback, handled by MenuScreen
 * - **Quick Add**: If no modifiers, add directly with quantity 1
 *
 * @param order Current order (to check if can add items)
 * @param products Filtered products from ViewModel
 * @param categories Product categories from backend
 * @param searchQuery Current search query
 * @param selectedCategory Currently selected category filter
 * @param onCategorySelected Callback when category is selected
 * @param onSearchQueryChange Callback when search query changes
 * @param onClearSearch Callback to clear search
 * @param onProductClick Callback when product is clicked (handled by MenuScreen)
 * @param isRefreshing Whether a refresh is in progress
 * @param onRefresh Callback to trigger a refresh
 * @param modifier Optional modifier
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MenuTab(
    order: Order,
    products: List<Product>,
    categories: List<ProductCategory>,
    searchQuery: String,
    selectedCategory: ProductCategory?,
    onCategorySelected: (ProductCategory?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onProductClick: (Product) -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Local state for inline search expansion
    var isSearchExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(WindowInsets.ime)
    ) {
        // Product grid with pull-to-refresh (takes all available space)
        AvoqadoPullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
        ) {
            ProductGrid(
                products = products,
                selectedCategory = selectedCategory,
                onProductClick = onProductClick,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom bar: Search icon + Category tabs OR expanded search field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSearchExpanded) {
                // Expanded search mode: Show TextField with close button
                IconButton(onClick = {
                    onClearSearch()
                    isSearchExpanded = false
                }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar búsqueda"
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Nombre, SKU o descripción...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Buscar"
                        )
                    },
                    singleLine = true
                )
            } else {
                // Collapsed mode: Show search icon + category tabs
                IconButton(
                    onClick = { isSearchExpanded = true }
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar productos",
                        tint = if (searchQuery.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Category tabs
                CategoryTabs(
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onCategorySelected = onCategorySelected,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ============================================================
// PREVIEWS
// ============================================================

@Preview(showBackground = true)
@Composable
private fun MenuTabPreview() {
    AvoqadoTheme {
        // Preview requires mock data - simplified for now
        Text("MenuTab Preview\n(Requires mock data)")
    }
}
