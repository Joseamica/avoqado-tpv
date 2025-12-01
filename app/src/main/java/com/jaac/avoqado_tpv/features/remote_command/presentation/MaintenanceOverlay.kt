package com.jaac.avoqado_tpv.features.remote_command.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Maintenance Overlay - Full-Screen Maintenance Mode Blocker
 *
 * **WHY**: When admin sends a MAINTENANCE_MODE command, this overlay
 * blocks payments but allows staff to exit locally. Unlike LOCK,
 * maintenance mode is for planned downtime (updates, hardware checks).
 *
 * **Difference from Lock**:
 * - LOCK: Emergency/security - NO user actions allowed, admin unlock only
 * - MAINTENANCE: Planned downtime - Staff can exit locally, no payments
 *
 * **Use Cases**:
 * - Software updates: Put terminal in maintenance before update
 * - Hardware maintenance: Temporarily disable for cleaning/repairs
 * - Configuration changes: Prevent payments during config sync
 * - End of shift: Maintenance mode until next shift starts
 *
 * **Design**:
 * - Full-screen overlay with zIndex 90 (below lock, above content)
 * - Amber/warning colored (not red like lock)
 * - Shows "Exit Maintenance" button for local exit
 * - Can also be exited remotely via EXIT_MAINTENANCE command
 *
 * **Pattern**: In-composition overlay (NOT ModalBottomSheet)
 * per CLAUDE.md performance guidelines for 1GB RAM devices.
 *
 * @param visible Whether the overlay is shown
 * @param maintenanceReason Why maintenance mode was entered
 * @param initiatedBy Who put terminal in maintenance mode
 * @param onExitMaintenance Callback when staff clicks "Exit Maintenance"
 *
 * @see MaintenanceManager State management for maintenance
 * @see CommandExecutor.executeMaintenanceMode Where maintenance is applied
 */
@Composable
fun MaintenanceOverlay(
    visible: Boolean,
    maintenanceReason: String?,
    initiatedBy: String?,
    onExitMaintenance: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Amber/warning color for maintenance (different from error red for lock)
    val amberColor = Color(0xFFFFA000)
    val darkBackground = Color(0xFF1C1C1C)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(darkBackground)
                .zIndex(90f),  // Below lock (100), above content
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                // Build/Maintenance Icon
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = "Modo mantenimiento",
                    modifier = Modifier.size(100.dp),
                    tint = amberColor
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Title
                Text(
                    text = "Modo Mantenimiento",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Main message
                Text(
                    text = "Terminal en mantenimiento.\nNo se pueden procesar pagos.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                // Reason (if provided)
                if (!maintenanceReason.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Motivo: $maintenanceReason",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }

                // Initiated by (if provided)
                if (!initiatedBy.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Iniciado por: $initiatedBy",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Exit Maintenance Button
                OutlinedButton(
                    onClick = onExitMaintenance,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "Salir de Mantenimiento",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Help text
                Text(
                    text = "O contacte al administrador para salir remotamente",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.3f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 1024, heightDp = 600)
@Composable
private fun MaintenanceOverlayPreview() {
    MaterialTheme {
        MaintenanceOverlay(
            visible = true,
            maintenanceReason = "Actualización de software programada",
            initiatedBy = "Gerente de Turno",
            onExitMaintenance = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 1024, heightDp = 600)
@Composable
private fun MaintenanceOverlayMinimalPreview() {
    MaterialTheme {
        MaintenanceOverlay(
            visible = true,
            maintenanceReason = null,
            initiatedBy = null,
            onExitMaintenance = {}
        )
    }
}
