package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.checkout.domain.model.SelectedModifier
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import java.math.BigDecimal

/**
 * Bottom-sheet content for choosing modifiers on a product (Image 13 pattern).
 *
 * - Top: price (big bold) + product name + category subtitle + close X
 * - For each [ModifierGroup]: title + "Requerido X/Y" badge (red when unmet),
 *   then a list of options. SINGLE_CHOICE uses radio buttons, MULTIPLE_CHOICE
 *   uses checkboxes. Max-selections enforced silently (extra taps are no-ops
 *   when the cap is hit on MULTIPLE_CHOICE; SINGLE_CHOICE always replaces).
 * - Bottom: "Agregar al carrito $X.XX" — disabled until all required groups
 *   have their `effectiveMinSelections` met.
 *
 * The price ticks up as the operator selects priced modifiers so they see
 * the running total before committing.
 */
@Composable
fun ProductDetailSheet(
    product: Product,
    onClose: () -> Unit,
    onAddToCart: (List<SelectedModifier>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Map of group id → set of selected modifier ids. We track by ID so a
    // group's selection survives if its modifier list reorders.
    var selections by remember(product.id) {
        mutableStateOf<Map<String, Set<String>>>(emptyMap())
    }

    val basePriceCents = product.price.multiply(BigDecimal(100)).toInt()
    val modifierTotalCents = product.modifierGroups.sumOf { group ->
        val ids = selections[group.id].orEmpty()
        group.modifiers
            .filter { it.id in ids }
            .sumOf { it.priceAdjustment.multiply(BigDecimal(100)).toInt() }
    }
    val totalCents = basePriceCents + modifierTotalCents
    val formattedTotal = "$${String.format("%.2f", totalCents / 100.0)}"

    // Groups sorted by displayOrder — same order the UI renders them.
    val orderedGroups = remember(product.id) {
        product.modifierGroups.sortedBy { it.displayOrder }
    }

    // First group whose required-min hasn't been met. Powers both the
    // disabled-state button label and the auto-scroll behavior.
    val firstUnmetIndex = orderedGroups.indexOfFirst { group ->
        (selections[group.id]?.size ?: 0) < group.effectiveMinSelections
    }
    val firstUnmet: ModifierGroup? = orderedGroups.getOrNull(firstUnmetIndex)
    val canAdd = firstUnmet == null

    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 16.dp),
    ) {
        // Top: close + price/name/category header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formattedTotal,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = product.categoryName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Cerrar")
            }
        }

        HorizontalDivider()

        // Scrollable groups list. LazyColumn lets us scroll-to-index when the
        // operator taps the "Selecciona X" hint button below.
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            itemsIndexed(items = orderedGroups, key = { _, g -> g.id }) { _, group ->
                ModifierGroupSection(
                    group = group,
                    selectedIds = selections[group.id].orEmpty(),
                    onToggle = { modifierId ->
                        selections = selections.toggle(group, modifierId)
                    },
                )
            }
        }

        // Footer — smart button.
        // - When all required groups satisfied: filled "Agregar al carrito $X.XX"
        // - When not: outlined "Selecciona [groupName] ↓" that scrolls to it.
        //   Keeping the button always enabled lets the operator tap-to-jump
        //   instead of staring at a grayed-out button wondering what's wrong.
        Button(
            onClick = {
                if (canAdd) {
                    onAddToCart(buildSelectedList(product, selections))
                } else if (firstUnmetIndex >= 0) {
                    scrollScope.launch { listState.animateScrollToItem(firstUnmetIndex) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = if (canAdd) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                )
            } else {
                // Visible-but-secondary state: keeps the button tappable and
                // signals "you have work left" via warm error tint.
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            },
        ) {
            if (canAdd) {
                Text(
                    text = "Agregar al carrito $formattedTotal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Selecciona ${firstUnmet?.name.orEmpty()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModifierGroupSection(
    group: ModifierGroup,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (group.effectiveMinSelections > 0) {
                val have = selectedIds.size
                val need = group.effectiveMinSelections
                val unmet = have < need
                Text(
                    text = "Requerido $have/$need",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (unmet) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        group.modifiers
            .sortedBy { it.displayOrder }
            .forEach { mod ->
                ModifierRow(
                    modifier = mod,
                    selected = mod.id in selectedIds,
                    type = group.type,
                    onClick = { onToggle(mod.id) },
                )
            }
    }
}

@Composable
private fun ModifierRow(
    modifier: ProductModifier,
    selected: Boolean,
    type: ModifierType,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (type) {
            ModifierType.SINGLE_CHOICE -> RadioButton(selected = selected, onClick = onClick)
            ModifierType.MULTIPLE_CHOICE -> Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = modifier.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (modifier.priceAdjustment != BigDecimal.ZERO) {
            Text(
                text = modifier.formattedPrice,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Selection helpers
// ─────────────────────────────────────────────────────────────────────────

private fun Map<String, Set<String>>.toggle(
    group: ModifierGroup,
    modifierId: String,
): Map<String, Set<String>> {
    val current = this[group.id].orEmpty()
    val next = when (group.type) {
        ModifierType.SINGLE_CHOICE -> {
            // Radio behavior: tapping replaces the selection. Tapping the
            // already-selected one clears it unless the group is required.
            if (modifierId in current) {
                if (group.effectiveMinSelections > 0) current else emptySet()
            } else {
                setOf(modifierId)
            }
        }
        ModifierType.MULTIPLE_CHOICE -> {
            if (modifierId in current) {
                current - modifierId
            } else {
                val max = group.effectiveMaxSelections
                if (max != null && current.size >= max) current else current + modifierId
            }
        }
    }
    return this.toMutableMap().also { it[group.id] = next }
}

private fun buildSelectedList(
    product: Product,
    selections: Map<String, Set<String>>,
): List<SelectedModifier> = product.modifierGroups.flatMap { group ->
    val ids = selections[group.id].orEmpty()
    group.modifiers
        .filter { it.id in ids }
        .map { mod ->
            SelectedModifier(
                groupId = group.id,
                groupName = group.name,
                modifierId = mod.id,
                modifierName = mod.name,
                priceInCents = mod.priceAdjustment.multiply(BigDecimal(100)).toInt(),
            )
        }
}

// ─────────────────────────────────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────────────────────────────────

@Preview(name = "Modifier sheet — hamburguesa", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun ProductDetailHamburguesaPreview() {
    AvoqadoTheme {
        ProductDetailSheet(
            product = Product(
                id = "p-1",
                name = "Hamburguesa Clásica",
                sku = "h-001",
                price = BigDecimal("144.50"),
                categoryId = "c-1",
                categoryName = "Hamburguesas",
                description = null,
                emoji = "",
                imageUrl = null,
                available = true,
                modifierGroups = listOf(
                    ModifierGroup(
                        id = "g1",
                        name = "requerido!",
                        type = ModifierType.MULTIPLE_CHOICE,
                        required = true,
                        minSelections = 2,
                        maxSelections = 2,
                        modifiers = listOf(
                            ProductModifier("m1", "test1", BigDecimal("1.00"), ModifierType.MULTIPLE_CHOICE),
                            ProductModifier("m2", "test2", BigDecimal("2.00"), ModifierType.MULTIPLE_CHOICE),
                            ProductModifier("m3", "test3", BigDecimal("3.00"), ModifierType.MULTIPLE_CHOICE),
                        ),
                    ),
                    ModifierGroup(
                        id = "g2",
                        name = "Aderezos",
                        type = ModifierType.MULTIPLE_CHOICE,
                        modifiers = listOf(
                            ProductModifier("m4", "BBQ", BigDecimal("12.50"), ModifierType.MULTIPLE_CHOICE),
                            ProductModifier("m5", "Chipotle Mayo", BigDecimal("15.00"), ModifierType.MULTIPLE_CHOICE),
                            ProductModifier("m6", "Mostaza", BigDecimal.ZERO, ModifierType.MULTIPLE_CHOICE),
                        ),
                    ),
                ),
            ),
            onClose = {},
            onAddToCart = {},
        )
    }
}
