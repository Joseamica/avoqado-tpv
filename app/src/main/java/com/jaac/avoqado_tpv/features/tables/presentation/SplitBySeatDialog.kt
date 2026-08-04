package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * "Dividir por puesto" (Fase 3, `SPLIT_BY_SEAT`) — a diferencia de
 * [SplitOrderSheet]/[MergeOrdersSheet]/[AssignWaiterSheet] no hay NADA que
 * elegir: el server agrupa los artículos YA enviados por `seat` solo (sin
 * body, ver KDoc de [com.jaac.avoqado_tpv.features.tables.data.TablesRepository.splitOrderBySeat]).
 * Por eso un `AlertDialog` — no un `ModalBottomSheet` — es la pantalla
 * CORRECTA aquí, no una simplificación: el founder pidió que esto sea rápido y
 * obvio frente a un cliente impaciente ("no un formulario"), y con cero
 * decisiones que tomar, un solo tap de confirmación es todo el flujo.
 *
 * Ni rojo ni ícono de advertencia (a diferencia de [CancelOrderDialog]): esta
 * acción no es destructiva — reorganiza la misma cuenta en cheques por asiento,
 * nunca pierde dinero ni cancela nada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitBySeatDialog(
    isSplitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { if (!isSplitting) onDismiss() },
        modifier = modifier,
        icon = { Icon(imageVector = Icons.AutoMirrored.Filled.CallSplit, contentDescription = null) },
        title = { Text("Dividir por puesto") },
        text = {
            Text(
                text = "Se crea un cheque por cada asiento con artículos enviados; el asiento más bajo se queda en esta cuenta. " +
                    "Si ningún artículo tiene asiento asignado, el server rechaza la división.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isSplitting) {
                Text(if (isSplitting) "Dividiendo…" else "Dividir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSplitting) {
                Text("Cancelar")
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
private fun SplitBySeatDialogPreview() {
    AvoqadoTheme {
        SplitBySeatDialog(isSplitting = false, onDismiss = {}, onConfirm = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — dividiendo")
@Composable
private fun SplitBySeatDialogLoadingPreview() {
    AvoqadoTheme {
        SplitBySeatDialog(isSplitting = true, onDismiss = {}, onConfirm = {})
    }
}
