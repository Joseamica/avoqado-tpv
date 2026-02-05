package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.dto.PendingCommandDto
import com.jaac.avoqado_tpv.core.data.network.dto.toTpvCommand
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.DeviceHealthMonitor
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.features.remote_command.data.model.CommandResult
import com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor
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
    private val connectivityObserver: ConnectivityObserver,
    private val heartbeatRepository: HeartbeatRepository,
    private val deviceInfoManager: DeviceInfoManager,
    private val deviceHealthMonitor: DeviceHealthMonitor,
    private val connectionEventManager: ConnectionEventManager,
    private val commandExecutor: CommandExecutor,
    private val connectionStateManager: ConnectionStateManager
) : ViewModel() {

    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Checking)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var monitoringJob: Job? = null
    private var networkObserverJob: Job? = null
    private var reconnectionAttempts = 0
    private val maxReconnectionAttempts = Int.MAX_VALUE // Keep trying forever
    private var isDismissed = false  // User manually dismissed banner

    // ══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ══════════════════════════════════════════════════════════════════════

    init {
        // Send immediate heartbeat when app starts/restarts
        // This ensures dashboard knows terminal is back online quickly after RESTART command
        // The startMonitoring() function calls checkConnection() BEFORE any delay
        Timber.i("🚀 [Connection] ViewModel initialized - sending immediate heartbeat")
        startMonitoring()
        observeNetworkChanges()
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
                    is ConnectionState.DisconnectedNoInternet,
                    is ConnectionState.DisconnectedServerDown -> calculateBackoffDelay() // Exponential backoff
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
            isDismissed = false  // Clear dismissed state when manually retrying
            checkConnection()
        }
    }

    /**
     * Dismiss the connection banner temporarily
     *
     * User can dismiss the offline banner if they want to work without distractions.
     * Banner will reappear if connection state changes again.
     */
    fun dismissBanner() {
        Timber.d("🌐 [Connection] User dismissed banner")
        isDismissed = true
        _state.value = ConnectionState.Dismissed
    }

    // ══════════════════════════════════════════════════════════════════════
    // NETWORK STATE OBSERVATION (Toast/Square Pattern)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Observe network connectivity changes in real-time
     *
     * **Problem Solved:**
     * When screen is locked/unlocked, the periodic 30s check might show "Sin conexión"
     * banner briefly because network isn't fully ready yet. This observer reacts
     * IMMEDIATELY when Android reports network available, triggering a fresh check.
     *
     * **Toast POS Pattern:**
     * - Network restored → Automatic reconnection (no user action needed)
     * - Banner disappears automatically when connection is restored
     *
     * **Grace Period:**
     * We wait 2 seconds after network becomes available before checking.
     * This gives WiFi/DNS time to fully initialize after screen wake.
     */
    private fun observeNetworkChanges() {
        networkObserverJob?.cancel()
        networkObserverJob = viewModelScope.launch {
            connectivityObserver.observe().collect { status ->
                when (status) {
                    NetworkStatus.Available -> {
                        Timber.i("🌐 [Connection] Network restored - checking connection after grace period")
                        // Grace period: Wait 2s for network to fully stabilize after wake
                        delay(2000)
                        // Clear dismissed state - network change is a new situation
                        isDismissed = false
                        checkConnection()
                    }
                    NetworkStatus.Unavailable -> {
                        Timber.w("⚠️ [Connection] Network lost")
                        // Update state immediately when network is lost
                        if (!isDismissed) {
                            _state.value = ConnectionState.DisconnectedNoInternet
                        }
                        // Update unified alert system
                        connectionStateManager.setInternetConnected(false)
                        reconnectionAttempts++
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Check connection to backend
     *
     * Steps:
     * 1. Check if terminal is activated (venueId exists)
     * 2. Check network connectivity (NetworkMonitor)
     * 3. Send test heartbeat to backend
     * 4. Update state based on result
     */
    private suspend fun checkConnection() {
        // 🔐 Skip connection check if terminal is NOT activated yet
        // Heartbeat will fail because terminal doesn't exist in backend
        // This prevents false "Sin conexión" banner on Activation screen
        if (!deviceInfoManager.isDeviceActivated()) {
            Timber.d("🔐 [Connection] Terminal not activated - skipping heartbeat check")
            _state.value = ConnectionState.Connected  // Don't show banner
            return
        }

        // Check network connectivity first
        val networkInfo = networkMonitor.getCurrentNetworkInfo()
        if (!networkInfo.isConnected) {
            Timber.w("⚠️ [Connection] No network connection")
            // Don't override Dismissed state - user dismissed banner
            if (!isDismissed) {
                _state.value = ConnectionState.DisconnectedNoInternet
            }
            // Update unified alert system
            connectionStateManager.setInternetConnected(false)
            reconnectionAttempts++
            return
        }

        // Network available → Try backend heartbeat
        try {
            Timber.d("🌐 [Connection] Checking backend connectivity...")

            // ✅ FIX: Only show Reconnecting if we were previously disconnected
            // This prevents "banner flash" during routine checks when already connected
            if (_state.value is ConnectionState.DisconnectedNoInternet || _state.value is ConnectionState.DisconnectedServerDown) {
                _state.value = ConnectionState.Reconnecting
            }

            // Send lightweight heartbeat
            val heartbeat = buildLightweightHeartbeat()
            val result = heartbeatRepository.sendHeartbeat(heartbeat)

            when (result) {
                is Result.Success -> {
                    Timber.i("✅ [Connection] Backend connected")

                    // Update unified alert system - fully connected
                    connectionStateManager.updateState(hasInternet = true, hasServer = true)

                    // 🎯 Square Terminal API Pattern: Process pending commands from heartbeat response
                    // Commands are delivered via HTTP polling instead of socket push
                    // This runs every 30 seconds (more reliable than WorkManager's 15-min minimum)
                    val pendingCommands = result.data.pendingCommands
                    if (!pendingCommands.isNullOrEmpty()) {
                        Timber.i("📥 [Connection] Received ${pendingCommands.size} pending command(s)")
                        processPendingCommands(pendingCommands)
                    }

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
                    // Don't override Dismissed state - user dismissed banner
                    if (!isDismissed) {
                        _state.value = ConnectionState.DisconnectedServerDown
                    }
                    // Update unified alert system - internet OK, server down
                    connectionStateManager.updateState(hasInternet = true, hasServer = false)
                    reconnectionAttempts++
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Connection] Check failed")
            // Don't override Dismissed state - user dismissed banner
            if (!isDismissed) {
                _state.value = ConnectionState.DisconnectedServerDown
            }
            // Update unified alert system - internet OK (we got here), server down
            connectionStateManager.updateState(hasInternet = true, hasServer = false)
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

    // ══════════════════════════════════════════════════════════════════════
    // Square Terminal API Polling Pattern
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Process pending commands received via heartbeat response
     *
     * **Square Terminal API Pattern:**
     * Commands are delivered via HTTP polling (heartbeat response) instead of socket push.
     * This ensures commands reach the terminal even when:
     * - Socket is not connected (user on login screen)
     * - Socket connection dropped temporarily
     * - Network is unstable
     *
     * **Note:** This is the primary command delivery mechanism since WorkManager's
     * PeriodicWorkRequest has a 15-minute minimum interval. ConnectionViewModel
     * checks every 30 seconds, making it more reliable for command delivery.
     *
     * @param pendingCommands List of commands from heartbeat response
     */
    private suspend fun processPendingCommands(pendingCommands: List<PendingCommandDto>) {
        // Get terminal serial number for security validation in ACKs
        val terminalId = deviceInfoManager.getSerialNumber()

        for (commandDto in pendingCommands) {
            try {
                Timber.i("🔄 [Connection] Processing command: ${commandDto.type} (id=${commandDto.commandId})")

                // 1. Convert DTO to domain model
                val command = commandDto.toTpvCommand()
                if (command == null) {
                    Timber.w("⚠️ [Connection] Unknown command type: ${commandDto.type}, skipping")
                    // Send REJECTED ACK for unknown command (with terminalId for security)
                    val rejectResult = CommandResult.rejected("Unknown command type: ${commandDto.type}")
                    heartbeatRepository.sendCommandAck(commandDto.commandId, terminalId, rejectResult)
                    continue
                }

                // 2. Execute command via CommandExecutor
                val result = commandExecutor.execute(command)

                // 3. Send HTTP ACK to backend (primary acknowledgment for polling pattern)
                val ackResult = heartbeatRepository.sendCommandAck(command.commandId, terminalId, result)
                if (ackResult is Result.Success) {
                    Timber.i("✅ [Connection] Command completed and ACK sent: ${command.type.name} → ${result.status.name}")
                } else {
                    Timber.w("⚠️ [Connection] Command executed but ACK failed: ${command.commandId}")
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Connection] Failed to process command: ${commandDto.commandId}")
                // Try to send FAILED ACK (with terminalId for security)
                try {
                    val failResult = CommandResult.failed("Execution error: ${e.message}")
                    heartbeatRepository.sendCommandAck(commandDto.commandId, terminalId, failResult)
                } catch (ackError: Exception) {
                    Timber.e(ackError, "❌ [Connection] Failed to send FAILED ACK: ${commandDto.commandId}")
                }
            }
        }
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
            versionCode = com.jaac.avoqado_tpv.BuildConfig.VERSION_CODE,
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
        networkObserverJob?.cancel()
        networkObserverJob = null
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
     * No network connectivity (WiFi/mobile off) - Show "Sin conexión a internet"
     */
    data object DisconnectedNoInternet : ConnectionState()

    /**
     * Network available but server unreachable - Show "Sin conexión al servidor"
     */
    data object DisconnectedServerDown : ConnectionState()

    /**
     * Attempting to reconnect - Show reconnecting banner
     */
    data object Reconnecting : ConnectionState()

    /**
     * Successfully reconnected - Show success banner briefly
     */
    data object Reconnected : ConnectionState()

    /**
     * User dismissed the banner - Hide it temporarily
     */
    data object Dismissed : ConnectionState()
}
