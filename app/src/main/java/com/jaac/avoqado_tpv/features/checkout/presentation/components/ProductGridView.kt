package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.CategoryColors
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import java.math.BigDecimal

/**
 * Categories-first product grid for the "Todos los productos" tab.
 *
 * Ported from avoqado-android `product/ProductGridView.kt`. Two layers:
 *
 * 1. Categories grid: 3-col grid of colored tiles, each showing the
 *    [Icons.Filled.GridView] icon top-left and the category name bottom-left.
 *    Tap → drills into the category. Uncategorized products are shown
 *    inline after the category tiles.
 * 2. Products grid: same 3-col layout but each tile shows initials over a
 *    tinted background, name + price below. Modifier indicator badge
 *    (top-right) shows when `product.hasModifiers`. Tap → caller's
 *    [onProductTap] (typically opens the modifier sheet or adds direct).
 *
 * A breadcrumb at the top (`Todos los productos > Category`) lets the
 * operator back out without losing their place.
 */
@Composable
fun ProductGridView(
    products: List<Product>,
    categories: List<ProductCategory>,
    isLoading: Boolean,
    onProductTap: (Product) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf<ProductCategory?>(null) }

    // Reset the drill-in when venue switches (products clear).
    LaunchedEffect(products.isEmpty()) {
        if (products.isEmpty() && selectedCategory != null) {
            selectedCategory = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (selectedCategory != null) {
            BreadcrumbNavigation(
                categoryName = selectedCategory!!.name,
                onBack = { selectedCategory = null },
            )
        }

        when {
            isLoading && products.isEmpty() -> LoadingState()
            selectedCategory == null -> {
                val uncategorized = products.filter {
                    it.categoryId.isBlank() || categories.none { c -> c.id == it.categoryId }
                }
                if (categories.isEmpty() && uncategorized.isEmpty()) {
                    EmptyState(message = "No hay productos. Refresca o configúralos en el dashboard.")
                } else {
                    CategoriesGrid(
                        categories = categories,
                        uncategorizedProducts = uncategorized,
                        onCategoryTap = { selectedCategory = it },
                        onProductTap = onProductTap,
                    )
                }
            }
            else -> {
                val categoryProducts = products.filter { it.categoryId == selectedCategory!!.id }
                if (categoryProducts.isEmpty()) {
                    EmptyState(message = "No hay productos en esta categoría")
                } else {
                    ProductsGrid(products = categoryProducts, onProductTap = onProductTap)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Breadcrumb
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun BreadcrumbNavigation(categoryName: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Regresar a categorías",
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Todos",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onBack),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = categoryName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Grids
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoriesGrid(
    categories: List<ProductCategory>,
    uncategorizedProducts: List<Product>,
    onCategoryTap: (ProductCategory) -> Unit,
    onProductTap: (Product) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = categories, key = { "cat_${it.id}" }) { category ->
            CategoryTile(category = category, onClick = { onCategoryTap(category) })
        }
        items(items = uncategorizedProducts, key = { "prod_${it.id}" }) { product ->
            ProductTile(product = product, onClick = { onProductTap(product) })
        }
    }
}

@Composable
private fun ProductsGrid(products: List<Product>, onProductTap: (Product) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = products, key = { it.id }) { product ->
            ProductTile(product = product, onClick = { onProductTap(product) })
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Tiles
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryTile(category: ProductCategory, onClick: () -> Unit) {
    val color = category.color ?: CategoryColors.getAutoColor(category.id)
    val bgColor = parseHexColor(color) ?: MaterialTheme.colorScheme.primary
    val contentColor = Color.White

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Filled.GridView,
            contentDescription = null,
            modifier = Modifier
                .padding(8.dp)
                .size(20.dp)
                .align(Alignment.TopStart),
            tint = contentColor.copy(alpha = 0.85f),
        )
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ProductTile(product: Product, onClick: () -> Unit) {
    val tintHex = product.categoryColor ?: CategoryColors.getAutoColor(product.categoryId)
    val tint = parseHexColor(tintHex) ?: MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
        enabled = product.available,
    ) {
        Column {
            // Top thumbnail area — product photo if available, else initials
            // over a tinted background.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(tint.copy(alpha = 0.85f)),
            ) {
                if (!product.imageUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(product.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = product.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = product.name.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                if (product.hasModifiers) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = "Tiene modificadores",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }

            // Info row
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface,
                    // Nombre largo que no cabe → se desliza solo hacia la
                    // izquierda revelándose completo (estilo "marquee").
                    modifier = Modifier.basicMarquee(),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = product.formattedPrice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// States
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Cargando productos…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────

private fun parseHexColor(hex: String): Color? {
    return try {
        val cleanHex = if (hex.startsWith("#")) hex else "#$hex"
        Color(android.graphics.Color.parseColor(cleanHex))
    } catch (_: Exception) {
        null
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────

@Preview(name = "Products - Categories", widthDp = 360, heightDp = 480, showBackground = true)
@Composable
private fun ProductGridCategoriesPreview() {
    AvoqadoTheme {
        ProductGridView(
            products = emptyList(),
            categories = listOf(
                fakeCategory("c1", "Hamburguesas", "#FF5722"),
                fakeCategory("c2", "Tacos Mexicanos", "#4CAF50"),
                fakeCategory("c3", "Pizzas", "#E91E63"),
                fakeCategory("c4", "Entradas", "#9C27B0"),
                fakeCategory("c5", "Bebidas", "#2196F3"),
                fakeCategory("c6", "Postres", "#FFEB3B"),
            ),
            isLoading = false,
            onProductTap = {},
        )
    }
}

@Preview(name = "Products - Inside category", widthDp = 360, heightDp = 480, showBackground = true)
@Composable
private fun ProductGridInsideCategoryPreview() {
    AvoqadoTheme {
        ProductGridView(
            products = listOf(
                fakeProduct("p1", "Hamburguesa Clásica", 14450, hasModifiers = true, categoryId = "c1"),
                fakeProduct("p2", "Hamburguesa BBQ", 16500, hasModifiers = true, categoryId = "c1"),
                fakeProduct("p3", "Hamburguesa Doble", 18900, hasModifiers = false, categoryId = "c1"),
            ),
            categories = listOf(fakeCategory("c1", "Hamburguesas", "#FF5722")),
            isLoading = false,
            onProductTap = {},
        )
    }
}

@Preview(name = "Products - Loading", widthDp = 360, heightDp = 480, showBackground = true)
@Composable
private fun ProductGridLoadingPreview() {
    AvoqadoTheme {
        ProductGridView(products = emptyList(), categories = emptyList(), isLoading = true, onProductTap = {})
    }
}

private fun fakeCategory(id: String, name: String, color: String?) = ProductCategory(
    id = id,
    name = name,
    displayOrder = 0,
    productCount = 0,
    color = color,
)

private fun fakeProduct(
    id: String,
    name: String,
    priceCents: Int,
    hasModifiers: Boolean = false,
    categoryId: String = "c1",
) = Product(
    id = id,
    name = name,
    sku = id,
    price = BigDecimal(priceCents).divide(BigDecimal(100)),
    categoryId = categoryId,
    categoryName = "Hamburguesas",
    description = null,
    emoji = "",
    imageUrl = null,
    available = true,
    modifierGroups = if (hasModifiers) {
        listOf(
            com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup(
                id = "g1",
                name = "Término",
                modifiers = emptyList(),
                type = com.jaac.avoqado_tpv.features.ordering.domain.ModifierType.SINGLE_CHOICE,
                required = true,
            ),
        )
    } else emptyList(),
)
