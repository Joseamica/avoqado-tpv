package com.jaac.avoqado_tpv.features.self_update.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTextButton
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.features.self_update.domain.UpdateRequestState

/**
 * UpdateRequestDialog - Dialog for remote update requests
 *
 * Shows different UI based on the update state:
 * - ShowDialog: Ask user to accept/dismiss update
 * - Downloading: Show download progress
 * - Installing: Show installation in progress
 * - InstallComplete: Show success message
 * - Error: Show error message
 */
@Composable
fun UpdateRequestDialog(
    state: UpdateRequestState,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {}
) {
    when (state) {
        is UpdateRequestState.ShowDialog -> {
            UpdateAvailableDialog(
                updateInfo = state.updateInfo,
                onAccept = onAccept,
                onDismiss = onDismiss
            )
        }

        is UpdateRequestState.Checking -> {
            UpdateCheckingDialog()
        }

        is UpdateRequestState.Downloading -> {
            UpdateDownloadingDialog(progress = state.progress)
        }

        is UpdateRequestState.Installing -> {
            UpdateInstallingDialog()
        }

        is UpdateRequestState.InstallComplete -> {
            UpdateCompleteDialog(
                versionName = state.versionName,
                onDismiss = onDismiss
            )
        }

        is UpdateRequestState.Error -> {
            UpdateErrorDialog(
                message = state.message,
                onRetry = onRetry,
                onDismiss = onDismiss
            )
        }

        UpdateRequestState.Idle -> {
            // No dialog to show
        }
    }
}

/**
 * Dialog asking user to accept or dismiss the update
 */
@Composable
private fun UpdateAvailableDialog(
    updateInfo: AvoqadoUpdateInfo,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Actualización Disponible",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Versión ${updateInfo.versionName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(Spacing.Space2))

                if (!updateInfo.releaseNotes.isNullOrBlank()) {
                    Text(
                        text = updateInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(Spacing.Space2))
                }

                Text(
                    text = "Tamaño: ${formatFileSize(updateInfo.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            AvoqadoButton(
                text = "Actualizar Ahora",
                onClick = onAccept
            )
        },
        dismissButton = {
            AvoqadoTextButton(
                text = "Más Tarde",
                onClick = onDismiss
            )
        }
    )
}

/**
 * Dialog showing update check in progress
 */
@Composable
private fun UpdateCheckingDialog() {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss while checking */ },
        icon = {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Verificando Actualización",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Text(
                text = "Conectando con el servidor...",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { /* No buttons while checking */ }
    )
}

/**
 * Dialog showing download progress
 */
@Composable
private fun UpdateDownloadingDialog(progress: Int) {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss while downloading */ },
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Descargando Actualización",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )

                Spacer(modifier = Modifier.height(Spacing.Space2))

                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(Spacing.Space1))

                Text(
                    text = "No cierre la aplicación",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { /* No buttons while downloading */ }
    )
}

/**
 * Dialog showing installation in progress
 */
@Composable
private fun UpdateInstallingDialog() {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss while installing */ },
        icon = {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Instalando Actualización",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "La aplicación se reiniciará automáticamente",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(Spacing.Space2))

                Text(
                    text = "No apague el terminal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = { /* No buttons while installing */ }
    )
}

/**
 * Dialog showing successful installation
 */
@Composable
private fun UpdateCompleteDialog(
    versionName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Actualización Completada",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Text(
                text = "Se instaló la versión $versionName correctamente",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            AvoqadoButton(
                text = "Aceptar",
                onClick = onDismiss
            )
        }
    )
}

/**
 * Dialog showing an error
 */
@Composable
private fun UpdateErrorDialog(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Error de Actualización",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error
            )
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                AvoqadoTextButton(
                    text = "Cerrar",
                    onClick = onDismiss
                )
                AvoqadoButton(
                    text = "Reintentar",
                    onClick = onRetry
                )
            }
        }
    )
}

/**
 * Format file size for display
 */
private fun formatFileSize(sizeStr: String): String {
    return try {
        val bytes = sizeStr.toLongOrNull() ?: return sizeStr
        when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    } catch (e: Exception) {
        sizeStr
    }
}
