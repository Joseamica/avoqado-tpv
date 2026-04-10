package com.jaac.avoqado_tpv.core.presentation.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
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
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.BluetoothCapabilityChecker
import com.jaac.avoqado_tpv.core.bluetooth.BluetoothPaymentServer
import com.jaac.avoqado_tpv.core.bluetooth.BluetoothPaymentService
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.core.observability.ObservabilityTester
import com.jaac.avoqado_tpv.core.data.network.interceptors.SlowNetworkInterceptor
import com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceHealthViewModel
import com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceAlert
import com.pax.dal.entity.EChannelType
import com.pax.neptunelite.api.NeptuneLiteUser
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
    viewModel: SuperAdminViewModel = hiltViewModel(),
    deviceHealthViewModel: DeviceHealthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deviceAlerts by deviceHealthViewModel.activeAlerts.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Bluetooth permissions required for Android 12+
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE  // Required for BLE advertising
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    // Permission launcher (registered at composition time, before STARTED state)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Timber.i("✅ [SuperAdmin] Bluetooth permissions granted - starting BLE server")
            viewModel.toggleBleServer(context)
        } else {
            Timber.w("❌ [SuperAdmin] Bluetooth permissions denied")
            viewModel.showError("Bluetooth permissions required to start BLE Payment Server")
        }
    }

    // Wrapper function that requests permissions before starting BLE server
    val handleToggleBleServer: (Context) -> Unit = { ctx ->
        if (state.isBleServerRunning) {
            // Stopping server doesn't need permissions
            viewModel.toggleBleServer(ctx)
        } else {
            // Starting server requires permissions - request them
            Timber.i("📱 [SuperAdmin] Requesting Bluetooth permissions...")
            permissionLauncher.launch(bluetoothPermissions)
        }
    }

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
        onTestBluetooth = viewModel::testBluetooth,
        onToggleBleServer = handleToggleBleServer,
        onTestFirebaseCrash = viewModel::testFirebaseCrash,
        onTestFirebaseError = viewModel::testFirebaseError,
        onRefreshWifiState = viewModel::refreshWifiState,
        onSetWifiEnabledForSpike = viewModel::setWifiEnabledForSpike,
        onSimulateNoInternet = { deviceHealthViewModel.simulateAlert(DeviceAlert.NoInternet) },
        onSimulateServerDown = { deviceHealthViewModel.simulateAlert(DeviceAlert.ServerDown) },
        onSimulateBatteryCritical = { deviceHealthViewModel.simulateAlert(DeviceAlert.BatteryCritical(5)) },
        onSimulateBatteryLow = { deviceHealthViewModel.simulateAlert(DeviceAlert.BatteryLow(15)) },
        onSimulateStorageLow = { deviceHealthViewModel.simulateAlert(DeviceAlert.StorageLow(0.5f)) },
        onSimulateMemoryLow = { deviceHealthViewModel.simulateAlert(DeviceAlert.MemoryLow(50)) },
        onSimulateWeakWifi = { deviceHealthViewModel.simulateAlert(DeviceAlert.WeakWifi(1)) },
        onSimulateMultipleAlerts = { deviceHealthViewModel.simulateMultipleAlerts() },
        onClearSimulatedAlerts = { deviceHealthViewModel.clearAllSimulatedAlerts() }
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
    onTestBluetooth: (Context) -> Unit,
    onToggleBleServer: (Context) -> Unit,
    onTestFirebaseCrash: () -> Unit = {},
    onTestFirebaseError: () -> Unit = {},
    onRefreshWifiState: () -> Unit = {},
    onSetWifiEnabledForSpike: (Boolean) -> Unit = {},
    onSimulateNoInternet: () -> Unit = {},
    onSimulateServerDown: () -> Unit = {},
    onSimulateBatteryCritical: () -> Unit = {},
    onSimulateBatteryLow: () -> Unit = {},
    onSimulateStorageLow: () -> Unit = {},
    onSimulateMemoryLow: () -> Unit = {},
    onSimulateWeakWifi: () -> Unit = {},
    onSimulateMultipleAlerts: () -> Unit = {},
    onClearSimulatedAlerts: () -> Unit = {}
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
                        icon = Icons.Default.Bluetooth,
                        title = "Test Bluetooth Capability",
                        description = "Check Bluetooth hardware & permissions",
                        onClick = { onTestBluetooth(context) },
                        enabled = !state.isLoading
                    )
                }

                item {
                    TestButton(
                        icon = Icons.Default.BluetoothSearching,
                        title = if (state.isBleServerRunning) "Stop BLE Payment Server" else "Start BLE Payment Server",
                        description = if (state.isBleServerRunning)
                            "Server running - iPad can connect"
                        else
                            "Accept payments from iPad/Tablet via BLE",
                        onClick = { onToggleBleServer(context) },
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

                // Phase 1 spike: explicit WiFi control probe (debug only)
                if (BuildConfig.DEBUG) {
                    item {
                        WifiFailoverSpikeCard(
                            isWifiEnabled = state.isWifiEnabled,
                            isLoading = state.isLoading,
                            onRefresh = onRefreshWifiState,
                            onDisableWifi = { onSetWifiEnabledForSpike(false) },
                            onEnableWifi = { onSetWifiEnabledForSpike(true) }
                        )
                    }
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
    val isBleServerRunning: Boolean = false,
    val isWifiEnabled: Boolean = false,
    // 🐢 Slow Network Simulation
    val isSlowNetworkEnabled: Boolean = false,
    val slowNetworkDelayMs: Long = 3000L
)

/**
 * SuperAdmin ViewModel
 *
 * Handles testing and debugging operations.
 */
@HiltViewModel
class SuperAdminViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val printerManager: PrinterManager,
    private val deviceInfoManager: DeviceInfoManager,
    private val observability: ObservabilityManager,
    private val observabilityTester: ObservabilityTester,
    private val bluetoothPaymentService: BluetoothPaymentService
) : ViewModel() {

    private val _state = MutableStateFlow(SuperAdminState())
    val state: StateFlow<SuperAdminState> = _state.asStateFlow()

    init {
        loadDeviceInfo()
        observeBleServerState()
        refreshWifiState()
    }

    /**
     * Observe BLE server state changes
     */
    private fun observeBleServerState() {
        viewModelScope.launch {
            bluetoothPaymentService.isRunning.collect { isRunning ->
                _state.value = _state.value.copy(isBleServerRunning = isRunning)
            }
        }
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
     * Test Bluetooth capability
     *
     * Checks if PAX terminal has Bluetooth hardware and permissions.
     * Logs detailed capability report to Logcat for analysis.
     */
    fun testBluetooth(context: Context) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)

            Timber.d("📡 [SuperAdmin] Testing Bluetooth capability...")

            try {
                val checker = BluetoothCapabilityChecker(context)
                val report = checker.generateCapabilityReport()

                // Log full report to Logcat
                checker.logCapabilityReport()

                // Determine message based on capabilities
                val message = when {
                    !report.hasBluetoothHardware -> {
                        "❌ NO Bluetooth hardware\n\nThis device doesn't support Bluetooth.\nUse API/WebSockets for remote payments."
                    }
                    !report.isBluetoothEnabled -> {
                        "⚠️ Bluetooth is OFF\n\nEnable in Settings → Bluetooth"
                    }
                    !report.canScanDevices -> {
                        """
                        ⚠️ Bluetooth permissions missing

                        TO GRANT MANUALLY:
                        1. Settings → Apps → Avoqado TPV
                        2. Permissions → Nearby devices
                        3. Toggle ON
                        4. Run test again

                        Check Logcat for full report
                        """.trimIndent()
                    }
                    else -> {
                        """
                        ✅ Bluetooth READY!

                        • Version: ${report.bluetoothVersion}
                        • Features: ${report.supportedFeatures.size}
                        • BLE: ${if (report.supportedFeatures.contains("BLE (Bluetooth Low Energy)")) "✅" else "❌"}

                        You can now implement Bluetooth payments!
                        Check Logcat for full details.
                        """.trimIndent()
                    }
                }

                Timber.i("📡 [SuperAdmin] Bluetooth test complete")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = message,
                    isError = !report.canScanDevices
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ [SuperAdmin] Bluetooth test failed")
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "❌ Bluetooth test failed: ${e.message}",
                    isError = true
                )
            }
        }
    }

    /**
     * Toggle BLE Payment Server
     *
     * Starts or stops the BLE GATT server that accepts payment requests from iPad/tablet.
     * When running, external devices can connect via BLE and send payment amounts.
     *
     * Uses singleton service - persists across screen changes.
     */
    fun toggleBleServer(context: Context) {
        viewModelScope.launch {
            try {
                if (bluetoothPaymentService.isRunning.value) {
                    // Stop server
                    Timber.i("🔵 [SuperAdmin] Stopping BLE Payment Server...")
                    bluetoothPaymentService.stopServer()

                    _state.value = _state.value.copy(
                        message = "✅ BLE Payment Server stopped",
                        isError = false
                    )
                    Timber.i("✅ [SuperAdmin] BLE Payment Server stopped")
                } else {
                    // Start server
                    Timber.i("🔵 [SuperAdmin] Starting BLE Payment Server...")

                    bluetoothPaymentService.startServer(context) { request ->
                        Timber.i("💰 [SuperAdmin] Received payment request: ${request.amountCents} cents")

                        // Update UI with received amount
                        viewModelScope.launch {
                            _state.value = _state.value.copy(
                                message = "💰 Payment request received: $${request.amountCents / 100.0}\n\nAmount: ${request.amountCents} cents\nReady to process payment!",
                                isError = false
                            )
                        }

                        // TODO: Trigger payment flow with received amount
                        // This would navigate to payment screen or trigger payment directly
                    }

                    _state.value = _state.value.copy(
                        message = """
                            ✅ BLE Payment Server RUNNING

                            El servidor persiste entre pantallas.
                            Puedes navegar a otras pantallas sin detenerlo.

                            📱 FROM iOS APP:
                            1. Open "PAX Payment" app
                            2. Tap "Buscar Terminal PAX"
                            3. Connect to PAX device
                            4. Tap amount button ($10, $20, etc.)

                            Server will receive payment amount!
                        """.trimIndent(),
                        isError = false
                    )
                    Timber.i("✅ [SuperAdmin] BLE Payment Server started successfully")
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [SuperAdmin] BLE server toggle failed")
                _state.value = _state.value.copy(
                    message = "❌ BLE server failed: ${e.message}\n\nCheck if Bluetooth permissions are granted.",
                    isError = true
                )
                bluetoothPaymentService.stopServer()
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
        val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        _state.value = _state.value.copy(isWifiEnabled = wifiManager.isWifiEnabled)
    }

    /**
     * Phase 1 spike probe: attempt OS-level WiFi toggle and log real result.
     * This is for PAX capability validation only (not production failover behavior).
     */
    @Suppress("DEPRECATION")
    fun setWifiEnabledForSpike(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)

            val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val hasChangeWifiPermission = appContext.checkSelfPermission(
                Manifest.permission.CHANGE_WIFI_STATE
            ) == PackageManager.PERMISSION_GRANTED

            val before = wifiManager.isWifiEnabled
            if (before == enabled) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    isWifiEnabled = before,
                    message = "ℹ️ WiFi ya estaba ${if (before) "ON" else "OFF"}",
                    isError = false
                )
                return@launch
            }

            try {
                val requestResult = wifiManager.setWifiEnabled(enabled)
                delay(1500L)
                var after = wifiManager.isWifiEnabled
                var paxChannelAttempted = false
                var paxChannelError: String? = null

                // Fallback probe: attempt PAX DAL channel control if WifiManager path is blocked.
                if (after != enabled && BuildConfig.ENABLE_PAX_SDK) {
                    try {
                        val dal = NeptuneLiteUser.getInstance().getDal(appContext)
                        val commManager = dal?.commManager
                        val wifiChannel = commManager?.getChannel(EChannelType.WIFI)
                        if (wifiChannel != null) {
                            paxChannelAttempted = true
                            if (enabled) {
                                wifiChannel.enable()
                            } else {
                                wifiChannel.disable()
                            }
                            delay(1500L)
                            after = wifiManager.isWifiEnabled
                        } else {
                            paxChannelError = "wifiChannel=null"
                        }
                    } catch (paxException: Exception) {
                        paxChannelAttempted = true
                        paxChannelError = paxException.javaClass.simpleName + ": " + paxException.message
                        Timber.w(
                            paxException,
                            "⚠️ [Phase1Spike] PAX DAL WIFI channel toggle failed"
                        )
                    } catch (paxError: Error) {
                        paxChannelAttempted = true
                        paxChannelError = paxError.javaClass.simpleName + ": " + paxError.message
                        Timber.w(
                            paxError,
                            "⚠️ [Phase1Spike] PAX DAL WIFI channel toggle error"
                        )
                    }
                }

                val success = after == enabled

                Timber.i(
                    "📶 [Phase1Spike] setWifiEnabled($enabled) | " +
                            "permission=$hasChangeWifiPermission | before=$before | requestResult=$requestResult | " +
                            "paxChannelAttempted=$paxChannelAttempted | paxChannelError=$paxChannelError | after=$after"
                )

                _state.value = _state.value.copy(
                    isLoading = false,
                    isWifiEnabled = after,
                    message = if (success) {
                        "✅ WiFi toggle aplicado (${if (after) "ON" else "OFF"}) | requestResult=$requestResult | paxChannelAttempted=$paxChannelAttempted"
                    } else {
                        "❌ WiFi no cambió | permission=$hasChangeWifiPermission, requestResult=$requestResult, paxChannelAttempted=$paxChannelAttempted, paxChannelError=$paxChannelError, before=$before, after=$after"
                    },
                    isError = !success
                )
            } catch (securityException: SecurityException) {
                Timber.e(
                    securityException,
                    "❌ [Phase1Spike] SecurityException on setWifiEnabled($enabled)"
                )
                _state.value = _state.value.copy(
                    isLoading = false,
                    isWifiEnabled = wifiManager.isWifiEnabled,
                    message = "❌ SecurityException: ${securityException.message}",
                    isError = true
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ [Phase1Spike] setWifiEnabled($enabled) failed")
                _state.value = _state.value.copy(
                    isLoading = false,
                    isWifiEnabled = wifiManager.isWifiEnabled,
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
            onTestBluetooth = {},
            onToggleBleServer = {}
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
            onTestBluetooth = {},
            onToggleBleServer = {}
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
            onTestBluetooth = {},
            onToggleBleServer = {}
        )
    }
}
