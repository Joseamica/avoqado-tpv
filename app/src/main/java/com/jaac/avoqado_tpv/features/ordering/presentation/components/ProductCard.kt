package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import java.math.BigDecimal

/**
 * Product card for menu grid
 *
 * Square card displaying:
 * - Product name (1 line max)
 * - Price (bold, primary color)
 * - Inventory badge (top-right corner, shows stock quantity)
 *
 * Design:
 * - 1:1 aspect ratio (square) - perfect for 4-column grid
 * - 4 columns in portrait mode (compact POS layout)
 * - Tappable → Opens quantity selector
 *
 * Space efficiency:
 * - Ultra compact design fits 4 products per row
 * - No images (faster loading, minimal data usage)
 *
 * @param product Product to display
 * @param onClick Callback when card is tapped
 * @param modifier Modifier for customization
 *
 * @see com.jaac.avoqado_tpv.features.ordering.domain.Product
 * @see com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
 */
@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),  // Square - perfect for 4-column layout
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Product content (centered)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Product name (ultra compact, single line)
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 9.sp  // Very tiny text
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Price (very compact)
                Text(
                    text = product.formattedPrice,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp  // Very small price
                )
            }

            // Inventory badge (top-right corner)
            // ✅ TOAST PATTERN: Works for both QUANTITY and RECIPE tracking
            if (product.trackInventory && product.availableQuantity != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(
                            color = when {
                                product.availableQuantity == 0 -> MaterialTheme.colorScheme.error
                                product.availableQuantity <= 5 -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.primaryContainer
                            },
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = product.availableQuantity.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            product.availableQuantity == 0 -> MaterialTheme.colorScheme.onError
                            product.availableQuantity <= 5 -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

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
