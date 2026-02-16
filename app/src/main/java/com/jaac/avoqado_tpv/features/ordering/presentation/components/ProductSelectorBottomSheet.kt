package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import java.math.BigDecimal

/**
 * Product Customization Screen (Full-Screen)
 *
 * ⚡ Performance: Full-screen overlay (no animations, no ModalBottomSheet window)
 * - Instant show/hide (0ms overhead for 1GB RAM devices)
 * - TopBar with close button (left) and add button (right)
 * - Same content as previous bottom sheet (quantity, modifiers, notes)
 *
 * Layout:
 * ```
 * ┌─────────────────────────────────────┐
 * │ [✕]  Pizza Margherita      [✓]      │ ← TopBar
 * ├─────────────────────────────────────┤
 * │ 🍕 (emoji, 40sp)                    │
 * │ Pizza Margherita                    │
 * │ Base: $15.00                        │
 * │                                     │
 * │ Cantidad: [-] 1 [+]                 │
 * │                                     │
 * │ Tamaño *                            │
 * │ [Chica] [Mediana] [Grande]          │
 * │                                     │
 * │ Extras (opcional)                   │
 * │ [Queso +$2] [Tocino +$3]            │
 * │                                     │
 * │ Notas (opcional)                    │
 * │ [TextField]                         │
 * │                                     │
 * │ Total: $17.00                       │
 * └─────────────────────────────────────┘
 * ```
 *
 * @param visible Whether the screen is visible
 * @param product Product to customize (nullable when not visible)
 * @param modifierGroups Available modifier groups for this product
 * @param onDismiss Close screen without adding
 * @param onAddToCart Callback with quantity, selected modifiers, and notes
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProductSelectorBottomSheet(
    visible: Boolean,
    product: Product?,
    modifierGroups: List<ModifierGroup>,
    onDismiss: () -> Unit,
    onAddToCart: (quantity: Int, selectedModifiers: List<ProductModifier>, notes: String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Early return if not visible or no product
    if (!visible || product == null) return

    var quantity by remember(product.id) { mutableIntStateOf(1) }
    var notes by remember(product.id) { mutableStateOf("") }
    var selectedModifiers by remember(product.id) { mutableStateOf<Map<String, ProductModifier>>(emptyMap()) }
    val focusManager = LocalFocusManager.current

    // Calculate total price (base + modifiers) × quantity
    val modifiersTotal = selectedModifiers.values.sumOf { it.priceAdjustment }
    val totalPrice = (product.price + modifiersTotal) * BigDecimal(quantity.toString())

    // Helper function: Count selections for a group
    fun countSelectionsForGroup(groupId: String, type: ModifierType): Int {
        return when (type) {
            ModifierType.SINGLE_CHOICE -> if (selectedModifiers.containsKey(groupId)) 1 else 0
            ModifierType.MULTIPLE_CHOICE -> selectedModifiers.keys.count { it.startsWith("${groupId}_") }
        }
    }

    // ✅ VALIDATION: Check if all groups meet their min/max selection requirements
    val allSelectionRulesMet = modifierGroups.all { group ->
        val count = countSelectionsForGroup(group.id, group.type)
        val minMet = count >= group.effectiveMinSelections
        val maxMet = group.effectiveMaxSelections?.let { count <= it } ?: true
        minMet && maxMet
    }

    // Find groups that don't meet minimum requirements (for warning message)
    val groupsNotMeetingMin = modifierGroups.filter { group ->
        val count = countSelectionsForGroup(group.id, group.type)
        count < group.effectiveMinSelections
    }

    // Handle back button press
    BackHandler(enabled = true) {
        onDismiss()
    }

    // Full-screen overlay (covers EVERYTHING including TopBar and tabs)
    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f)
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
        ) {
            // Header bar with close and add buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    focusManager.clearFocus(force = true)
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Add to cart button (enabled only when all selection rules are met)
                IconButton(
                    onClick = {
                        if (allSelectionRulesMet) {
                            focusManager.clearFocus(force = true)
                            onAddToCart(quantity, selectedModifiers.values.toList(), notes)
                            onDismiss()
                        }
                    },
                    enabled = allSelectionRulesMet
                ) {
                    Icon(
                        imageVector = Icons.Default.AddShoppingCart,
                        contentDescription = "Agregar al pedido",
                        tint = if (allSelectionRulesMet) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }

            HorizontalDivider()

            // Scrollable content
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Product header (emoji + base price)
                item {
                    Spacer(modifier = Modifier.height(8.dp))
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
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { if (quantity > 1) quantity-- },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "Disminuir cantidad",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = quantity.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(40.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            IconButton(
                                onClick = { if (quantity < 99) quantity++ },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Aumentar cantidad",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Modifier groups
                modifierGroups.forEach { group ->
                    item {
                        // Calculate current selection count for this group
                        val currentCount = countSelectionsForGroup(group.id, group.type)
                        val minRequired = group.effectiveMinSelections
                        val maxAllowed = group.effectiveMaxSelections

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Group name with selection requirements
                            val selectionHint = when {
                                minRequired > 0 && maxAllowed != null && minRequired == maxAllowed ->
                                    "(Elige $minRequired)"
                                minRequired > 0 && maxAllowed != null ->
                                    "(Elige $minRequired-$maxAllowed)"
                                minRequired > 0 ->
                                    "(Mínimo $minRequired)"
                                maxAllowed != null ->
                                    "(Máximo $maxAllowed)"
                                else -> ""
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = group.name + if (minRequired > 0) " *" else "",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                if (selectionHint.isNotEmpty()) {
                                    Text(
                                        text = "$selectionHint $currentCount/${maxAllowed ?: "∞"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (currentCount < minRequired)
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

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
                                                            fontSize = 12.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (modifier.priceAdjustment != BigDecimal.ZERO) {
                                                            Text(
                                                                text = modifier.formattedPrice,
                                                                fontSize = 10.sp,
                                                                color = MaterialTheme.colorScheme.primary,
                                                                maxLines = 1
                                                            )
                                                        }
                                                    }
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            )
                                        }
                                    }
                                }
                                ModifierType.MULTIPLE_CHOICE -> {
                                    // Chips for multiple choice (checkbox behavior)
                                    // Check if max selections reached
                                    val canSelectMore = maxAllowed?.let { currentCount < it } ?: true

                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        group.modifiers.forEach { modifier ->
                                            val isSelected = selectedModifiers.values.any { it.id == modifier.id }
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    selectedModifiers = selectedModifiers.toMutableMap().apply {
                                                        val key = "${group.id}_${modifier.id}"
                                                        if (containsKey(key)) {
                                                            remove(key)
                                                        } else if (canSelectMore) {
                                                            // Only add if under max limit
                                                            put(key, modifier)
                                                        }
                                                    }
                                                },
                                                enabled = isSelected || canSelectMore,  // Disable if max reached and not selected
                                                label = {
                                                    Column {
                                                        Text(
                                                            text = modifier.name,
                                                            fontSize = 12.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (modifier.priceAdjustment != BigDecimal.ZERO) {
                                                            Text(
                                                                text = modifier.formattedPrice,
                                                                fontSize = 10.sp,
                                                                color = MaterialTheme.colorScheme.primary,
                                                                maxLines = 1
                                                            )
                                                        }
                                                    }
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                    disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
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
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Ej: Sin aceitunas, para llevar") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus(force = true)
                                }
                            ),
                            trailingIcon = {
                                if (notes.isNotEmpty()) {
                                    IconButton(onClick = { notes = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Limpiar notas"
                                        )
                                    }
                                }
                            },
                            maxLines = 2,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Bottom spacing for scroll
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }  // Close LazyColumn

            // STICKY FOOTER - Total y botón Agregar (siempre visible)
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ⚠️ Dynamic warning message when selection requirements not met
                if (groupsNotMeetingMin.isNotEmpty()) {
                    val warningText = if (groupsNotMeetingMin.size == 1) {
                        val group = groupsNotMeetingMin.first()
                        val current = countSelectionsForGroup(group.id, group.type)
                        val needed = group.effectiveMinSelections - current
                        "⚠️ Faltan $needed en ${group.name}"
                    } else {
                        "⚠️ Selecciona modificadores obligatorios (*)"
                    }
                    Text(
                        text = warningText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                // Total price display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total:",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "$$totalPrice",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Botón Agregar
                Button(
                    onClick = {
                        if (allSelectionRulesMet) {
                            focusManager.clearFocus(force = true)
                            onAddToCart(quantity, selectedModifiers.values.toList(), notes)
                            onDismiss()
                        }
                    },
                    enabled = allSelectionRulesMet,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Text(
                        text = "Agregar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }  // Close Column
    }  // Close Box
}

// ============================================================================
// Previews
// ============================================================================

@Preview(name = "Pizza with Modifiers", showBackground = true, heightDp = 800, widthDp = 400)
@Composable
private fun ProductSelectorPizzaPreview() {
    AvoqadoTheme {
        val product = MockProducts.getProductById("prod_comida_1")!!
        val modifiers = MockProducts.getModifiersForProduct(product.id)

        ProductSelectorBottomSheet(
            visible = true,
            product = product,
            modifierGroups = modifiers,
            onDismiss = {},
            onAddToCart = { _, _, _ -> }
        )
    }
}

@Preview(name = "Burger with Término", showBackground = true, heightDp = 800, widthDp = 400)
@Composable
private fun ProductSelectorBurgerPreview() {
    AvoqadoTheme {
        val product = MockProducts.getProductById("prod_comida_2")!!
        val modifiers = MockProducts.getModifiersForProduct(product.id)

        ProductSelectorBottomSheet(
            visible = true,
            product = product,
            modifierGroups = modifiers,
            onDismiss = {},
            onAddToCart = { _, _, _ -> }
        )
    }
}

@Preview(name = "Bebida No Modifiers", showBackground = true, heightDp = 600, widthDp = 400)
@Composable
private fun ProductSelectorNoModifiersPreview() {
    AvoqadoTheme {
        val product = MockProducts.getProductById("prod_bebida_2")!!  // Agua Natural (no modifiers)
        val modifiers = MockProducts.getModifiersForProduct(product.id)

        ProductSelectorBottomSheet(
            visible = true,
            product = product,
            modifierGroups = modifiers,
            onDismiss = {},
            onAddToCart = { _, _, _ -> }
        )
    }
}
