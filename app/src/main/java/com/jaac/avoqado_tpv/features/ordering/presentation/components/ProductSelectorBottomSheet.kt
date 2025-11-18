package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import java.math.BigDecimal

/**
 * Product Selector Bottom Sheet
 *
 * Compact bottom sheet for PAX A910S (720x1280) that allows:
 * 1. Quantity selection (with +/- buttons)
 * 2. Modifier selection (grouped, single/multiple choice)
 * 3. Optional notes (free text, 2-3 lines)
 * 4. Live price calculation (base + modifiers × quantity)
 *
 * Design: Hybrid Toast/Square pattern
 * - Draggable handle
 * - Scrollable content (if many modifiers)
 * - Large "Add to cart" button with live price
 *
 * @param product Product to customize
 * @param modifierGroups Available modifier groups for this product
 * @param onDismiss Close bottom sheet
 * @param onAddToCart Callback with quantity, selected modifiers, and notes
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductSelectorBottomSheet(
    product: Product,
    modifierGroups: List<ModifierGroup>,
    onDismiss: () -> Unit,
    onAddToCart: (quantity: Int, selectedModifiers: List<ProductModifier>, notes: String) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    var quantity by remember { mutableIntStateOf(1) }
    var notes by remember { mutableStateOf("") }
    var selectedModifiers by remember { mutableStateOf<Map<String, ProductModifier>>(emptyMap()) }

    // Calculate total price (base + modifiers) × quantity
    val modifiersTotal = selectedModifiers.values.sumOf { it.priceAdjustment }
    val totalPrice = (product.price + modifiersTotal) * BigDecimal(quantity.toString())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Product header
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = product.emoji,
                        style = MaterialTheme.typography.displayLarge,
                        fontSize = 40.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Base: ${product.formattedPrice}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                HorizontalDivider()
            }

            // Quantity selector
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cantidad",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { if (quantity > 1) quantity-- },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Disminuir cantidad")
                        }

                        Text(
                            text = quantity.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.Center
                        )

                        IconButton(
                            onClick = { if (quantity < 99) quantity++ },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Aumentar cantidad")
                        }
                    }
                }
            }

            // Modifier groups
            modifierGroups.forEach { group ->
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = group.name + if (group.required) " *" else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        when (group.type) {
                            ModifierType.SINGLE_CHOICE -> {
                                // Chips for single choice (radio button behavior)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    group.modifiers.forEach { modifier ->
                                        FilterChip(
                                            selected = selectedModifiers[group.id]?.id == modifier.id,
                                            onClick = {
                                                selectedModifiers = selectedModifiers.toMutableMap().apply {
                                                    put(group.id, modifier)
                                                }
                                            },
                                            label = {
                                                Column {
                                                    Text(
                                                        text = modifier.name,
                                                        fontSize = 12.sp
                                                    )
                                                    if (modifier.priceAdjustment != BigDecimal.ZERO) {
                                                        Text(
                                                            text = modifier.formattedPrice,
                                                            fontSize = 10.sp,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                                            )
                                        )
                                    }
                                }
                            }
                            ModifierType.MULTIPLE_CHOICE -> {
                                // Chips for multiple choice (checkbox behavior)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    group.modifiers.forEach { modifier ->
                                        FilterChip(
                                            selected = selectedModifiers.values.any { it.id == modifier.id },
                                            onClick = {
                                                selectedModifiers = selectedModifiers.toMutableMap().apply {
                                                    val key = "${group.id}_${modifier.id}"
                                                    if (containsKey(key)) {
                                                        remove(key)
                                                    } else {
                                                        put(key, modifier)
                                                    }
                                                }
                                            },
                                            label = {
                                                Column {
                                                    Text(
                                                        text = modifier.name,
                                                        fontSize = 12.sp
                                                    )
                                                    if (modifier.priceAdjustment != BigDecimal.ZERO) {
                                                        Text(
                                                            text = modifier.formattedPrice,
                                                            fontSize = 10.sp,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Notes field
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Notas (opcional)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Ej: Sin aceitunas, para llevar") },
                        maxLines = 2,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Add to cart button
            item {
                Button(
                    onClick = {
                        onAddToCart(quantity, selectedModifiers.values.toList(), notes)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "Agregar al carrito • $$totalPrice",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Pizza with Modifiers", showBackground = true, heightDp = 800, widthDp = 400)
@Composable
private fun ProductSelectorPizzaPreview() {
    AvoqadoTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            val product = MockProducts.getProductById("prod_comida_1")!!
            val modifiers = MockProducts.getModifiersForProduct(product.id)

            ProductSelectorBottomSheet(
                product = product,
                modifierGroups = modifiers,
                onDismiss = {},
                onAddToCart = { _, _, _ -> }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Burger with Término", showBackground = true, heightDp = 800, widthDp = 400)
@Composable
private fun ProductSelectorBurgerPreview() {
    AvoqadoTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            val product = MockProducts.getProductById("prod_comida_2")!!
            val modifiers = MockProducts.getModifiersForProduct(product.id)

            ProductSelectorBottomSheet(
                product = product,
                modifierGroups = modifiers,
                onDismiss = {},
                onAddToCart = { _, _, _ -> }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Bebida No Modifiers", showBackground = true, heightDp = 600, widthDp = 400)
@Composable
private fun ProductSelectorNoModifiersPreview() {
    AvoqadoTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            val product = MockProducts.getProductById("prod_bebida_2")!!  // Agua Natural (no modifiers)
            val modifiers = MockProducts.getModifiersForProduct(product.id)

            ProductSelectorBottomSheet(
                product = product,
                modifierGroups = modifiers,
                onDismiss = {},
                onAddToCart = { _, _, _ -> }
            )
        }
    }
}
