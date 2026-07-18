package com.jaac.avoqado_tpv.features.remote_command.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors

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
    val warningColor = MaterialTheme.avoqadoColors.statusWarning
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.76f)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .zIndex(90f),  // Below lock (100), above content
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(24.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    // 🩹 A long admin-supplied maintenanceReason no longer pushes the exit button off-card.
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 28.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = warningColor.copy(alpha = 0.18f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Build,
                            contentDescription = "Modo mantenimiento",
                            modifier = Modifier
                                .size(92.dp)
                                .padding(22.dp),
                            tint = warningColor
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Modo mantenimiento",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Terminal en mantenimiento. No se pueden procesar pagos.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (!maintenanceReason.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = "Motivo: $maintenanceReason",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }

                    if (!initiatedBy.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Iniciado por: $initiatedBy",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onExitMaintenance,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text(
                            text = "Salir de mantenimiento",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Si no puedes salir, contacte al administrador.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
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
