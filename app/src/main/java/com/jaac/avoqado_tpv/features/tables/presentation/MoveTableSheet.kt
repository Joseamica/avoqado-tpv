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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable

/**
 * "Mover mesa" (Fase 1, `MOVE_ORDER`) — grupos cambian de mesa constantemente;
 * sin esto la cuenta queda varada en la mesa equivocada. Un solo tap sobre la
 * mesa destino confirma el movimiento de inmediato (mismo lenguaje que
 * [TableSheet] al elegir una mesa libre) — no hay nada más que decidir, así
 * que un botón de confirmación extra solo agregaría un paso.
 *
 * 🔴 [unavailable] es DISTINTO de `tables.isEmpty()` (mismo patrón que
 * `TableOrderLoadFailedState` en `TableOrderScreen.kt`, `7df5819`): si
 * [TableOrderViewModel.loadAvailableTargetTables][com.jaac.avoqado_tpv.features.tables.presentation.TableOrderViewModel.loadAvailableTargetTables]
 * no pudo verificar qué mesas están libres (sin red, timeout), este sheet
 * NUNCA debe decir "No hay mesas libres" — eso es un hecho plausible pero
 * falso que un mesero sin señal lee como "el salón está lleno". Nunca se
 * sirve una lista vieja aquí — "libre" caduca en minutos y confirmar un
 * movimiento contra un dato caducado es un choque real con otro dispositivo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveTableSheet(
    tables: List<DiningTable>,
    isLoading: Boolean,
    isMoving: Boolean,
    unavailable: Boolean,
    onDismiss: () -> Unit,
    onSelect: (DiningTable) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4).padding(bottom = Spacing.Space6)) {
            Text(text = "Mover mesa", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.Space1))
            Text(
                text = "Elige la mesa libre a la que se mueve esta cuenta.",
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

                unavailable -> {
                    PickerUnavailableState(
                        message = "No se pudo verificar qué mesas están libres — revisa tu conexión.",
                        onRetry = onRetry,
                    )
                }

                tables.isEmpty() -> {
                    Text(
                        text = "No hay mesas libres en este momento.",
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
                            MoveTargetRow(table = table, enabled = !isMoving, onClick = { onSelect(table) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveTargetRow(table: DiningTable, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4, vertical = Spacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.TableRestaurant,
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
                table.areaName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "${table.capacity}p",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "No se pudo cargar" dentro de un picker — mismo lenguaje visual que
 * `TableOrderLoadFailedState` (`TableOrderScreen.kt`, `7df5819`): ícono +
 * mensaje + "Reintentar". Compartido por [MoveTableSheet] y
 * [MergeOrdersSheet][com.jaac.avoqado_tpv.features.tables.presentation.MergeOrdersSheet] —
 * ambos pickers de mesa que NUNCA sirven una lista caducada (ver KDoc de
 * cada uno). Visibilidad de paquete a propósito: mismo archivo de features,
 * evita duplicar el composable byte a byte.
 */
@Composable
internal fun PickerUnavailableState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth().padding(vertical = Spacing.Space4)) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(Spacing.Space2))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Space3))
        TextButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.padding(start = Spacing.Space1))
            Text("Reintentar")
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 480, heightDp = 640, name = "NEXGO 480×480")
@Composable
private fun MoveTableSheetPreview() {
    AvoqadoTheme {
        MoveTableSheet(
            tables = listOf(
                DiningTable(id = "2", number = "2", capacity = 4, areaName = "Terraza", status = "AVAILABLE"),
                DiningTable(id = "3", number = "3", capacity = 6, areaName = "Salón", status = "AVAILABLE"),
            ),
            isLoading = false,
            isMoving = false,
            unavailable = false,
            onDismiss = {},
            onSelect = {},
            onRetry = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — sin conexión")
@Composable
private fun MoveTableSheetUnavailablePreview() {
    AvoqadoTheme {
        MoveTableSheet(
            tables = emptyList(),
            isLoading = false,
            isMoving = false,
            unavailable = true,
            onDismiss = {},
            onSelect = {},
            onRetry = {},
        )
    }
}
