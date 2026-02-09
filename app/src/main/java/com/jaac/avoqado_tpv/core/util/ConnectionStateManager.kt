package com.jaac.avoqado_tpv.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection State Manager
 *
 * Singleton that holds the current connection state.
 * This bridges ConnectionViewModel (which handles reconnection logic)
 * with DeviceHealthViewModel (which shows unified alerts).
 *
 * **Why Singleton?**
 * - ConnectionViewModel updates connection state
 * - DeviceHealthViewModel observes and includes in unified alert list
 * - Single source of truth for connection status across the app
 *
 * **Alert Priority:**
 * - P0: No internet (most critical)
 * - P2: Server down
 *
 * **Slow Connection Detection:**
 * Uses hysteresis to prevent the "Internet lento" banner from flickering.
 * Once a slow measurement (>= 5000ms) is detected, the alert stays active
 * for at least [SLOW_COOLDOWN_MS] even if subsequent measurements are fast
 * (heartbeat payloads are small, so warm connections appear fast even on slow networks).
 */
@Singleton
class ConnectionStateManager @Inject constructor() {

    companion object {
        const val SLOW_THRESHOLD_MS = 5_000L
        const val SLOW_COOLDOWN_MS = 60_000L
    }

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Timestamp (epoch ms) when a slow measurement was last observed */
    @Volatile
    private var lastSlowDetectedAt: Long = 0L

    /** Skip the first measurement (cold TCP + TLS + DNS is always slow) */
    @Volatile
    private var measurementCount: Int = 0

    /**
     * Update internet connectivity status
     */
    fun setInternetConnected(connected: Boolean) {
        val current = _connectionState.value
        if (current.hasInternet != connected) {
            Timber.i("🌐 [ConnectionState] Internet: $connected")
            _connectionState.value = current.copy(hasInternet = connected)
        }
    }

    /**
     * Update server connectivity status
     */
    fun setServerConnected(connected: Boolean) {
        val current = _connectionState.value
        if (current.hasServer != connected) {
            Timber.i("🖥️ [ConnectionState] Server: $connected")
            _connectionState.value = current.copy(hasServer = connected)
        }
    }

    /**
     * Update both states at once (with optional latency measurement).
     * Applies hysteresis for slow connection detection.
     */
    fun updateState(hasInternet: Boolean, hasServer: Boolean, latencyMs: Long? = null) {
        val now = System.currentTimeMillis()

        // Skip first measurement (cold TCP + TLS handshake is always slow)
        if (latencyMs != null) measurementCount++
        val isWarm = measurementCount > 1

        // Track slow detection with hysteresis (only after first measurement)
        val isMeasurementSlow = isWarm && latencyMs != null && latencyMs >= SLOW_THRESHOLD_MS
        if (isMeasurementSlow) {
            lastSlowDetectedAt = now
        }
        val inCooldown = (now - lastSlowDetectedAt) < SLOW_COOLDOWN_MS
        val effectiveSlow = isMeasurementSlow || (inCooldown && lastSlowDetectedAt > 0L)

        val newState = ConnectionState(
            hasInternet = hasInternet,
            hasServer = hasServer,
            latencyMs = latencyMs,
            isSlowConnection = effectiveSlow
        )
        if (_connectionState.value != newState) {
            val latencyStr = if (latencyMs != null) ", latency=${latencyMs}ms" else ""
            val slowStr = if (effectiveSlow) " [SLOW]" else ""
            Timber.i("🔄 [ConnectionState] Updated: internet=$hasInternet, server=$hasServer$latencyStr$slowStr")
            _connectionState.value = newState
        }
    }

    /**
     * Check if fully connected (has internet AND server)
     */
    fun isFullyConnected(): Boolean {
        val state = _connectionState.value
        return state.hasInternet && state.hasServer
    }

    /**
     * Reset to connected state (e.g., after successful reconnection)
     */
    fun resetToConnected() {
        lastSlowDetectedAt = 0L
        measurementCount = 0
        _connectionState.value = ConnectionState(hasInternet = true, hasServer = true, latencyMs = null)
        Timber.i("✅ [ConnectionState] Reset to fully connected")
    }
}

/**
 * Connection state data class
 *
 * [isSlowConnection] is computed by [ConnectionStateManager] with hysteresis,
 * not purely from a single [latencyMs] measurement.
 */
data class ConnectionState(
    val hasInternet: Boolean = true,
    val hasServer: Boolean = true,
    val latencyMs: Long? = null,
    val isSlowConnection: Boolean = false
) {
    val isFullyConnected: Boolean get() = hasInternet && hasServer
    val hasAnyIssue: Boolean get() = !hasInternet || !hasServer
}
