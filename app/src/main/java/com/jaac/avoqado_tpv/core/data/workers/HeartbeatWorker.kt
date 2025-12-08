package com.jaac.avoqado_tpv.core.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.dto.PendingCommandDto
import com.jaac.avoqado_tpv.core.data.network.dto.toTpvCommand
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.models.Heartbeat
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.models.TerminalStatus
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.core.util.DeviceHealthMonitor
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.remote_command.data.model.CommandResult
import com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Instant

/**
 * Heartbeat Worker
 *
 * Periodic background worker that sends device health metrics to the backend.
 * Follows Square POS and Toast POS patterns for reliable device monitoring.
 *
 * **Execution Pattern:**
 * - Runs every 30 seconds (configurable via Constraints)
 * - Only runs when device is activated AND user is logged in
 * - Automatically retries on failure with exponential backoff
 * - Continues to run even when app is closed (background worker)
 *
 * **Design Principles:**
 * - **Offline-first**: Heartbeat failure does NOT block app functionality
 * - **Battery-aware**: Interval adjusts based on battery level
 * - **Network-aware**: Conserves data on cellular connections
 * - **Graceful degradation**: Terminal works fine if heartbeat fails
 *
 * **Lifecycle:**
 * 1. HeartbeatScheduler.start() → Enqueues periodic work
 * 2. WorkManager triggers every 30s (or custom interval)
 * 3. Worker collects device metrics
 * 4. Worker sends heartbeat to backend
 * 5. On success → Result.success()
 * 6. On failure → Result.retry() (WorkManager handles backoff)
 * 7. HeartbeatScheduler.stop() → Cancels work
 *
 * **Usage:**
 * This worker is NOT called directly. Use HeartbeatScheduler to manage it:
 * ```kotlin
 * HeartbeatScheduler.start(context) // Start on login
 * HeartbeatScheduler.stop(context)  // Stop on logout
 * ```
 */
@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val deviceInfoManager: DeviceInfoManager,
    private val deviceHealthMonitor: DeviceHealthMonitor,
    private val networkMonitor: NetworkMonitor,
    private val heartbeatRepository: HeartbeatRepository,
    private val secureStorage: SecureStorage,
    private val maintenanceManager: MaintenanceManager,
    private val commandExecutor: CommandExecutor
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        /**
         * Number of consecutive 404 "not found" errors required before clearing activation.
         * This protects against temporary issues or code bugs causing false 404s.
         *
         * Set to 10 to be more tolerant of temporary server issues:
         * - At 30s heartbeat interval, this means ~5 minutes of sustained 404s
         * - Prevents false deactivation when server is restarting or temporarily unreachable
         */
        private const val MAX_NOT_FOUND_BEFORE_CLEAR = 10

        /**
         * SharedPreferences key for tracking consecutive 404 errors.
         * Stored in regular prefs (not encrypted) since it's just a counter.
         */
        private const val PREF_NOT_FOUND_COUNT = "heartbeat_not_found_count"
    }

    /**
     * Execute heartbeat task
     *
     * **Return Values:**
     * - Result.success() → Heartbeat sent successfully
     * - Result.retry() → Temporary failure, WorkManager will retry with backoff
     * - Result.failure() → Permanent failure, stop retrying
     *
     * **Retry Policy:**
     * WorkManager uses exponential backoff: 10s → 20s → 40s → 80s → 5min (max)
     */
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return try {
            Timber.d("💓 Heartbeat worker started")

            // ✅ Square/Toast Pattern: Send heartbeat if terminal is ACTIVATED
            // DO NOT check if user is logged in - heartbeats run independently of login state
            //
            // Why? This prevents deadlock:
            // 1. User logs out → HeartbeatWorker would stop if we check isLoggedIn
            // 2. Backend marks terminal INACTIVE after 2 min without heartbeats
            // 3. User tries to login → Would fail if backend blocked INACTIVE
            // 4. Terminal can't recover because heartbeat doesn't run without login
            //
            // Solution: Heartbeat runs whenever terminal is activated (not logged in)
            val isActivated = secureStorage.isTerminalActivated()
            if (!isActivated) {
                Timber.w("⚠️ Terminal not activated, skipping heartbeat")
                return androidx.work.ListenableWorker.Result.failure()
            }

            // Build heartbeat from current device state
            val heartbeat = buildHeartbeat()

            // Log heartbeat details
            Timber.d("📊 Heartbeat metrics:")
            Timber.d("  Terminal ID: ${heartbeat.terminalId}")
            Timber.d("  Status: ${heartbeat.status}")
            Timber.d("  Battery: ${heartbeat.systemInfo.batteryLevel}% (charging: ${heartbeat.systemInfo.batteryCharging})")
            Timber.d("  Storage: ${heartbeat.systemInfo.storageAvailableGB} GB")
            Timber.d("  Memory: ${heartbeat.systemInfo.memoryInfo.usedMB}/${heartbeat.systemInfo.memoryInfo.totalMB} MB (${heartbeat.systemInfo.memoryInfo.freeMB} MB free)")
            Timber.d("  Network: ${heartbeat.networkInfo.type} (metered: ${heartbeat.networkInfo.isMetered})")

            // Send heartbeat to backend
            val heartbeatResult = heartbeatRepository.sendHeartbeat(heartbeat)
            return when (heartbeatResult) {
                is com.jaac.avoqado_tpv.core.domain.models.Result.Success -> {
                    // Reset "not found" counter on success
                    setNotFoundCount(0)
                    Timber.d("✅ Heartbeat sent successfully. Server status: ${heartbeatResult.data.serverStatus}")

                    // 🎯 Square Terminal API Pattern: Process pending commands from heartbeat response
                    // Commands are delivered via HTTP polling instead of socket push
                    val pendingCommands = heartbeatResult.data.pendingCommands
                    if (!pendingCommands.isNullOrEmpty()) {
                        Timber.i("📥 [Heartbeat] Received ${pendingCommands.size} pending command(s)")
                        processPendingCommands(pendingCommands)
                    }

                    androidx.work.ListenableWorker.Result.success()
                }
                is com.jaac.avoqado_tpv.core.domain.models.Result.Error -> {
                    val exception = heartbeatResult.exception
                    val errorMessage = exception?.message ?: ""

                    // 🔍 Only HttpError should trigger deactivation checks
                    // NetworkError (connection refused, timeout) should just retry
                    val httpError = exception as? com.jaac.avoqado_tpv.core.domain.models.ApiException.HttpError

                    // 🚨 SECURITY: Terminal has been RETIRED by admin (Square/Toast pattern)
                    // This happens when device is stolen, employee fired, or security breach
                    // Force clear activation and stop heartbeat immediately
                    // ⚠️ ONLY check for HTTP errors, not connection errors
                    if (httpError != null && errorMessage.contains("retired", ignoreCase = true)) {
                        Timber.e("🚨 Terminal has been RETIRED by administrator - clearing all data")

                        // Clear ALL data including activation (forces user back to activation screen)
                        // This revokes device activation, not just user session
                        secureStorage.clearAll()

                        // Stop heartbeat worker (no more heartbeats from this device)
                        HeartbeatScheduler.stop(applicationContext)

                        // Return failure (don't retry - terminal is permanently disabled)
                        return androidx.work.ListenableWorker.Result.failure()
                    }

                    // 🚨 Terminal not found in backend (HTTP 404 ONLY)
                    // This happens when:
                    // - Database was reset/migrated
                    // - Terminal was deleted from backend
                    // - Terminal never existed (invalid serialNumber)
                    //
                    // ⚠️ CRITICAL: Only count explicit HTTP 404 responses, NOT:
                    // - Connection errors (server crashed/restarting)
                    // - Timeout errors
                    // - Network errors
                    // - Any error message that happens to contain "404" or "not found"
                    //
                    // Safety: Only clear activation after MAX_NOT_FOUND_BEFORE_CLEAR consecutive 404s
                    if (httpError != null && httpError.code == 404) {
                        val currentCount = getNotFoundCount() + 1
                        setNotFoundCount(currentCount)

                        Timber.w("⚠️ Terminal NOT FOUND - HTTP 404 (attempt $currentCount/$MAX_NOT_FOUND_BEFORE_CLEAR)")

                        if (currentCount >= MAX_NOT_FOUND_BEFORE_CLEAR) {
                            Timber.e("🚨 Terminal NOT FOUND after $MAX_NOT_FOUND_BEFORE_CLEAR consecutive HTTP 404s - clearing activation data")

                            // Reset counter
                            setNotFoundCount(0)

                            // Clear ALL data including activation (forces user back to activation screen)
                            secureStorage.clearAll()

                            // Stop heartbeat worker (no more heartbeats until re-activated)
                            HeartbeatScheduler.stop(applicationContext)

                            // Return failure (don't retry - terminal needs re-activation)
                            return androidx.work.ListenableWorker.Result.failure()
                        }

                        // Not enough consecutive failures yet, retry
                        return androidx.work.ListenableWorker.Result.retry()
                    }

                    // ✅ Reset "not found" counter on any non-404 error
                    // This ensures transient errors don't accumulate towards deactivation
                    if (httpError == null || httpError.code != 404) {
                        val previousCount = getNotFoundCount()
                        if (previousCount > 0) {
                            Timber.d("🔄 Resetting NOT FOUND counter (was $previousCount) - non-404 error received")
                            setNotFoundCount(0)
                        }
                    }

                    Timber.w(heartbeatResult.exception, "❌ Heartbeat failed, will retry | type=${exception?.javaClass?.simpleName}")
                    // WorkManager will retry with exponential backoff
                    androidx.work.ListenableWorker.Result.retry()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "💥 Heartbeat worker crashed")
            // Retry on unexpected errors
            androidx.work.ListenableWorker.Result.retry()
        }
    }

    /**
     * Build heartbeat from current device state
     *
     * Collects metrics from:
     * - DeviceInfoManager (serial number)
     * - DeviceHealthMonitor (battery, storage, memory)
     * - NetworkMonitor (connection type, quality)
     */
    private fun buildHeartbeat(): Heartbeat {
        // Get terminal serial number (with AVQD- prefix, e.g., AVQD-2841548417)
        // Backend handles both formats: "AVQD-2841548417" and "2841548417"
        val terminalId = deviceInfoManager.getSerialNumber()

        // Get system health metrics
        val systemHealth = deviceHealthMonitor.getSystemHealth()

        // Get network information
        val networkInfo = networkMonitor.getCurrentNetworkInfo()

        // Determine terminal status based on MaintenanceManager state
        // If in maintenance mode, send MAINTENANCE status to prevent server from overriding it
        val status = if (maintenanceManager.isInMaintenance.value) {
            Timber.d("🛠️ [Heartbeat] Terminal is in MAINTENANCE mode")
            TerminalStatus.MAINTENANCE
        } else {
            TerminalStatus.ACTIVE
        }

        return Heartbeat(
            terminalId = terminalId,
            timestamp = Instant.now().toString(),
            status = status,
            version = BuildConfig.VERSION_NAME,
            systemInfo = systemHealth,
            networkInfo = networkInfo
        )
    }

    /**
     * Get the current "not found" error count from SharedPreferences
     */
    private fun getNotFoundCount(): Int {
        val prefs = applicationContext.getSharedPreferences("heartbeat_prefs", Context.MODE_PRIVATE)
        return prefs.getInt(PREF_NOT_FOUND_COUNT, 0)
    }

    /**
     * Set the "not found" error count in SharedPreferences
     */
    private fun setNotFoundCount(count: Int) {
        val prefs = applicationContext.getSharedPreferences("heartbeat_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_NOT_FOUND_COUNT, count).apply()
    }

    // ========================================
    // Square Terminal API Polling Pattern
    // ========================================

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
     * **Flow:**
     * 1. Convert each PendingCommandDto to TpvCommand domain model
     * 2. Execute command via CommandExecutor
     * 3. Send HTTP ACK to backend (more reliable than socket)
     * 4. Backend updates TpvCommandQueue status and broadcasts to dashboard
     *
     * **Why HTTP ACK instead of Socket?**
     * - Works even when socket not connected (login screen)
     * - More reliable delivery (HTTP retry vs socket fire-and-forget)
     * - Backend needs confirmation that command was processed
     *
     * @param pendingCommands List of commands from heartbeat response
     */
    private suspend fun processPendingCommands(pendingCommands: List<PendingCommandDto>) {
        // Get terminal serial number for security validation in ACKs
        val terminalId = deviceInfoManager.getSerialNumber()

        for (commandDto in pendingCommands) {
            try {
                Timber.i("🔄 [Heartbeat] Processing command: ${commandDto.type} (id=${commandDto.commandId})")

                // 1. Convert DTO to domain model
                val command = commandDto.toTpvCommand()
                if (command == null) {
                    Timber.w("⚠️ [Heartbeat] Unknown command type: ${commandDto.type}, skipping")
                    // Send REJECTED ACK for unknown command (with terminalId for security)
                    val rejectResult = CommandResult.rejected("Unknown command type: ${commandDto.type}")
                    heartbeatRepository.sendCommandAck(commandDto.commandId, terminalId, rejectResult)
                    continue
                }

                // 2. Execute command via CommandExecutor
                // Note: CommandExecutor also sends socket ACKs (which may fail silently if not connected)
                // The HTTP ACK below ensures backend always gets the result
                val result = commandExecutor.execute(command)

                // 3. Send HTTP ACK to backend (primary acknowledgment for polling pattern)
                // This is more reliable than socket ACK and works even from login screen
                // terminalId is required for security validation (backend verifies ownership)
                val ackResult = heartbeatRepository.sendCommandAck(command.commandId, terminalId, result)
                if (ackResult is Result.Success) {
                    Timber.i("✅ [Heartbeat] Command completed and ACK sent: ${command.type.name} → ${result.status.name}")
                } else {
                    // ACK failed but command was executed - backend will eventually timeout
                    // and mark as failed, dashboard will show "no response"
                    Timber.w("⚠️ [Heartbeat] Command executed but ACK failed: ${command.commandId}")
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Heartbeat] Failed to process command: ${commandDto.commandId}")
                // Try to send FAILED ACK (with terminalId for security)
                try {
                    val failResult = CommandResult.failed("Execution error: ${e.message}")
                    heartbeatRepository.sendCommandAck(commandDto.commandId, terminalId, failResult)
                } catch (ackError: Exception) {
                    Timber.e(ackError, "❌ [Heartbeat] Failed to send FAILED ACK: ${commandDto.commandId}")
                }
            }
        }
    }
}
