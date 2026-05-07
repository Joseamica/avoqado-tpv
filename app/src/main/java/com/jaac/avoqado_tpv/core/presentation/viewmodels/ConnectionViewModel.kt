package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.dto.PendingCommandDto
import com.jaac.avoqado_tpv.core.data.network.dto.toTpvCommand
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.observability.CrashlyticsContext
import com.jaac.avoqado_tpv.core.util.CriticalNetworkOperationManager
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.DeviceHealthMonitor
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.core.util.NetworkType
import com.jaac.avoqado_tpv.core.util.WifiFailoverController
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.CellularFailoverMode
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
    private val connectionStateManager: ConnectionStateManager,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val wifiFailoverController: WifiFailoverController,
    private val criticalNetworkOperationManager: CriticalNetworkOperationManager,
) : ViewModel() {

    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Checking)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var monitoringJob: Job? = null
    private var networkObserverJob: Job? = null
    private var reconnectedBannerJob: Job? = null
    private var offlineTransitionJob: Job? = null
    private var reconnectionAttempts = 0
    private val maxReconnectionAttempts = Int.MAX_VALUE // Keep trying forever
    private var isDismissed = false  // User manually dismissed banner
    private var failoverBadReadingsStreak = 0
    private var failoverHealthyCellStreak = 0
    private var lastWifiToggleAtMs: Long? = null
    private var lastAutoWifiDisableAtMs: Long? = null
    private var failoverTransitionInProgress = false

    // 🛡️ Banner hysteresis state — see SERVER_DOWN_FAILURE_THRESHOLD
    private var consecutiveServerProbeFailures = 0
    private var lastSuccessfulProbeAt: Long = System.currentTimeMillis()
    private var lastUnreachableNonFatalAt: Long = 0L

    /**
     * Last terminal connection state (Connected or DisconnectedServerDown) we've
     * confirmed via probe results. Used to revert the UI when a single below-
     * threshold failure happens while the user is on a transient state like
     * Reconnecting or Checking — without this, the spinner would be stuck until
     * the next monitoring loop iteration (5-30s away).
     */
    private var lastConfirmedConnectionState: ConnectionState = ConnectionState.Connected

    /**
     * Coroutine that re-probes ~2s after a below-threshold failure, so we reach
     * the SERVER_DOWN_FAILURE_THRESHOLD quickly when a real outage starts —
     * instead of waiting the next 5-30s monitoring backoff. Critical for
     * forceCheck (user tapped Reintentar): without this, the user would stare
     * at a Reconnecting spinner that never resolves until the loop catches up.
     */
    private var fastReprobeJob: Job? = null

    companion object {
        /** Grace period before declaring offline — absorbs transient network flickers */
        const val OFFLINE_GRACE_MS = 3_000L
        /** Stabilization delay after WiFi Available (DNS/DHCP setup time) */
        const val ONLINE_STABILIZATION_MS = 2_000L

        /**
         * 🛡️ Hysteresis on the server-down banner.
         *
         * The banner ("Sin conexión al servidor / Reintentando conexión...") must
         * persist long enough that the user actually sees it but short enough that
         * a transient HTTP/2 stream stall (the kind that pingInterval will evict
         * within 15s) doesn't paint a false alarm.
         *
         * Require N consecutive failures before declaring `hasServer=false`. With
         * the 30s monitoring loop + 8s probe timeout, 2 consecutive failures means
         * "the backend has been unreachable for at least ~38s", which is real.
         */
        const val SERVER_DOWN_FAILURE_THRESHOLD = 2

        /**
         * Minimum gap between successive `recordBackendUnreachable` non-fatals so
         * one extended outage doesn't spam Crashlytics. We get one event per real
         * incident; the gap_ms key on each event tells the story of how long the
         * banner stayed up.
         */
        const val UNREACHABLE_NONFATAL_COOLDOWN_MS = 5 * 60 * 1000L
    }

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
                evaluateCellularFailoverSafely(source = "monitoring_loop")

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
            evaluateCellularFailoverSafely(source = "force_check")
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
                        Timber.i("🌐 [Connection] Network restored - checking after stabilization")
                        cancelOfflineTransition()
                        // Grace period: Wait for network to fully stabilize after wake (DNS/DHCP)
                        delay(ONLINE_STABILIZATION_MS)
                        // Clear dismissed state - network change is a new situation
                        isDismissed = false
                        val version = nextVersion()
                        probeConnectivity(version)
                        evaluateCellularFailoverSafely(source = "network_available")

                        // NOTE: SDK re-init on network restore is handled by HomeViewModel.listenForConnectionRestored()
                        // which passes the correct merchantPosId. Do NOT call ensureInitialized() here
                        // without posId — it causes a race condition that leaves the SDK with stale posId.
                    }
                    NetworkStatus.Unavailable -> {
                        Timber.w("⚠️ [Connection] Network lost — scheduling offline transition")
                        scheduleOfflineTransition("observer")
                        evaluateCellularFailoverSafely(source = "network_unavailable")
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // OFFLINE HYSTERESIS (Asymmetric: slow offline, fast online)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Schedule a delayed transition to DisconnectedNoInternet.
     *
     * Absorbs transient network flickers (screen lock/unlock, WiFi roaming).
     * After [OFFLINE_GRACE_MS], re-checks the network to avoid stale transitions.
     * If network recovered during the grace period, the transition is skipped.
     *
     * @param source Logging label for the caller (observer, probe, heartbeat)
     */
    private fun scheduleOfflineTransition(source: String) {
        if (offlineTransitionJob?.isActive == true) return // Already scheduled

        offlineTransitionJob = viewModelScope.launch {
            Timber.d("⏳ [Connection] Offline grace period started ($source)")
            delay(OFFLINE_GRACE_MS)

            // Re-validate: network may have recovered during grace period
            val networkInfo = networkMonitor.getCurrentNetworkInfo()
            if (!networkInfo.isConnected) {
                Timber.w("⚠️ [Connection] Confirmed offline after grace period ($source)")
                if (!isDismissed) _state.value = ConnectionState.DisconnectedNoInternet
                connectionStateManager.setInternetConnected(false)
                reconnectionAttempts++
            } else {
                Timber.i("🌐 [Connection] Network recovered during grace period ($source)")
            }
        }
    }

    private fun cancelOfflineTransition() {
        offlineTransitionJob?.cancel()
        offlineTransitionJob = null
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
                scheduleOfflineTransition("probe")
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
                    cancelOfflineTransition()
                    markServerProbeSucceeded()
                    connectionStateManager.updateState(hasInternet = true, hasServer = true, latencyMs = latencyMs)
                    handleReconnectionSuccess(version)

                    // Process pending commands — critical for offline→online transition
                    // Backend marks commands as SENT when included in ANY heartbeat response,
                    // so we must process them here or they'll be lost (never re-delivered)
                    val pendingCommands = result.data.pendingCommands
                    if (!pendingCommands.isNullOrEmpty()) {
                        Timber.i("📥 [Connection] Probe received ${pendingCommands.size} pending command(s)")
                        processPendingCommands(pendingCommands)
                    }
                }
                is Result.Error -> {
                    Timber.w("⚠️ [Connection] Probe: backend unreachable: ${result.exception?.message}")
                    markServerProbeFailed(source = "probe", cause = result.exception)
                }
            }
        } catch (e: TimeoutCancellationException) {
            if (!isLatest(version)) return
            Timber.w("⚠️ [Connection] Probe timed out (8s)")
            markServerProbeFailed(source = "probe_timeout", cause = e)
        } catch (e: CancellationException) {
            throw e  // Coroutine cancelled — rethrow, don't set error state
        } catch (e: Exception) {
            if (!isLatest(version)) return
            Timber.e(e, "❌ [Connection] Probe failed")
            markServerProbeFailed(source = "probe_exception", cause = e)
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
                scheduleOfflineTransition("heartbeat")
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
                        cancelOfflineTransition()
                        markServerProbeSucceeded()
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
                        markServerProbeFailed(source = "heartbeat", cause = result.exception)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ [Connection] Heartbeat failed")
            if (isLatest(version)) {
                markServerProbeFailed(source = "heartbeat_exception", cause = e)
            }
        }
    }

    /**
     * Mark a server probe as failed.
     *
     * 🛡️ Centralizes the "we couldn't reach the backend" handling for both
     * [probeConnectivity] and [performFullHeartbeat]. Implements the hysteresis
     * rule (SERVER_DOWN_FAILURE_THRESHOLD) so a single transient stall doesn't
     * flicker the banner, plus single-shot Crashlytics non-fatal so each real
     * incident gets exactly one event in the dashboard.
     *
     * @param source Logging tag — "probe", "probe_timeout", "probe_exception",
     *               "heartbeat", "heartbeat_exception". Becomes a Crashlytics key.
     * @param cause Exception that triggered the failure (if any). Attached to the
     *              non-fatal so we can see the actual stack (e.g. Http2Stream timeout).
     */
    private fun markServerProbeFailed(source: String, cause: Throwable? = null) {
        consecutiveServerProbeFailures++
        val gap = System.currentTimeMillis() - lastSuccessfulProbeAt

        Timber.w(
            "⚠️ [Connection] Server probe failed ($source) — streak=$consecutiveServerProbeFailures, gap=${gap}ms"
        )
        CrashlyticsContext.logNetworkBreadcrumb(
            "probe failed: source=$source streak=$consecutiveServerProbeFailures gap=${gap}ms"
        )

        // Below threshold → keep showing Connected/banner, give the network a
        // chance to recover before painting the red banner. This filters out
        // single-failure noise from HTTP/2 stale streams that pingInterval will
        // evict within 15s.
        if (consecutiveServerProbeFailures < SERVER_DOWN_FAILURE_THRESHOLD) {
            // 🛡️ If the UI is currently on a transient state (Reconnecting from
            // forceCheck / post-disconnect probe, or Checking from app init), it
            // would otherwise stay stuck on the spinner until the next monitoring
            // loop iteration (5-30s). Revert to the last confirmed terminal state
            // so the user sees a definitive UI.
            if (_state.value is ConnectionState.Reconnecting ||
                _state.value is ConnectionState.Checking
            ) {
                _state.value = lastConfirmedConnectionState
            }
            // Schedule a fast follow-up probe so we hit SERVER_DOWN_FAILURE_THRESHOLD
            // within ~2s instead of waiting the regular 5-30s monitoring backoff.
            // Critical for forceCheck UX: without this, "Reintentar" gives no
            // definitive feedback for up to 30s after a single failed probe.
            scheduleFastReprobe(reason = source)
            return
        }

        // At/over threshold → declare server-down (banner shows)
        fastReprobeJob?.cancel()
        fastReprobeJob = null
        cancelOfflineTransition() // Internet confirmed — cancel pending NoInternet
        if (!isDismissed) _state.value = ConnectionState.DisconnectedServerDown
        lastConfirmedConnectionState = ConnectionState.DisconnectedServerDown
        connectionStateManager.updateState(hasInternet = true, hasServer = false)
        reconnectionAttempts++

        // Single-shot non-fatal per incident (cooldown UNREACHABLE_NONFATAL_COOLDOWN_MS)
        // First time: lastUnreachableNonFatalAt == 0 → fires immediately
        // Repeat firings only after cooldown to avoid spam during a long outage
        val now = System.currentTimeMillis()
        if (now - lastUnreachableNonFatalAt > UNREACHABLE_NONFATAL_COOLDOWN_MS) {
            CrashlyticsContext.recordBackendUnreachable(
                gapSinceLastSuccessMs = gap,
                cause = cause,
                source = source,
            )
            lastUnreachableNonFatalAt = now
        }
    }

    /**
     * Mark a server probe as successful — resets hysteresis state.
     */
    private fun markServerProbeSucceeded() {
        // Cancel any pending fast re-probe — we're already healthy, no need to retry
        fastReprobeJob?.cancel()
        fastReprobeJob = null

        if (consecutiveServerProbeFailures > 0) {
            CrashlyticsContext.logNetworkBreadcrumb(
                "probe recovered after $consecutiveServerProbeFailures failures"
            )
        }
        consecutiveServerProbeFailures = 0
        lastSuccessfulProbeAt = System.currentTimeMillis()
        lastConfirmedConnectionState = ConnectionState.Connected
        // Allow a fresh non-fatal next time we go down
        lastUnreachableNonFatalAt = 0L
    }

    /**
     * Schedule a follow-up probe ~2s after a below-threshold failure.
     *
     * Without this, a user tapping "Reintentar" while seeing the offline banner
     * would have to wait 5-30s for the next monitoring loop iteration to confirm
     * whether the network is actually still down. With it, the second
     * confirmation arrives within ~2s and the banner stabilizes (either back to
     * Connected if recovered, or to DisconnectedServerDown if truly down).
     */
    private fun scheduleFastReprobe(reason: String) {
        // Idempotent — multiple failures in quick succession share one re-probe
        if (fastReprobeJob?.isActive == true) return
        fastReprobeJob = viewModelScope.launch {
            delay(2_000)
            // Bail out if state was resolved by another path during the wait
            if (consecutiveServerProbeFailures == 0) return@launch
            if (_state.value is ConnectionState.DisconnectedServerDown) return@launch

            Timber.d("⏱️ [Connection] Fast re-probe (reason=$reason) — confirming below-threshold failure")
            CrashlyticsContext.logNetworkBreadcrumb("fast re-probe firing (reason=$reason)")
            val version = nextVersion()
            probeConnectivity(version)
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
    // PHASE 1: CELLULAR FAILOVER (SAFE, FLAG-DRIVEN)
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun evaluateCellularFailoverSafely(source: String) {
        try {
            evaluateCellularFailover(source = source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "⚠️ [Failover] Evaluation failed (source=$source)")
        }
    }

    private suspend fun evaluateCellularFailover(source: String) {
        val settings = tpvSettingsRepository.getCurrentSettings()
        val mode = settings.cellularFailoverMode
        if (mode == CellularFailoverMode.OFF || mode == CellularFailoverMode.MANUAL_TOGGLE) {
            failoverBadReadingsStreak = 0
            failoverHealthyCellStreak = 0
            return
        }

        val networkInfo = networkMonitor.getCurrentNetworkInfo()
        val connection = connectionStateManager.connectionState.value
        val threshold = settings.cellularFailoverBadReadingsThreshold.coerceAtLeast(1)
        val now = System.currentTimeMillis()

        if (networkInfo.type == NetworkType.WIFI) {
            failoverHealthyCellStreak = 0
            val badReading = connection.isSlowConnection || !connection.hasInternet
            failoverBadReadingsStreak = if (badReading) {
                failoverBadReadingsStreak + 1
            } else {
                0
            }

            if (!badReading) return

            if (failoverBadReadingsStreak < threshold) {
                Timber.w(
                    "⚠️ [Failover] Bad WiFi reading ${failoverBadReadingsStreak}/$threshold " +
                        "(slow=${connection.isSlowConnection}, hasInternet=${connection.hasInternet}, source=$source)"
                )
                return
            }

            val gateReason = failoverToggleGateReason(
                settings = settings,
                now = now
            )
            if (gateReason != null) {
                Timber.w("🛑 [Failover] WiFi disable blocked ($gateReason, source=$source)")
                return
            }

            if (mode == CellularFailoverMode.AUTO_SHADOW) {
                Timber.i(
                    "👤 [Failover][SHADOW] Would disable WiFi (badReadings=${failoverBadReadingsStreak}, source=$source)"
                )
                failoverBadReadingsStreak = 0
                return
            }

            failoverTransitionInProgress = true
            try {
                val result = wifiFailoverController.setWifiEnabled(
                    enabled = false,
                    source = "auto_failover:$source"
                )
                lastWifiToggleAtMs = now
                if (result.success) {
                    lastAutoWifiDisableAtMs = now
                    failoverBadReadingsStreak = 0
                    failoverHealthyCellStreak = 0
                    Timber.i("✅ [Failover] WiFi disabled, forcing cellular route")
                } else {
                    Timber.e("❌ [Failover] Failed to disable WiFi for cellular failover")
                }
            } finally {
                failoverTransitionInProgress = false
            }
            return
        }

        failoverBadReadingsStreak = 0

        if (networkInfo.type != NetworkType.CELLULAR) {
            failoverHealthyCellStreak = 0
            return
        }

        val disabledAt = lastAutoWifiDisableAtMs ?: return
        if (wifiFailoverController.isWifiEnabled()) {
            // WiFi was re-enabled manually; do not auto-manage until next auto-disable event.
            lastAutoWifiDisableAtMs = null
            failoverHealthyCellStreak = 0
            return
        }

        val minCellHoldMs = settings.cellularFailoverMinCellHoldSeconds
            .coerceAtLeast(0)
            .toLong() * 1000L
        val elapsedOnCell = now - disabledAt
        if (elapsedOnCell < minCellHoldMs) {
            return
        }

        val healthyCellReading = connection.hasInternet &&
            connection.hasServer &&
            !connection.isSlowConnection
        failoverHealthyCellStreak = if (healthyCellReading) {
            failoverHealthyCellStreak + 1
        } else {
            0
        }
        if (failoverHealthyCellStreak < threshold) {
            Timber.w(
                "⚠️ [Failover] Cellular health ${failoverHealthyCellStreak}/$threshold before WiFi restore " +
                    "(slow=${connection.isSlowConnection}, internet=${connection.hasInternet}, server=${connection.hasServer}, source=$source)"
            )
            return
        }

        val gateReason = failoverToggleGateReason(
            settings = settings,
            now = now
        )
        if (gateReason != null) {
            Timber.w("🛑 [Failover] WiFi restore blocked ($gateReason, source=$source)")
            return
        }

        if (mode == CellularFailoverMode.AUTO_SHADOW) {
            Timber.i(
                "👤 [Failover][SHADOW] Would re-enable WiFi (cellHold=${elapsedOnCell}ms, source=$source)"
            )
            return
        }

        failoverTransitionInProgress = true
        try {
            val result = wifiFailoverController.setWifiEnabled(
                enabled = true,
                source = "auto_restore:$source"
            )
            lastWifiToggleAtMs = now
            if (result.success) {
                lastAutoWifiDisableAtMs = null
                failoverHealthyCellStreak = 0
                Timber.i("✅ [Failover] WiFi re-enabled after cellular hold window")
            } else {
                Timber.e("❌ [Failover] Failed to re-enable WiFi after cellular hold window")
            }
        } finally {
            failoverTransitionInProgress = false
        }
    }

    private fun failoverToggleGateReason(
        settings: com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings,
        now: Long
    ): String? {
        if (failoverTransitionInProgress) {
            return "transition_in_progress"
        }
        if (criticalNetworkOperationManager.isAnyCriticalOperationInProgress()) {
            return "critical_operation_in_progress"
        }

        val cooldownMs = settings.cellularFailoverCooldownSeconds
            .coerceAtLeast(0)
            .toLong() * 1000L
        val lastToggle = lastWifiToggleAtMs
        if (lastToggle != null && (now - lastToggle) < cooldownMs) {
            val remaining = cooldownMs - (now - lastToggle)
            return "cooldown_active_${remaining}ms"
        }
        return null
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
        offlineTransitionJob?.cancel()
        offlineTransitionJob = null
        fastReprobeJob?.cancel()
        fastReprobeJob = null
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
