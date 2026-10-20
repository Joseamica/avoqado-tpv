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
import androidx.compose.ui.draw.alpha
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
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.variantes.TileDeFamilia
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.checkout.presentation.reglasDeCobro
import com.jaac.avoqado_tpv.features.ordering.domain.CategoryColors
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.formatoDePrecio
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
 *
 * Las tallas/colores de una misma familia salen en UN botón ([TileDeFamilia]) en el lugar de su primera variante de la lista que se
 * pinta (la de la categoría abierta, o la de los sin categoría): una categoría con una sola variante igual muestra el botón de la
 * familia. Tocarlo llama a [onFamiliaTap] y nunca agrega nada por sí solo. [familias] sale de `CheckoutViewModel.familias`.
 */
@Composable
fun ProductGridView(
    products: List<Product>,
    categories: List<ProductCategory>,
    isLoading: Boolean,
    onProductTap: (Product) -> Unit,
    modifier: Modifier = Modifier,
    familias: Map<String, Familia<Product>> = emptyMap(),
    onFamiliaTap: (Familia<Product>) -> Unit = {},
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
                        familias = familias,
                        onCategoryTap = { selectedCategory = it },
                        onProductTap = onProductTap,
                        onFamiliaTap = onFamiliaTap,
                    )
                }
            }
            else -> {
                val categoryProducts = products.filter { it.categoryId == selectedCategory!!.id }
                if (categoryProducts.isEmpty()) {
                    EmptyState(message = "No hay productos en esta categoría")
                } else {
                    ProductsGrid(
                        products = categoryProducts,
                        familias = familias,
                        onProductTap = onProductTap,
                        onFamiliaTap = onFamiliaTap,
                    )
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
    familias: Map<String, Familia<Product>>,
    onCategoryTap: (ProductCategory) -> Unit,
    onProductTap: (Product) -> Unit,
    onFamiliaTap: (Familia<Product>) -> Unit,
) {
    val entradas = remember(uncategorizedProducts, familias) { reglasDeCobro.agruparCatalogo(uncategorizedProducts, familias) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = categories, key = { "cat_${it.id}" }) { category ->
            CategoryTile(category = category, onClick = { onCategoryTap(category) })
        }
        items(items = entradas, key = ::claveDeEntrada) { entrada ->
            EntradaTile(entrada = entrada, onProductTap = onProductTap, onFamiliaTap = onFamiliaTap)
        }
    }
}

@Composable
private fun ProductsGrid(
    products: List<Product>,
    familias: Map<String, Familia<Product>>,
    onProductTap: (Product) -> Unit,
    onFamiliaTap: (Familia<Product>) -> Unit,
) {
    val entradas = remember(products, familias) { reglasDeCobro.agruparCatalogo(products, familias) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = entradas, key = ::claveDeEntrada) { entrada ->
            EntradaTile(entrada = entrada, onProductTap = onProductTap, onFamiliaTap = onFamiliaTap)
        }
    }
}

// La llave de una entrada: «prod_<id>» o «familia_<groupId>» (en la cuadrícula de arriba conviven con «cat_<id>»; el prefijo evita choques).
private fun claveDeEntrada(entrada: EntradaDeCatalogo<Product>): String = when (entrada) {
    is EntradaDeCatalogo.Suelto -> "prod_${entrada.producto.id}"
    is EntradaDeCatalogo.DeFamilia -> "familia_${entrada.familia.groupId}"
}

@Composable
private fun EntradaTile(
    entrada: EntradaDeCatalogo<Product>,
    onProductTap: (Product) -> Unit,
    onFamiliaTap: (Familia<Product>) -> Unit,
) {
    when (entrada) {
        is EntradaDeCatalogo.Suelto -> ProductTile(product = entrada.producto, onClick = { onProductTap(entrada.producto) })
        is EntradaDeCatalogo.DeFamilia -> TileDeFamilia(
            familia = entrada.familia,
            reglas = reglasDeCobro,
            precioTexto = formatoDePrecio,
            onClick = { onFamiliaTap(entrada.familia) },
            relacionDeAspecto = RELACION_TILE_FAMILIA,
        )
    }
}

/**
 * Ancho/alto del botón de familia en esta cuadrícula, igual al de un [ProductTile]: foto 1.2:1 + nombre y precio. Con 3 columnas en la
 * PAX (360 dp − 24 de margen − 16 de separación = 320 / 3 ≈ 106.7 dp por celda): ProductTile = 106.7/1.2 (88.9) + 12 de margen + 16 del
 * nombre (bodySmall) + 2 + 16 del precio (labelSmall) = 134.9 dp; 106.7/134.9 ⇒ 0.79 ⇒ 135 dp.
 *
 * Lo que `TileDeFamilia` necesita dentro de esos 135 dp (contenido de 106.7 − 4 de acento − 24 de margen = 78.7 dp de ancho, margen
 * vertical 2×10; texto: titleSmall 20 dp por renglón, insignia 16 + 2×2 = 20, labelLarge 20):
 * - normal (nombre en 2 renglones, 1 insignia, precio en 2 renglones, «desde» / «$199.00»): 20 + 40 + 20 + 2 + 40 = 122 dp ≤ 135;
 * - todas agotadas (la insignia «Agotado» baja a su renglón: 20 + 2 + 20; el nombre se queda en 1 renglón, 20; precio en 2): 20 + 20 + 42
 *   + 2 + 40 = 124 dp ≤ 135.
 * Ancho (78.7 dp), medido con dmsans_bold.ttf a 11 sp: «3 variantes» 61.2 + 2×4 = 69.2 dp y «12 variantes» 73.4 caben; «desde» (42.4) y
 * «$199.00» (54.6, labelLarge) caben cada uno en su renglón.
 */
private const val RELACION_TILE_FAMILIA = 0.79f

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

    val isOutOfStock = !product.available
    val stockQty = product.availableQuantity
    val isRecipe = product.inventoryMethod == "RECIPE"
    // Muestra el numerito solo cuando hay stock (>0) en un producto rastreado.
    // El 0 no se pinta aquí — lo cubre el scrim de agotado.
    val showStockCount = product.trackInventory && stockQty != null && stockQty > 0
    val isLowStock = showStockCount && (stockQty ?: 0) <= 5
    val lowStockColor = MaterialTheme.avoqadoColors.statusWarning
    // Etiqueta de agotado según el MOTIVO:
    //  - stock 0 por RECETA → se acabó un insumo de la receta (no el producto)
    //  - stock 0 por CANTIDAD → el producto mismo se acabó
    //  - no disponible por otra causa (inactivo) → genérico
    val outOfStockLabel = when {
        product.trackInventory && (stockQty ?: 0) <= 0 && isRecipe -> "SIN INSUMOS"
        product.trackInventory && (stockQty ?: 0) <= 0 -> "AGOTADO"
        else -> "NO DISPONIBLE"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
        // Siempre tocable: el agotado ya no se agrega (el handler lo intercepta y
        // abre el modal informativo), pero el tap DEBE llegar para poder informar.
        // El look "deshabilitado" lo dan el scrim + la atenuación, no enabled=false.
        enabled = true,
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

                // 🔢 Badge de stock (arriba-izquierda). RECETA se muestra con "~"
                // porque es una estimación (los insumos se comparten entre
                // productos); CANTIDAD es el conteo exacto. Ámbar cuando queda
                // poco (≤5) para que el cajero note que hay que resurtir.
                if (showStockCount) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isLowStock) lowStockColor
                                else Color.Black.copy(alpha = 0.6f),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (isRecipe) "~$stockQty" else "$stockQty",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isLowStock) Color.Black else Color.White,
                        )
                    }
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
                // 🚫 Agotado: Surface(enabled=false) ya bloquea el tap, pero sin
                // señal visual el tile se veía idéntico a uno disponible (foto,
                // precio, incluso el ícono "tiene modificadores"), así que el
                // toque no hacía nada y se sentía como app rota. El texto cambia
                // según el motivo: RECETA = "SIN INSUMOS" (se acabó un insumo, no
                // el producto), CANTIDAD = "AGOTADO".
                if (isOutOfStock) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = outOfStockLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
            }

            // Info row — atenuado cuando está agotado, para reforzar el estado
            // no-seleccionable del scrim de arriba.
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .alpha(if (isOutOfStock) 0.5f else 1f),
            ) {
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
    AvoqadoFullScreenLoading(message = "Cargando productos...")
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
                // Con stock (receta) → badge "~12".
                fakeProduct("p1", "Hamburguesa de Pollo", 11900, hasModifiers = true, categoryId = "c1", trackInventory = true, inventoryMethod = "RECIPE", availableQuantity = 12),
                // 🚫 Agotado por receta + con modificadores: reproduce el caso que
                // se veía "roto" — ícono de modificadores visible pero tap sin
                // efecto. Ahora muestra "SIN INSUMOS".
                fakeProduct("p2", "Hamburguesa BBQ", 16500, hasModifiers = true, categoryId = "c1", available = false, trackInventory = true, inventoryMethod = "RECIPE", availableQuantity = 0),
                // Poco stock (cantidad) → badge "3" en ámbar.
                fakeProduct("p3", "Coca-Cola 600ml", 3500, categoryId = "c1", trackInventory = true, inventoryMethod = "QUANTITY", availableQuantity = 3),
                // Agotado por cantidad → "AGOTADO".
                fakeProduct("p4", "Agua Mineral 1L", 2500, categoryId = "c1", available = false, trackInventory = true, inventoryMethod = "QUANTITY", availableQuantity = 0),
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

// Celdas de ~106.7 dp: los dos botones de familia miden 135 dp (RELACION_TILE_FAMILIA; su contenido necesita 122 y 124 dp) y las dos
// tarjetas sueltas ~135 dp; 2 filas de 135 + 8 de separación + 24 de margen = 302 dp, de sobra en los 640 dp.
@Preview(name = "Products - Familias", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun ProductGridFamiliasPreview() {
    val productos = listOf(
        // Una familia normal: 3 tallas con precios distintos ⇒ «3 variantes» y «desde $199.00».
        fakeVariante("pl-s", "Playera básica S", 19900, "playera", "Playera básica", "S"),
        fakeVariante("pl-m", "Playera básica M", 19900, "playera", "Playera básica", "M"),
        fakeVariante("pl-l", "Playera básica L", 24900, "playera", "Playera básica", "L"),
        // Una familia con TODAS las tallas agotadas ⇒ la insignia «Agotado» baja a su renglón.
        fakeVariante("ga-s", "Gorra de lana S", 12900, "gorra", "Gorra de lana", "S", available = false),
        fakeVariante("ga-m", "Gorra de lana M", 14900, "gorra", "Gorra de lana", "M", available = false),
        // Productos sueltos, uno agotado: la cuadrícula no cambia de ritmo.
        fakeProduct("p1", "Coca-Cola 600ml", 3500, categoryId = "c1"),
        fakeProduct("p2", "Agua Mineral 1L", 2500, categoryId = "c1", available = false, trackInventory = true, inventoryMethod = "QUANTITY", availableQuantity = 0),
    )
    AvoqadoTheme {
        ProductGridView(
            products = productos,
            categories = emptyList(),
            isLoading = false,
            onProductTap = {},
            familias = reglasDeCobro.familiasDe(productos),
            onFamiliaTap = {},
        )
    }
}

private fun fakeCategory(id: String, name: String, color: String?) = ProductCategory(
    id = id,
    name = name,
    displayOrder = 0,
    productCount = 0,
    color = color,
)

// Una talla de una familia con un solo eje («Talla»); `internal` porque el preview de la búsqueda usa los mismos datos.
internal fun fakeVariante(
    id: String,
    name: String,
    priceCents: Int,
    groupId: String,
    groupName: String,
    talla: String,
    available: Boolean = true,
) = fakeProduct(
    id = id,
    name = name,
    priceCents = priceCents,
    available = available,
    familia = DatosDeFamilia(
        groupId = groupId,
        groupName = groupName,
        groupImageUrl = null,
        opciones = listOf(OpcionDeVariante("Talla", talla, 0, listOf("S", "M", "L").indexOf(talla))),
    ),
)

internal fun fakeProduct(
    id: String,
    name: String,
    priceCents: Int,
    hasModifiers: Boolean = false,
    categoryId: String = "c1",
    available: Boolean = true,
    trackInventory: Boolean = false,
    inventoryMethod: String? = null,
    availableQuantity: Int? = null,
    familia: DatosDeFamilia? = null,
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
    available = available,
    trackInventory = trackInventory,
    inventoryMethod = inventoryMethod,
    availableQuantity = availableQuantity,
    familia = familia,
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
