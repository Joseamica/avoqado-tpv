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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.OpenCheckSummary

/**
 * "Fusionar cuentas" (Fase 2, `MERGE_ORDERS`) — un grupo crece o dos mesas se
 * juntan; elegir la mesa CORRECTA de la que viene el dinero tiene que ser
 * obvio, igual riesgo que [SplitOrderSheet] ("si lo hace mal o feo puede
 * enojar al cliente"). A diferencia de [MoveTableSheet] (mesas LIBRES), aquí
 * la lista son mesas OCUPADAS — cada fila muestra mesero + total de ESA
 * cuenta para que el tap sea una elección informada, no una lista de números
 * sin contexto (el mismo dinero que se está por fusionar).
 *
 * Un solo tap confirma — mismo lenguaje que [MoveTableSheet]: la fila YA es
 * el preview (mesa + mesero + monto exacto que se va a mover), un botón de
 * confirmación aparte solo agregaría un paso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeOrdersSheet(
    tables: List<DiningTable>,
    isLoading: Boolean,
    isMerging: Boolean,
    onDismiss: () -> Unit,
    onSelect: (DiningTable) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4).padding(bottom = Spacing.Space6)) {
            Text(text = "Fusionar cuentas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.Space1))
            Text(
                text = "Elige la mesa cuya cuenta se junta con esta. La cuenta elegida se cierra y su mesa queda libre.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.Space4))

            when {
                isLoading -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.height(32.dp))
                    }
                }

                tables.isEmpty() -> {
                    Text(
                        text = "No hay otras mesas con cuenta abierta ahora mismo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        contentPadding = PaddingValues(vertical = Spacing.Space2),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Space2),
                    ) {
                        items(tables, key = { it.id }) { table ->
                            MergeCandidateRow(table = table, enabled = !isMerging, onClick = { onSelect(table) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MergeCandidateRow(table: DiningTable, enabled: Boolean, onClick: () -> Unit) {
    val check: OpenCheckSummary? = table.primaryCheck
    Card(
        onClick = onClick,
        enabled = enabled && check != null,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4, vertical = Spacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.CallMerge,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(20.dp),
            )
            Spacer(Modifier.padding(start = Spacing.Space2))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Mesa ${table.number}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    table.areaName?.takeIf { it.isNotBlank() },
                    check?.waiterName?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = check?.totalDisplay ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 480, heightDp = 640, name = "NEXGO 480×480")
@Composable
private fun MergeOrdersSheetPreview() {
    AvoqadoTheme {
        MergeOrdersSheet(
            tables = listOf(
                DiningTable(
                    id = "2", number = "2", capacity = 4, areaName = "Terraza", status = "OCCUPIED",
                    currentOrder = null,
                    openOrders = listOf(OpenCheckSummary(id = "o1", orderNumber = "A-201", waiterName = "Fátima Flores", total = java.math.BigDecimal("340.00"))),
                ),
                DiningTable(
                    id = "3", number = "7", capacity = 6, areaName = "Salón", status = "OCCUPIED",
                    currentOrder = null,
                    openOrders = listOf(OpenCheckSummary(id = "o2", orderNumber = "A-202", waiterName = "Diego Ruiz", total = java.math.BigDecimal("120.50"))),
                ),
            ),
            isLoading = false,
            isMerging = false,
            onDismiss = {},
            onSelect = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — sin candidatos")
@Composable
private fun MergeOrdersSheetEmptyPreview() {
    AvoqadoTheme {
        MergeOrdersSheet(
            tables = emptyList(),
            isLoading = false,
            isMerging = false,
            onDismiss = {},
            onSelect = {},
        )
    }
}
