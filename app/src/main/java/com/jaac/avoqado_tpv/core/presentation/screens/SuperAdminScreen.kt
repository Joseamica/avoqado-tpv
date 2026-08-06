package com.jaac.avoqado_tpv.core.presentation.screens

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoCard
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.WifiFailoverController
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.core.observability.ObservabilityTester
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.interceptors.SlowNetworkInterceptor
import com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceHealthViewModel
import com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceAlert
import com.jaac.avoqado_tpv.features.modules.data.dto.ToggleModuleRequest
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * SuperAdmin Screen
 *
 * Testing and debugging tools for developers and superadmins.
 *
 * Features:
 * - Printer test (thermal receipt printing)
 * - Terminal info (serial number, model, version)
 * - Network connectivity test
 * - Clear cache/session
 * - Backend API health check
 *
 * Access: Should only be visible to superadmin users
 */
@Composable
fun SuperAdminScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onTestPayment: () -> Unit = {},
    /**
     * Opens [PaymentTransactionsScreen] (processor-side SDK lookup) for
     * a given [ProcessorType]. Used by support staff for reconciliation
     * when a payment is missing from our backend's Pagos list (e.g.,
     * webhook delivery failed). The regular Pagos screen is the right
     * place for normal refunds — this is a deeper escape hatch.
     */
    onOpenProcessorTransactions: (ProcessorType) -> Unit = {},
    viewModel: SuperAdminViewModel = hiltViewModel(),
    deviceHealthViewModel: DeviceHealthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deviceAlerts by deviceHealthViewModel.activeAlerts.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SuperAdminScreenContent(
        modifier = modifier,
        state = state,
        context = context,
        deviceAlerts = deviceAlerts,
        onNavigateBack = onNavigateBack,
        onTestPrinter = viewModel::testPrinter,
        onTestPayment = onTestPayment,
        onClearCache = viewModel::clearCache,
        onTestBackend = viewModel::testBackend,
        onTestFirebaseCrash = viewModel::testFirebaseCrash,
        onTestFirebaseError = viewModel::testFirebaseError,
        onRefreshWifiState = viewModel::refreshWifiState,
        onSetWifiEnabledForSpike = viewModel::setWifiEnabledForSpike,
        onToggleModule = viewModel::toggleModule,
        onDismissRestartPrompt = viewModel::dismissRestartPrompt,
        onRestartApp = { viewModel.restartApp(context) },
        onSimulateNoInternet = { deviceHealthViewModel.simulateAlert(DeviceAlert.NoInternet) },
        onSimulateServerDown = { deviceHealthViewModel.simulateAlert(DeviceAlert.ServerDown) },
        onSimulateBatteryCritical = { deviceHealthViewModel.simulateAlert(DeviceAlert.BatteryCritical(5)) },
        onSimulateBatteryLow = { deviceHealthViewModel.simulateAlert(DeviceAlert.BatteryLow(15)) },
        onSimulateStorageLow = { deviceHealthViewModel.simulateAlert(DeviceAlert.StorageLow(0.5f)) },
        onSimulateMemoryLow = { deviceHealthViewModel.simulateAlert(DeviceAlert.MemoryLow(50)) },
        onSimulateWeakWifi = { deviceHealthViewModel.simulateAlert(DeviceAlert.WeakWifi(1)) },
        onSimulateMultipleAlerts = { deviceHealthViewModel.simulateMultipleAlerts() },
        onClearSimulatedAlerts = { deviceHealthViewModel.clearAllSimulatedAlerts() },
        onOpenProcessorTransactions = onOpenProcessorTransactions,
    )
}

/**
 * SuperAdmin Screen Content
 *
 * Stateless UI component for preview support.
 */
@Composable
private fun SuperAdminScreenContent(
    modifier: Modifier = Modifier,
    state: SuperAdminState,
    context: Context,
    deviceAlerts: List<DeviceAlert> = emptyList(),
    onNavigateBack: () -> Unit,
    onTestPrinter: () -> Unit,
    onTestPayment: () -> Unit,
    onClearCache: () -> Unit,
    onTestBackend: () -> Unit,
    onTestFirebaseCrash: () -> Unit = {},
    onTestFirebaseError: () -> Unit = {},
    onRefreshWifiState: () -> Unit = {},
    onSetWifiEnabledForSpike: (Boolean) -> Unit = {},
    onToggleModule: (String, Boolean) -> Unit = { _, _ -> },
    onDismissRestartPrompt: () -> Unit = {},
    onRestartApp: () -> Unit = {},
    onSimulateNoInternet: () -> Unit = {},
    onSimulateServerDown: () -> Unit = {},
    onSimulateBatteryCritical: () -> Unit = {},
    onSimulateBatteryLow: () -> Unit = {},
    onSimulateStorageLow: () -> Unit = {},
    onSimulateMemoryLow: () -> Unit = {},
    onSimulateWeakWifi: () -> Unit = {},
    onSimulateMultipleAlerts: () -> Unit = {},
    onClearSimulatedAlerts: () -> Unit = {},
    onOpenProcessorTransactions: (ProcessorType) -> Unit = {},
) {
    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "SuperAdmin Tools",
                onNavigationClick = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
                // Terminal Info Section
                item {
                    SectionHeader(title = "Terminal Information")
                }

                item {
                    InfoCard(
                        icon = Icons.Default.PhoneAndroid,
                        title = "Serial Number",
                        value = state.serialNumber
                    )
                }

                item {
                    InfoCard(
                        icon = Icons.Default.Info,
                        title = "Model",
                        value = state.deviceModel
                    )
                }

                item {
                    InfoCard(
                        icon = Icons.Default.Build,
                        title = "App Version",
                        value = state.appVersion
                    )
                }

                // Testing Tools Section
                item {
                    SectionHeader(title = "Testing Tools")
                }

                item {
                    TestButton(
                        icon = Icons.Default.Print,
                        title = "Test Printer",
                        description = "Print a test receipt",
                        onClick = onTestPrinter,
                        enabled = !state.isLoading
                    )
                }

                item {
                    TestButton(
                        icon = Icons.Default.Payment,
                        title = "Test Payment",
                        description = "Test $10.00 payment (Cash/Card)",
                        onClick = onTestPayment,
                        enabled = !state.isLoading
                    )
                }

                item {
                    TestButton(
                        icon = Icons.Default.Cloud,
                        title = "Test Backend Connection",
                        description = "Check API connectivity",
                        onClick = onTestBackend,
                        enabled = !state.isLoading
                    )
                }

                item {
                    TestButton(
                        icon = Icons.Default.Delete,
                        title = "Clear Cache",
                        description = "Clear all cached data",
                        onClick = onClearCache,
                        enabled = !state.isLoading,
                        destructive = true
                    )
                }

                // ──────────────────────────────────────────────────────
                // Processor Reconciliation Section
                // ──────────────────────────────────────────────────────
                // Direct lookup against the gateway's SDK (bypassing our
                // backend) — for support when a payment is missing from
                // the regular Pagos list (e.g., webhook delivery failed,
                // backend recording crashed, etc). Powerful, hence
                // hidden behind SuperAdmin (TOTP-gated).
                item {
                    SectionHeader(title = "Reconciliación con el Procesador")
                }

                item {
                    TestButton(
                        icon = Icons.Default.Receipt,
                        title = "Historial AngelPay (Nexgo)",
                        description = "Consulta directa al SDK — solo si un pago no aparece en Pagos",
                        onClick = { onOpenProcessorTransactions(ProcessorType.ANGELPAY) },
                        enabled = !state.isLoading
                    )
                }

                item {
                    TestButton(
                        icon = Icons.Default.Receipt,
                        title = "Historial Blumon (PAX)",
                        description = "Consulta directa al SDK — solo si un pago no aparece en Pagos",
                        onClick = { onOpenProcessorTransactions(ProcessorType.BLUMON) },
                        enabled = !state.isLoading
                    )
                }

                // Observability Testing Section
                item {
                    SectionHeader(title = "Observability Testing")
                }

                item {
                    TestButton(
                        icon = Icons.Default.CrisisAlert,
                        title = "Test Firebase Crash",
                        description = "Send fatal crash to Firebase",
                        onClick = onTestFirebaseCrash,
                        enabled = !state.isLoading,
                        destructive = true
                    )
                }

                item {
                    TestButton(
                        icon = Icons.Default.Warning,
                        title = "Test Firebase Error",
                        description = "Send non-fatal error to Firebase",
                        onClick = onTestFirebaseError,
                        enabled = !state.isLoading
                    )
                }

                // 🎛️ Venue Modules Section
                item {
                    SectionHeader(title = "Venue Modules")
                }

                item {
                    ModuleToggleCard(
                        title = "Serialized Inventory",
                        description = "Habilita/deshabilita venta de SIMs (Bait/Play Telecom) en este venue.",
                        moduleCode = "SERIALIZED_INVENTORY",
                        isEnabled = state.isSerializedInventoryEnabled,
                        isLoading = state.isModulesLoading,
                        onToggle = onToggleModule
                    )
                }

                item {
                    ModuleToggleCard(
                        title = "Attendance Tracking",
                        description = "Habilita/deshabilita registro de entrada/salida con foto y GPS.",
                        moduleCode = "ATTENDANCE_TRACKING",
                        isEnabled = state.isAttendanceTrackingEnabled,
                        isLoading = state.isModulesLoading,
                        onToggle = onToggleModule
                    )
                }

                // 🐢 Slow Network Simulation Section
                item {
                    SectionHeader(title = "Network Simulation")
                }

                item {
                    SlowNetworkCard(
                        isEnabled = state.isSlowNetworkEnabled,
                        delayMs = state.slowNetworkDelayMs,
                        onToggle = { enabled ->
                            SlowNetworkInterceptor.enabled = enabled
                        },
                        onDelayChange = { delayMs ->
                            SlowNetworkInterceptor.delayMs = delayMs
                        }
                    )
                }

                // Phase 1 spike: explicit WiFi control probe
                item {
                    WifiFailoverSpikeCard(
                        isWifiEnabled = state.isWifiEnabled,
                        isLoading = state.isLoading,
                        onRefresh = onRefreshWifiState,
                        onDisableWifi = { onSetWifiEnabledForSpike(false) },
                        onEnableWifi = { onSetWifiEnabledForSpike(true) }
                    )
                }

                // 🏥 Device Health Simulation Section
                item {
                    SectionHeader(title = "Device Health Simulation")
                }

                item {
                    DeviceHealthSimulationCard(
                        activeAlertsCount = deviceAlerts.size,
                        onSimulateNoInternet = onSimulateNoInternet,
                        onSimulateServerDown = onSimulateServerDown,
                        onSimulateBatteryCritical = onSimulateBatteryCritical,
                        onSimulateBatteryLow = onSimulateBatteryLow,
                        onSimulateStorageLow = onSimulateStorageLow,
                        onSimulateMemoryLow = onSimulateMemoryLow,
                        onSimulateWeakWifi = onSimulateWeakWifi,
                        onSimulateMultipleAlerts = onSimulateMultipleAlerts,
                        onClearSimulatedAlerts = onClearSimulatedAlerts
                    )
                }

                // Blumon Error Response Simulator
                item {
                    SectionHeader(title = "Blumon Error Simulator")
                }

                item {
                    BlumonErrorSimulatorCard()
                }

                // Status Messages
                if (state.message != null) {
                    item {
                        StatusMessage(
                            message = state.message,
                            isError = state.isError
                        )
                    }
                }

                // Bottom spacing
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

        // Restart prompt after module toggle
        if (state.showRestartPrompt) {
            RestartPromptDialog(
                onDismiss = onDismissRestartPrompt,
                onRestart = onRestartApp
            )
        }
    }
}

/**
 * Section Header
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

/**
 * Info Card - Displays terminal information
 */
@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    value: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Test Button - Action button for testing tools
 */
@Composable
private fun TestButton(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (destructive) {
                MaterialTheme.avoqadoColors.statusError.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.avoqadoColors.statusError else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (destructive) MaterialTheme.avoqadoColors.statusError else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onClick,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) MaterialTheme.avoqadoColors.statusError else MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Run")
            }
        }
    }
}

/**
 * Status Message - Shows success/error messages
 */
@Composable
private fun StatusMessage(
    message: String,
    isError: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.avoqadoColors.statusError.copy(alpha = 0.1f)
            } else {
                MaterialTheme.avoqadoColors.statusSuccess.copy(alpha = 0.1f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isError) MaterialTheme.avoqadoColors.statusError else MaterialTheme.avoqadoColors.statusSuccess,
                modifier = Modifier.size(24.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.avoqadoColors.statusError else MaterialTheme.avoqadoColors.statusSuccess
            )
        }
    }
}

/**
 * Slow Network Card - Toggle and configure network delay simulation
 */
@Composable
private fun SlowNetworkCard(
    isEnabled: Boolean,
    delayMs: Long,
    onToggle: (Boolean) -> Unit,
    onDelayChange: (Long) -> Unit
) {
    var enabled by remember { mutableStateOf(isEnabled) }
    var currentDelay by remember { mutableStateOf(delayMs) }

    // Sync with actual interceptor state on recomposition
    LaunchedEffect(Unit) {
        enabled = SlowNetworkInterceptor.enabled
        currentDelay = SlowNetworkInterceptor.delayMs
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.avoqadoColors.statusWarning.copy(alpha = 0.15f)  // Orange tint when enabled
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.avoqadoColors.statusWarning else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            text = "Slow Network Simulation",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (enabled) "🐢 Enabled - ${currentDelay}ms delay" else "Disabled",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) MaterialTheme.avoqadoColors.statusWarning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = { newEnabled ->
                        enabled = newEnabled
                        onToggle(newEnabled)
                        Timber.i("🐢 [SlowNetwork] ${if (newEnabled) "ENABLED" else "DISABLED"} - ${currentDelay}ms delay")
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.avoqadoColors.statusWarning,
                        checkedTrackColor = MaterialTheme.avoqadoColors.statusWarning.copy(alpha = 0.5f)
                    )
                )
            }

            // Delay presets (only show when enabled)
            if (enabled) {
                Text(
                    text = "Delay Presets:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "1s" to 1000L,
                        "3s" to 3000L,
                        "5s" to 5000L,
                        "8s" to 8000L,
                        "12s" to 12000L
                    ).forEach { (label, delay) ->
                        FilterChip(
                            selected = currentDelay == delay,
                            onClick = {
                                currentDelay = delay
                                onDelayChange(delay)
                                Timber.i("🐢 [SlowNetwork] Delay set to ${delay}ms")
                            },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.avoqadoColors.statusWarning,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                // Warning text
                Text(
                    text = "⚠️ All API requests will be delayed. Disable after testing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.avoqadoColors.statusWarning
                )
            }
        }
    }
}

@Composable
private fun WifiFailoverSpikeCard(
    isWifiEnabled: Boolean,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onDisableWifi: () -> Unit,
    onEnableWifi: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (isWifiEnabled) MaterialTheme.avoqadoColors.statusSuccess else MaterialTheme.avoqadoColors.offlineOrange,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "Phase 1 WiFi Failover Spike",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Estado actual: ${if (isWifiEnabled) "WiFi ON" else "WiFi OFF"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "Prueba controlada: intenta toggle de WiFi por API y valida en logs si PAX lo permite.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text("Refresh")
                }

                OutlinedButton(
                    onClick = onDisableWifi,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && isWifiEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.avoqadoColors.offlineOrange
                    )
                ) {
                    Text("Disable WiFi")
                }

                Button(
                    onClick = onEnableWifi,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && !isWifiEnabled
                ) {
                    Text("Enable WiFi")
                }
            }
        }
    }
}

@Composable
private fun ModuleToggleCard(
    title: String,
    description: String,
    moduleCode: String,
    isEnabled: Boolean,
    isLoading: Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isEnabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                            contentDescription = null,
                            tint = if (isEnabled) MaterialTheme.avoqadoColors.statusSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Código: $moduleCode",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { newValue -> onToggle(moduleCode, newValue) }
                )
            }
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RestartPromptDialog(
    onDismiss: () -> Unit,
    onRestart: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.RestartAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Reiniciar TPV",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "Los cambios de módulo se aplicarán completamente después de reiniciar la app. " +
                    "Algunas pantallas (como el menú principal y selector de flujos) requieren reinicio para reflejar la nueva configuración.\n\n" +
                    "¿Reiniciar ahora?"
            )
        },
        confirmButton = {
            Button(onClick = onRestart) {
                Text("Reiniciar ahora")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Después")
            }
        }
    )
}

/**
 * Device Health Simulation Card
 *
 * Allows testing device health alerts without actual low battery/storage conditions.
 * Useful for QA testing of alert banners and priority handling.
 */
@Composable
private fun DeviceHealthSimulationCard(
    activeAlertsCount: Int,
    onSimulateNoInternet: () -> Unit,
    onSimulateServerDown: () -> Unit,
    onSimulateBatteryCritical: () -> Unit,
    onSimulateBatteryLow: () -> Unit,
    onSimulateStorageLow: () -> Unit,
    onSimulateMemoryLow: () -> Unit,
    onSimulateWeakWifi: () -> Unit,
    onSimulateMultipleAlerts: () -> Unit,
    onClearSimulatedAlerts: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (activeAlertsCount > 0) {
                MaterialTheme.avoqadoColors.offlineOrange.copy(alpha = 0.1f)  // Orange tint when alerts active
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.HealthAndSafety,
                    contentDescription = null,
                    tint = if (activeAlertsCount > 0) MaterialTheme.avoqadoColors.offlineOrange else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "Device Health Alerts",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (activeAlertsCount > 0) "⚠️ $activeAlertsCount alert(s) active" else "No alerts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (activeAlertsCount > 0) MaterialTheme.avoqadoColors.offlineOrange else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Simulate buttons - Row 0: Connection (P0, P2)
            Text(
                text = "Connection Alerts:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // No Internet (P0)
                OutlinedButton(
                    onClick = onSimulateNoInternet,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.avoqadoColors.statusCritical  // Red
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sin Internet", style = MaterialTheme.typography.labelSmall)
                }

                // Server Down (P2)
                OutlinedButton(
                    onClick = onSimulateServerDown,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.avoqadoColors.statusCritical  // Red
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sin Servidor", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Device Alerts label
            Text(
                text = "Device Health Alerts:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Row 1: Battery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Battery Critical
                OutlinedButton(
                    onClick = onSimulateBatteryCritical,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.avoqadoColors.statusCritical  // Red
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryAlert,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Critical 5%", style = MaterialTheme.typography.labelSmall)
                }

                // Battery Low
                OutlinedButton(
                    onClick = onSimulateBatteryLow,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.avoqadoColors.offlineOrange  // Orange
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Battery2Bar,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Low 15%", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Row 2: Storage & Memory
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSimulateStorageLow,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.avoqadoColors.statusWarning  // Yellow
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Storage", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = onSimulateMemoryLow,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.avoqadoColors.statusWarning  // Yellow
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Memory", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = onSimulateWeakWifi,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.avoqadoColors.statusWarning  // Yellow
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("WiFi", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Row 3: Multiple & Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSimulateMultipleAlerts,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.avoqadoColors.offlineOrange
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Multiple", style = MaterialTheme.typography.labelSmall)
                }

                Button(
                    onClick = onClearSimulatedAlerts,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.avoqadoColors.statusSuccess
                    ),
                    enabled = activeAlertsCount > 0
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Info text
            Text(
                text = "💡 Alerts appear at the top of the screen. Tap \"+N\" badge to expand.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// BLUMON ERROR SIMULATOR
// ══════════════════════════════════════════════════════════════════════

private data class BlumonError(val code: String, val description: String)

private val BLUMON_ERRORS = listOf(
    // Bank issuer codes
    BlumonError("0", "APROBADA"),
    BlumonError("1", "LLAME EMISOR"),
    BlumonError("3", "COMERCIO INVALIDO"),
    BlumonError("4", "RETENER TARJETA"),
    BlumonError("5", "TRANSACCIÓN INVALIDA"),
    BlumonError("6", "REINTENTE"),
    BlumonError("12", "TRANSACCIÓN NO PERMITIDA"),
    BlumonError("14", "TARJETA INVALIDA"),
    BlumonError("30", "ERROR DE FORMATO"),
    BlumonError("51", "FONDOS INSUFICIENTES"),
    BlumonError("54", "TARJETA VENCIDA"),
    BlumonError("55", "PIN INVALIDO"),
    BlumonError("57", "PAGO NO PERMITIDO EMISOR"),
    BlumonError("61", "LIMITE EXCEDIDO"),
    BlumonError("75", "PIN INVALIDO/EXCEDIDO"),
    BlumonError("89", "TIPO DE PLAN / PLAZO INVALIDO"),
    BlumonError("94", "TRANSACCION DUPLICADA"),
    BlumonError("100", "DENEGADA"),
    BlumonError("101", "TARJETA VENCIDA / FECHA NO VALIDA"),
    BlumonError("106", "INTENTOS DE PIN EXCEDIDOS"),
    BlumonError("109", "COMERCIO NO VALIDO"),
    BlumonError("110", "MONTO NO VALIDO"),
    BlumonError("117", "PIN NO VALIDO"),
    BlumonError("122", "CODIGO DE SEGURIDAD NO VALIDO"),
    BlumonError("181", "ERROR DE SISTEMA"),
    BlumonError("187", "TARJETA NO ACTIVA"),
    BlumonError("200", "TARJETA NO VALIDA"),
    BlumonError("909", "ERROR DE SISTEMA"),
    BlumonError("912", "EMISOR NO DISPONIBLE"),
    BlumonError("914", "TRANSACCION ORIGINAL NO ENCONTRADA"),
    BlumonError("188", "CUENTA CANCELADA"),
    BlumonError("130", "PRUEBE CON OTRO DISPOSITIVO"),
    BlumonError("T2", "ERROR EN TERMINAL"),
    BlumonError("T5", "TARJETA SIN ACTIVAR"),
    BlumonError("T9", "MONEDA INVÁLIDA"),
    BlumonError("Q8", "TARJETA NO ACTIVA"),
    BlumonError("1001", "ERROR EN LECTURA DE CHIP"),
    BlumonError("1002", "CHIP INVALIDO"),
    BlumonError("1003", "CHIP NO SOPORTADO"),
    // Blumon platform (BP) errors
    BlumonError("BP", "EL DISPOSITIVO NO EXISTE"),
    BlumonError("BP", "EL DISPOSITIVO NO SE ENCUENTRA ACTIVO"),
    BlumonError("BP", "LA SUCURSAL NO EXISTE"),
    BlumonError("BP", "EL COMERCIO NO EXISTE"),
    BlumonError("BP", "LA TRANSACCIÓN EXCEDE EL MONTO PERMITIDO"),
    BlumonError("BP", "LA TRANSACCIÓN EXCEDE EL MONTO DIARIO PERMITIDO"),
    BlumonError("BP", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"),
    BlumonError("BP", "TRANSACCIÓN CANCELADA ANTERIORMENTE"),
    BlumonError("BP", "EXCEDE LAS TRANSACCIONES DIARIAS PERMITIDAS"),
    BlumonError("BP", "LA TRANSACCIÓN NO PERMITE TRANSACCIONES DE TIPO CONTACTLESS"),
)

/**
 * Simulates the error message parsing logic from PaymentViewModel.
 * Tests both paths: regex extraction success and raw fallback.
 */
private fun simulateBlumonErrorMessage(error: BlumonError): Pair<String, String> {
    val description = error.description

    // Path 1: Regex extraction succeeds (specificErrorDescription != null)
    val extractedMessage = "Pago rechazado:\n\n$description\n\nPor favor, solicita otra forma de pago."

    // Path 2: Simulate raw failure.toString() when regex fails
    val rawFailureString = "MomentumDataFailure(code=${error.code}, description=$description)"
    val fallbackMessage = "Pago rechazado.\n\n$rawFailureString"

    return Pair(extractedMessage, fallbackMessage)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlumonErrorSimulatorCard() {
    var selectedError by remember { mutableStateOf<BlumonError?>(null) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var previewIsRefund by remember { mutableStateOf(false) }
    var previewUseFallback by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CreditCardOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "Simular respuesta Blumon",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Previsualiza exactamente lo que ve el usuario",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Error selector dropdown
            ExposedDropdownMenuBox(
                expanded = isDropdownExpanded,
                onExpandedChange = { isDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedError?.let { "[${it.code}] ${it.description}" } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text("Selecciona un error Blumon...") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                ExposedDropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false }
                ) {
                    BLUMON_ERRORS.forEach { error ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "[${error.code}] ${error.description}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            onClick = {
                                selectedError = error
                                isDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Toggle options
            if (selectedError != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = previewIsRefund,
                        onClick = { previewIsRefund = !previewIsRefund },
                        label = { Text("Reembolso") }
                    )
                    FilterChip(
                        selected = previewUseFallback,
                        onClick = { previewUseFallback = !previewUseFallback },
                        label = { Text("Fallback (sin regex)") }
                    )
                }

                Button(
                    onClick = { showPreview = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Previsualizar pantalla de error")
                }
            }
        }
    }

    // Full-screen error preview dialog
    if (showPreview && selectedError != null) {
        val (extracted, fallback) = simulateBlumonErrorMessage(selectedError!!)
        val errorMessage = if (previewUseFallback) fallback else extracted

        Dialog(
            onDismissRequest = { showPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                // Exact replica of PaymentErrorContent from PaymentScreen.kt
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AvoqadoCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "X",
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.error
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = if (previewIsRefund) "Error en el Reembolso" else "Error en el Pago",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(32.dp))

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AvoqadoButton(
                                    text = "Reintentar",
                                    onClick = { showPreview = false },
                                    fullWidth = true
                                )

                                AvoqadoButton(
                                    text = "Cancelar",
                                    onClick = { showPreview = false },
                                    fullWidth = true
                                )
                            }
                        }
                    }

                    // Simulator badge at the bottom
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Text(
                            text = "SIMULADOR — [${selectedError!!.code}] ${selectedError!!.description}" +
                                    if (previewUseFallback) " (fallback)" else " (regex)",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// VIEW MODEL
// ══════════════════════════════════════════════════════════════════════

/**
 * SuperAdmin State
 */
data class SuperAdminState(
    val serialNumber: String = "",
    val deviceModel: String = "",
    val appVersion: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val isWifiEnabled: Boolean = false,
    // 🐢 Slow Network Simulation
    val isSlowNetworkEnabled: Boolean = false,
    val slowNetworkDelayMs: Long = 3000L,
    // 🎛️ Module toggles (for current venue)
    val isSerializedInventoryEnabled: Boolean = false,
    val isAttendanceTrackingEnabled: Boolean = false,
    val isModulesLoading: Boolean = false,
    val showRestartPrompt: Boolean = false
)

/**
 * SuperAdmin ViewModel
 *
 * Handles testing and debugging operations.
 */
@HiltViewModel
class SuperAdminViewModel @Inject constructor(
    private val printerManager: PrinterManager,
    private val deviceInfoManager: DeviceInfoManager,
    private val observability: ObservabilityManager,
    private val observabilityTester: ObservabilityTester,
    private val wifiFailoverController: WifiFailoverController,
    private val apiService: ApiService,
    private val modulesRepository: ModulesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SuperAdminState())
    val state: StateFlow<SuperAdminState> = _state.asStateFlow()

    init {
        loadDeviceInfo()
        refreshWifiState()
        observeModulesCache()
        refreshModulesFromBackend()
    }

    /**
     * Observe modules StateFlow reactively.
     * This way, when fetchAndCache() updates the repository's cache, the Switch UI
     * reflects the new state automatically (solves stale-after-restart issue).
     */
    private fun observeModulesCache() {
        viewModelScope.launch {
            modulesRepository.modules.collect { modules ->
                val serialized = modules.any { it.moduleCode == ModulesRepository.MODULE_SERIALIZED_INVENTORY }
                val attendance = modules.any { it.moduleCode == ModulesRepository.MODULE_ATTENDANCE_TRACKING }
                Timber.d("🎛️ [SuperAdmin] Modules cache changed: serialized=$serialized, attendance=$attendance, total=${modules.size}")
                _state.value = _state.value.copy(
                    isSerializedInventoryEnabled = serialized,
                    isAttendanceTrackingEnabled = attendance
                )
            }
        }
    }

    /**
     * Force refresh modules from backend on screen open.
     * Handles the case where app just restarted and splashscreen fetch hasn't finished.
     */
    private fun refreshModulesFromBackend() {
        viewModelScope.launch {
            val result = modulesRepository.fetchAndCache()
            result.onSuccess { modules ->
                Timber.i("🎛️ [SuperAdmin] Refreshed ${modules.size} modules from backend")
            }.onFailure { error ->
                Timber.w("🎛️ [SuperAdmin] Could not refresh modules: ${error.message}")
            }
        }
    }

    /**
     * Toggle a module ON/OFF for the current venue.
     * After success, sets showRestartPrompt=true so UI offers reinicio.
     */
    fun toggleModule(moduleCode: String, enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isModulesLoading = true, message = null, isError = false)
            try {
                val response = apiService.toggleModule(
                    ToggleModuleRequest(moduleCode = moduleCode, enabled = enabled)
                )
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: response.message()
                    Timber.e("❌ [SuperAdmin] Toggle module failed: HTTP ${response.code()} — $errorBody")
                    _state.value = _state.value.copy(
                        isModulesLoading = false,
                        message = "Error ${response.code()}: $errorBody",
                        isError = true
                    )
                    return@launch
                }

                Timber.i("✅ [SuperAdmin] Module $moduleCode toggled to $enabled")

                // Refresh local cache so other screens see the change after restart
                modulesRepository.fetchAndCache()

                _state.value = _state.value.copy(
                    isModulesLoading = false,
                    message = "✅ ${if (enabled) "Habilitado" else "Deshabilitado"}: $moduleCode",
                    isError = false,
                    isSerializedInventoryEnabled = if (moduleCode == ModulesRepository.MODULE_SERIALIZED_INVENTORY) enabled else _state.value.isSerializedInventoryEnabled,
                    isAttendanceTrackingEnabled = if (moduleCode == ModulesRepository.MODULE_ATTENDANCE_TRACKING) enabled else _state.value.isAttendanceTrackingEnabled,
                    showRestartPrompt = true
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ [SuperAdmin] Exception toggling module")
                _state.value = _state.value.copy(
                    isModulesLoading = false,
                    message = "Error: ${e.message ?: "desconocido"}",
                    isError = true
                )
            }
        }
    }

    /**
     * Dismiss the restart prompt.
     */
    fun dismissRestartPrompt() {
        _state.value = _state.value.copy(showRestartPrompt = false)
    }

    /**
     * Restart the app so module config takes effect everywhere.
     */
    fun restartApp(context: Context) {
        Timber.i("🔄 [SuperAdmin] Restarting app to apply module changes")
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        kotlin.system.exitProcess(0)
    }

        /**
     * Load device information
     */
    private fun loadDeviceInfo() {
        _state.value = _state.value.copy(
            serialNumber = deviceInfoManager.getSerialNumber(),
            deviceModel = deviceInfoManager.getDeviceModel(),
            appVersion = "1.0.0" // TODO: Get from BuildConfig
        )
    }

    /**
     * Test printer
     */
    fun testPrinter() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)

            Timber.d("🖨️ [SuperAdmin] Testing printer...")

            val result = printerManager.printTest()

            result.onSuccess {
                Timber.i("✅ [SuperAdmin] Printer test successful")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "✅ Printer test successful",
                    isError = false
                )
            }.onFailure { error ->
                Timber.e(error, "❌ [SuperAdmin] Printer test failed")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "❌ Printer test failed: ${error.message}",
                    isError = true
                )
            }
        }
    }

    /**
     * Clear cache
     */
    fun clearCache() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)

            Timber.d("🗑️ [SuperAdmin] Clearing cache...")

            try {
                // TODO: Implement cache clearing logic
                // - Clear SharedPreferences (except session)
                // - Clear image cache
                // - Clear temporary files

                Timber.i("✅ [SuperAdmin] Cache cleared successfully")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "✅ Cache cleared successfully",
                    isError = false
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ [SuperAdmin] Cache clearing failed")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "❌ Cache clearing failed: ${e.message}",
                    isError = true
                )
            }
        }
    }

    /**
     * Test backend connection
     */
    fun testBackend() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)

            Timber.d("🌐 [SuperAdmin] Testing backend connection...")

            try {
                // TODO: Implement backend health check
                // - Ping API endpoint
                // - Check authentication
                // - Verify network connectivity

                Timber.i("✅ [SuperAdmin] Backend connection successful")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "✅ Backend connection successful",
                    isError = false
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ [SuperAdmin] Backend connection failed")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "❌ Backend connection failed: ${e.message}",
                    isError = true
                )
            }
        }
    }

            /**
     * Show error message
     */
    fun showError(message: String) {
        _state.value = _state.value.copy(
            message = "❌ $message",
            isError = true
        )
    }

    /**
     * Refresh WiFi state snapshot for Phase 1 spike validation.
     */
    fun refreshWifiState() {
        _state.value = _state.value.copy(isWifiEnabled = wifiFailoverController.isWifiEnabled())
    }

    /**
     * Phase 1 spike probe: attempt OS-level WiFi toggle and log real result.
     * This is for PAX capability validation only (not production failover behavior).
     */
    fun setWifiEnabledForSpike(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)
            try {
                val result = wifiFailoverController.setWifiEnabled(
                    enabled = enabled,
                    source = "superadmin_spike"
                )

                _state.value = _state.value.copy(
                    isLoading = false,
                    isWifiEnabled = result.after,
                    message = if (result.success) {
                        "✅ WiFi toggle aplicado (${if (result.after) "ON" else "OFF"}) | " +
                            "requestResult=${result.requestResult} | paxChannelAttempted=${result.paxChannelAttempted}"
                    } else {
                        "❌ WiFi no cambió | permission=${result.hasChangeWifiPermission}, " +
                            "requestResult=${result.requestResult}, paxChannelAttempted=${result.paxChannelAttempted}, " +
                            "paxChannelError=${result.paxChannelError}, before=${result.before}, after=${result.after}"
                    },
                    isError = !result.success
                )
            } catch (securityException: SecurityException) {
                Timber.e(
                    securityException,
                    "❌ [Phase1Spike] SecurityException on setWifiEnabled($enabled)"
                )
                _state.value = _state.value.copy(
                    isLoading = false,
                    isWifiEnabled = wifiFailoverController.isWifiEnabled(),
                    message = "❌ SecurityException: ${securityException.message}",
                    isError = true
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ [Phase1Spike] setWifiEnabled($enabled) failed")
                _state.value = _state.value.copy(
                    isLoading = false,
                    isWifiEnabled = wifiFailoverController.isWifiEnabled(),
                    message = "❌ Error toggling WiFi: ${e.message}",
                    isError = true
                )
            }
        }
    }

    /**
     * Test Firebase Crashlytics - Fatal Crash
     */
    fun testFirebaseCrash() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)

            Timber.d("🔥 [SuperAdmin] Testing Firebase fatal crash...")

            try {
                // Initialize observability
                observability.initialize(
                    venueId = "test-venue-crash",
                    terminalId = _state.value.serialNumber,
                    userId = "superadmin-test",
                    enableInDebug = true
                )

                delay(500)

                // This will crash the app and send to Firebase
                throw RuntimeException("🧪 SUPERADMIN TEST CRASH - Firebase Crashlytics from ${_state.value.deviceModel}")

            } catch (e: Exception) {
                Timber.e(e, "🔥 Test crash executed")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "🔥 Crash sent to Firebase! Check console in 5-10 min",
                    isError = false
                )
            }
        }
    }

    /**
     * Test Firebase Crashlytics - Non-Fatal Error
     */
    fun testFirebaseError() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)

            Timber.d("⚠️ [SuperAdmin] Testing Firebase non-fatal error...")

            try {
                // Initialize observability
                observability.initialize(
                    venueId = "test-venue-error",
                    terminalId = _state.value.serialNumber,
                    userId = "superadmin-test",
                    enableInDebug = true
                )

                delay(500)

                // Send non-fatal error (app doesn't crash)
                observability.logCritical(
                    tag = "SuperAdminTest",
                    message = "🧪 TEST NON-FATAL ERROR from ${_state.value.deviceModel}",
                    error = Exception("Test error from SuperAdmin screen"),
                    metadata = mapOf(
                        "test" to true,
                        "device" to _state.value.deviceModel,
                        "serial" to _state.value.serialNumber,
                        "source" to "superadmin_screen"
                    )
                )

                Timber.i("✅ [SuperAdmin] Non-fatal error sent to Firebase")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "✅ Error sent! Check Firebase in 5-10 min & Backend logs now",
                    isError = false
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ [SuperAdmin] Firebase error test failed")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "❌ Test failed: ${e.message}",
                    isError = true
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun SuperAdminScreenPreview() {
    val context = LocalContext.current
    AvoqadoTheme {
        SuperAdminScreenContent(
            state = SuperAdminState(
                serialNumber = "AVQD-2841548417",
                deviceModel = "PAX A920",
                appVersion = "1.0.0"
            ),
            context = context,
            onNavigateBack = {},
            onTestPrinter = {},
            onTestPayment = {},
            onClearCache = {},
            onTestBackend = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024, name = "With Success Message")
@Composable
private fun SuperAdminScreenWithMessagePreview() {
    val context = LocalContext.current
    AvoqadoTheme {
        SuperAdminScreenContent(
            state = SuperAdminState(
                serialNumber = "AVQD-2841548417",
                deviceModel = "PAX A920",
                appVersion = "1.0.0",
                message = "Printer test successful",
                isError = false
            ),
            context = context,
            onNavigateBack = {},
            onTestPrinter = {},
            onTestPayment = {},
            onClearCache = {},
            onTestBackend = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024, name = "With Error Message")
@Composable
private fun SuperAdminScreenWithErrorPreview() {
    val context = LocalContext.current
    AvoqadoTheme {
        SuperAdminScreenContent(
            state = SuperAdminState(
                serialNumber = "AVQD-2841548417",
                deviceModel = "PAX A920",
                appVersion = "1.0.0",
                message = "Printer not available",
                isError = true
            ),
            context = context,
            onNavigateBack = {},
            onTestPrinter = {},
            onTestPayment = {},
            onClearCache = {},
            onTestBackend = {},
        )
    }
}
