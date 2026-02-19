package com.jaac.avoqado_tpv.features.authentication.presentation

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.authentication.presentation.components.PinDisplay
import com.jaac.avoqado_tpv.features.authentication.presentation.components.PinPad

/**
 * Login Screen
 *
 * PIN entry screen for TPV authentication.
 * Professional PIN pad interface following Square POS and Toast POS patterns.
 *
 * **Features:**
 * - Custom numeric PIN pad (no system keyboard)
 * - Variable length PIN support (4-10 digits)
 * - Visual PIN display with show/hide toggle
 * - Character counter always visible
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
    val isPinComplete = pin.length >= 4 // Minimum 4 digits to enable buttons
    val isInteractionEnabled = state !is LoginState.Loading && state !is LoginState.Success

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

                    // Adaptive PIN button sizing (Square/Clover pattern)
                    // No scroll — everything fits on screen by adapting sizes
                    val pinButtonSize = when (sizes.sizeCategory) {
                        "small" -> 56.dp   // PAX A80 / small screens
                        "medium" -> 68.dp  // PAX A910S / A920
                        else -> 80.dp      // Tablets
                    }
                    val pinButtonSpacing = when (sizes.sizeCategory) {
                        "small" -> 6.dp
                        "medium" -> 8.dp
                        else -> 12.dp
                    }

                    // Title
                    Text(
                        text = "Ingresa tu PIN",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(sizes.spacingSmall))

                    // PIN Display (masked digits with show/hide toggle and counter)
                    PinDisplay(
                        pin = pin,
                        maxLength = 10,
                        isError = state is LoginState.Error
                    )

                    Spacer(modifier = Modifier.height(sizes.spacingSmall))

                    // PinPad + "Ir" button side by side
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(pinButtonSpacing),
                        modifier = Modifier.height(IntrinsicSize.Min)
                    ) {
                        // Custom PIN Pad (Square/Toast style — adaptive sizing)
                        PinPad(
                            onNumberClick = { digit ->
                                if (pin.length < 10 && isInteractionEnabled) {
                                    pin += digit
                                }
                            },
                            onBackspace = {
                                if (pin.isNotEmpty() && isInteractionEnabled) {
                                    pin = pin.dropLast(1)
                                }
                            },
                            onClear = {
                                if (isInteractionEnabled) {
                                    pin = ""
                                }
                            },
                            enabled = isInteractionEnabled,
                            buttonSize = pinButtonSize,
                            buttonSpacing = pinButtonSpacing
                        )

                        // Login button (Ir) — same width as PIN buttons, full height of pad
                        ElevatedButton(
                            onClick = {
                                if (isPinComplete) {
                                    val currentPin = pin
                                    pin = ""
                                    onPinEntered(currentPin)
                                }
                            },
                            enabled = isPinComplete && isInteractionEnabled,
                            modifier = Modifier
                                .width(pinButtonSize)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(pinButtonSize / 2),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            elevation = ButtonDefaults.elevatedButtonElevation(
                                defaultElevation = 2.dp,
                                pressedElevation = 6.dp,
                                disabledElevation = 0.dp
                            )
                        ) {
                            Text(
                                text = "Ir",
                                fontSize = if (pinButtonSize < 72.dp) 16.sp else 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Version text at the bottom
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )

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

                // ✅ Venue not operational overlay (SUSPENDED, CLOSED, etc.)
                // Full-screen blocking overlay - user cannot proceed until venue status changes
                if (state is LoginState.VenueNotOperational) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Warning icon
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )

                                // Title
                                Text(
                                    text = "Establecimiento No Disponible",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold
                                )

                                // Message from backend
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Retry button
                                OutlinedButton(
                                    onClick = onDismissError,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Text("Reintentar")
                                }
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
                // Show overlay for both Loading AND Success to prevent flash during navigation
                if (state is LoginState.Loading || state is LoginState.Success) {
                    AvoqadoLoadingOverlay(message = "Autenticando...")
                }
            }
        }
    }
}

// ========== Previews ==========
// PAX A910S: 720x1280px @ 320dpi = 360x640dp
// Using device spec string for accurate DPI-aware rendering (closer to real hardware)
private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

@Preview(name = "Login - PAX A910S", device = PAX_A910S, showSystemUi = true)
@Composable
private fun LoginScreenPaxA910sPreview() {
    AvoqadoTheme(darkTheme = false) {
        LoginContent(
            state = LoginState.Idle,
            venueLogo = null,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(name = "Login - PAX A910S Dark", device = PAX_A910S, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoginScreenPaxA910sDarkPreview() {
    AvoqadoTheme(darkTheme = true) {
        LoginContent(
            state = LoginState.Idle,
            venueLogo = null,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(name = "Login - PAX A910S Loading", device = PAX_A910S, showSystemUi = true)
@Composable
private fun LoginScreenPaxA910sLoadingPreview() {
    AvoqadoTheme(darkTheme = false) {
        LoginContent(
            state = LoginState.Loading,
            venueLogo = null,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(name = "Login - PAX A910S Error", device = PAX_A910S, showSystemUi = true)
@Composable
private fun LoginScreenPaxA910sErrorPreview() {
    AvoqadoTheme(darkTheme = false) {
        LoginContent(
            state = LoginState.Error("PIN incorrecto. Intenta de nuevo."),
            venueLogo = null,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

@Preview(name = "Rate Limit Error - Dark", device = PAX_A910S, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoginScreenRateLimitErrorDarkPreview() {
    AvoqadoTheme(darkTheme = true) {
        LoginContent(
            state = LoginState.Error(
                "Demasiados intentos. Por favor espera un momento e intenta nuevamente.\n\n" +
                        "Si estás en desarrollo, el backend debe configurar rate limits más altos para DEV."
            ),
            venueLogo = null,
            onPinEntered = {},
            onDismissError = {}
        )
    }
}

