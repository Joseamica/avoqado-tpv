package com.jaac.avoqado_tpv.features.settings.presentation

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.VenueStatusRow
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
    onNavigateToShifts: () -> Unit = {},
    onNavigateToSelfUpdate: () -> Unit = {},
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

    // Active Shift Blocked Dialog (Option E validation)
    if (state.showActiveShiftBlockedDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissActiveShiftBlockedDialog() },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text("Turno de caja activo")
            },
            text = {
                Column {
                    Text(
                        text = "No puedes apagar los turnos de caja mientras haya una caja abierta.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Turno de caja: ${state.activeShiftStaffName ?: "Desconocido"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Primero cierra la caja desde la pantalla de Turnos de caja.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.dismissActiveShiftBlockedDialog()
                        onNavigateToShifts()
                    }
                ) {
                    Text("Ir a Turnos de caja")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissActiveShiftBlockedDialog() }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Open Shift First Dialog (Kiosk activation requires open shift)
    if (state.showOpenShiftFirstDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissOpenShiftFirstDialog() },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Storefront,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("Abre la caja primero")
            },
            text = {
                Column {
                    Text(
                        text = "Para activar el modo kiosko, primero debes abrir la caja.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "El kiosko necesita un turno de caja abierto para procesar pedidos correctamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.dismissOpenShiftFirstDialog()
                        onNavigateToShifts()
                    }
                ) {
                    Text("Ir a Turnos de caja")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissOpenShiftFirstDialog() }) {
                    Text("Cancelar")
                }
            }
        )
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

            // Track expanded state for each section
            var terminalExpanded by remember { mutableStateOf(true) }
            var shiftsExpanded by remember { mutableStateOf(false) }
            var tablesExpanded by remember { mutableStateOf(false) }
            var paymentExpanded by remember { mutableStateOf(false) }
            var kioskExpanded by remember { mutableStateOf(false) }
            var verificationExpanded by remember { mutableStateOf(false) }
            var securityExpanded by remember { mutableStateOf(false) }
            var actionsExpanded by remember { mutableStateOf(true) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(sizes.paddingScreen),
                verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
            ) {
                // ═══════════════════════════════════════════════════════════════
                // TERMINAL INFORMATION
                // ═══════════════════════════════════════════════════════════════
                item {
                    CollapsibleSection(
                        title = "Información del Terminal",
                        icon = Icons.Outlined.Info,
                        expanded = terminalExpanded,
                        onToggle = { terminalExpanded = !terminalExpanded }
                    ) {
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
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        VenueStatusRow(
                            status = state.venueStatus,
                            modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp)
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // SHIFT SYSTEM SETTINGS
                // ═══════════════════════════════════════════════════════════════
                item {
                    CollapsibleSection(
                        title = "Turnos de caja",
                        icon = Icons.Filled.Schedule,
                        subtitle = "Gestión de caja y personal",
                        expanded = shiftsExpanded,
                        onToggle = { shiftsExpanded = !shiftsExpanded }
                    ) {
                        SettingsToggleRow(
                            label = "Usar turnos de caja",
                            description = "Hay que abrir la caja para poder cobrar",
                            enabled = state.isShiftSystemEnabled,
                            isSaving = false,
                            onToggle = { viewModel.toggleShiftSystem() }
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // MESAS MODULE (restaurant mode)
                // ═══════════════════════════════════════════════════════════════
                item {
                    CollapsibleSection(
                        title = "Módulo Mesas",
                        icon = Icons.Outlined.TableRestaurant,
                        subtitle = "Servicio de mesas en el terminal",
                        expanded = tablesExpanded,
                        onToggle = { tablesExpanded = !tablesExpanded }
                    ) {
                        SettingsToggleRow(
                            label = "Modo Restaurante",
                            description = "Muestra Mesas en el inicio y oculta Órdenes",
                            enabled = state.tpvSettings.restaurantModeEnabled,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleRestaurantMode() }
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // TPV SETTINGS (editable, per-terminal)
                // ═══════════════════════════════════════════════════════════════
                item {
                    CollapsibleSection(
                        title = "Configuración de Pago",
                        icon = Icons.Outlined.CreditCard,
                        subtitle = "Propina, recibo y calificación",
                        expanded = paymentExpanded,
                        onToggle = { paymentExpanded = !paymentExpanded }
                    ) {
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
                // KIOSK MODE FUNCTIONALITY
                // ═══════════════════════════════════════════════════════════════
                item {
                    CollapsibleSection(
                        title = "Modo Kiosko",
                        icon = Icons.Outlined.Storefront,
                        subtitle = "Funcionalidad de autoservicio",
                        expanded = kioskExpanded,
                        onToggle = { kioskExpanded = !kioskExpanded }
                    ) {
                        SettingsToggleRow(
                            label = "Habilitar Kiosko",
                            description = "Permite activar modo autoservicio en este terminal",
                            enabled = state.tpvSettings.kioskModeEnabled,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleKioskModeEnabled() }
                        )
                        // Only show merchant dropdown when kiosk is enabled
                        if (state.tpvSettings.kioskModeEnabled) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            MerchantDropdownRow(
                                merchants = state.merchants,
                                selectedMerchantId = state.tpvSettings.kioskDefaultMerchantId,
                                onMerchantSelected = { viewModel.setKioskDefaultMerchant(it) }
                            )
                        }
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // VERIFICATION SETTINGS (Step 4: Sale Verification)
                // ═══════════════════════════════════════════════════════════════
                item {
                    CollapsibleSection(
                        title = "Verificación de Venta",
                        icon = Icons.Outlined.CameraAlt,
                        subtitle = "Captura de evidencia post-pago",
                        expanded = verificationExpanded,
                        onToggle = { verificationExpanded = !verificationExpanded }
                    ) {
                        SettingsToggleRow(
                            label = "Pantalla de Verificación",
                            description = "Mostrar captura de fotos/códigos después del pago",
                            enabled = state.tpvSettings.showVerificationScreen,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleShowVerificationScreen() }
                        )
                        // Only show sub-options when verification screen is enabled
                        if (state.tpvSettings.showVerificationScreen) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsToggleRow(
                                label = "Requiere Foto",
                                description = "Foto obligatoria para confirmar venta",
                                enabled = state.tpvSettings.requireVerificationPhoto,
                                isSaving = state.isSaving,
                                onToggle = { viewModel.toggleRequireVerificationPhoto() }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsToggleRow(
                                label = "Requiere Código de Barras",
                                description = "Escaneo de código obligatorio para confirmar venta",
                                enabled = state.tpvSettings.requireVerificationBarcode,
                                isSaving = state.isSaving,
                                onToggle = { viewModel.toggleRequireVerificationBarcode() }
                            )
                        }
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // SECURITY & ATTENDANCE SETTINGS
                // ═══════════════════════════════════════════════════════════════
                item {
                    CollapsibleSection(
                        title = "Seguridad y Verificación",
                        icon = Icons.Outlined.Security,
                        subtitle = "Control de asistencia y acceso",
                        expanded = securityExpanded,
                        onToggle = { securityExpanded = !securityExpanded }
                    ) {
                        SettingsToggleRow(
                            label = "Foto al Registrar Entrada",
                            description = "Requiere selfie con GPS al hacer clock-in",
                            enabled = state.tpvSettings.requireClockInPhoto,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleRequireClockInPhoto() }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            label = "Foto al Registrar Salida",
                            description = "Requiere selfie con GPS al hacer clock-out",
                            enabled = state.tpvSettings.requireClockOutPhoto,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleRequireClockOutPhoto() }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            label = "Requiere Clock-in para Iniciar Sesión",
                            description = "El personal debe tener entrada activa para acceder",
                            enabled = state.tpvSettings.requireClockInToLogin,
                            isSaving = state.isSaving,
                            onToggle = { viewModel.toggleRequireClockInToLogin() }
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // ACTIONS
                // ═══════════════════════════════════════════════════════════════
                item {
                    CollapsibleSection(
                        title = "Acciones",
                        icon = Icons.Outlined.Build,
                        subtitle = "Pruebas y actualizaciones",
                        expanded = actionsExpanded,
                        onToggle = { actionsExpanded = !actionsExpanded }
                    ) {
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
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsActionRow(
                            icon = Icons.Outlined.SystemUpdate,
                            label = "Buscar Actualizaciones",
                            description = "Versión actual: ${BuildConfig.VERSION_NAME}",
                            onClick = { onNavigateToSelfUpdate() },
                            isLoading = false
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

/**
 * Collapsible section with animated expand/collapse.
 * Combines a header with an optional card content that can be toggled.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron_rotation"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            // Header (always visible, clickable to toggle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Colapsar" else "Expandir",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotationAngle)
                )
            }

            // Collapsible content with animation
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

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

/**
 * Dropdown row for selecting default merchant in kiosk mode.
 * Shows available merchant accounts and allows selecting a default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MerchantDropdownRow(
    merchants: List<MerchantAccount>,
    selectedMerchantId: String?,
    onMerchantSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedMerchant = merchants.find { it.merchantAccountId == selectedMerchantId || it.id == selectedMerchantId }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Merchant Predeterminado",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Selecciona la cuenta de pago para kiosko",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedMerchant?.displayName ?: "Mostrar selección",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                colors = OutlinedTextFieldDefaults.colors()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                // Option to show selection (null = no default)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                "Mostrar selección",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "El cliente elige al pagar",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onMerchantSelected(null)
                        expanded = false
                    },
                    leadingIcon = {
                        if (selectedMerchantId == null) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )

                // Divider between "show selection" and merchant options
                if (merchants.isNotEmpty()) {
                    HorizontalDivider()
                }

                // Merchant options
                merchants.forEach { merchant ->
                    val isSelected = merchant.merchantAccountId == selectedMerchantId || merchant.id == selectedMerchantId
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    merchant.displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                merchant.description?.let { desc ->
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            // Prefer merchantAccountId (backend CUID) if available, otherwise use local id
                            onMerchantSelected(merchant.merchantAccountId ?: merchant.id)
                            expanded = false
                        },
                        leadingIcon = {
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
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
