package com.jaac.avoqado_tpv.features.settings.presentation

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Settings Screen
 *
 * Displays terminal configuration and TPV settings:
 * - Terminal Information (serial, version, venue)
 * - TPV Settings (editable, synced with backend)
 * - Actions (test print, refresh settings)
 *
 * Pattern: Toast POS + Square Terminal settings screens
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar messages
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Configuración",
                subtitle = "Ajustes del terminal",
                onNavigationClick = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        ResponsiveScaffold(
            modifier = Modifier.padding(paddingValues),
            scrollable = false
        ) {
            val sizes = LocalResponsiveSizes.current

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(sizes.paddingScreen),
                verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
            ) {
                // ═══════════════════════════════════════════════════════════════
                // TERMINAL INFORMATION
                // ═══════════════════════════════════════════════════════════════
                item {
                    SectionHeader(
                        title = "Información del Terminal",
                        icon = Icons.Outlined.Info
                    )
                }

                item {
                    SettingsCard {
                        SettingsRow(
                            label = "Número de Serie",
                            value = state.serialNumber ?: "No disponible"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            label = "Venue",
                            value = state.venueName ?: "No configurado"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            label = "Versión de App",
                            value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            label = "Dispositivo",
                            value = "${Build.MANUFACTURER} ${Build.MODEL}"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            label = "Android",
                            value = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // TPV SETTINGS (editable, per-terminal)
                // ═══════════════════════════════════════════════════════════════
                item {
                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                    SectionHeader(
                        title = "Configuración de Pago",
                        icon = Icons.Outlined.CreditCard,
                        subtitle = "Toca para activar/desactivar"
                    )
                }

                item {
                    SettingsCard {
                        SettingsToggleRow(
                            label = "Pantalla de Calificación",
                            description = "Mostrar estrellas después del monto",
                            enabled = state.tpvSettings.showReviewScreen,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleShowReviewScreen() }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            label = "Pantalla de Propina",
                            description = "Mostrar opciones de propina",
                            enabled = state.tpvSettings.showTipScreen,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleShowTipScreen() }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            label = "Opciones de Recibo",
                            description = "Mostrar QR y botón de imprimir",
                            enabled = state.tpvSettings.showReceiptScreen,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleShowReceiptScreen() }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            label = "Propina por Defecto",
                            value = state.tpvSettings.defaultTipPercentage?.let { "$it%" } ?: "Sin preselección"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            label = "Sugerencias de Propina",
                            value = state.tpvSettings.tipSuggestions.joinToString(", ") { "$it%" }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            label = "Requiere PIN",
                            description = "PIN obligatorio para login",
                            enabled = state.tpvSettings.requirePinLogin,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleRequirePinLogin() }
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // ACTIONS
                // ═══════════════════════════════════════════════════════════════
                item {
                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                    SectionHeader(
                        title = "Acciones",
                        icon = Icons.Outlined.Build
                    )
                }

                item {
                    SettingsCard {
                        SettingsActionRow(
                            icon = Icons.Outlined.Print,
                            label = "Imprimir Prueba",
                            description = "Imprime un recibo de prueba",
                            onClick = { viewModel.printTestReceipt() },
                            isLoading = state.isPrinting
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsActionRow(
                            icon = Icons.Outlined.Refresh,
                            label = "Actualizar Configuración",
                            description = "Obtener ajustes del servidor",
                            onClick = { viewModel.refreshSettings() },
                            isLoading = state.isRefreshing
                        )
                    }
                }

                // Bottom spacing
                item {
                    Spacer(modifier = Modifier.height(sizes.spacingLarge))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    description: String,
    enabled: Boolean,
    isSaving: Boolean = false,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isSaving) { onToggle() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Interactive Switch
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            enabled = !isSaving,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    isLoading: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            FilledTonalButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Ejecutar")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    AvoqadoTheme {
        // Preview would need a mock ViewModel
    }
}
