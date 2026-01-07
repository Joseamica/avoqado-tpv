package com.jaac.avoqado_tpv.features.kiosk.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.authentication.domain.models.StaffRole
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * EntryPoint for accessing AuthRepository in KioskExitPinDialog
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KioskExitPinEntryPoint {
    fun authRepository(): com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
    fun secureStorage(): com.jaac.avoqado_tpv.core.data.local.SecureStorage
}

/**
 * Roles authorized to exit kiosk mode
 */
private val KIOSK_EXIT_AUTHORIZED_ROLES = setOf(
    StaffRole.SUPERADMIN,
    StaffRole.OWNER,
    StaffRole.ADMIN,
    StaffRole.MANAGER
)

/**
 * Kiosk Exit PIN Dialog
 *
 * Appears when staff performs secret gesture to exit kiosk mode.
 * Validates PIN against backend and checks that user has elevated role.
 *
 * @param onDismiss Callback when dialog is dismissed
 * @param onExitSuccess Callback when staff successfully authenticates and has permission
 */
@Composable
fun KioskExitPinDialog(
    onDismiss: () -> Unit,
    onExitSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Get dependencies
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            KioskExitPinEntryPoint::class.java
        )
    }
    val authRepository = remember { entryPoint.authRepository() }
    val secureStorage = remember { entryPoint.secureStorage() }
    // 🥝 KIOSK: Prioritize kioskVenueId (configured separately for kiosk mode)
    val venueId = remember { secureStorage.getKioskVenueId() ?: secureStorage.getVenueId() ?: "" }

    var pin by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Timber.d("🥝 [KIOSK] Exit PIN dialog opened")

    Dialog(onDismissRequest = {
        Timber.d("🥝 [KIOSK] Exit PIN dialog dismissed via outside click")
        onDismiss()
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "Salir del Modo Kiosko",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Ingresa tu PIN de administrador",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                // PIN dots display
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    repeat(10) { index ->
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index < pin.length)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Number pad
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Row 1-3
                    listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9")
                    ).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { digit ->
                                NumberButton(
                                    text = digit,
                                    onClick = {
                                        if (pin.length < 10) {
                                            pin += digit
                                            errorMessage = null
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Row 4: Clear, 0, Backspace
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Clear button
                        NumberButton(
                            text = "C",
                            onClick = {
                                pin = ""
                                errorMessage = null
                            }
                        )

                        // 0
                        NumberButton(
                            text = "0",
                            onClick = {
                                if (pin.length < 10) {
                                    pin += "0"
                                    errorMessage = null
                                }
                            }
                        )

                        // Backspace
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    if (pin.isNotEmpty()) {
                                        pin = pin.dropLast(1)
                                        errorMessage = null
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Borrar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Error message
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        Timber.d("🥝 [KIOSK] Exit PIN dialog canceled")
                        onDismiss()
                    }) {
                        Text("Cancelar")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (pin.length >= 4) {
                                Timber.i("🥝 [KIOSK] PIN submitted (${pin.length} digits) - validating...")
                                isLoading = true
                                errorMessage = null

                                scope.launch {
                                    try {
                                        // Validate PIN against backend
                                        val result = authRepository.loginWithPin(pin, venueId)

                                        when (result) {
                                            is Result.Success -> {
                                                val response = result.data
                                                // Check if role is authorized to exit kiosk
                                                val role = response.role
                                                if (role in KIOSK_EXIT_AUTHORIZED_ROLES) {
                                                    Timber.i("🥝 [KIOSK] Exit authorized for role: $role")
                                                    // Clear session created by login (we just wanted to validate)
                                                    // Note: The login already saved session, but since we're
                                                    // exiting kiosk, we want to go to Home which requires session
                                                    onExitSuccess()
                                                } else {
                                                    Timber.w("🥝 [KIOSK] Exit denied - role $role not authorized")
                                                    errorMessage = "Tu rol no tiene permiso para salir del modo kiosko"
                                                }
                                            }
                                            is Result.Error -> {
                                                Timber.e(result.exception, "🥝 [KIOSK] PIN validation failed")
                                                errorMessage = "PIN incorrecto"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Timber.e(e, "🥝 [KIOSK] Error validating PIN")
                                        errorMessage = "Error de conexion. Intenta de nuevo."
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        enabled = pin.length >= 4 && !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Salir")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
