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
import com.jaac.avoqado_tpv.features.authentication.presentation.components.PinDisplay
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KIOSK_ASSIGNABLE_ROLES
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskStaffSession
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Kiosk Staff Assign Dialog
 *
 * Dialog for staff to enter their PIN and assign themselves to the kiosk.
 * Used for sales attribution (commissions/tips).
 *
 * @param onDismiss Callback when dialog is dismissed
 * @param onStaffAssigned Callback with staff session when successfully assigned
 */
@Composable
fun KioskStaffAssignDialog(
    onDismiss: () -> Unit,
    onStaffAssigned: (KioskStaffSession) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Get dependencies via EntryPoint (same pattern as KioskAdminPinDialog)
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            KioskAdminPinEntryPoint::class.java
        )
    }
    val authRepository = remember { entryPoint.authRepository() }
    val secureStorage = remember { entryPoint.secureStorage() }
    val venueId = remember { secureStorage.getKioskVenueId() ?: secureStorage.getVenueId() ?: "" }

    var pin by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Timber.d("🥝 [KIOSK-STAFF] Staff assign dialog opened")

    Dialog(onDismissRequest = {
        Timber.d("🥝 [KIOSK-STAFF] Assign dialog dismissed via outside click")
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
                    text = "Asignar Empleado",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Ingresa tu PIN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                // PIN display
                PinDisplay(
                    pin = pin,
                    maxLength = 10,
                    isError = errorMessage != null
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Number pad
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Rows 1-3
                    listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9")
                    ).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { digit ->
                                StaffNumberButton(
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
                        StaffNumberButton(
                            text = "C",
                            onClick = {
                                pin = ""
                                errorMessage = null
                            }
                        )

                        StaffNumberButton(
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
                        Timber.d("🥝 [KIOSK-STAFF] Assign dialog canceled")
                        onDismiss()
                    }) {
                        Text("Cancelar")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (pin.length >= 4) {
                                Timber.i("🥝 [KIOSK-STAFF] PIN submitted (${pin.length} digits) - validating...")
                                isLoading = true
                                errorMessage = null

                                scope.launch {
                                    try {
                                        val result = authRepository.loginWithPin(pin, venueId)

                                        when (result) {
                                            is Result.Success -> {
                                                val response = result.data
                                                val role = response.role

                                                // Check if role can be assigned to kiosk
                                                if (role in KIOSK_ASSIGNABLE_ROLES) {
                                                    Timber.i("🥝 [KIOSK-STAFF] Staff assigned: ${response.staff.displayName} ($role)")

                                                    val session = KioskStaffSession(
                                                        staffId = response.staffId,
                                                        staffName = response.staff.displayName,
                                                        staffInitials = KioskStaffSession.generateInitials(response.staff.displayName),
                                                        role = role
                                                    )
                                                    onStaffAssigned(session)
                                                } else {
                                                    Timber.w("🥝 [KIOSK-STAFF] Role $role cannot be assigned to kiosk")
                                                    errorMessage = "Tu rol no puede asignarse al kiosk"
                                                }
                                            }
                                            is Result.Error -> {
                                                Timber.e(result.exception, "🥝 [KIOSK-STAFF] PIN validation failed")
                                                val errorMsg = result.exception.message ?: ""
                                                errorMessage = when {
                                                    errorMsg.contains("502") ||
                                                    errorMsg.contains("503") ||
                                                    errorMsg.contains("504") ||
                                                    errorMsg.contains("timeout", ignoreCase = true) ||
                                                    errorMsg.contains("network", ignoreCase = true) ||
                                                    errorMsg.contains("connection", ignoreCase = true) ->
                                                        "Sin conexión a internet"
                                                    else -> "PIN incorrecto"
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Timber.e(e, "🥝 [KIOSK-STAFF] Error validating PIN")
                                        errorMessage = "Sin conexión a internet"
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        enabled = pin.length >= 4 && !isLoading,
                        modifier = Modifier.width(100.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Asignar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaffNumberButton(
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
