package com.jaac.avoqado_tpv.core.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jaac.avoqado_tpv.core.data.workers.HeartbeatWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Heartbeat Scheduler
 *
 * Manages the lifecycle of the HeartbeatWorker.
 * Follows Square POS pattern for background task management.
 *
 * **Responsibilities:**
 * - Start heartbeat on login
 * - Stop heartbeat on logout
 * - Configure heartbeat interval and constraints
 * - Ensure only ONE heartbeat worker runs at a time
 *
 * **Usage:**
 * ```kotlin
 * // Start heartbeat (call on login success)
 * HeartbeatScheduler.start(context)
 *
 * // Stop heartbeat (call on logout)
 * HeartbeatScheduler.stop(context)
 *
 * // Check if running
 * val isRunning = HeartbeatScheduler.isRunning(context)
 * ```
 *
 * **Design Decisions:**
 * - **ExistingPeriodicWorkPolicy.REPLACE**: Ensures only one heartbeat worker
 * - **CONNECTED network constraint**: Don't waste battery trying when offline
 * - **30 second interval**: Standard for POS systems (Square uses 30s, Toast uses 60s)
 * - **KEEP policy on UPDATE**: Restart heartbeat if worker code changes
 *
 * **Why Static Object?**
 * - Heartbeat scheduling doesn't hold state
 * - Simple API matching Android best practices (like NotificationManager)
 * - Avoids DI complexity for utility class
 */
object HeartbeatScheduler {

    private const val HEARTBEAT_WORK_NAME = "heartbeat_worker"
    private const val HEARTBEAT_INTERVAL_SECONDS = 30L // Standard: Square=30s, Toast=60s

    /**
     * Start heartbeat monitoring
     *
     * **When to call:**
     * - After successful login
     * - After app restart (if user already logged in)
     *
     * **Behavior:**
     * - Replaces any existing heartbeat worker
     * - Starts immediately, then repeats every 30s
     * - Runs in background even when app is closed
     *
     * **Constraints:**
     * - Requires network connection (CONNECTED)
     * - No battery/charging constraints (terminal must always report)
     *
     * @param context Application or Activity context
     */
    fun start(context: Context) {
        Timber.d("🚀 Starting heartbeat scheduler (interval: ${HEARTBEAT_INTERVAL_SECONDS}s)")

        // Define constraints: Only run when network is available
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Don't run when offline
            .build()

        // Build periodic work request
        val heartbeatWorkRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            repeatInterval = HEARTBEAT_INTERVAL_SECONDS,
            repeatIntervalTimeUnit = TimeUnit.SECONDS
        )
            .setConstraints(constraints)
            .addTag(HEARTBEAT_WORK_NAME)
            .build()

        // Enqueue work (REPLACE policy ensures only one instance)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEARTBEAT_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE, // Replace existing work if any
            heartbeatWorkRequest
        )

        Timber.d("✅ Heartbeat scheduler started")
    }

    /**
     * Stop heartbeat monitoring
     *
     * **When to call:**
     * - On logout
     * - When terminal is deactivated
     *
     * **Behavior:**
     * - Cancels all pending and running heartbeat workers
     * - Gracefully stops background execution
     *
     * @param context Application or Activity context
     */
    fun stop(context: Context) {
        Timber.d("🛑 Stopping heartbeat scheduler")

        WorkManager.getInstance(context).cancelUniqueWork(HEARTBEAT_WORK_NAME)

        Timber.d("✅ Heartbeat scheduler stopped")
    }

    /**
     * Check if heartbeat is currently running
     *
     * **Use cases:**
     * - Debugging: Verify heartbeat started after login
     * - UI indicators: Show "Device Online" badge
     * - Health checks: Ensure heartbeat running in production
     *
     * @param context Application or Activity context
     * @return true if heartbeat worker is enqueued or running
     */
    suspend fun isRunning(context: Context): Boolean {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(HEARTBEAT_WORK_NAME)
            .get()

        val isRunning = workInfos.isNotEmpty() && workInfos.any { workInfo ->
            !workInfo.state.isFinished
        }

        Timber.d("Heartbeat running: $isRunning")
        return isRunning
    }

    /**
     * Force immediate heartbeat execution (for testing)
     *
     * **ONLY FOR DEBUGGING** - Do not use in production code
     *
     * WorkManager doesn't guarantee immediate execution,
     * but this requests it to run ASAP.
     */
    fun runNow(context: Context) {
        Timber.d("🔥 Requesting immediate heartbeat execution (testing only)")

        // Note: WorkManager may delay execution up to 10 minutes
        // This is by design - for true immediate execution, call repository directly
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateWorkRequest = androidx.work.OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(immediateWorkRequest)
    }

    /**
     * Update heartbeat interval dynamically (Phase 2 - Future)
     *
     * Adaptive intervals based on device state:
     * - WiFi + charging = 15s (aggressive)
     * - Cellular = 60s (conserve data)
     * - Low battery = 120s (conserve power)
     *
     * Phase 1: Fixed 30s interval (industry standard)
     */
    // TODO: Implement adaptive intervals in Phase 2
    // fun updateInterval(context: Context, intervalSeconds: Long) { ... }
}
