package com.jaac.avoqado_tpv.features.permissions.presentation.override

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.jaac.avoqado_tpv.features.authentication.presentation.components.PinDisplay
import com.jaac.avoqado_tpv.features.authentication.presentation.components.PinPad

private const val PIN_MIN = 4
private const val PIN_MAX = 10

@Composable
fun PinDeGerenteDialog(prompt: PromptDePin, onEnviar: (String) -> Unit, onCancelar: () -> Unit) {
    var pin by remember(prompt.id) { mutableStateOf("") }
    var anterior by remember(prompt.id) { mutableStateOf<PromptDePin?>(prompt) }
    // Un error nuevo borra el PIN tecleado; mismo prompt sin error nuevo lo conserva.
    LaunchedEffect(prompt) {
        pin = pinTrasCambio(pin, anterior, prompt)
        anterior = prompt
    }
    AlertDialog(
        onDismissRequest = onCancelar, // back; clic fuera está apagado
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("PIN de un gerente") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt.motivo, style = MaterialTheme.typography.bodyMedium)
                prompt.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                PinDisplay(pin = pin, maxLength = PIN_MAX, isError = prompt.error != null)
                Spacer(Modifier.height(4.dp))
                PinPad(
                    onNumberClick = { if (pin.length < PIN_MAX) pin += it },
                    onBackspace = { pin = pin.dropLast(1) },
                    onClear = { pin = "" },
                    enabled = !prompt.enviando,
                    buttonSize = 60.dp,
                    buttonSpacing = 8.dp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onEnviar(pin) }, enabled = pin.length >= PIN_MIN && !prompt.enviando) { Text("Autorizar") }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}
