package com.jaac.avoqado_tpv.features.verification.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.verification.presentation.NumberCaptureController
import com.jaac.avoqado_tpv.features.verification.presentation.NumberKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

const val LINKING_NUMBER_MAX_DIGITS = 10
const val LINKING_NUMBER_LOCKED_TEXT = "Corregido por el back-office"
const val LINKING_NUMBER_NEEDS_SALE_TEXT = "Disponible cuando se registre la venta"
const val CORRECT_NUMBER_TEXT = "Corregir número"

/** 9 o 10 dígitos (Isaac, 2-nov: «mínimo 9 y no más de 10»). El de 9 se sube así y el back-office lo ve marcado. */
fun isValidLinkingNumber(digits: String): Boolean = digits.length in 9..LINKING_NUMBER_MAX_DIGITS && digits.all(Char::isDigit)

/**
 * Lo que queda en el campo después de escribir o pegar [typed]: sólo dígitos y nunca más de 10. Si lo pegado tiene más
 * («+52 55 1234 5678» son 12), no entra y se queda lo que había (Review Focus 1). Bloqueado, no cambia.
 */
fun linkingNumberInput(current: String, typed: String, locked: Boolean = false): String {
    if (locked) return current
    val digits = typed.filter(Char::isDigit)
    return if (digits.length <= LINKING_NUMBER_MAX_DIGITS) digits else current
}

/** «Confirmar» con el campo: 9 o 10 dígitos. Sin salida «no se lee» (la quitó el cliente). Bloqueado manda el back-office. */
fun canConfirmLinkingNumber(value: String, locked: Boolean): Boolean = locked || isValidLinkingNumber(value)

/**
 * «Confirmar» de un número escrito por el promotor: pregunta por repetido si hay a quién ([capture]) y sólo llama
 * [onConfirm] si se guarda. Sin [capture] (pruebas viejas) guarda directo: el servidor revisa al recibirlo.
 */
fun CoroutineScope.submitLinkingNumber(
    number: String, kind: NumberKind, verificationId: String?, capture: NumberCaptureController?, onConfirm: (String) -> Unit,
) {
    if (capture == null) {
        onConfirm(number)
        return
    }
    launch { if (capture.confirm(number, kind, verificationId)) onConfirm(number) }
}

/**
 * El campo del número (vinculación o portado), con el patrón de `WhatsAppReceiptDialog`: teclado numérico, contador
 * «n/10» y sólo dígitos. Bloqueado (lo corrigió el back-office) se muestra sin poder editarse.
 */
@Composable
fun LinkingNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    locked: Boolean,
    modifier: Modifier = Modifier,
) {
    if (locked) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value.ifEmpty { "—" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = LINKING_NUMBER_LOCKED_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(linkingNumberInput(value, it)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        supportingText = {
            Text(
                text = "${value.length}/$LINKING_NUMBER_MAX_DIGITS",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** El aviso de repetido con el texto de Isaac: «Corregir» regresa al campo, «Guardar así» sigue. */
@Composable
fun DuplicatePromptDialog(capture: NumberCaptureController?) {
    val prompt by (capture?.prompt ?: NO_PROMPT).collectAsStateWithLifecycle()
    val text = prompt?.text ?: return
    AlertDialog(
        onDismissRequest = { capture?.correct() },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { capture?.saveAnyway() }) { Text("Guardar así") } },
        dismissButton = { TextButton(onClick = { capture?.correct() }) { Text("Corregir") } },
    )
}

private val NO_PROMPT: StateFlow<Nothing?> = MutableStateFlow(null)

/**
 * «Corregir número» en «Pendientes» y en la corrección: el campo solo, sin volver a tomar la foto. Al guardar se encola
 * un `NUMBER_ONLY` (lo hace quien llama en [onConfirm]).
 */
@Composable
fun LinkingNumberDialog(
    kind: NumberKind,
    initialNumber: String?,
    verificationId: String?,
    capture: NumberCaptureController?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(linkingNumberInput("", initialNumber.orEmpty())) }
    val scope = rememberCoroutineScope()
    val checking by (capture?.checking ?: NOT_CHECKING).collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(CORRECT_NUMBER_TEXT) },
        text = { LinkingNumberField(label = kind.label, value = value, onValueChange = { value = it }, locked = false) },
        confirmButton = {
            TextButton(
                onClick = { scope.submitLinkingNumber(value, kind, verificationId, capture, onConfirm) },
                enabled = canConfirmLinkingNumber(value, locked = false) && !checking,
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
    DuplicatePromptDialog(capture)
}

private val NOT_CHECKING: StateFlow<Boolean> = MutableStateFlow(false)

@Preview(widthDp = 360, heightDp = 640, name = "Número de vinculación - PAX A910S")
@Composable
private fun LinkingNumberFieldPreview() {
    AvoqadoTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LinkingNumberField(label = NumberKind.LINKING.label, value = "551234567", onValueChange = {}, locked = false)
                LinkingNumberField(label = NumberKind.PORTED.label, value = "5598765432", onValueChange = {}, locked = true)
            }
        }
    }
}
