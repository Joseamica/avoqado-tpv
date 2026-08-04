package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * "Editar detalles" (Fase 3, `UPDATE_DETAILS`) — comensales y nombre en el
 * cheque (Square las llama "Conteo de clientes"/"Nombre"; `notes`/`customerId`/
 * `orderType` existen en el reducer pero quedan fuera de alcance — ver KDoc de
 * [com.jaac.avoqado_tpv.features.tables.data.TablesRepository.updateOrderDetails]).
 *
 * Precargado con lo que ya tiene la cuenta ([initialName]/[initialCovers]) —
 * "Guardar" siempre manda AMBOS campos (nunca null), así que un campo sin
 * tocar simplemente se re-escribe con el mismo valor: idempotente, nunca
 * corrompe `notes`/`customerId` que este sheet ni siquiera toca.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDetailsSheet(
    initialName: String?,
    initialCovers: Int?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, covers: Int) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    var name by remember(initialName) { mutableStateOf(initialName.orEmpty()) }
    var covers by remember(initialCovers) { mutableIntStateOf(initialCovers?.coerceIn(1, 200) ?: 1) }

    ModalBottomSheet(onDismissRequest = { if (!isSaving) onDismiss() }, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4).padding(bottom = Spacing.Space6)) {
            Text(text = "Editar detalles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.Space4))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre en el cheque") },
                placeholder = { Text("Ej. \"Cumpleaños de Ana\"") },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.Space4))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Comensales", style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Space3)) {
                    FilledIconButton(
                        onClick = { covers = (covers - 1).coerceIn(1, 200) },
                        enabled = !isSaving && covers > 1,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Menos comensales")
                    }
                    Text(
                        text = covers.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = Spacing.Space2),
                    )
                    FilledIconButton(
                        onClick = { covers = (covers + 1).coerceIn(1, 200) },
                        enabled = !isSaving && covers < 200,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Más comensales")
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Space5))

            AvoqadoButton(
                text = if (isSaving) "Guardando…" else "Guardar",
                onClick = { onConfirm(name.trim(), covers) },
                enabled = !isSaving,
                loading = isSaving,
                fullWidth = true,
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
private fun EditDetailsSheetPreview() {
    AvoqadoTheme {
        EditDetailsSheet(
            initialName = "Cumpleaños de Ana",
            initialCovers = 4,
            isSaving = false,
            onDismiss = {},
            onConfirm = { _, _ -> },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — vacío")
@Composable
private fun EditDetailsSheetEmptyPreview() {
    AvoqadoTheme {
        EditDetailsSheet(
            initialName = null,
            initialCovers = null,
            isSaving = false,
            onDismiss = {},
            onConfirm = { _, _ -> },
        )
    }
}
