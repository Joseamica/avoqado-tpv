package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * "Dar de cortesía" (Fase 1, `COMP_ORDER`) — daily service recovery cuando un
 * plato sale mal. Dos modos que la UI hace explícitos porque el server los
 * trata muy distinto (ver KDoc de
 * [TablesRepository.compOrder][com.jaac.avoqado_tpv.features.tables.data.TablesRepository.compOrder]):
 *
 * - **"Artículo específico"** — cortesía de UN plato ya enviado. Regla dura
 *   `offline-first-y-hub-lan.md` §5: SIEMPRE online, nunca se encola. Si no
 *   hay red, el botón se deshabilita CON la razón visible en vez de fallar
 *   en silencio al tocar.
 * - **"Toda la cuenta"** — sí tiene equivalente offline (`COMP_ORDER` intent).
 *
 * Razones espejo de `COMP_REASONS`
 * (`avoqado-server/src/services/mobile/comp-item.mobile.service.ts:22-29`) +
 * "Otro" con texto libre para no bloquear un caso que el catálogo no cubrió.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompOrderSheet(
    check: OrderDetail?,
    isComping: Boolean,
    isOnline: Boolean,
    onDismiss: () -> Unit,
    onConfirmWhole: (reason: String) -> Unit,
    onConfirmItems: (itemIds: Set<String>, reason: String) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    var wholeOrder by remember(check?.id) { mutableStateOf(true) }
    var selectedReason by remember { mutableStateOf(COMP_REASONS.first()) }
    var customReason by remember { mutableStateOf("") }
    var selectedItems by remember(check?.id) { mutableStateOf(emptySet<String>()) }

    val compableItems = check?.items?.filter { !it.isCortesia } ?: emptyList()
    val reason = if (selectedReason == OTHER_REASON) customReason.trim() else selectedReason
    val itemCompDisabled = !wholeOrder && !isOnline

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4).padding(bottom = Spacing.Space6)) {
            Text(text = "Cortesía", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.Space1))
            Text(
                text = "Un plato salió mal, un reclamo, cortesía de la casa — elige qué se perdona.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.Space4))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)) {
                FilterChip(selected = wholeOrder, onClick = { wholeOrder = true }, label = { Text("Toda la cuenta") })
                FilterChip(selected = !wholeOrder, onClick = { wholeOrder = false }, label = { Text("Artículos específicos") })
            }
            if (itemCompDisabled) {
                Spacer(Modifier.height(Spacing.Space1))
                Text(
                    text = "Cortesía de artículos ya enviados necesita conexión.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(Spacing.Space3))

            if (!wholeOrder) {
                if (compableItems.isEmpty()) {
                    Text(
                        text = "No hay artículos por dar de cortesía.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                        contentPadding = PaddingValues(vertical = Spacing.Space2),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Space2),
                    ) {
                        items(compableItems, key = { it.id }) { item ->
                            CompItemRow(
                                item = item,
                                selected = item.id in selectedItems,
                                onToggle = { selectedItems = if (item.id in selectedItems) selectedItems - item.id else selectedItems + item.id },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.Space3))
            }

            Text(text = "Razón", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.Space2))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2),
            ) {
                (COMP_REASONS + OTHER_REASON).forEach { r ->
                    FilterChip(
                        selected = selectedReason == r,
                        onClick = { selectedReason = r },
                        label = { Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            if (selectedReason == OTHER_REASON) {
                Spacer(Modifier.height(Spacing.Space2))
                OutlinedTextField(
                    value = customReason,
                    onValueChange = { customReason = it },
                    label = { Text("Especifica la razón") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(Spacing.Space4))
            val confirmEnabled = reason.isNotBlank() && !isComping &&
                if (wholeOrder) compableItems.isNotEmpty() else (selectedItems.isNotEmpty() && !itemCompDisabled)
            AvoqadoButton(
                text = if (wholeOrder) {
                    "Dar de cortesía toda la cuenta"
                } else if (selectedItems.isNotEmpty()) {
                    "Dar de cortesía ${selectedItems.size} artículo${if (selectedItems.size == 1) "" else "s"}"
                } else {
                    "Dar de cortesía"
                },
                onClick = { if (wholeOrder) onConfirmWhole(reason) else onConfirmItems(selectedItems, reason) },
                enabled = confirmEnabled,
                loading = isComping,
                fullWidth = true,
            )
        }
    }
}

@Composable
private fun CompItemRow(item: OrderDetailItem, selected: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space3, vertical = Spacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Text(
                text = "${item.quantity}× ${item.productName ?: "Artículo"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = moneyDisplay(item.total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

private fun moneyDisplay(amount: BigDecimal): String = "$${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()}"

/** Espejo de `COMP_REASONS` — `avoqado-server/src/services/mobile/comp-item.mobile.service.ts:22-29`. */
private val COMP_REASONS = listOf(
    "Error de entrada",
    "El cliente cambió de parecer",
    "Reclamo del cliente",
    "Amigos y familia",
    "Descuento de empleado",
    "Especial del administrador",
)
private const val OTHER_REASON = "Otro"

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 480, heightDp = 720, name = "NEXGO 480×480")
@Composable
private fun CompOrderSheetPreview() {
    AvoqadoTheme {
        CompOrderSheet(
            check = OrderDetail(
                id = "order-1",
                items = listOf(
                    OrderDetailItem(id = "i1", productName = "Café americano", quantity = 2, total = BigDecimal("90.00")),
                    OrderDetailItem(id = "i2", productName = "Croissant", quantity = 1, total = BigDecimal("55.00")),
                ),
            ),
            isComping = false,
            isOnline = true,
            onDismiss = {},
            onConfirmWhole = {},
            onConfirmItems = { _, _ -> },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — sin red")
@Composable
private fun CompOrderSheetOfflinePreview() {
    AvoqadoTheme {
        CompOrderSheet(
            check = OrderDetail(
                id = "order-1",
                items = listOf(OrderDetailItem(id = "i1", productName = "Café americano", quantity = 2, total = BigDecimal("90.00"))),
            ),
            isComping = false,
            isOnline = false,
            onDismiss = {},
            onConfirmWhole = {},
            onConfirmItems = { _, _ -> },
        )
    }
}
