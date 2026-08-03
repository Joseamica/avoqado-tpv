package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.UpdateMode
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.DeviceHealthMonitor
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.core.util.NetworkType
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.core.util.SimulatedAlertsManager
import com.jaac.avoqado_tpv.core.util.UpdateCheckManager
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
    private val connectionStateManager: ConnectionStateManager,
    private val updateCheckManager: UpdateCheckManager,
    private val paymentQueueStateManager: PaymentQueueStateManager,
    private val paymentQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
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
    private var updateObserverJob: Job? = null
    private var paymentQueueObserverJob: Job? = null

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
        observeUpdates()
        observePaymentQueue()
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
     * Observe pending updates from UpdateCheckManager
     * Updates are checked after login and periodically
     */
    private fun observeUpdates() {
        updateObserverJob?.cancel()
        updateObserverJob = viewModelScope.launch {
            updateCheckManager.pendingUpdate.collect { update ->
                if (update != null) {
                    Timber.i("📦 [DeviceHealth] Update available: ${update.versionName} (mode=${update.updateMode})")
                }
                updateAlerts()
            }
        }
    }

    /**
     * Observe payment queue state from PaymentQueueStateManager
     * Shows alert when there are pending or failed payments
     */
    private fun observePaymentQueue() {
        paymentQueueObserverJob?.cancel()
        paymentQueueObserverJob = viewModelScope.launch {
            paymentQueueStateManager.queueState.collect { state ->
                if (state.hasAnyPayments) {
                    Timber.d("💳 [DeviceHealth] Payment queue: pending=${state.pendingCount}, failed=${state.failedCount}")
                }
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
        val pendingUpdate = updateCheckManager.pendingUpdate.value

        // ══════════════════════════════════════════════════════════════════════
        // UPDATE ALERTS (P-1) - Highest priority (special blue color)
        // ══════════════════════════════════════════════════════════════════════

        // P-1: Update available (BANNER or FORCE mode)
        if (pendingUpdate != null && pendingUpdate.updateMode != UpdateMode.NONE) {
            alerts.add(
                DeviceAlert.UpdateAvailable(
                    versionName = pendingUpdate.versionName,
                    versionCode = pendingUpdate.versionCode,
                    releaseNotes = pendingUpdate.releaseNotes,
                    isForced = pendingUpdate.updateMode == UpdateMode.FORCE
                )
            )
        }

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

        // P3: Slow connection (only if connected but latency >5s)
        // latencyMs can be null during cooldown (e.g., heartbeat error), use last known or 0
        if (connectionState.isFullyConnected && connectionState.isSlowConnection) {
            alerts.add(DeviceAlert.SlowConnection(connectionState.latencyMs ?: 0L))
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

        // P5: Weak signal (WiFi or cellular, not ethernet)
        if (networkInfo.isConnected && networkInfo.signalStrength != null && networkInfo.signalStrength in 0..1) {
            when (networkInfo.type) {
                NetworkType.WIFI -> alerts.add(DeviceAlert.WeakWifi(networkInfo.signalStrength, NetworkType.WIFI))
                NetworkType.CELLULAR -> alerts.add(DeviceAlert.WeakWifi(networkInfo.signalStrength, NetworkType.CELLULAR))
                else -> { /* Ethernet/Other — no signal alert */ }
            }
        }

        // P6: Memory low
        if (health.memoryInfo.freeMB in 0..100) {
            alerts.add(DeviceAlert.MemoryLow(health.memoryInfo.freeMB))
        }

        // ══════════════════════════════════════════════════════════════════════
        // PAYMENT QUEUE ALERTS (P4) - Pending or failed payments
        // ══════════════════════════════════════════════════════════════════════
        val queueState = paymentQueueStateManager.queueState.value
        if (queueState.hasAnyPayments) {
            alerts.add(DeviceAlert.PendingPayments(
                pendingCount = queueState.pendingCount,
                failedCount = queueState.failedCount
            ))
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

    /**
     * Reset FAILED payments back to PENDING for retry — INCLUDING `permanent` rows
     * (fix round 1). Called when the user deliberately taps the "pending payments" alert.
     *
     * **Why `resetAllFailedIncludingPermanent`, not `resetAllFailed`:** the automatic
     * reconnect path ([com.jaac.avoqado_tpv.core.presentation.viewmodels.HomeViewModel])
     * must keep excluding `permanent = true` rows (F-10 — that's the infinite-retry loop
     * this whole change exists to kill). But a HUMAN tap is different: the badge reads
     * "$failedCount pagos fallidos — Toca para reintentar sincronización" counting ALL
     * FAILED rows regardless of `permanent` ([DeviceAlert.PendingPayments], driven by
     * [getFailedCount]), so if every failed row happened to be permanent (e.g. a stuck
     * token refresh misfired a 401 before fix round 1 — see `SyncOutcome.kt`),
     * `resetAllFailed()` would silently reset 0 rows: the operator taps, nothing
     * happens, the banner keeps inviting them to tap again — a dead affordance.
     * `resetAllFailedIncludingPermanent()` is the intended escape hatch instead.
     *
     * @param onResult Always invoked with the actual reset count, even 0 — so the caller
     *   can surface real feedback instead of the previous `if (count > 0)` silence (a
     *   genuine 0, e.g. from an underlying DB write failure, used to leave the operator
     *   with zero indication the tap did anything at all).
     */
    fun retryFailedPayments(onResult: (resetCount: Int) -> Unit = {}) {
        viewModelScope.launch {
            val count = paymentQueueRepository.resetAllFailedIncludingPermanent()
            if (count > 0) {
                Timber.i("🔄 [DeviceHealth] Reset $count failed payments (incl. permanent) back to PENDING for retry")
            } else {
                Timber.w("⚠️ [DeviceHealth] retryFailedPayments(): nothing was reset")
            }
            // Siempre refresca, no solo cuando count > 0 (fix round 1) — antes, un 0
            // dejaba el badge mostrando el conteo viejo sin ninguna corroboración de que
            // el tap se procesó de verdad.
            val pending = paymentQueueRepository.getPendingCount()
            val failed = paymentQueueRepository.getFailedCount()
            paymentQueueStateManager.refreshCounts(pending, failed)
            onResult(count)
        }
    }

    override fun onCleared() {
        super.onCleared()
        monitoringJob?.cancel()
        networkObserverJob?.cancel()
        simulatedAlertsObserverJob?.cancel()
        connectionStateObserverJob?.cancel()
        updateObserverJob?.cancel()
        paymentQueueObserverJob?.cancel()
    }
}

// ══════════════════════════════════════════════════════════════════════
// DEVICE ALERT MODEL
// ══════════════════════════════════════════════════════════════════════

/**
 * Device Alert Types (for tracking dismissed alerts)
 */
enum class DeviceAlertType {
    UPDATE_AVAILABLE,  // P-1 - Highest priority (special - blue color)
    NO_INTERNET,       // P0 - Most critical
    BATTERY_CRITICAL,  // P1
    SERVER_DOWN,       // P2
    SLOW_CONNECTION,   // P3 - Slow internet (latency >5s)
    BATTERY_LOW,       // P3
    STORAGE_LOW,       // P4
    PENDING_PAYMENTS,  // P4 - Payments waiting to sync
    WEAK_WIFI,         // P5
    MEMORY_LOW         // P6
}

/**
 * Device Alert - Represents a device/connection warning
 *
 * Sealed class with priority for sorting.
 * Lower priority number = more critical.
 *
 * **Priority Hierarchy (Unified):**
 * - P-1: Update available - BLUE (highest priority, special color)
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
     * ¿Esta alerta muestra botón "Reintentar"?
     *
     * Dos significados distintos detrás del MISMO botón: en las de conexión reintenta el
     * enlace con el servidor; en PendingPayments reencola los pagos fallidos. Quien maneje
     * el tap debe distinguirlos — el feedback al operador no es intercambiable.
     */
    val isRetryable: Boolean
        get() = this is NoInternet || this is ServerDown || this is SlowConnection || this is PendingPayments

    /**
     * Update available (P-1) - Highest priority, special blue color
     * Shows banner for BANNER mode, triggers blocking modal for FORCE mode
     */
    data class UpdateAvailable(
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String?,
        val isForced: Boolean // true = FORCE mode (blocking), false = BANNER mode
    ) : DeviceAlert(-1, DeviceAlertType.UPDATE_AVAILABLE) {
        val message: String get() = if (isForced) "Actualización obligatoria" else "Nueva versión disponible"
        val description: String get() = "v$versionName ${if (isForced) "- Requerida para continuar" else "- Toca para actualizar"}"
    }

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
     * Slow internet connection (P3) - Latency >5 seconds
     * Payments may take longer, warn the cashier
     */
    data class SlowConnection(val latencyMs: Long) : DeviceAlert(3, DeviceAlertType.SLOW_CONNECTION) {
        val message: String get() = "Internet lento"
        val description: String get() = "Los pagos pueden tardar más — Latencia: ${latencyMs / 1000}s"
    }

    /**
     * Pending payments in sync queue (P4)
     * Orange if failed, yellow if only pending
     */
    data class PendingPayments(
        val pendingCount: Int,
        val failedCount: Int
    ) : DeviceAlert(4, DeviceAlertType.PENDING_PAYMENTS) {
        val message: String get() = if (failedCount > 0) "$failedCount pagos fallidos" else "$pendingCount pagos pendientes"
        val description: String get() = if (failedCount > 0) "Toca para reintentar sincronización" else "Pendientes de sincronizar"
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
    data class WeakWifi(
        val signalStrength: Int,
        val networkType: NetworkType = NetworkType.WIFI
    ) : DeviceAlert(5, DeviceAlertType.WEAK_WIFI) {
        val message: String get() = when (networkType) {
            NetworkType.CELLULAR -> "Señal celular débil"
            else -> "Señal WiFi débil"
        }
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
    return when {
        priority == -1 -> AlertColor.UPDATE        // Blue - Update available (special)
        priority in 0..2 -> AlertColor.CRITICAL    // Red - P0 (No internet), P1 (Battery critical), P2 (Server down)
        this is DeviceAlert.SlowConnection -> AlertColor.WARNING  // Orange - Slow connection
        this is DeviceAlert.PendingPayments && (this as DeviceAlert.PendingPayments).failedCount > 0 -> AlertColor.WARNING  // Orange - Failed payments
        priority == 3 -> AlertColor.WARNING        // Orange - P3 (Battery low)
        else -> AlertColor.CAUTION                 // Yellow - P4-P6
    }
}

/**
 * Alert color categories
 */
enum class AlertColor {
    UPDATE,    // Blue - Update available (special, not an error)
    CRITICAL,  // Red - Immediate action required
    WARNING,   // Orange - Action needed soon
    CAUTION    // Yellow - Informational warning
}
