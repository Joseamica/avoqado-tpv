package com.jaac.avoqado_tpv.features.kiosk.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.core.presentation.components.variantes.TileDeFamilia
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskProduct
import com.jaac.avoqado_tpv.features.kiosk.presentation.claveDeEntradaDeKiosco
import com.jaac.avoqado_tpv.features.kiosk.presentation.entradasDeBusquedaEnKiosco
import com.jaac.avoqado_tpv.features.kiosk.presentation.reglasDeKiosco
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import timber.log.Timber
import java.math.BigDecimal
import com.jaac.avoqado_tpv.core.util.rememberCurrencyFormat

/**
 * Kiosk Search Dialog
 *
 * Beautiful full-screen search overlay optimized for PAX A910S (5" screen).
 *
 * Features:
 * - Auto-focus search field with keyboard
 * - Real-time filtering as user types
 * - Product grid with visual cards (a family of variants is ONE card)
 * - Easy dismiss with X button
 *
 * @param visible Whether the dialog is visible
 * @param products All available products to search from
 * @param familias The families of [products] (por `groupId`), armadas una vez por catálogo
 * @param onDismiss Callback when user closes the search
 * @param onProductClick Callback when user selects a product
 * @param onFamiliaClick Callback when user selects a family (opens the sheet to pick the variant)
 */
@Composable
fun KioskSearchDialog(
    visible: Boolean,
    products: List<KioskProduct>,
    familias: Map<String, Familia<KioskProduct>>,
    onDismiss: () -> Unit,
    onProductClick: (KioskProduct) -> Unit,
    onFamiliaClick: (Familia<KioskProduct>) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var searchQuery by remember { mutableStateOf("") }

    // Filter products based on search query: by name, ignoring case (a blank text searches nothing). A family shows once.
    val resultados by remember(searchQuery, products, familias) {
        derivedStateOf { entradasDeBusquedaEnKiosco(products, familias, searchQuery) }
    }

    // Auto-focus when dialog opens
    LaunchedEffect(visible) {
        if (visible) {
            searchQuery = ""  // Reset search when opening
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (e: Exception) {
                Timber.w("🔍 Could not request focus: ${e.message}")
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it / 4 },
        exit = fadeOut() + slideOutVertically { -it / 4 }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header with search bar
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Title row with close button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "Buscar Producto",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            IconButton(
                                onClick = {
                                    keyboardController?.hide()
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cerrar",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Search input field
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            placeholder = {
                                Text(
                                    text = "Buscar...",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Limpiar",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = { keyboardController?.hide() }
                            )
                        )
                    }
                }

                // Results area
                when {
                    searchQuery.isBlank() -> {
                        // Empty state - prompt to search
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "🔍",
                                    fontSize = 64.sp
                                )
                                Text(
                                    text = "Escribe para buscar",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Encuentra productos rápidamente\npor nombre",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    resultados.isEmpty() -> {
                        // No results
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "😕",
                                    fontSize = 64.sp
                                )
                                Text(
                                    text = "Sin resultados",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "No encontramos productos\ncon \"$searchQuery\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    else -> {
                        // Results header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${resultados.size} resultado${if (resultados.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Product grid
                        ResultadosDeBusqueda(
                            entradas = resultados,
                            searchQuery = searchQuery,
                            onProductClick = { product ->
                                keyboardController?.hide()
                                onProductClick(product)
                            },
                            onFamiliaClick = { familia ->
                                // El teclado se esconde Y se suelta el foco: si no, taparía «Agregar al carrito» de la hoja que se abre encima.
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onFamiliaClick(familia)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * La cuadrícula de resultados: productos sueltos y botones de familia (tallas). Una familia sale UNA vez aunque coincidan varias tallas.
 */
@Composable
private fun ResultadosDeBusqueda(
    entradas: List<EntradaDeCatalogo<KioskProduct>>,
    searchQuery: String,
    onProductClick: (KioskProduct) -> Unit,
    onFamiliaClick: (Familia<KioskProduct>) -> Unit
) {
    val currencyFormat = rememberCurrencyFormat()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = entradas,
            key = { claveDeEntradaDeKiosco(it) }
        ) { entrada ->
            when (entrada) {
                is EntradaDeCatalogo.Suelto -> SearchResultCard(
                    product = entrada.producto,
                    searchQuery = searchQuery,
                    onClick = { onProductClick(entrada.producto) }
                )
                is EntradaDeCatalogo.DeFamilia -> TileDeFamilia(
                    familia = entrada.familia,
                    reglas = reglasDeKiosco,
                    precioTexto = { currencyFormat.format(it) },
                    onClick = { onFamiliaClick(entrada.familia) },
                    relacionDeAspecto = RELACION_FAMILIA_EN_BUSQUEDA
                )
            }
        }
    }
}

/**
 * Relación ancho/alto del botón de familia en la búsqueda: la celda de [SearchResultCard]. En la PAX (360 dp) la celda mide
 * (360 − 2×16 de margen − 2×12 de separación) / 3 = 101.3 dp de ancho y [SearchResultCard] 160 dp de alto ⇒ 101.3 / 160 = 0.633.
 *
 * Cabe sin cortarse (DM Sans real de `res/font`, escala 1.0): el contenido mide 101.3 − 4 (acento) − 24 (márgenes) = 73.3 dp de ancho
 * y 160 − 20 = 140 dp de alto. Alto: nombre 2 renglones (40) + insignia (20) + 2 + precio hasta 2 renglones (40) = 102 dp ≤ 140.
 * Ancho: «N variantes» (dmsans_bold 11 sp + 2×4 de margen) 69.2 dp con N de un dígito (hasta 99: «10 variantes» 74.3, «99 variantes» 76.4
 * se cortan con «…», raro); precio (dmsans_semibold 14 sp): «desde $199.00» 100.6 dp no cabe en un renglón y baja a «desde» (42.4) y
 * «$199.00» (54.6); «$1,299.00» 66.5 y «$9,999.00» 70.5 caben; de cinco dígitos («$12,999.00», 75.4) se corta con «…» (la hoja trae el precio).
 */
private const val RELACION_FAMILIA_EN_BUSQUEDA = 0.633f

/**
 * Search Result Product Card
 *
 * Displays a product in the search results with highlighted matching text.
 */
@Composable
private fun SearchResultCard(
    product: KioskProduct,
    searchQuery: String,
    onClick: () -> Unit
) {
    val currencyFormat = rememberCurrencyFormat()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Product image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (!product.imageUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(product.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = product.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = product.name.take(2).uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Add button overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Agregar",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Modifier indicator
                if (product.hasModifiers) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "✨",
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Product info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 14.sp
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = currencyFormat.format(product.price),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ============================================================================
// Preview (los datos de ejemplo también los usa el del menú)
// ============================================================================

private fun varianteDeEjemplo(id: String, grupo: String, nombreDelGrupo: String, valor: String, posicion: Int, precio: String) = KioskProduct(
    id = id,
    name = "$nombreDelGrupo $valor",
    price = BigDecimal(precio),
    categoryId = "c-1",
    familia = DatosDeFamilia(grupo, nombreDelGrupo, null, listOf(OpcionDeVariante("Tamaño", valor, 0, posicion)))
)

/** Un catálogo con dos familias (una de nombre largo), un producto suelto y uno personalizable. */
internal fun kioskCatalogoDeEjemplo(): List<KioskProduct> = listOf(
    varianteDeEjemplo("cafe-ch", "g-cafe", "Café americano", "Chico", 0, "35.00"),
    KioskProduct(id = "pastel", name = "Pastel de chocolate", price = BigDecimal("65.00"), categoryId = "c-1"),
    varianteDeEjemplo("cafe-me", "g-cafe", "Café americano", "Mediano", 1, "40.00"),
    varianteDeEjemplo("cafe-gr", "g-cafe", "Café americano", "Grande", 2, "45.00"),
    varianteDeEjemplo("hamb-se", "g-hamb", "Hamburguesa con queso y tocino", "Sencilla", 0, "129.00"),
    varianteDeEjemplo("hamb-do", "g-hamb", "Hamburguesa con queso y tocino", "Doble", 1, "169.00"),
    KioskProduct(
        id = "te", name = "Té verde", price = BigDecimal("28.00"), categoryId = "c-1",
        modifierGroups = listOf(
            ModifierGroup(
                id = "mg-1", name = "Endulzante", type = ModifierType.SINGLE_CHOICE,
                modifiers = listOf(ProductModifier("m-1", "Miel", BigDecimal("5.00"), ModifierType.SINGLE_CHOICE))
            )
        )
    ),
)

@Preview(name = "Resultados con una familia y un producto", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ResultadosDeBusquedaPreview() {
    AvoqadoTheme {
        val productos = kioskCatalogoDeEjemplo()
        ResultadosDeBusqueda(
            entradas = entradasDeBusquedaEnKiosco(productos, reglasDeKiosco.familiasDe(productos), "a"),
            searchQuery = "a",
            onProductClick = {},
            onFamiliaClick = {}
        )
    }
}
