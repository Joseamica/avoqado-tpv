package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simulated Alerts Manager
 *
 * Singleton that holds simulated device alerts for testing purposes.
 * This persists across navigation, so alerts stay visible when leaving SuperAdmin.
 *
 * **Why Singleton?**
 * - DeviceHealthViewModel in AppNavigation and SuperAdminScreen are different instances
 * - Without a shared state, simulated alerts would be lost on navigation
 * - This manager provides a single source of truth for simulated alerts
 *
 * **Usage:**
 * - SuperAdmin calls simulateAlert() to add test alerts
 * - DeviceHealthViewModel observes simulatedAlerts flow
 * - Alerts persist until clearAll() is called
 */
@Singleton
class SimulatedAlertsManager @Inject constructor() {

    private val _simulatedAlerts = MutableStateFlow<Set<DeviceAlert>>(emptySet())
    val simulatedAlerts: StateFlow<Set<DeviceAlert>> = _simulatedAlerts.asStateFlow()

    /**
     * Simulate a single alert
     */
    fun simulateAlert(alert: DeviceAlert) {
        Timber.i("🧪 [SimulatedAlerts] Adding alert: ${alert.type}")
        _simulatedAlerts.value = _simulatedAlerts.value + alert
    }

    /**
     * Clear a specific alert type
     */
    fun clearAlert(alert: DeviceAlert) {
        Timber.i("🧪 [SimulatedAlerts] Clearing alert: ${alert.type}")
        _simulatedAlerts.value = _simulatedAlerts.value.filter { it.type != alert.type }.toSet()
    }

    /**
     * Simulate multiple alerts at once (for testing priority display)
     */
    fun simulateMultipleAlerts() {
        Timber.i("🧪 [SimulatedAlerts] Simulating multiple alerts")
        _simulatedAlerts.value = setOf(
            DeviceAlert.BatteryLow(15),
            DeviceAlert.StorageLow(0.8f),
            DeviceAlert.WeakWifi(1),
            DeviceAlert.MemoryLow(80)
        )
    }

    /**
     * Clear all simulated alerts
     */
    fun clearAll() {
        Timber.i("🧪 [SimulatedAlerts] Clearing all alerts")
        _simulatedAlerts.value = emptySet()
    }

    /**
     * Check if there are any simulated alerts
     */
    fun hasSimulatedAlerts(): Boolean = _simulatedAlerts.value.isNotEmpty()
}
