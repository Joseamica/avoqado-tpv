package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.TextosVariantes
import com.jaac.avoqado_tpv.core.presentation.components.variantes.textoDePrecio
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.checkout.presentation.CheckoutViewModel
import com.jaac.avoqado_tpv.features.checkout.presentation.reglasDeCobro
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.formatoDePrecio

/**
 * Full-screen search overlay launched by tapping the [SearchBarView] pill.
 *
 * Subscribes to [CheckoutViewModel.searchResults] which filters the cached
 * product list reactively (no network call per keystroke). Tapping a result
 * calls [onProductTap] — the parent screen typically adds it to the cart and
 * dismisses the overlay. Una FAMILIA de variantes (talla/color) sale en UNA fila
 * y tocarla llama a [onFamiliaTap] (abre la hoja de tallas; no agrega nada).
 *
 * Auto-focuses the text field on entry so the operator can start typing
 * immediately, mirroring avoqado-android UX.
 */
@Composable
fun SearchOverlayView(
    viewModel: CheckoutViewModel,
    onProductTap: (Product) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onFamiliaTap: (Familia<Product>) -> Unit = {},
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Wrap in Surface so LocalContentColor resolves to `onSurface` for every
    // child Text/Icon. The previous `Modifier.background(...)` painted the
    // background but didn't propagate `contentColor`, so text/icons fell back
    // to LocalContentColor's default (Color.Black) and disappeared on the
    // dark theme.
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = { Text("Buscar producto") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                )

                IconButton(onClick = {
                    viewModel.updateSearchQuery("")
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cerrar búsqueda",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            HorizontalDivider()

            if (results.isEmpty()) {
                EmptyState(query = query)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = results,
                        key = {
                            when (it) {
                                is EntradaDeCatalogo.Suelto -> it.producto.id
                                is EntradaDeCatalogo.DeFamilia -> "familia:${it.familia.groupId}"
                            }
                        },
                    ) { entrada ->
                        when (entrada) {
                            is EntradaDeCatalogo.Suelto ->
                                ProductResultRow(product = entrada.producto, onClick = { onProductTap(entrada.producto) })
                            is EntradaDeCatalogo.DeFamilia ->
                                FamiliaResultRow(familia = entrada.familia, onClick = { onFamiliaTap(entrada.familia) })
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductResultRow(product: Product, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2-letter uppercase initials thumbnail (no fork-and-plate emoji
        // fallback — operators reported it looked dated and inconsistent
        // across products without imagery).
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = product.name.take(2).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                // Nombre largo → se desliza solo hacia la izquierda (marquee).
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text = product.categoryName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = product.formattedPrice,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * La fila de una familia en la búsqueda: mismas medidas y letra que [ProductResultRow]; en vez de la categoría dice cuántas variantes
 * tiene, y el precio lleva «desde» si no todas cuestan lo mismo. «Agotado» sólo si TODAS lo están (no bloquea el toque: la hoja avisa
 * de la que se elija).
 */
@Composable
private fun FamiliaResultRow(familia: Familia<Product>, onClick: () -> Unit) {
    val precio = reglasDeCobro.textoDePrecio(familia, formatoDePrecio)
    val agotada = reglasDeCobro.agotada(familia)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = TextosVariantes.accesibleFamilia(familia.nombre, familia.variantes.size, precio) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = familia.nombre.take(2).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = familia.nombre,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text = if (agotada) {
                    "${TextosVariantes.variantes(familia.variantes.size)} · ${TextosVariantes.AGOTADO}"
                } else {
                    TextosVariantes.variantes(familia.variantes.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = precio,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyState(query: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (query.isBlank()) {
                "Empieza a escribir para buscar productos"
            } else {
                "Sin resultados para “$query”"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Alto de cada fila: 12 + 40 (miniatura) + 12 = 64 dp. Ancho del texto: 360 − 32 (margen) − 40 − 12 − 8 − ~93 («desde $199.00», bodyMedium) ≈ 175 dp,
// de sobra para «3 variantes · Agotado» (bodySmall, ~21 caracteres ≈ 130 dp); el nombre largo se desliza (marquee).
@Preview(name = "Búsqueda - filas de familia", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun FamiliaResultRowPreview() {
    val productos = listOf(
        fakeVariante("pl-s", "Playera básica S", 19900, "playera", "Playera básica", "S"),
        fakeVariante("pl-m", "Playera básica M", 19900, "playera", "Playera básica", "M"),
        fakeVariante("pl-l", "Playera básica L", 24900, "playera", "Playera básica", "L"),
        fakeVariante("ga-s", "Gorra de lana S", 12900, "gorra", "Gorra de lana", "S", available = false),
        fakeVariante("ga-m", "Gorra de lana M", 12900, "gorra", "Gorra de lana", "M", available = false),
    )
    val familias = reglasDeCobro.familiasDe(productos)
    AvoqadoTheme {
        Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column {
                // Normal: «3 variantes» y «desde $199.00».
                FamiliaResultRow(familia = familias.getValue("playera"), onClick = {})
                HorizontalDivider()
                // Todas agotadas: «2 variantes · Agotado» y el precio solo (todas cuestan lo mismo).
                FamiliaResultRow(familia = familias.getValue("gorra"), onClick = {})
            }
        }
    }
}
