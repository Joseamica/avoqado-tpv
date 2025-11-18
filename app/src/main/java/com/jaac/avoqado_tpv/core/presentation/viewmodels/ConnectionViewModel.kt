package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.DeviceHealthMonitor
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
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
 * Connection ViewModel
 *
 * Monitors backend connectivity and displays "Offline" banner when disconnected.
 * Follows Square POS and Toast POS patterns for offline-first operation.
 *
 * **Features:**
 * - Real-time connection status monitoring
 * - Automatic reconnection attempts
 * - Discrete warning banner (doesn't block operations)
 * - Graceful degradation (app works offline)
 *
 * **UI Pattern (Square/Toast):**
 * - Connected: No banner
 * - Disconnected: Yellow banner "Trabajando sin conexión - Las ventas se guardarán localmente"
 * - Reconnecting: Yellow banner "Reconectando al servidor..."
 * - Reconnected: Green banner briefly "Conectado al servidor" (2s)
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun AppContent(connectionViewModel: ConnectionViewModel = hiltViewModel()) {
 *     val connectionState by connectionViewModel.state.collectAsStateWithLifecycle()
 *
 *     Column {
 *         ConnectionBanner(state = connectionState)
 *         // Rest of app content
 *     }
 * }
 * ```
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val heartbeatRepository: HeartbeatRepository,
    private val deviceInfoManager: DeviceInfoManager,
    private val deviceHealthMonitor: DeviceHealthMonitor,
    private val connectionEventManager: ConnectionEventManager
) : ViewModel() {

    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Checking)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var monitoringJob: Job? = null
    private var reconnectionAttempts = 0
    private val maxReconnectionAttempts = Int.MAX_VALUE // Keep trying forever

    // ══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ══════════════════════════════════════════════════════════════════════

    init {
        startMonitoring()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start connection monitoring
     *
     * Runs in background, checking connectivity every 30 seconds.
     * Uses exponential backoff when disconnected.
     */
    fun startMonitoring() {
        Timber.d("🌐 [Connection] Starting monitoring")

        monitoringJob?.cancel()
        monitoringJob = viewModelScope.launch {
            while (true) {
                checkConnection()

                // Adaptive interval based on connection state
                val interval = when (_state.value) {
                    is ConnectionState.Connected -> 30_000L // 30s when connected
                    is ConnectionState.Disconnected -> calculateBackoffDelay() // Exponential backoff
                    is ConnectionState.Reconnecting -> 5_000L // 5s when reconnecting
                    else -> 10_000L // 10s default
                }

                delay(interval)
            }
        }
    }

    /**
     * Stop connection monitoring
     *
     * Called when app is destroyed or user logs out.
     */
    fun stopMonitoring() {
        Timber.d("🌐 [Connection] Stopping monitoring")
        monitoringJob?.cancel()
        monitoringJob = null
    }

    /**
     * Force immediate connection check
     *
     * Used when user manually triggers refresh or returns to app.
     */
    fun forceCheck() {
        viewModelScope.launch {
            checkConnection()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Check connection to backend
     *
     * Steps:
     * 1. Check network connectivity (NetworkMonitor)
     * 2. Send test heartbeat to backend
     * 3. Update state based on result
     */
    private suspend fun checkConnection() {
        // Check network connectivity first
        val networkInfo = networkMonitor.getCurrentNetworkInfo()
        if (!networkInfo.isConnected) {
            Timber.w("⚠️ [Connection] No network connection")
            _state.value = ConnectionState.Disconnected
            reconnectionAttempts++
            return
        }

        // Network available → Try backend heartbeat
        try {
            Timber.d("🌐 [Connection] Checking backend connectivity...")

            // ✅ FIX: Only show Reconnecting if we were previously disconnected
            // This prevents "banner flash" during routine checks when already connected
            if (_state.value is ConnectionState.Disconnected) {
                _state.value = ConnectionState.Reconnecting
            }

            // Send lightweight heartbeat
            val heartbeat = buildLightweightHeartbeat()
            val result = heartbeatRepository.sendHeartbeat(heartbeat)

            when (result) {
                is Result.Success -> {
                    Timber.i("✅ [Connection] Backend connected")

                    // 🔄 Trigger reconciliation sync if connection was restored
                    if (reconnectionAttempts > 0) {
                        Timber.i("🔄 [Connection] Connection restored after $reconnectionAttempts attempts - triggering data sync")

                        // Emit event for listeners (HomeViewModel, ShiftViewModel, etc.) via singleton manager
                        connectionEventManager.emitConnectionRestored(
                            attemptsBeforeReconnection = reconnectionAttempts
                        )

                        // Show "Reconnected" banner
                        _state.value = ConnectionState.Reconnected
                        // After 2 seconds, switch to Connected
                        delay(2000)
                    }

                    _state.value = ConnectionState.Connected
                    reconnectionAttempts = 0
                }
                is Result.Error -> {
                    Timber.w("⚠️ [Connection] Backend unreachable: ${result.exception?.message}")
                    _state.value = ConnectionState.Disconnected
                    reconnectionAttempts++
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Connection] Check failed")
            _state.value = ConnectionState.Disconnected
            reconnectionAttempts++
        }
    }

    /**
     * Calculate exponential backoff delay
     *
     * Pattern (Toast POS):
     * - 1st attempt: 5s
     * - 2nd attempt: 10s
     * - 3rd attempt: 20s
     * - 4th attempt: 30s
     * - 5th+ attempt: 30s (max)
     */
    private fun calculateBackoffDelay(): Long {
        val baseDelay = 5_000L // 5 seconds
        val maxDelay = 30_000L // 30 seconds

        val delay = (baseDelay * (1 shl minOf(reconnectionAttempts, 4))).coerceAtMost(maxDelay)
        Timber.d("⏱️ [Connection] Retry in ${delay / 1000}s (attempt #${reconnectionAttempts + 1})")
        return delay
    }

    /**
     * Build lightweight heartbeat for connection check
     *
     * Minimal payload to check backend connectivity quickly.
     */
    private fun buildLightweightHeartbeat(): com.jaac.avoqado_tpv.core.domain.models.Heartbeat {
        val terminalId = deviceInfoManager.getSerialNumber()
        val systemHealth = deviceHealthMonitor.getSystemHealth()
        val networkInfo = networkMonitor.getCurrentNetworkInfo()

        return com.jaac.avoqado_tpv.core.domain.models.Heartbeat(
            terminalId = terminalId,
            timestamp = java.time.Instant.now().toString(),
            status = com.jaac.avoqado_tpv.core.domain.models.TerminalStatus.ACTIVE,
            version = com.jaac.avoqado_tpv.BuildConfig.VERSION_NAME,
            systemInfo = systemHealth,
            networkInfo = networkInfo
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
    }
}

/**
 * Connection State
 *
 * Sealed class representing backend connectivity state.
 * Drives UI banner visibility and message.
 */
sealed class ConnectionState {
    /**
     * Initial state - Checking connection
     */
    data object Checking : ConnectionState()

    /**
     * Connected to backend - No banner
     */
    data object Connected : ConnectionState()

    /**
     * Disconnected from backend - Show warning banner
     */
    data object Disconnected : ConnectionState()

    /**
     * Attempting to reconnect - Show reconnecting banner
     */
    data object Reconnecting : ConnectionState()

    /**
     * Successfully reconnected - Show success banner briefly
     */
    data object Reconnected : ConnectionState()
}
