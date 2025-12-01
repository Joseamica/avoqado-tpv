package com.jaac.avoqado_tpv.features.activation.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Activation Screen
 *
 * Allows terminal activation using a 6-character alphanumeric code.
 * Similar to Square POS device activation flow.
 *
 * **Features:**
 * - Display device serial number
 * - Input field for 6-character code (auto-uppercase)
 * - Real-time validation
 * - Loading state during activation
 * - Error messages with retry attempts
 *
 * **States:**
 * - Idle: Waiting for code input
 * - Loading: Activation in progress
 * - Error: Show error message + attempts remaining
 * - Success: Navigate to login screen
 */
@Composable
fun ActivationScreen(
    serialNumber: String,
    onActivate: (String) -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    var activationCode by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // Helper to dismiss keyboard
    val dismissKeyboard: () -> Unit = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    Scaffold(
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()  // Handle keyboard
                .verticalScroll(scrollState)  // Make scrollable
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { dismissKeyboard() }  // Tap outside to dismiss keyboard
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Title
            Text(
                text = "Activar Terminal",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle
            Text(
                text = "Ingrese el código de activación proporcionado por el administrador",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Device Serial Number - Pill shaped card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),  // Pill shape
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Número de Serie",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = serialNumber,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Activation Code Input - Pill shaped
            OutlinedTextField(
                value = activationCode,
                onValueChange = { newValue ->
                    // Only allow alphanumeric, max 6 characters, auto-uppercase
                    if (newValue.length <= 6 && newValue.all { it.isLetterOrDigit() }) {
                        activationCode = newValue.uppercase()
                    }
                },
                label = { Text("Código de Activación") },
                placeholder = { Text("A3F9K2") },
                singleLine = true,
                enabled = !isLoading,
                isError = errorMessage != null,
                supportingText = {
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text("6 caracteres alfanuméricos")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (activationCode.length == 6) {
                            keyboardController?.hide()
                            onActivate(activationCode)
                        }
                    }
                ),
                shape = RoundedCornerShape(50),  // Pill shape
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Activate Button - Pill shaped
            Button(
                onClick = {
                    keyboardController?.hide()
                    onActivate(activationCode)
                },
                enabled = activationCode.length == 6 && !isLoading,
                shape = RoundedCornerShape(50),  // Pill shape
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = "Activar Terminal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Help Text
            Text(
                text = "¿No tiene un código? Solicítelo desde el dashboard de administración.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun ActivationScreenIdlePreview() {
    AvoqadoTheme {
        ActivationScreen(
            serialNumber = "AVQD-1A2B3C4D5E6F",
            onActivate = {},
            isLoading = false,
            errorMessage = null
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ActivationScreenLoadingPreview() {
    AvoqadoTheme {
        ActivationScreen(
            serialNumber = "AVQD-1A2B3C4D5E6F",
            onActivate = {},
            isLoading = true,
            errorMessage = null
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ActivationScreenErrorPreview() {
    AvoqadoTheme {
        ActivationScreen(
            serialNumber = "AVQD-1A2B3C4D5E6F",
            onActivate = {},
            isLoading = false,
            errorMessage = "Código incorrecto. 3 intento(s) restantes"
        )
    }
}
