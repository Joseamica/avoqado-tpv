package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoPullToRefresh
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductDisplayMode
import com.jaac.avoqado_tpv.features.ordering.presentation.components.CategoryRow
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductGrid

/**
 * MenuTab - Square POS-style product catalog
 *
 * Two-state drill-down navigation:
 *
 * ## Home View (selectedCategory == null)
 * ```
 * ┌─────────────────────────────────────┐
 * │  [Display Mode ▾]                   │
 * ├─────────────────────────────────────┤
 * │  [🥤 Bebidas    5 articulos    >]   │ ← Category rows
 * │  [🍽️ Comidas    5 articulos    >]   │
 * │  [🍰 Postres    5 articulos    >]   │
 * ├─────────────────────────────────────┤
 * │  [Product Grid / List - All]        │ ← All products below
 * └─────────────────────────────────────┘
 * ```
 *
 * ## Category View (selectedCategory != null)
 * ```
 * ┌─────────────────────────────────────┐
 * │  [←] Bebidas                        │ ← Back header
 * │  [🔍 Buscar...]                     │ ← Search bar
 * ├─────────────────────────────────────┤
 * │  [Product Grid / List - Filtered]   │ ← Category products
 * └─────────────────────────────────────┘
 * ```
 *
 * @param order Current order (to check if can add items)
 * @param products Filtered products from ViewModel
 * @param categories Product categories from backend
 * @param searchQuery Current search query
 * @param productDisplayMode Current visual mode for product catalog
 * @param selectedCategory Currently selected category filter
 * @param onCategorySelected Callback when category is selected
 * @param onSearchQueryChange Callback when search query changes
 * @param onClearSearch Callback to clear search
 * @param onProductDisplayModeChange Callback when display mode changes
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
    productDisplayMode: ProductDisplayMode,
    selectedCategory: ProductCategory?,
    onCategorySelected: (ProductCategory?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onProductDisplayModeChange: (ProductDisplayMode) -> Unit,
    onProductClick: (Product) -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Filter out the synthetic "Todos" category — only real categories as rows
    val realCategories = remember(categories) {
        categories.filter { it.id != "all" }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(WindowInsets.ime)
    ) {
        val scrollResetKey = "${selectedCategory?.id ?: "all"}|$searchQuery"

        if (selectedCategory == null) {
            // ── HOME VIEW: Search + categories + all products ──

            val isSearching = searchQuery.isNotEmpty()

            AvoqadoPullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                ProductGrid(
                    products = products,
                    selectedCategory = null,
                    displayMode = productDisplayMode,
                    onProductClick = onProductClick,
                    headerContent = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Full-width search bar (always visible)
                            HomeSearchBar(
                                query = searchQuery,
                                onChange = onSearchQueryChange,
                                onClear = onClearSearch
                            )

                            // Display mode selector
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                ProductDisplayModeSelector(
                                    currentMode = productDisplayMode,
                                    onModeSelected = onProductDisplayModeChange
                                )
                            }

                            // Category rows — hidden when searching
                            if (!isSearching) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    realCategories.forEach { category ->
                                        CategoryRow(
                                            category = category,
                                            onClick = {
                                                onClearSearch()
                                                onCategorySelected(category)
                                            }
                                        )
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    scrollResetKey = scrollResetKey
                )
            }
        } else {
            // ── CATEGORY VIEW: Back header + search + filtered products ──

            // Back header
            CategoryViewHeader(
                categoryName = selectedCategory.name,
                onBack = {
                    onClearSearch()
                    onCategorySelected(null)
                }
            )

            // Search bar
            CategorySearchBar(
                query = searchQuery,
                onChange = onSearchQueryChange,
                onClear = onClearSearch
            )

            // Filtered product grid
            AvoqadoPullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                ProductGrid(
                    products = products,
                    selectedCategory = selectedCategory,
                    displayMode = productDisplayMode,
                    onProductClick = onProductClick,
                    headerContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            ProductDisplayModeSelector(
                                currentMode = productDisplayMode,
                                onModeSelected = onProductDisplayModeChange
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    scrollResetKey = scrollResetKey
                )
            }
        }
    }
}

// ── Private composables ─────────────────────────────────────────

@Composable
private fun CategoryViewHeader(
    categoryName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver a categorias"
            )
        }

        Text(
            text = categoryName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HomeSearchBar(
    query: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                "Buscar en todos los articulos...",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Buscar"
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Limpiar"
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun CategorySearchBar(
    query: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        placeholder = {
            Text(
                "Buscar en esta categoria...",
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Buscar"
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Limpiar"
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        textStyle = MaterialTheme.typography.bodySmall,
        colors = OutlinedTextFieldDefaults.colors()
    )
}

@Composable
private fun ProductDisplayModeSelector(
    currentMode: ProductDisplayMode,
    onModeSelected: (ProductDisplayMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(onClick = { isMenuExpanded = true }) {
            Text(currentMode.shortLabel())
        }

        DropdownMenu(
            expanded = isMenuExpanded,
            onDismissRequest = { isMenuExpanded = false }
        ) {
            ProductDisplayMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.fullLabel()) },
                    trailingIcon = {
                        if (mode == currentMode) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null
                            )
                        }
                    },
                    onClick = {
                        onModeSelected(mode)
                        isMenuExpanded = false
                    }
                )
            }
        }
    }
}

private fun ProductDisplayMode.shortLabel(): String = when (this) {
    ProductDisplayMode.GRID_3 -> "3 cols"
    ProductDisplayMode.GRID_2 -> "2 cols"
    ProductDisplayMode.LIST -> "Lista"
}

private fun ProductDisplayMode.fullLabel(): String = when (this) {
    ProductDisplayMode.GRID_3 -> "Cuadricula 3 columnas"
    ProductDisplayMode.GRID_2 -> "Cuadricula 2 columnas"
    ProductDisplayMode.LIST -> "Lista detallada"
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
