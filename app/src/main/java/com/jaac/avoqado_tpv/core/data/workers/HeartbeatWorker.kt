package com.jaac.avoqado_tpv.core.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.models.Heartbeat
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.models.TerminalStatus
import com.jaac.avoqado_tpv.core.util.DeviceHealthMonitor
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
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
    private val secureStorage: SecureStorage
) : CoroutineWorker(appContext, workerParams) {

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
            Timber.d("  Memory: ${heartbeat.systemInfo.memoryAvailableMB} MB")
            Timber.d("  Network: ${heartbeat.networkInfo.type} (metered: ${heartbeat.networkInfo.isMetered})")

            // Send heartbeat to backend
            val heartbeatResult = heartbeatRepository.sendHeartbeat(heartbeat)
            return when (heartbeatResult) {
                is com.jaac.avoqado_tpv.core.domain.models.Result.Success -> {
                    Timber.d("✅ Heartbeat sent successfully. Server status: ${heartbeatResult.data.serverStatus}")
                    androidx.work.ListenableWorker.Result.success()
                }
                is com.jaac.avoqado_tpv.core.domain.models.Result.Error -> {
                    val errorMessage = heartbeatResult.exception?.message ?: ""

                    // 🚨 SECURITY: Terminal has been RETIRED by admin (Square/Toast pattern)
                    // This happens when device is stolen, employee fired, or security breach
                    // Force clear activation and stop heartbeat immediately
                    if (errorMessage.contains("retired", ignoreCase = true)) {
                        Timber.e("🚨 Terminal has been RETIRED by administrator - clearing all data")

                        // Clear ALL data including activation (forces user back to activation screen)
                        // This revokes device activation, not just user session
                        secureStorage.clearAll()

                        // Stop heartbeat worker (no more heartbeats from this device)
                        HeartbeatScheduler.stop(applicationContext)

                        // Return failure (don't retry - terminal is permanently disabled)
                        return androidx.work.ListenableWorker.Result.failure()
                    }

                    Timber.w(heartbeatResult.exception, "❌ Heartbeat failed, will retry")
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

        // Determine terminal status
        // Phase 1: Always ACTIVE (idle detection in Phase 2)
        val status = TerminalStatus.ACTIVE

        return Heartbeat(
            terminalId = terminalId,
            timestamp = Instant.now().toString(),
            status = status,
            version = BuildConfig.VERSION_NAME,
            systemInfo = systemHealth,
            networkInfo = networkInfo
        )
    }
}
