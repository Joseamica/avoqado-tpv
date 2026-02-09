package com.jaac.avoqado_tpv.features.kiosk.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskProduct
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import java.math.BigDecimal
import com.jaac.avoqado_tpv.core.util.rememberCurrencyFormat
import java.text.NumberFormat

/**
 * KioskModifierDialog - Modifier Selection for Kiosk Mode
 *
 * Modal dialog optimized for PAX A910S (5" screen) that allows customers
 * to select product modifiers before adding to cart.
 *
 * Features:
 * - Shows product name and base price
 * - Lists modifier groups with FilterChips
 * - Required groups marked with "*"
 * - Real-time total price calculation
 * - Warning message when required modifiers are missing
 * - "Agregar" button disabled until all required modifiers selected
 *
 * Layout (optimized for 5" PAX):
 * ```
 * ┌─────────────────────────────────┐
 * │  🍔 Hamburguesa Clásica         │  ← Header con nombre
 * │  Base: $89.00                   │
 * ├─────────────────────────────────┤
 * │  Término *                      │  ← Grupo required
 * │  [Rojo] [Medio] [Bien cocido]   │  ← FilterChips
 * │                                 │
 * │  Extras                         │  ← Grupo opcional
 * │  [Queso +$15] [Tocino +$20]     │
 * ├─────────────────────────────────┤
 * │  ⚠️ Selecciona modificadores *  │  ← Warning si falta
 * │  Total: $104.00                 │  ← Precio calculado
 * │  [Cancelar]    [Agregar]        │  ← Acciones
 * └─────────────────────────────────┘
 * ```
 *
 * @param product The KioskProduct with modifier groups
 * @param onDismiss Called when user cancels or taps outside
 * @param onConfirm Called with selected modifiers and total price when user confirms
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KioskModifierDialog(
    product: KioskProduct,
    onDismiss: () -> Unit,
    onConfirm: (selectedModifiers: List<ProductModifier>, totalPrice: BigDecimal) -> Unit
) {
    // Currency formatter for Mexican pesos
    val currencyFormat = rememberCurrencyFormat()

    // State: Map of selections
    // For SINGLE_CHOICE: key = groupId, value = selected modifier
    // For MULTIPLE_CHOICE: key = groupId_modifierId, value = selected modifier
    var selectedModifiers by remember { mutableStateOf<Map<String, ProductModifier>>(emptyMap()) }

    // Calculate total price (base + modifier adjustments)
    val modifiersTotal = selectedModifiers.values.sumOf { it.priceAdjustment }
    val totalPrice = product.price + modifiersTotal

    // Helper function: Count selections for a group
    fun countSelectionsForGroup(groupId: String, type: ModifierType): Int {
        return when (type) {
            ModifierType.SINGLE_CHOICE -> if (selectedModifiers.containsKey(groupId)) 1 else 0
            ModifierType.MULTIPLE_CHOICE -> selectedModifiers.keys.count { it.startsWith("${groupId}_") }
        }
    }

    // Validation: Check if all groups meet their min/max selection requirements
    val allSelectionRulesMet = product.modifierGroups.all { group ->
        val count = countSelectionsForGroup(group.id, group.type)
        val minMet = count >= group.effectiveMinSelections
        val maxMet = group.effectiveMaxSelections?.let { count <= it } ?: true
        minMet && maxMet
    }

    // Find groups that don't meet requirements (for warning message)
    val groupsNotMeetingMin = product.modifierGroups.filter { group ->
        val count = countSelectionsForGroup(group.id, group.type)
        count < group.effectiveMinSelections
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,  // Prevent accidental dismiss on kiosk
            usePlatformDefaultWidth = false  // Allow custom width for PAX screen
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)  // Nearly full width for 5" screen
                .heightIn(max = 600.dp),  // Max height to ensure buttons visible
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header: Product name and base price
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Base: ${currencyFormat.format(product.price)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable modifier groups
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    product.modifierGroups.forEach { group ->
                        item(key = group.id) {
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
                                        color = MaterialTheme.colorScheme.onSurface
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

                                // Modifier chips
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (group.type) {
                                        ModifierType.SINGLE_CHOICE -> {
                                            // Radio-like behavior: only one can be selected
                                            group.modifiers.forEach { modifier ->
                                                val isSelected = selectedModifiers[group.id]?.id == modifier.id

                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = {
                                                        selectedModifiers = selectedModifiers.toMutableMap().apply {
                                                            put(group.id, modifier)
                                                        }
                                                    },
                                                    label = {
                                                        ModifierChipContent(
                                                            name = modifier.name,
                                                            priceAdjustment = modifier.priceAdjustment,
                                                            currencyFormat = currencyFormat
                                                        )
                                                    },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                    ),
                                                    modifier = Modifier.height(48.dp)  // Touch-friendly height
                                                )
                                            }
                                        }
                                        ModifierType.MULTIPLE_CHOICE -> {
                                            // Checkbox-like behavior: multiple can be selected (respecting maxSelections)
                                            val canSelectMore = maxAllowed?.let { currentCount < it } ?: true

                                            group.modifiers.forEach { modifier ->
                                                val key = "${group.id}_${modifier.id}"
                                                val isSelected = selectedModifiers.containsKey(key)
                                                // Disable chip if not selected AND max reached
                                                val isEnabled = isSelected || canSelectMore

                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = {
                                                        if (isEnabled) {
                                                            selectedModifiers = selectedModifiers.toMutableMap().apply {
                                                                if (isSelected) {
                                                                    remove(key)
                                                                } else if (canSelectMore) {
                                                                    put(key, modifier)
                                                                }
                                                            }
                                                        }
                                                    },
                                                    enabled = isEnabled,
                                                    label = {
                                                        ModifierChipContent(
                                                            name = modifier.name,
                                                            priceAdjustment = modifier.priceAdjustment,
                                                            currencyFormat = currencyFormat
                                                        )
                                                    },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    ),
                                                    modifier = Modifier.height(48.dp)  // Touch-friendly height
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Footer: Warning, Total, and Buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Warning message when selection rules are not met
                    if (groupsNotMeetingMin.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFF3CD)  // Light yellow warning
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFF856404)  // Amber warning color
                                )
                                val warningText = if (groupsNotMeetingMin.size == 1) {
                                    val g = groupsNotMeetingMin.first()
                                    val current = countSelectionsForGroup(g.id, g.type)
                                    "Faltan ${g.effectiveMinSelections - current} en ${g.name}"
                                } else {
                                    "Completa las selecciones obligatorias (*)"
                                }
                                Text(
                                    text = warningText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF856404),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
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
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = currencyFormat.format(totalPrice),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Cancel button
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Cancelar",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Add button (disabled until all selection rules are met)
                        Button(
                            onClick = {
                                if (allSelectionRulesMet) {
                                    onConfirm(selectedModifiers.values.toList(), totalPrice)
                                }
                            },
                            enabled = allSelectionRulesMet,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
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
                }
            }
        }
    }
}

/**
 * Content for modifier chip label
 * Shows modifier name and price adjustment if applicable
 */
@Composable
private fun ModifierChipContent(
    name: String,
    priceAdjustment: BigDecimal,
    currencyFormat: NumberFormat
) {
    Column {
        Text(
            text = name,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (priceAdjustment != BigDecimal.ZERO) {
            val priceText = if (priceAdjustment > BigDecimal.ZERO) {
                "+${currencyFormat.format(priceAdjustment)}"
            } else {
                currencyFormat.format(priceAdjustment)
            }
            Text(
                text = priceText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}
