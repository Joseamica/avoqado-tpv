package com.jaac.avoqado_tpv.features.tables.presentation

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
 * "Separar cuenta" (Fase 1, `SPLIT_ORDER`) — un grupo la pide primero: elegir
 * QUÉ se mueve a la cuenta nueva tiene que ser obvio y rápido frente a
 * clientes impacientes (riesgo explícito del founder). Checklist plano de lo
 * YA enviado a cocina (`check.items`, sin `paidItemIds` — no se puede separar
 * algo que ya se cobró) + un solo botón con el conteo y el monto exacto de lo
 * seleccionado. Todo-o-nada es garantía del SERVER (ver KDoc de
 * [TablesRepository.splitOrder][com.jaac.avoqado_tpv.features.tables.data.TablesRepository.splitOrder]) —
 * esta hoja solo tiene que mandar la selección completa en un solo tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitOrderSheet(
    check: OrderDetail?,
    isSplitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    var selected by remember(check?.id) { mutableStateOf(emptySet<String>()) }
    val payableItems = check?.items?.filter { it.id !in check.paidItemIds } ?: emptyList()
    val selectedTotal = payableItems.filter { it.id in selected }.fold(BigDecimal.ZERO) { acc, item -> acc + item.total }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4).padding(bottom = Spacing.Space6)) {
            Text(text = "Separar cuenta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.Space1))
            Text(
                text = "Elige qué artículos se mueven a una cuenta nueva.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.Space4))

            if (payableItems.isEmpty()) {
                Text(
                    text = "No hay artículos por separar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = Spacing.Space2),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Space2),
                ) {
                    items(payableItems, key = { it.id }) { item ->
                        SplitItemRow(
                            item = item,
                            selected = item.id in selected,
                            onToggle = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.Space4))
            AvoqadoButton(
                text = if (selected.isNotEmpty()) "Separar ${selected.size} artículo${if (selected.size == 1) "" else "s"} · ${moneyDisplay(selectedTotal)}" else "Separar cuenta",
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty() && !isSplitting && selected.size < payableItems.size,
                loading = isSplitting,
                fullWidth = true,
            )
            if (selected.isNotEmpty() && selected.size == payableItems.size) {
                Spacer(Modifier.height(Spacing.Space1))
                Text(
                    text = "Selecciona menos de todos los artículos — para mover la cuenta completa, usa \"Mover mesa\".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SplitItemRow(item: OrderDetailItem, selected: Boolean, onToggle: () -> Unit) {
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

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 480, heightDp = 640, name = "NEXGO 480×480")
@Composable
private fun SplitOrderSheetPreview() {
    AvoqadoTheme {
        SplitOrderSheet(
            check = OrderDetail(
                id = "order-1",
                items = listOf(
                    OrderDetailItem(id = "i1", productName = "Café americano", quantity = 2, total = BigDecimal("90.00")),
                    OrderDetailItem(id = "i2", productName = "Croissant", quantity = 1, total = BigDecimal("55.00")),
                    OrderDetailItem(id = "i3", productName = "Agua mineral", quantity = 2, total = BigDecimal("60.00")),
                ),
            ),
            isSplitting = false,
            onDismiss = {},
            onConfirm = {},
        )
    }
}
