package com.jaac.avoqado_tpv.features.authentication.presentation

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.authentication.presentation.components.PinIndicator
import com.jaac.avoqado_tpv.features.authentication.presentation.components.PinPad

/**
 * Login Screen
 *
 * PIN entry screen for TPV authentication.
 * Professional PIN pad interface following Square POS and Toast POS patterns.
 *
 * **Features:**
 * - Custom numeric PIN pad (no system keyboard)
 * - Visual PIN indicator (4 filled circles)
 * - Auto-submit on 4 digits (Square/Toast standard)
 * - Large touch targets for busy environments
 *
 * @param venueId Venue ID from activation
 * @param onLoginSuccess Callback when login succeeds
 * @param onNavigateToActivation Callback when terminal is deactivated (requires re-activation)
 */
@Composable
fun LoginScreen(
    venueId: String,
    onLoginSuccess: () -> Unit,
    onNavigateToActivation: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Auto-navigate on success
    LaunchedEffect(state) {
        when (state) {
            is LoginState.Success -> onLoginSuccess()
            is LoginState.TerminalNotActivated -> onNavigateToActivation()
            else -> Unit
        }
    }

    LoginContent(
        state = state,
        onPinEntered = { pin -> viewModel.loginWithPin(pin, venueId) },
        onDismissError = { viewModel.resetState() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginContent(
    state: LoginState,
    onPinEntered: (String) -> Unit,
    onDismissError: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    // Auto-submit when PIN length is 4 digits (Square/Toast standard)
    LaunchedEffect(pin) {
        if (pin.length == 4 && state !is LoginState.Loading) {
            onPinEntered(pin)
            pin = "" // Clear after submit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Iniciar Sesión") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Main content - PIN pad (always visible)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Title
                    Text(
                        text = "Ingresa tu PIN",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(48.dp))

                    // PIN Indicator (circles showing how many digits entered)
                    PinIndicator(
                        pinLength = pin.length,
                        maxLength = 4
                    )

                    Spacer(modifier = Modifier.height(48.dp))

                    // Custom PIN Pad (Square/Toast style)
                    PinPad(
                        onNumberClick = { digit ->
                            if (pin.length < 4 && state !is LoginState.Loading) {
                                pin += digit
                            }
                        },
                        onBackspace = {
                            if (pin.isNotEmpty() && state !is LoginState.Loading) {
                                pin = pin.dropLast(1)
                            }
                        },
                        onClear = {
                            if (state !is LoginState.Loading) {
                                pin = ""
                            }
                        },
                        enabled = state !is LoginState.Loading
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Terminal deactivated message
                    if (state is LoginState.TerminalNotActivated) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Este terminal ha sido desactivado.\nSolicita un nuevo código de activación al administrador.",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Redirigiendo a activación...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // ✅ Error banner overlay (Square/Toast pattern) - Non-blocking banner at top
                if (state is LoginState.Error) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(padding),  // Apply Scaffold padding to appear below TopBar
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = MaterialTheme.shapes.extraSmall  // Minimal rounded corners
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Error message
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )

                            // Close/Retry button
                            IconButton(onClick = onDismissError) {
                                Text(
                                    "✕",
                                    color = MaterialTheme.colorScheme.onError,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                        }
                    }
                }

                // ✅ Loading overlay (Square/Toast pattern) - Reusable component from Design System
                if (state is LoginState.Loading) {
                    AvoqadoLoadingOverlay(message = "Autenticando...")
                }
            }
        }
    }
// ========== Previews ==========
}
@Preview(
    name = "Login - Light Mode",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
private fun LoginScreenIdlePreview() {
    AvoqadoTheme(darkTheme = false) {
        LoginContent(
            state = LoginState.Idle,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(
    name = "Login - Dark Mode (Dashboard Web)",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun LoginScreenIdleDarkPreview() {
    AvoqadoTheme(darkTheme = true) {
        LoginContent(
            state = LoginState.Idle,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(
    name = "Loading - Light Mode",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
private fun LoginScreenLoadingPreview() {
    AvoqadoTheme(darkTheme = false) {
        LoginContent(
            state = LoginState.Loading,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(
    name = "Loading - Dark Mode (Dashboard Web)",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun LoginScreenLoadingDarkPreview() {
    AvoqadoTheme(darkTheme = true) {
        LoginContent(
            state = LoginState.Loading,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(
    name = "Error - Light Mode",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
private fun LoginScreenErrorPreview() {
    AvoqadoTheme(darkTheme = false) {
        LoginContent(
            state = LoginState.Error("PIN incorrecto. Intenta de nuevo."),
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(
    name = "Error - Dark Mode (Dashboard Web)",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun LoginScreenErrorDarkPreview() {
    AvoqadoTheme(darkTheme = true) {
        LoginContent(
            state = LoginState.Error("PIN incorrecto. Intenta de nuevo."),
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(
    name = "Rate Limit Error - Dark Mode",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun LoginScreenRateLimitErrorDarkPreview() {
    AvoqadoTheme(darkTheme = true) {
        LoginContent(
            state = LoginState.Error(
                "Demasiados intentos. Por favor espera un momento e intenta nuevamente.\n\n" +
                        "ℹ️ Si estás en desarrollo, el backend debe configurar rate limits más altos para DEV."
            ),
            onPinEntered = {},
            onDismissError = {}
        )
    }
}
