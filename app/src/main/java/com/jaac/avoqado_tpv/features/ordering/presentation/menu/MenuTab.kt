package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import com.jaac.avoqado_tpv.features.ordering.presentation.components.CategoryTabs
import com.jaac.avoqado_tpv.features.ordering.presentation.components.PanelState
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductGrid
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductSelectorBottomSheet
import timber.log.Timber

/**
 * MenuTab - Product browsing and adding to order
 *
 * Extracted from MenuScreen as part of 4-tab interface redesign.
 * This tab shows the product catalog with category filtering and search.
 *
 * ## Layout
 * ```
 * ┌─────────────────────────────────────┐
 * │ [🔍] [Bebidas] [Comida] [Postres]   │ ← Search + Category tabs
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
 * ## Features
 * - **Search**: Click search icon to open search dialog
 * - **Categories**: Horizontal scrollable tabs from backend
 * - **Products**: Grid of products with quick-add or modal
 * - **Modifiers**: If product has modifiers, show ProductSelectorBottomSheet
 * - **Quick Add**: If no modifiers, add directly with quantity 1
 *
 * @param order Current order (to check if can add items)
 * @param products Filtered products from ViewModel
 * @param categories Product categories from backend
 * @param searchQuery Current search query
 * @param selectedCategory Currently selected category filter
 * @param selectedProduct Product selected for modifier modal (if any)
 * @param showProductSelector Whether to show ProductSelectorBottomSheet
 * @param onCategorySelected Callback when category is selected
 * @param onSearchQueryChange Callback when search query changes
 * @param onClearSearch Callback to clear search
 * @param onProductClick Callback when product is clicked
 * @param onProductSelectorDismiss Callback when product selector is dismissed
 * @param onProductSelectorConfirm Callback when product selector is confirmed
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
    selectedProduct: Product?,
    showProductSelector: Boolean,
    onCategorySelected: (ProductCategory?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onProductClick: (Product) -> Unit,
    onProductSelectorDismiss: () -> Unit,
    onProductSelectorConfirm: (product: Product, quantity: Int, modifiers: List<ProductModifier>, notes: String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Local state for search dialog
    var showSearchDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Product grid (takes all available space)
        ProductGrid(
            products = products,
            selectedCategory = selectedCategory,
            onProductClick = onProductClick,
            modifier = Modifier.weight(1f)
        )

        // Bottom bar: Search icon + Category tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Search icon button
            IconButton(
                onClick = { showSearchDialog = true }
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

    // Search dialog
    if (showSearchDialog) {
        SearchDialog(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onDismiss = { showSearchDialog = false },
            onClear = {
                onClearSearch()
                showSearchDialog = false
            }
        )
    }

    // Product selector bottom sheet
    // ⚡ Performance: Uses in-composition overlay instead of ModalBottomSheet
    ProductSelectorBottomSheet(
        visible = showProductSelector,
        product = selectedProduct,
        modifierGroups = selectedProduct?.modifierGroups ?: emptyList(),
        onDismiss = onProductSelectorDismiss,
        onAddToCart = { quantity, modifiers, notes ->
            selectedProduct?.let { product ->
                onProductSelectorConfirm(product, quantity, modifiers, notes)
            }
        }
    )
}

/**
 * Search Dialog
 *
 * Modal dialog for product search with text field and clear button.
 * Appears when user clicks the search icon in the category bar.
 *
 * @param searchQuery Current search query
 * @param onSearchQueryChange Callback when search query changes
 * @param onDismiss Callback to dismiss dialog
 * @param onClear Callback to clear search and dismiss dialog
 */
@Composable
private fun SearchDialog(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Buscar productos")
        },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Nombre, SKU o descripción...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Buscar"
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = onClear) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Limpiar búsqueda"
                                )
                            }
                        }
                    },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
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
