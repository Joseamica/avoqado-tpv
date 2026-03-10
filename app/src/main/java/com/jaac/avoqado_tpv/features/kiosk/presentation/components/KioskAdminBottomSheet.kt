package com.jaac.avoqado_tpv.features.kiosk.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * EntryPoint for accessing repositories in KioskAdminBottomSheet
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KioskAdminBottomSheetEntryPoint {
    fun secureStorage(): com.jaac.avoqado_tpv.core.data.local.SecureStorage
    fun shiftRepository(): com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
}

/**
 * Kiosk Admin Bottom Sheet
 *
 * Shows admin options for configuring kiosk while in kiosk mode.
 * Allows staff to:
 * - View/toggle shift status
 * - Toggle tips (propinas)
 * - Toggle review prompts
 * - Toggle verification
 * - Exit kiosk mode
 *
 * Pattern: Staff can configure everything without leaving kiosk mode
 *
 * @param adminAuth Authenticated staff info (from PIN dialog)
 * @param onDismiss Callback when sheet is dismissed
 * @param onExitKiosk Callback when staff chooses to exit kiosk mode
 * @param onShiftStatusChanged Callback when shift status changes (for state refresh)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KioskAdminBottomSheet(
    adminAuth: KioskAdminAuth,
    onDismiss: () -> Unit,
    onExitKiosk: () -> Unit,
    onShiftStatusChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Get dependencies
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            KioskAdminBottomSheetEntryPoint::class.java
        )
    }
    val secureStorage = remember { entryPoint.secureStorage() }
    val shiftRepository = remember { entryPoint.shiftRepository() }
    // 🥝 KIOSK: Prioritize kioskVenueId (configured separately for kiosk mode)
    val venueId = remember { secureStorage.getKioskVenueId() ?: secureStorage.getVenueId() ?: "" }

    // State for toggles
    var isTipsEnabled by remember { mutableStateOf(secureStorage.isTipsEnabled()) }
    var isReviewEnabled by remember { mutableStateOf(secureStorage.isReviewEnabled()) }
    var isVerificationEnabled by remember { mutableStateOf(secureStorage.isVerificationEnabled()) }

    // Shift state - need full Shift object to get ID for closing
    var currentShift by remember { mutableStateOf<Shift?>(null) }
    var isLoadingShift by remember { mutableStateOf(true) }
    val isShiftOpen = currentShift?.status == ShiftStatus.OPEN

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Load current shift status
    LaunchedEffect(Unit) {
        Timber.d("🥝 [KIOSK-ADMIN] Loading shift status for venue: $venueId")
        try {
            val result = shiftRepository.getCurrentShift(venueId)
            when (result) {
                is Result.Success -> {
                    currentShift = result.data
                    Timber.d("🥝 [KIOSK-ADMIN] Shift status: ${currentShift?.status}, shiftId: ${currentShift?.id}")
                }
                is Result.Error -> {
                    Timber.e(result.exception, "🥝 [KIOSK-ADMIN] Error loading shift status")
                    currentShift = null
                }
            }
        } finally {
            isLoadingShift = false
        }
    }

    Timber.d("🥝 [KIOSK-ADMIN] Admin bottom sheet opened for: ${adminAuth.staffName} (${adminAuth.role})")

    ModalBottomSheet(
        onDismissRequest = {
            Timber.d("🥝 [KIOSK-ADMIN] Admin bottom sheet dismissed")
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            // Header with staff info
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Modo Administrador",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${adminAuth.staffName} - ${adminAuth.roleDisplayName ?: adminAuth.role.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Shift status option
            AdminOption(
                icon = Icons.Default.Schedule,
                label = if (isLoadingShift) "Cargando..." else if (isShiftOpen) "Turno Abierto" else "Turno Cerrado",
                description = if (isShiftOpen) "El kiosko puede aceptar pedidos" else "Abre un turno para recibir pedidos",
                actionContent = {
                    if (!isLoadingShift) {
                        Switch(
                            checked = isShiftOpen,
                            onCheckedChange = { shouldOpen ->
                                scope.launch {
                                    Timber.i("🥝 [KIOSK-ADMIN] Toggle shift: ${if (shouldOpen) "OPEN" else "CLOSE"}")
                                    try {
                                        val result = if (shouldOpen) {
                                            // Open new shift with admin's staff ID
                                            shiftRepository.openShift(
                                                venueId = venueId,
                                                staffId = adminAuth.staffId,
                                                startingCash = 0.0  // No cash drawer in kiosk mode
                                            )
                                        } else {
                                            // Close current shift - need shift ID
                                            val shiftId = currentShift?.id
                                            if (shiftId != null) {
                                                shiftRepository.closeShift(
                                                    venueId = venueId,
                                                    shiftId = shiftId
                                                )
                                            } else {
                                                Timber.e("🥝 [KIOSK-ADMIN] Cannot close shift - no shift ID")
                                                return@launch
                                            }
                                        }

                                        when (result) {
                                            is Result.Success -> {
                                                currentShift = if (shouldOpen) result.data else null
                                                Timber.i("🥝 [KIOSK-ADMIN] Shift ${if (shouldOpen) "opened" else "closed"} successfully")
                                                onShiftStatusChanged()
                                            }
                                            is Result.Error -> {
                                                Timber.e(result.exception, "🥝 [KIOSK-ADMIN] Error toggling shift")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Timber.e(e, "🥝 [KIOSK-ADMIN] Exception toggling shift")
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))

            // Tips toggle
            AdminToggleOption(
                icon = Icons.Outlined.AttachMoney,
                label = "Propinas",
                description = "Permitir propinas en pagos",
                checked = isTipsEnabled,
                onCheckedChange = { enabled ->
                    Timber.i("🥝 [KIOSK-ADMIN] Tips ${if (enabled) "enabled" else "disabled"}")
                    isTipsEnabled = enabled
                    secureStorage.saveTipsEnabled(enabled)
                }
            )

            // Review toggle
            AdminToggleOption(
                icon = Icons.Default.RateReview,
                label = "Solicitar Resena",
                description = "Mostrar pantalla de resena despues del pago",
                checked = isReviewEnabled,
                onCheckedChange = { enabled ->
                    Timber.i("🥝 [KIOSK-ADMIN] Review ${if (enabled) "enabled" else "disabled"}")
                    isReviewEnabled = enabled
                    secureStorage.saveReviewEnabled(enabled)
                }
            )

            // Verification toggle
            AdminToggleOption(
                icon = Icons.Default.VerifiedUser,
                label = "Verificacion",
                description = "Verificar identidad del cliente",
                checked = isVerificationEnabled,
                onCheckedChange = { enabled ->
                    Timber.i("🥝 [KIOSK-ADMIN] Verification ${if (enabled) "enabled" else "disabled"}")
                    isVerificationEnabled = enabled
                    secureStorage.saveVerificationEnabled(enabled)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(modifier = Modifier.height(8.dp))

            // Exit kiosk option
            AdminOption(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                label = "Salir del Modo Kiosko",
                description = "Volver al modo staff",
                onClick = {
                    Timber.i("🥝 [KIOSK-ADMIN] Exit kiosk requested by ${adminAuth.staffName}")
                    onExitKiosk()
                },
                actionContent = {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Admin option with custom action content
 */
@Composable
private fun AdminOption(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: (() -> Unit)? = null,
    actionContent: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        actionContent()
    }
}

/**
 * Admin toggle option with switch
 */
@Composable
private fun AdminToggleOption(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    AdminOption(
        icon = icon,
        label = label,
        description = description,
        actionContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    )
}
