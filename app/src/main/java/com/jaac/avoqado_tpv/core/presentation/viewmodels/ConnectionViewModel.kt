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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    private var reconnectedBannerJob: Job? = null
    private var reconnectionAttempts = 0
    private val maxReconnectionAttempts = Int.MAX_VALUE // Keep trying forever
    private var isDismissed = false  // User manually dismissed banner

    // Anti-stale guard: monotonic counter to prevent race conditions between
    // concurrent callers (monitoring loop, network observer, forceCheck).
    // Accessed only from Main dispatcher — no AtomicInteger needed.
    private var checkVersion = 0
    private fun nextVersion(): Int = ++checkVersion
    private fun isLatest(version: Int): Boolean = version == checkVersion

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
                val version = nextVersion()
                performFullHeartbeat(version)

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
            _state.value = ConnectionState.Reconnecting  // Immediate visual feedback
            val version = nextVersion()
            probeConnectivity(version)
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
                        val version = nextVersion()
                        probeConnectivity(version)
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
    // CONNECTIVITY CHECKS (Split: UI probe vs full heartbeat)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fast UI-only connectivity probe
     *
     * Used by forceCheck (Reintentar) and network observer.
     * Sends a heartbeat with an 8s timeout for UI responsiveness.
     * Does NOT process pending commands — that's the monitoring loop's job.
     *
     * @param version Anti-stale guard — side effects skipped if superseded
     */
    private suspend fun probeConnectivity(version: Int) {
        if (!deviceInfoManager.isDeviceActivated()) {
            if (isLatest(version)) _state.value = ConnectionState.Connected
            return
        }

        val networkInfo = networkMonitor.getCurrentNetworkInfo()
        if (!networkInfo.isConnected) {
            if (isLatest(version)) {
                if (!isDismissed) _state.value = ConnectionState.DisconnectedNoInternet
                connectionStateManager.setInternetConnected(false)
                reconnectionAttempts++
            }
            return
        }

        try {
            Timber.d("🌐 [Connection] Probe: checking backend connectivity...")

            if (isLatest(version)) {
                val wasDisconnected = _state.value is ConnectionState.DisconnectedNoInternet ||
                        _state.value is ConnectionState.DisconnectedServerDown
                if (wasDisconnected) _state.value = ConnectionState.Reconnecting
            }

            val heartbeat = buildLightweightHeartbeat()
            val startMs = System.currentTimeMillis()

            // Short timeout for UI responsiveness — don't wait the full 30s OkHttp timeout
            val result = withTimeout(8_000) {
                heartbeatRepository.sendHeartbeat(heartbeat)
            }
            val latencyMs = System.currentTimeMillis() - startMs

            // After suspension point — re-check version before writing ANY side effect
            if (!isLatest(version)) return

            when (result) {
                is Result.Success -> {
                    Timber.i("✅ [Connection] Probe OK (latency=${latencyMs}ms)")
                    connectionStateManager.updateState(hasInternet = true, hasServer = true, latencyMs = latencyMs)
                    handleReconnectionSuccess(version)
                    // NOTE: pendingCommands in result are IGNORED — monitoring loop handles them
                }
                is Result.Error -> {
                    Timber.w("⚠️ [Connection] Probe: backend unreachable: ${result.exception?.message}")
                    if (!isDismissed) _state.value = ConnectionState.DisconnectedServerDown
                    connectionStateManager.updateState(hasInternet = true, hasServer = false)
                    reconnectionAttempts++
                }
            }
        } catch (e: TimeoutCancellationException) {
            if (!isLatest(version)) return
            Timber.w("⚠️ [Connection] Probe timed out (8s)")
            if (!isDismissed) _state.value = ConnectionState.DisconnectedServerDown
            connectionStateManager.updateState(hasInternet = true, hasServer = false)
            reconnectionAttempts++
        } catch (e: CancellationException) {
            throw e  // Coroutine cancelled — rethrow, don't set error state
        } catch (e: Exception) {
            if (!isLatest(version)) return
            Timber.e(e, "❌ [Connection] Probe failed")
            if (!isDismissed) _state.value = ConnectionState.DisconnectedServerDown
            connectionStateManager.updateState(hasInternet = true, hasServer = false)
            reconnectionAttempts++
        }
    }

    /**
     * Full heartbeat with command processing
     *
     * Used ONLY by the monitoring loop. Sends heartbeat (full OkHttp timeout),
     * updates UI state, AND processes pending commands from the response.
     *
     * @param version Anti-stale guard — UI side effects skipped if superseded.
     *                Commands ALWAYS execute regardless of staleness.
     */
    private suspend fun performFullHeartbeat(version: Int) {
        if (!deviceInfoManager.isDeviceActivated()) {
            if (isLatest(version)) _state.value = ConnectionState.Connected
            return
        }

        val networkInfo = networkMonitor.getCurrentNetworkInfo()
        if (!networkInfo.isConnected) {
            if (isLatest(version)) {
                if (!isDismissed) _state.value = ConnectionState.DisconnectedNoInternet
                connectionStateManager.setInternetConnected(false)
                reconnectionAttempts++
            }
            return
        }

        try {
            Timber.d("🌐 [Connection] Heartbeat: checking backend connectivity...")

            if (isLatest(version)) {
                if (_state.value is ConnectionState.DisconnectedNoInternet || _state.value is ConnectionState.DisconnectedServerDown) {
                    _state.value = ConnectionState.Reconnecting
                }
            }

            val heartbeat = buildLightweightHeartbeat()
            val heartbeatStartMs = System.currentTimeMillis()
            val result = heartbeatRepository.sendHeartbeat(heartbeat)
            val latencyMs = System.currentTimeMillis() - heartbeatStartMs

            when (result) {
                is Result.Success -> {
                    Timber.i("✅ [Connection] Heartbeat OK (latency=${latencyMs}ms)")

                    // UI state — gated by version
                    if (isLatest(version)) {
                        connectionStateManager.updateState(hasInternet = true, hasServer = true, latencyMs = latencyMs)
                        handleReconnectionSuccess(version)
                    }

                    // Commands — ALWAYS processed, never skipped by stale guard
                    val pendingCommands = result.data.pendingCommands
                    if (!pendingCommands.isNullOrEmpty()) {
                        Timber.i("📥 [Connection] Received ${pendingCommands.size} pending command(s)")
                        processPendingCommands(pendingCommands)
                    }
                }
                is Result.Error -> {
                    Timber.w("⚠️ [Connection] Heartbeat: backend unreachable: ${result.exception?.message}")
                    if (isLatest(version)) {
                        if (!isDismissed) _state.value = ConnectionState.DisconnectedServerDown
                        connectionStateManager.updateState(hasInternet = true, hasServer = false)
                        reconnectionAttempts++
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ [Connection] Heartbeat failed")
            if (isLatest(version)) {
                if (!isDismissed) _state.value = ConnectionState.DisconnectedServerDown
                connectionStateManager.updateState(hasInternet = true, hasServer = false)
                reconnectionAttempts++
            }
        }
    }

    /**
     * Handle successful reconnection — show Reconnected banner briefly
     *
     * Shared by probeConnectivity and performFullHeartbeat.
     * Cancels any previous banner timer to avoid duplicates.
     *
     * @param version Anti-stale guard
     */
    private suspend fun handleReconnectionSuccess(version: Int) {
        if (!isLatest(version)) return

        if (reconnectionAttempts > 0) {
            Timber.i("🔄 [Connection] Connection restored after $reconnectionAttempts attempts")

            connectionEventManager.emitConnectionRestored(
                attemptsBeforeReconnection = reconnectionAttempts
            )

            // Re-check after suspension point (emitConnectionRestored may suspend)
            if (!isLatest(version)) return

            _state.value = ConnectionState.Reconnected

            // Cancel previous banner timer to avoid duplicate transitions
            reconnectedBannerJob?.cancel()
            reconnectedBannerJob = viewModelScope.launch {
                delay(2000)
                if (_state.value is ConnectionState.Reconnected) {
                    _state.value = ConnectionState.Connected
                }
            }

            reconnectionAttempts = 0
        } else {
            _state.value = ConnectionState.Connected
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

            } catch (e: CancellationException) {
                throw e  // Don't send false FAILED ACK on cancellation
            } catch (e: Exception) {
                Timber.e(e, "❌ [Connection] Failed to process command: ${commandDto.commandId}")
                // Try to send FAILED ACK (with terminalId for security)
                try {
                    val failResult = CommandResult.failed("Execution error: ${e.message}")
                    heartbeatRepository.sendCommandAck(commandDto.commandId, terminalId, failResult)
                } catch (ackError: CancellationException) {
                    throw ackError
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
        reconnectedBannerJob?.cancel()
        reconnectedBannerJob = null
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
