package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory

/**
 * Category tabs for product filtering
 *
 * Horizontal scrollable tabs showing product categories.
 * Used in Menu screen to filter products by category.
 *
 * Features:
 * - ScrollableTabRow for many categories (horizontal scroll)
 * - "Todos" tab shows all products
 * - Tab indicator follows selection
 * - Compact height (48dp)
 *
 * @param categories List of categories (includes "Todos" as first)
 * @param selectedCategory Currently selected category (null = Todos)
 * @param onCategorySelected Callback when category is tapped
 * @param modifier Modifier for customization
 */
@Composable
fun CategoryTabs(
    categories: List<ProductCategory>,
    selectedCategory: ProductCategory?,
    onCategorySelected: (ProductCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    // Note: ProductCategory.ALL is already included in categories by MenuViewModel
    val selectedIndex = categories.indexOfFirst { it.id == (selectedCategory?.id ?: "all") }

    ScrollableTabRow(
        selectedTabIndex = selectedIndex.coerceAtLeast(0),
        modifier = modifier,
        edgePadding = 16.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = { tabPositions ->
            if (selectedIndex >= 0 && selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    ) {
        categories.forEachIndexed { index, category ->
            Tab(
                selected = selectedIndex == index,
                onClick = {
                    onCategorySelected(
                        if (category.id == "all") null else category
                    )
                },
                text = {
                    Text(
                        text = if (category.emoji.isNotEmpty()) {
                            "${category.emoji} ${category.name}"
                        } else {
                            category.name
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            )
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(name = "Todos Selected", showBackground = true, widthDp = 400)
@Composable
private fun CategoryTabsTodosPreview() {
    AvoqadoTheme {
        CategoryTabs(
            categories = MockProducts.categories,
            selectedCategory = null,  // Todos
            onCategorySelected = {}
        )
    }
}

@Preview(name = "Bebidas Selected", showBackground = true, widthDp = 400)
@Composable
private fun CategoryTabsBebidasPreview() {
    AvoqadoTheme {
        CategoryTabs(
            categories = MockProducts.categories,
            selectedCategory = MockProducts.categories[0],  // Bebidas
            onCategorySelected = {}
        )
    }
}

@Preview(name = "Comidas Selected", showBackground = true, widthDp = 400)
@Composable
private fun CategoryTabsComidasPreview() {
    AvoqadoTheme {
        CategoryTabs(
            categories = MockProducts.categories,
            selectedCategory = MockProducts.categories[1],  // Comidas
            onCategorySelected = {}
        )
    }
}

@Preview(name = "Postres Selected", showBackground = true, widthDp = 400)
@Composable
private fun CategoryTabsPostresPreview() {
    AvoqadoTheme {
        CategoryTabs(
            categories = MockProducts.categories,
            selectedCategory = MockProducts.categories[2],  // Postres
            onCategorySelected = {}
        )
    }
}
