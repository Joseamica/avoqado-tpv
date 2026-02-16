package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import java.math.BigDecimal

private val CardShape = RoundedCornerShape(12.dp)
private val InventoryBadgeShape = RoundedCornerShape(4.dp)
private val CategoryAccentShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
private val CategoryAccentWidth = 4.dp

/**
 * Product card for grid display.
 *
 * Visual strategy:
 * - Neutral card background for calmer UI
 * - Subtle category accent strip (instead of full-card color fill)
 * - Always show price for faster decision/tap flow
 *
 * @param product Product to display
 * @param onClick Callback when card is tapped
 * @param modifier Modifier for customization
 */
@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryColor = rememberCategoryColor(product)
    val inventoryColors = rememberInventoryColors(product)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = CardShape
            ),
        onClick = onClick,
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(CategoryAccentWidth)
                    .background(
                        color = categoryColor,
                        shape = CategoryAccentShape
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = product.formattedPrice,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (inventoryColors != null) {
                        InventoryBadge(
                            quantity = product.availableQuantity,
                            colors = inventoryColors
                        )
                    }
                }
            }
        }
    }
}

/**
 * Product row for list display.
 *
 * Shows name + SKU + price with larger tap target and stock badge.
 */
@Composable
fun ProductListItem(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryColor = rememberCategoryColor(product)
    val inventoryColors = rememberInventoryColors(product)

    Card(
        modifier = modifier
            .fillMaxWidth(),
        onClick = onClick,
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProductThumbnail(
                name = product.name,
                imageUrl = product.imageUrl,
                categoryColor = categoryColor,
                size = 48.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (product.sku.isNotBlank()) product.sku else product.categoryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                modifier = Modifier.padding(start = 8.dp, end = 6.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = product.formattedPrice,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                if (inventoryColors != null) {
                    InventoryBadge(
                        quantity = product.availableQuantity,
                        colors = inventoryColors
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberCategoryColor(product: Product): Color {
    return remember(product.effectiveCategoryColor) {
        try {
            Color(android.graphics.Color.parseColor(product.effectiveCategoryColor))
        } catch (_: Exception) {
            Color.Gray
        }
    }
}

@Composable
private fun rememberInventoryColors(product: Product): InventoryColors? {
    val colorScheme = MaterialTheme.colorScheme
    val showInventoryBadge = product.trackInventory && product.availableQuantity != null
    if (!showInventoryBadge) return null

    return remember(product.availableQuantity, colorScheme) {
        val quantity = product.availableQuantity ?: 0
        when {
            quantity == 0 -> InventoryColors(
                backgroundColor = colorScheme.error,
                textColor = colorScheme.onError
            )
            quantity <= 5 -> InventoryColors(
                backgroundColor = colorScheme.errorContainer,
                textColor = colorScheme.onErrorContainer
            )
            else -> InventoryColors(
                backgroundColor = colorScheme.primaryContainer,
                textColor = colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun InventoryBadge(
    quantity: Int?,
    colors: InventoryColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = colors.backgroundColor,
                shape = InventoryBadgeShape
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = quantity.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textColor,
            fontWeight = FontWeight.Bold
        )
    }
}

private data class InventoryColors(
    val backgroundColor: Color,
    val textColor: Color
)

// ============================================================================
// Previews
// ============================================================================

@Preview(name = "Bebida - Coca-Cola", showBackground = true, widthDp = 180)
@Composable
private fun ProductCardBebidaPreview() {
    AvoqadoTheme {
        ProductCard(
            product = Product(
                id = "prod_bebida_1",
                name = "Coca-Cola",
                sku = "BEB-001",
                price = BigDecimal("35.00"),
                categoryId = "cat_bebidas",
                categoryName = "Bebidas",
                description = "Refresco de cola 600ml",
                emoji = "🥤",
                imageUrl = null,
                available = true
            ),
            onClick = {}
        )
    }
}

@Preview(name = "Comida - Pizza", showBackground = true, widthDp = 180)
@Composable
private fun ProductCardComidaPreview() {
    AvoqadoTheme {
        ProductCard(
            product = Product(
                id = "prod_comida_1",
                name = "Pizza Margherita",
                sku = "COM-001",
                price = BigDecimal("180.00"),
                categoryId = "cat_comidas",
                categoryName = "Comidas",
                description = "Pizza con tomate, mozzarella",
                emoji = "🍕",
                imageUrl = null,
                available = true
            ),
            onClick = {}
        )
    }
}

@Preview(name = "Postre - Tiramisú", showBackground = true, widthDp = 180)
@Composable
private fun ProductCardPostrePreview() {
    AvoqadoTheme {
        ProductCard(
            product = Product(
                id = "prod_postre_1",
                name = "Tiramisú",
                sku = "POS-001",
                price = BigDecimal("80.00"),
                categoryId = "cat_postres",
                categoryName = "Postres",
                description = "Postre italiano",
                emoji = "🍰",
                imageUrl = null,
                available = true
            ),
            onClick = {}
        )
    }
}

@Preview(name = "Long Name - Overflow Test", showBackground = true, widthDp = 180)
@Composable
private fun ProductCardLongNamePreview() {
    AvoqadoTheme {
        ProductCard(
            product = Product(
                id = "prod_test",
                name = "Hamburguesa Doble con Queso y Tocino Extra",
                sku = "TEST-001",
                price = BigDecimal("250.00"),
                categoryId = "cat_comidas",
                categoryName = "Comidas",
                description = "Test overflow",
                emoji = "🍔",
                imageUrl = null,
                available = true
            ),
            onClick = {}
        )
    }
}

@Preview(name = "Grid - 2 Cards", showBackground = true, widthDp = 400)
@Composable
private fun ProductCardGridPreview() {
    AvoqadoTheme {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            ProductCard(
                product = MockProducts.allProducts[0],
                onClick = {},
                modifier = Modifier.weight(1f)
            )
            ProductCard(
                product = MockProducts.allProducts[5],
                onClick = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Preview(name = "List Item", showBackground = true, widthDp = 420)
@Composable
private fun ProductListItemPreview() {
    AvoqadoTheme {
        ProductListItem(
            product = MockProducts.allProducts.first(),
            onClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
