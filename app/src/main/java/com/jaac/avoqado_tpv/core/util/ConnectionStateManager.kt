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
 */
@Singleton
class ConnectionStateManager @Inject constructor() {

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

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
     * Update both states at once
     */
    fun updateState(hasInternet: Boolean, hasServer: Boolean) {
        val newState = ConnectionState(hasInternet = hasInternet, hasServer = hasServer)
        if (_connectionState.value != newState) {
            Timber.i("🔄 [ConnectionState] Updated: internet=$hasInternet, server=$hasServer")
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
        _connectionState.value = ConnectionState(hasInternet = true, hasServer = true)
        Timber.i("✅ [ConnectionState] Reset to fully connected")
    }
}

/**
 * Connection state data class
 */
data class ConnectionState(
    val hasInternet: Boolean = true,
    val hasServer: Boolean = true
) {
    val isFullyConnected: Boolean get() = hasInternet && hasServer
    val hasAnyIssue: Boolean get() = !hasInternet || !hasServer
}
