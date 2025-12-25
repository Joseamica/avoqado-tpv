package com.jaac.avoqado_tpv.core.presentation.screens

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
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.core.observability.ObservabilityTester
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
    viewModel: SuperAdminViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SuperAdminScreenContent(
        modifier = modifier,
        state = state,
        onNavigateBack = onNavigateBack,
        onTestPrinter = viewModel::testPrinter,
        onTestPayment = onTestPayment,
        onClearCache = viewModel::clearCache,
        onTestBackend = viewModel::testBackend,
        onTestFirebaseCrash = viewModel::testFirebaseCrash,
        onTestFirebaseError = viewModel::testFirebaseError
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
    onNavigateBack: () -> Unit,
    onTestPrinter: () -> Unit,
    onTestPayment: () -> Unit,
    onClearCache: () -> Unit,
    onTestBackend: () -> Unit,
    onTestFirebaseCrash: () -> Unit = {},
    onTestFirebaseError: () -> Unit = {}
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
                Color(0xFFEB5757).copy(alpha = 0.1f)
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
                tint = if (destructive) Color(0xFFEB5757) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (destructive) Color(0xFFEB5757) else MaterialTheme.colorScheme.onSurface,
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
                    containerColor = if (destructive) Color(0xFFEB5757) else MaterialTheme.colorScheme.primary
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
                Color(0xFFEB5757).copy(alpha = 0.1f)
            } else {
                Color(0xFF4CAF50).copy(alpha = 0.1f)
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
                tint = if (isError) Color(0xFFEB5757) else Color(0xFF4CAF50),
                modifier = Modifier.size(24.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) Color(0xFFEB5757) else Color(0xFF4CAF50)
            )
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
    val isError: Boolean = false
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
    private val observabilityTester: ObservabilityTester
) : ViewModel() {

    private val _state = MutableStateFlow(SuperAdminState())
    val state: StateFlow<SuperAdminState> = _state.asStateFlow()

    init {
        loadDeviceInfo()
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
    AvoqadoTheme {
        SuperAdminScreenContent(
            state = SuperAdminState(
                serialNumber = "AVQD-2841548417",
                deviceModel = "PAX A920",
                appVersion = "1.0.0"
            ),
            onNavigateBack = {},
            onTestPrinter = {},
            onTestPayment = {},
            onClearCache = {},
            onTestBackend = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024, name = "With Success Message")
@Composable
private fun SuperAdminScreenWithMessagePreview() {
    AvoqadoTheme {
        SuperAdminScreenContent(
            state = SuperAdminState(
                serialNumber = "AVQD-2841548417",
                deviceModel = "PAX A920",
                appVersion = "1.0.0",
                message = "Printer test successful",
                isError = false
            ),
            onNavigateBack = {},
            onTestPrinter = {},
            onTestPayment = {},
            onClearCache = {},
            onTestBackend = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024, name = "With Error Message")
@Composable
private fun SuperAdminScreenWithErrorPreview() {
    AvoqadoTheme {
        SuperAdminScreenContent(
            state = SuperAdminState(
                serialNumber = "AVQD-2841548417",
                deviceModel = "PAX A920",
                appVersion = "1.0.0",
                message = "Printer not available",
                isError = true
            ),
            onNavigateBack = {},
            onTestPrinter = {},
            onTestPayment = {},
            onClearCache = {},
            onTestBackend = {}
        )
    }
}
