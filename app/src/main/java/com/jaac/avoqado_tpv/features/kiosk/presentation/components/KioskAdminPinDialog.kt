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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.jaac.avoqado_tpv.features.authentication.presentation.components.PinDisplay
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
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * EntryPoint for accessing AuthRepository in KioskAdminPinDialog
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KioskAdminPinEntryPoint {
    fun authRepository(): com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
    fun secureStorage(): com.jaac.avoqado_tpv.core.data.local.SecureStorage
}

/**
 * Roles authorized to access admin mode in kiosk
 */
private val KIOSK_ADMIN_AUTHORIZED_ROLES = setOf(
    StaffRole.SUPERADMIN,
    StaffRole.OWNER,
    StaffRole.ADMIN,
    StaffRole.MANAGER
)

/**
 * Authenticated staff info passed to admin bottom sheet
 */
data class KioskAdminAuth(
    val staffId: String,
    val staffName: String,
    val role: StaffRole,
    val roleDisplayName: String? = null
)

/**
 * Kiosk Admin PIN Dialog
 *
 * Dialog for staff to authenticate and access admin mode within kiosk.
 * Similar to KioskExitPinDialog but on success shows admin options instead of exiting.
 *
 * @param onDismiss Callback when dialog is dismissed
 * @param onAuthSuccess Callback with staff auth info when successfully authenticated
 */
@Composable
fun KioskAdminPinDialog(
    onDismiss: () -> Unit,
    onAuthSuccess: (KioskAdminAuth) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Get dependencies
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            KioskAdminPinEntryPoint::class.java
        )
    }
    val authRepository = remember { entryPoint.authRepository() }
    val secureStorage = remember { entryPoint.secureStorage() }
    // 🥝 KIOSK: Prioritize kioskVenueId (configured separately for kiosk mode)
    val venueId = remember { secureStorage.getKioskVenueId() ?: secureStorage.getVenueId() ?: "" }

    var pin by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Timber.d("🥝 [KIOSK-ADMIN] Admin PIN dialog opened")

    Dialog(
        onDismissRequest = {
            Timber.d("🥝 [KIOSK-ADMIN] Admin PIN dialog dismissed via outside click")
            onDismiss()
        },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false // Allow custom width beyond platform defaults
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f) // Occupy most of the screen width
                .padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),  // Reduced padding
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title removed to fit buttons on small screens
                Text(
                    text = "Ingresa tu PIN",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // PIN display (matches LoginScreen design)
                PinDisplay(
                    pin = pin,
                    maxLength = 10,
                    isError = errorMessage != null
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Number pad
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Row 1-3
                    listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9")
                    ).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            row.forEach { digit ->
                                AdminNumberButton(
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Clear button
                        AdminNumberButton(
                            text = "C",
                            onClick = {
                                pin = ""
                                errorMessage = null
                            }
                        )

                        // 0
                        AdminNumberButton(
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
                                .size(64.dp) // Consistent with other dialog
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

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            Timber.d("🥝 [KIOSK-ADMIN] Admin PIN dialog canceled")
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    ) {
                        Text("Cancelar", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            if (pin.length >= 4) {
                                Timber.i("🥝 [KIOSK-ADMIN] PIN submitted (${pin.length} digits) - validating...")
                                isLoading = true
                                errorMessage = null

                                scope.launch {
                                    try {
                                        // Validate PIN against backend
                                        val result = authRepository.loginWithPin(pin, venueId)

                                        when (result) {
                                            is Result.Success -> {
                                                val response = result.data
                                                val role = response.role

                                                // Check if role is authorized for admin mode
                                                if (role in KIOSK_ADMIN_AUTHORIZED_ROLES) {
                                                    Timber.i("🥝 [KIOSK-ADMIN] Admin access authorized for role: $role")

                                                    // Pass auth info to callback
                                                    val adminAuth = KioskAdminAuth(
                                                        staffId = response.staffId,
                                                        staffName = response.staff.displayName,
                                                        role = role,
                                                        roleDisplayName = response.roleDisplayName
                                                    )
                                                    onAuthSuccess(adminAuth)
                                                } else {
                                                    Timber.w("🥝 [KIOSK-ADMIN] Admin access denied - role $role not authorized")
                                                    errorMessage = "Tu rol no tiene permiso de administrador"
                                                }
                                            }
                                            is Result.Error -> {
                                                Timber.e(result.exception, "🥝 [KIOSK-ADMIN] PIN validation failed")
                                                // Check error type to show appropriate message
                                                val errorMsg = result.exception.message ?: ""
                                                errorMessage = when {
                                                    errorMsg.contains("502") ||
                                                    errorMsg.contains("503") ||
                                                    errorMsg.contains("504") ||
                                                    errorMsg.contains("timeout", ignoreCase = true) ||
                                                    errorMsg.contains("network", ignoreCase = true) ||
                                                    errorMsg.contains("connection", ignoreCase = true) ->
                                                        "Sin conexión a internet"
                                                    errorMsg.contains("401") || errorMsg.contains("403") ->
                                                        "PIN incorrecto"
                                                    else -> "PIN incorrecto"
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Timber.e(e, "🥝 [KIOSK-ADMIN] Error validating PIN")
                                        errorMessage = "Sin conexión a internet"
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        enabled = pin.length >= 4 && !isLoading,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                "Acceder",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Visible
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminNumberButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp) // Consistent with other dialog
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
