package com.jaac.avoqado_tpv.features.authentication.presentation

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.jaac.avoqado_tpv.R
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
    val venueLogo by viewModel.venueLogo.collectAsStateWithLifecycle()

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
        venueLogo = venueLogo,
        onPinEntered = { pin -> viewModel.loginWithPin(pin, venueId) },
        onDismissError = { viewModel.resetState() }
    )
}

@Composable
private fun LoginContent(
    state: LoginState,
    venueLogo: String?,
    onPinEntered: (String) -> Unit,
    onDismissError: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    // Auto-submit when PIN length is 4 digits (Square/Toast standard)
    LaunchedEffect(pin) {
        if (pin.length == 4 && state !is LoginState.Loading && state !is LoginState.Success) {
            onPinEntered(pin)
            pin = "" // Clear after submit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // ✅ Responsive workflow screen (no scroll)
                ResponsiveScaffold(
                    modifier = Modifier.padding(padding),
                    scrollable = false,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val sizes = LocalResponsiveSizes.current

                    // Venue Logo (circular) - Dynamic size
                    AsyncImage(
                        model = venueLogo,
                        contentDescription = "Logo del venue",
                        modifier = Modifier
                            .size(sizes.logoSize)
                            .clip(CircleShape),
                        contentScale = ContentScale.Fit,
                        error = painterResource(R.drawable.isotipo),
                        placeholder = painterResource(R.drawable.isotipo)
                    )

                    Spacer(modifier = Modifier.height(sizes.spacingSmall))

                    // Title
                    Text(
                        text = "Ingresa tu PIN",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(sizes.spacingMedium))

                    // PIN Indicator (circles showing how many digits entered)
                    PinIndicator(
                        pinLength = pin.length,
                        maxLength = 4
                    )

                    Spacer(modifier = Modifier.height(sizes.spacingMedium))

                    // Custom PIN Pad (Square/Toast style)
                    PinPad(
                        onNumberClick = { digit ->
                            if (pin.length < 4 && state !is LoginState.Loading && state !is LoginState.Success) {
                                pin += digit
                            }
                        },
                        onBackspace = {
                            if (pin.isNotEmpty() && state !is LoginState.Loading && state !is LoginState.Success) {
                                pin = pin.dropLast(1)
                            }
                        },
                        onClear = {
                            if (state !is LoginState.Loading && state !is LoginState.Success) {
                                pin = ""
                            }
                        },
                        enabled = state !is LoginState.Loading && state !is LoginState.Success
                    )

                    Spacer(modifier = Modifier.height(sizes.spacingSmall))
                }

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
                // Show overlay for both Loading AND Success to prevent flash during navigation
                if (state is LoginState.Loading || state is LoginState.Success) {
                    AvoqadoLoadingOverlay(message = "Autenticando...")
                }
            }
        }
    }
}

// ========== Previews ==========
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
            venueLogo = null,
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
            venueLogo = null,
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
            venueLogo = null,
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
            venueLogo = null,
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
            venueLogo = null,
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
            venueLogo = null,
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
            venueLogo = null,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}
