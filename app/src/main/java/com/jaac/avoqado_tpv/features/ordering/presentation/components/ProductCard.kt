package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import java.math.BigDecimal

// ⚡ PERFORMANCE: Reusable shape constant (avoid allocations on each recomposition)
private val InventoryBadgeShape = RoundedCornerShape(4.dp)

/**
 * Product card for menu grid (Square POS style)
 *
 * ⚡ PERFORMANCE OPTIMIZATIONS (1GB RAM devices):
 * - Reusable CardElevation and Shape constants (avoid allocations)
 * - Inventory colors computed ONCE per badge (not 6 times)
 * - Simplified layout hierarchy (minimal nesting)
 * - Direct TextStyle properties (no style override)
 * - remember() for expensive calculations
 *
 * Design:
 * - Rectangular aspect ratio (1.7:1) - wider than tall (Square POS style)
 * - 3 columns in portrait mode (Square POS layout)
 * - Tappable → Opens quantity selector
 * - Large, readable text (14sp) - Square pattern
 * - Left-aligned text - easier to scan
 *
 * Space efficiency:
 * - No price displayed (cleaner UI)
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
    // ⚡ OPTIMIZATION: Compute inventory badge colors ONCE (not 6 times in when expressions)
    val colorScheme = MaterialTheme.colorScheme
    val showInventoryBadge = product.trackInventory && product.availableQuantity != null
    val inventoryColors = if (showInventoryBadge) {
        remember(product.availableQuantity, colorScheme) {
            val quantity = product.availableQuantity!!
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
    } else null

    // ✅ Box padre permite que el badge sobresalga del Card
    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.7f),  // Rectangular - wider than tall (Square POS style)
            onClick = onClick,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            // Product content (left-aligned, Square POS style)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.Start,  // ✅ Left-aligned (Square pattern)
                verticalArrangement = Arrangement.Center
            ) {
                // ⚡ OPTIMIZATION: Direct TextStyle properties (no style override)
                Text(
                    text = product.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,  // ✅ Left-aligned
                    maxLines = 2,  // ✅ 2 lines for longer names
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ✅ Badge FUERA del Card (no se corta)
        // Inventory badge (floating outside card bounds - top-right)
        // ⚡ OPTIMIZATION: Only render if needed, colors computed once
        if (inventoryColors != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)  // ✅ Float outside card (right, up)
                    .background(
                        color = inventoryColors.backgroundColor,
                        shape = InventoryBadgeShape  // ⚡ Reusable constant
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = product.availableQuantity.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = inventoryColors.textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp
                )
            }
        }
    }
}

// ⚡ PERFORMANCE: Data class for inventory colors (computed once, cached by remember)
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
