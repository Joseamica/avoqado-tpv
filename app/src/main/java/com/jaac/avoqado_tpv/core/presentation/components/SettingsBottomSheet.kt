package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Settings bottom sheet modal
 *
 * Displays user management and settings options:
 * - Cambiar usuario (Logout)
 * - Reiniciar App (Process restart - Toast/Square pattern)
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
 * @param onRestartApp Callback when "Reiniciar App" is clicked
 * @param onSettings Callback when "Configuración" is clicked (optional, disabled if null)
 * @param onHelp Callback when "Ayuda" is clicked (optional, disabled if null)
 * @param isKioskModeEnabled Current kiosk mode state (for switch)
 * @param onKioskModeToggle Callback when kiosk toggle is clicked (optional, disabled if null)
 * @param kioskModeAvailable Whether kiosk mode option should be shown (from TpvSettings.kioskModeEnabled)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    onRestartApp: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null,
    isKioskModeEnabled: Boolean = false,
    onKioskModeToggle: (() -> Unit)? = null,
    kioskModeAvailable: Boolean = false,
    isDarkMode: Boolean = false,
    onDarkModeToggle: (() -> Unit)? = null,
) {
    timber.log.Timber.d("[PERF] SettingsBottomSheet SHOW")
    // ✅ Skip partially expanded state - open fully
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp
    ) {
        HideSystemBarsInTutorialDialog()

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

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Spacer(modifier = Modifier.height(8.dp))

            // Option 1: Cambiar usuario (Logout)
            SettingsOption(
                icon = Icons.AutoMirrored.Filled.Logout,
                label = "Cambiar usuario",
                description = "Cerrar sesión y cambiar de usuario",
                enabled = true,
                onClick = onLogout
            )

            // Option 2: Reiniciar App (Toast/Square pattern)
            SettingsOption(
                icon = Icons.Outlined.RestartAlt,
                label = "Reiniciar App",
                description = "Reinicia la aplicación completamente",
                enabled = true,
                onClick = onRestartApp
            )

            // Option 3: Modo Kiosk (Self-service mode)
            // Only show if kioskModeEnabled is true in TpvSettings (dashboard config)
            if (kioskModeAvailable) {
                SettingsToggleOption(
                    icon = Icons.Default.Storefront,
                    label = "Modo Kiosk",
                    description = "Activar modo autoservicio para clientes",
                    enabled = onKioskModeToggle != null,
                    checked = isKioskModeEnabled,
                    onToggle = { onKioskModeToggle?.invoke() }
                )
            }

            // Option 4: Dark Mode Toggle (always visible)
            SettingsToggleOption(
                icon = Icons.Default.DarkMode,
                label = "Modo Oscuro",
                description = "Cambiar apariencia del terminal",
                enabled = true,
                checked = isDarkMode,
                onToggle = { onDarkModeToggle?.invoke() }
            )

            // Option 5: Configuración
            SettingsOption(
                icon = Icons.Default.Settings,
                label = "Configuración",
                description = "Ajustes del terminal",
                enabled = onSettings != null,
                onClick = { onSettings?.invoke() }
            )

            // Option 5: Ayuda (Future)
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

@Composable
private fun HideSystemBarsInTutorialDialog() {
    if (BuildConfig.ENABLE_PAX_SDK) return

    val view = LocalView.current
    LaunchedEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)

        val controller = WindowInsetsControllerCompat(dialogWindow, dialogWindow.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        dialogWindow.decorView.post {
            controller.hide(WindowInsetsCompat.Type.systemBars())
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

/**
 * Settings option item with toggle switch (for kiosk mode)
 */
@Composable
private fun SettingsToggleOption(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
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

        // Switch
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled
        )
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
                    onRestartApp = {},
                    onSettings = null, // Disabled
                    onHelp = null // Disabled
                )
            }
        }
    }
}
