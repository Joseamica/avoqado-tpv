package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Dialog for attaching a custom note to a cart line (currently driven from the
 * "+ Nota" button on [NumericKeypadView]).
 *
 * Ports the avoqado-android note dialog pattern. Multi-line TextField with a
 * 200-char cap to prevent runaway input. Save returns the trimmed text (or
 * empty string to clear the note).
 */
@Composable
fun NoteDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    title: String = "Agregar nota",
    placeholder: String = "Ej. Sin cebolla, propina mesero…",
) {
    var note by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= MAX_NOTE_LENGTH) note = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text(placeholder) },
                    supportingText = {
                        Text(
                            text = "${note.length}/$MAX_NOTE_LENGTH",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note.trim()) }) {
                Text(if (note.isBlank()) "Quitar nota" else "Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}

private const val MAX_NOTE_LENGTH = 200

@Preview(name = "Note dialog - Empty", showBackground = true, widthDp = 360, heightDp = 400)
@Composable
private fun NoteDialogEmptyPreview() {
    AvoqadoTheme {
        NoteDialog(initialText = "", onDismiss = {}, onSave = {})
    }
}

@Preview(name = "Note dialog - With text", showBackground = true, widthDp = 360, heightDp = 400)
@Composable
private fun NoteDialogWithTextPreview() {
    AvoqadoTheme {
        NoteDialog(initialText = "Propina mesero", onDismiss = {}, onSave = {})
    }
}
