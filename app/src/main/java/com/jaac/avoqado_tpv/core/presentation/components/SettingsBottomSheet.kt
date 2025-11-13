package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Settings bottom sheet modal
 *
 * Displays user management and settings options:
 * - Cambiar usuario (Logout)
 * - Configuración (Future: Settings screen)
 * - Ayuda (Future: Help/Support)
 *
 * Usage:
 * ```
 * var showSettings by remember { mutableStateOf(false) }
 *
 * if (showSettings) {
 *     SettingsBottomSheet(
 *         onDismiss = { showSettings = false },
 *         onLogout = {
 *             viewModel.handleLogout()
 *             showSettings = false
 *         }
 *     )
 * }
 * ```
 *
 * @param onDismiss Callback when sheet is dismissed
 * @param onLogout Callback when "Cambiar usuario" is clicked
 * @param onSettings Callback when "Configuración" is clicked (optional, disabled if null)
 * @param onHelp Callback when "Ayuda" is clicked (optional, disabled if null)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            // Header
            Text(
                text = "Opciones",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(8.dp))

            // Option 1: Cambiar usuario (Logout)
            SettingsOption(
                icon = Icons.AutoMirrored.Filled.Logout,
                label = "Cambiar usuario",
                description = "Cerrar sesión y cambiar de usuario",
                enabled = true,
                onClick = onLogout
            )

            // Option 2: Configuración (Future)
            SettingsOption(
                icon = Icons.Default.Settings,
                label = "Configuración",
                description = "Ajustes del terminal",
                enabled = onSettings != null,
                onClick = { onSettings?.invoke() }
            )

            // Option 3: Ayuda (Future)
            SettingsOption(
                icon = Icons.AutoMirrored.Filled.Help,
                label = "Ayuda",
                description = "Centro de soporte",
                enabled = onHelp != null,
                onClick = { onHelp?.invoke() }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Individual settings option item
 */
@Composable
private fun SettingsOption(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Text content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
        }

        // Chevron
        if (enabled) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================
// PREVIEW
// ============================================================

@Preview(showBackground = true)
@Composable
private fun SettingsBottomSheetPreview() {
    AvoqadoTheme {
        Surface {
            Column {
                Spacer(modifier = Modifier.height(300.dp))
                SettingsBottomSheet(
                    onDismiss = {},
                    onLogout = {},
                    onSettings = null, // Disabled
                    onHelp = null // Disabled
                )
            }
        }
    }
}
