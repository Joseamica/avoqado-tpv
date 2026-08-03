package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoInlineLoading
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cuarentena visible (Plan C, Task 9) — bottom sheet que lista las acciones
 * de Mesas que el server RECHAZÓ de forma permanente (`REJECTED`) al
 * reconectar, con una razón en español que un mesero puede accionar y
 * "Marcar resuelta" para descartarla. Es la superficie que hace visible lo
 * que antes solo vivía en Room sin que nadie lo mirara.
 *
 * Se abre desde `TablesScreen` vía [QuarantineBadgeButton] — nunca sola:
 * un rechazo sin badge visible es un rechazo que nadie sabe buscar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuarantineSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuarantineViewModel = hiltViewModel(),
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Space4)
                .padding(bottom = Spacing.Space6),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.avoqadoColors.statusError,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.padding(start = Spacing.Space2))
                Text(
                    text = "Acciones rechazadas",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(Spacing.Space1))
            Text(
                text = "El servidor no pudo aplicar estas acciones hechas sin conexión. Nada se perdió — revísalas y márcalas como resueltas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.Space4))

            when {
                state.isLoading && state.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Space6),
                        contentAlignment = Alignment.Center,
                    ) {
                        AvoqadoInlineLoading()
                    }
                }

                state.items.isEmpty() -> {
                    Text(
                        text = "No hay acciones pendientes de revisión.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.Space6),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Space2),
                    ) {
                        items(state.items, key = { it.id }) { item ->
                            QuarantineItemCard(item = item, onDismiss = { viewModel.dismiss(item.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuarantineItemCard(item: QuarantineItem, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.avoqadoColors.statusError.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(Spacing.Space3),
        verticalArrangement = Arrangement.spacedBy(Spacing.Space1),
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        item.message?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = item.resolutionHint,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = formatQuarantineTime(item.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text("Marcar resuelta")
            }
        }
    }
}

private fun formatQuarantineTime(epochMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale("es", "MX")).format(Date(epochMs))

/**
 * Botón con badge del topbar de `TablesScreen` — solo visible con `count >
 * 0`, para que la superficie no compita por espacio en pantallas chicas
 * (NEXGO 480×480) cuando no hay nada que revisar. Ámbar/rojo (nunca verde):
 * requiere atención, pero nada se perdió — ver KDoc de [QuarantineSheet].
 */
@Composable
fun QuarantineBadgeButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    BadgedBox(
        modifier = modifier,
        badge = {
            Badge(containerColor = MaterialTheme.colorScheme.error) {
                Text(if (count > 99) "99+" else "$count")
            }
        },
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "$count acción${if (count == 1) "" else "es"} rechazada${if (count == 1) "" else "s"} — toca para revisar",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 480, heightDp = 480, name = "Badge — NEXGO 480×480")
@Composable
private fun QuarantineBadgeButtonPreview() {
    AvoqadoTheme {
        Row(modifier = Modifier.padding(Spacing.Space4)) {
            QuarantineBadgeButton(count = 2, onClick = {})
        }
    }
}

@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "Item card — PAX")
@Composable
private fun QuarantineItemCardPreview() {
    AvoqadoTheme {
        Column(modifier = Modifier.padding(Spacing.Space4)) {
            QuarantineItemCard(
                item = QuarantineItem(
                    id = "1",
                    type = "PAY_CASH",
                    label = labelFor("PAY_CASH"),
                    resolutionHint = resolutionHintFor("OUTCOME_UNKNOWN", "PAY_CASH"),
                    message = "El servidor no confirmó el resultado del cobro",
                    createdAt = System.currentTimeMillis(),
                ),
                onDismiss = {},
            )
        }
    }
}
