package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.DeviceHealthMonitor
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.core.util.SimulatedAlertsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Device Health ViewModel
 *
 * Monitors device health (battery, storage, memory, WiFi signal) and emits alerts.
 * Follows Square/Toast/Shopify POS patterns for device health monitoring.
 *
 * **Pattern: Single Banner with Priority**
 * - Only shows the MOST CRITICAL alert in the banner
 * - User can tap to see all active alerts
 * - Alerts are sorted by priority (P0 = most critical)
 *
 * **Priority Hierarchy:**
 * - P0: No internet (handled by ConnectionViewModel)
 * - P1: Battery critical (<10%)
 * - P2: Server down (handled by ConnectionViewModel)
 * - P3: Battery low (<20%)
 * - P4: Storage low (<1GB)
 * - P5: WiFi weak (signal 0-1)
 * - P6: Memory low (<100MB)
 *
 * **Usage:**
 * ```kotlin
 * val alerts by deviceHealthViewModel.activeAlerts.collectAsStateWithLifecycle()
 * val topAlert = alerts.firstOrNull()
 *
 * if (topAlert != null) {
 *     DeviceAlertBanner(
 *         alert = topAlert,
 *         totalAlerts = alerts.size,
 *         onExpand = { /* show all alerts */ }
 *     )
 * }
 * ```
 */
@HiltViewModel
class DeviceHealthViewModel @Inject constructor(
    private val deviceHealthMonitor: DeviceHealthMonitor,
    private val networkMonitor: NetworkMonitor,
    private val connectivityObserver: ConnectivityObserver,
    private val simulatedAlertsManager: SimulatedAlertsManager,
    private val connectionStateManager: ConnectionStateManager
) : ViewModel() {

    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    private val _activeAlerts = MutableStateFlow<List<DeviceAlert>>(emptyList())
    val activeAlerts: StateFlow<List<DeviceAlert>> = _activeAlerts.asStateFlow()

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    private var monitoringJob: Job? = null
    private var networkObserverJob: Job? = null
    private var simulatedAlertsObserverJob: Job? = null
    private var connectionStateObserverJob: Job? = null

    // Dismissed alerts (user can dismiss non-critical alerts)
    private val dismissedAlerts = mutableSetOf<DeviceAlertType>()

    // ══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ══════════════════════════════════════════════════════════════════════

    init {
        Timber.i("🏥 [DeviceHealth] ViewModel initialized")
        startMonitoring()
        observeNetworkChanges()
        observeSimulatedAlerts()
        observeConnectionState()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Toggle expanded state to show all alerts
     */
    fun toggleExpanded() {
        _isExpanded.value = !_isExpanded.value
    }

    /**
     * Collapse the alerts panel
     */
    fun collapse() {
        _isExpanded.value = false
    }

    /**
     * Dismiss a non-critical alert
     * Critical alerts (P0-P2) cannot be dismissed
     */
    fun dismissAlert(alert: DeviceAlert) {
        if (alert.priority > 2) { // Only non-critical alerts can be dismissed
            dismissedAlerts.add(alert.type)
            updateAlerts()
            Timber.d("🏥 [DeviceHealth] Alert dismissed: ${alert.type}")
        }
    }

    /**
     * Clear all dismissed alerts (show them again)
     */
    fun clearDismissedAlerts() {
        dismissedAlerts.clear()
        updateAlerts()
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEBUG/TESTING METHODS (delegate to singleton SimulatedAlertsManager)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Simulate an alert for testing purposes
     * Uses SimulatedAlertsManager singleton so alerts persist across navigation
     */
    fun simulateAlert(alert: DeviceAlert) {
        simulatedAlertsManager.simulateAlert(alert)
    }

    /**
     * Clear a simulated alert
     */
    fun clearSimulatedAlert(alert: DeviceAlert) {
        simulatedAlertsManager.clearAlert(alert)
    }

    /**
     * Clear all simulated alerts
     */
    fun clearAllSimulatedAlerts() {
        simulatedAlertsManager.clearAll()
    }

    /**
     * Simulate multiple alerts at once (for testing priority display)
     */
    fun simulateMultipleAlerts() {
        simulatedAlertsManager.simulateMultipleAlerts()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start periodic health monitoring
     * Checks device health every 30 seconds
     */
    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = viewModelScope.launch {
            while (true) {
                updateAlerts()
                delay(30_000) // Check every 30 seconds
            }
        }
    }

    /**
     * Observe network changes for WiFi signal strength updates
     */
    private fun observeNetworkChanges() {
        networkObserverJob?.cancel()
        networkObserverJob = viewModelScope.launch {
            connectivityObserver.observe().collect { status ->
                // Update alerts when network status changes
                updateAlerts()
            }
        }
    }

    /**
     * Observe simulated alerts from singleton manager
     * This ensures alerts persist across navigation (SuperAdmin → AppNavigation)
     */
    private fun observeSimulatedAlerts() {
        simulatedAlertsObserverJob?.cancel()
        simulatedAlertsObserverJob = viewModelScope.launch {
            simulatedAlertsManager.simulatedAlerts.collect { alerts ->
                Timber.d("🧪 [DeviceHealth] Simulated alerts changed: ${alerts.map { it.type }}")
                updateAlerts()
            }
        }
    }

    /**
     * Observe connection state from ConnectionStateManager
     * This unifies connection alerts with device health alerts
     */
    private fun observeConnectionState() {
        connectionStateObserverJob?.cancel()
        connectionStateObserverJob = viewModelScope.launch {
            connectionStateManager.connectionState.collect { state ->
                Timber.d("🌐 [DeviceHealth] Connection state changed: internet=${state.hasInternet}, server=${state.hasServer}")
                updateAlerts()
            }
        }
    }

    /**
     * Update the list of active alerts based on current device state
     */
    private fun updateAlerts() {
        val alerts = mutableListOf<DeviceAlert>()

        // Get current states
        val health = deviceHealthMonitor.getSystemHealth()
        val networkInfo = networkMonitor.getCurrentNetworkInfo()
        val connectionState = connectionStateManager.connectionState.value

        // ══════════════════════════════════════════════════════════════════════
        // CONNECTION ALERTS (P0, P2) - Highest priority
        // ══════════════════════════════════════════════════════════════════════

        // P0: No internet
        if (!connectionState.hasInternet) {
            alerts.add(DeviceAlert.NoInternet)
        }

        // P2: Server down (only if we have internet but no server)
        if (connectionState.hasInternet && !connectionState.hasServer) {
            alerts.add(DeviceAlert.ServerDown)
        }

        // ══════════════════════════════════════════════════════════════════════
        // DEVICE HEALTH ALERTS (P1, P3-P6)
        // ══════════════════════════════════════════════════════════════════════

        // P1: Battery critical
        if (health.batteryLevel in 0..10 && !health.batteryCharging) {
            alerts.add(DeviceAlert.BatteryCritical(health.batteryLevel))
        }
        // P3: Battery low
        else if (health.batteryLevel in 11..20 && !health.batteryCharging) {
            alerts.add(DeviceAlert.BatteryLow(health.batteryLevel))
        }

        // P4: Storage low
        if (health.storageAvailableGB in 0f..1f) {
            alerts.add(DeviceAlert.StorageLow(health.storageAvailableGB))
        }

        // P5: WiFi weak (only if connected)
        if (networkInfo.isConnected && networkInfo.signalStrength != null) {
            if (networkInfo.signalStrength in 0..1) {
                alerts.add(DeviceAlert.WeakWifi(networkInfo.signalStrength))
            }
        }

        // P6: Memory low
        if (health.memoryInfo.freeMB in 0..100) {
            alerts.add(DeviceAlert.MemoryLow(health.memoryInfo.freeMB))
        }

        // ══════════════════════════════════════════════════════════════════════
        // SIMULATED ALERTS (for testing)
        // ══════════════════════════════════════════════════════════════════════
        alerts.addAll(simulatedAlertsManager.simulatedAlerts.value)

        // ══════════════════════════════════════════════════════════════════════
        // FILTER AND SORT
        // ══════════════════════════════════════════════════════════════════════
        val filteredAlerts = alerts
            .distinctBy { it.type } // Remove duplicates (real + simulated)
            .filter { it.type !in dismissedAlerts || it.priority <= 2 } // Critical alerts can't be dismissed
            .sortedBy { it.priority } // Sort by priority (lowest number = highest priority)

        _activeAlerts.value = filteredAlerts

        if (filteredAlerts.isNotEmpty()) {
            Timber.d("🏥 [DeviceHealth] Active alerts: ${filteredAlerts.map { it.type }}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        monitoringJob?.cancel()
        networkObserverJob?.cancel()
        simulatedAlertsObserverJob?.cancel()
        connectionStateObserverJob?.cancel()
    }
}

// ══════════════════════════════════════════════════════════════════════
// DEVICE ALERT MODEL
// ══════════════════════════════════════════════════════════════════════

/**
 * Device Alert Types (for tracking dismissed alerts)
 */
enum class DeviceAlertType {
    NO_INTERNET,      // P0 - Most critical
    BATTERY_CRITICAL, // P1
    SERVER_DOWN,      // P2
    BATTERY_LOW,      // P3
    STORAGE_LOW,      // P4
    WEAK_WIFI,        // P5
    MEMORY_LOW        // P6
}

/**
 * Device Alert - Represents a device/connection warning
 *
 * Sealed class with priority for sorting.
 * Lower priority number = more critical.
 *
 * **Priority Hierarchy (Unified):**
 * - P0: No internet - RED (most critical)
 * - P1: Battery critical (<10%) - RED
 * - P2: Server down - RED
 * - P3: Battery low (<20%) - ORANGE
 * - P4: Storage low (<1GB) - YELLOW
 * - P5: WiFi weak (signal 0-1) - YELLOW
 * - P6: Memory low (<100MB) - YELLOW
 */
sealed class DeviceAlert(
    val priority: Int,
    val type: DeviceAlertType
) {
    /**
     * No internet connection (P0) - Most critical
     * Cannot process any payments
     */
    data object NoInternet : DeviceAlert(0, DeviceAlertType.NO_INTERNET) {
        val message: String get() = "Sin conexión a internet"
        val description: String get() = "Verifica tu conexión WiFi"
    }

    /**
     * Battery critical (<10%) - Requires immediate action
     * Device may shut down during transaction
     */
    data class BatteryCritical(val level: Int) : DeviceAlert(1, DeviceAlertType.BATTERY_CRITICAL) {
        val message: String get() = "Batería crítica ($level%)"
        val description: String get() = "Conecta el cargador para evitar apagado"
    }

    /**
     * Server down (P2) - Cannot reach Avoqado servers
     * Payments will fail
     */
    data object ServerDown : DeviceAlert(2, DeviceAlertType.SERVER_DOWN) {
        val message: String get() = "Sin conexión al servidor"
        val description: String get() = "Reintentando conexión..."
    }

    /**
     * Battery low (<20%) - Warning state
     * User should charge soon
     */
    data class BatteryLow(val level: Int) : DeviceAlert(3, DeviceAlertType.BATTERY_LOW) {
        val message: String get() = "Batería baja ($level%)"
        val description: String get() = "Conecta el cargador pronto"
    }

    /**
     * Storage low (<1GB) - May cause write failures
     * Database operations could fail
     */
    data class StorageLow(val availableGB: Float) : DeviceAlert(4, DeviceAlertType.STORAGE_LOW) {
        val message: String get() = "Almacenamiento bajo"
        val description: String get() = "Solo ${String.format("%.1f", availableGB)} GB disponibles"
    }

    /**
     * WiFi weak (signal 0-1 out of 4) - May cause slow/failed requests
     * Payments may timeout
     */
    data class WeakWifi(val signalStrength: Int) : DeviceAlert(5, DeviceAlertType.WEAK_WIFI) {
        val message: String get() = "Señal WiFi débil"
        val description: String get() = "Los pagos pueden ser lentos"
    }

    /**
     * Memory low (<100MB free) - App may crash
     * System may kill the app
     */
    data class MemoryLow(val freeMB: Long) : DeviceAlert(6, DeviceAlertType.MEMORY_LOW) {
        val message: String get() = "Memoria baja"
        val description: String get() = "Solo $freeMB MB disponibles"
    }
}

/**
 * Extension to get alert color based on priority
 */
fun DeviceAlert.getAlertColor(): AlertColor {
    return when (priority) {
        0, 1, 2 -> AlertColor.CRITICAL  // Red - P0 (No internet), P1 (Battery critical), P2 (Server down)
        3 -> AlertColor.WARNING         // Orange - P3 (Battery low)
        else -> AlertColor.CAUTION      // Yellow - P4-P6
    }
}

/**
 * Alert color categories
 */
enum class AlertColor {
    CRITICAL,  // Red - Immediate action required
    WARNING,   // Orange - Action needed soon
    CAUTION    // Yellow - Informational warning
}
