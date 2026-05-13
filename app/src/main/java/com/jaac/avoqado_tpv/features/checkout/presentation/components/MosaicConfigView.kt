package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import kotlinx.coroutines.launch

private const val GRID_SLOTS = 9

/**
 * Configuration grid for the "Configurar" tab.
 *
 * Renders a fixed 3x3 grid of 9 slots. Filled slots show the assigned product
 * label and a close (×) button to clear them. Empty slots show a + tile.
 * Tapping an empty slot opens a bottom sheet listing all products from the
 * catalog — tapping a product assigns it to that slot via
 * [onAssignToSlot].
 *
 * Simplified for Phase 3: no drag-and-drop reorder, no color customization,
 * fixed 9-slot capacity. Those can land in a later iteration if operators
 * ask.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MosaicConfigView(
    shortcuts: List<MosaicShortcut>,
    products: List<Product>,
    onAssignToSlot: (Product, Int) -> Unit,
    onClearSlot: (MosaicShortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeSlot by remember { mutableStateOf<Int?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()

    // Build a slot view-model: position -> shortcut (or null)
    val slots: List<MosaicShortcut?> = remember(shortcuts) {
        (0 until GRID_SLOTS).map { pos -> shortcuts.firstOrNull { it.position == pos } }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Toca un slot vacío para asignar un producto. Aparecerá en la pestaña Shortcuts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = slots.withIndex().toList(), key = { it.index }) { (position, shortcut) ->
                if (shortcut != null) {
                    FilledSlot(shortcut = shortcut, onClear = { onClearSlot(shortcut) })
                } else {
                    EmptySlot(onClick = { activeSlot = position })
                }
            }
        }
    }

    val selectedSlot = activeSlot
    if (selectedSlot != null) {
        ModalBottomSheet(
            onDismissRequest = { activeSlot = null },
            sheetState = sheetState,
        ) {
            ProductPickerSheet(
                products = products,
                onPick = { product ->
                    onAssignToSlot(product, selectedSlot)
                    sheetScope.launch { sheetState.hide() }
                    activeSlot = null
                },
            )
        }
    }
}

@Composable
private fun FilledSlot(shortcut: MosaicShortcut, onClear: () -> Unit) {
    val tint = shortcut.colorHex
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(width = 1.dp, color = tint.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        Text(
            text = shortcut.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center),
            textAlign = TextAlign.Center,
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Quitar shortcut",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clickable(onClick = onClear),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptySlot(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Asignar producto",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProductPickerSheet(products: List<Product>, onPick: (Product) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Asignar producto",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        HorizontalDivider()
        if (products.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No hay productos disponibles. Sincroniza el catálogo primero.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = products, key = { it.id }) { product ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(product) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Initials thumbnail — same convention as the cart row
                        // and search overlay (no fork-and-plate emoji fallback).
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = product.name.take(2).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = product.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = product.categoryName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = product.formattedPrice,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
