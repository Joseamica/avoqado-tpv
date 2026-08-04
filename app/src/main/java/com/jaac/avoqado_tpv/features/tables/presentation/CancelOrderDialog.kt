package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * "Cancelar cuenta" (Fase 2, `CANCEL_ORDER`) — la única de las 3 acciones de
 * esta fase que es DESTRUCTIVA y difícil de deshacer (una vez cancelada, la
 * cuenta no se puede reabrir desde la TPV). Riesgo explícito del founder:
 * difícil de hacer sin querer, pero NO tan escondida que un mesero bajo
 * presión no la encuentre — por eso vive en el mismo menú ⋮ que el resto
 * ([TableOrderOverflowMenu]), pero un tap ahí solo ABRE este diálogo, nunca
 * cancela directo. Un solo `AlertDialog` (no un `ModalBottomSheet` como el
 * resto de Fase 1/2) — no hay nada que "navegar", es una confirmación.
 *
 * `reason` es OPCIONAL (mismo contrato que el server, ver KDoc de
 * [TablesRepository.cancelOrder][com.jaac.avoqado_tpv.features.tables.data.TablesRepository.cancelOrder]) —
 * el confirm nunca se bloquea por falta de razón, solo por estar en vuelo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CancelOrderDialog(
    isCancelling: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reason: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isCancelling) onDismiss() },
        modifier = modifier,
        icon = {
            Icon(imageVector = Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        title = { Text("Cancelar cuenta") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Esta acción no se puede deshacer. La mesa se libera y todo lo enviado a cocina se anula.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Spacing.Space3))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Motivo (opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    enabled = !isCancelling,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason.trim().ifBlank { null }) },
                enabled = !isCancelling,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(if (isCancelling) "Cancelando…" else "Sí, cancelar cuenta")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCancelling) {
                Text("Volver")
            }
        },
    )
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 480, heightDp = 480, name = "NEXGO 480×480")
@Composable
private fun CancelOrderDialogPreview() {
    AvoqadoTheme {
        CancelOrderDialog(isCancelling = false, onDismiss = {}, onConfirm = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — cancelando")
@Composable
private fun CancelOrderDialogLoadingPreview() {
    AvoqadoTheme {
        CancelOrderDialog(isCancelling = true, onDismiss = {}, onConfirm = {})
    }
}
