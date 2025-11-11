package com.jaac.avoqado_tpv.core.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Payment Sync Scheduler
 *
 * Manages the lifecycle of the PaymentSyncWorker.
 * Follows Square Terminal and Toast POS patterns for offline payment queue management.
 *
 * **Responsibilities:**
 * - Start payment sync on login
 * - Stop payment sync on logout
 * - Configure sync interval and constraints
 * - Ensure only ONE payment sync worker runs at a time
 *
 * **Usage:**
 * ```kotlin
 * // Start payment sync (call on login success)
 * PaymentSyncScheduler.start(context)
 *
 * // Stop payment sync (call on logout)
 * PaymentSyncScheduler.stop(context)
 *
 * // Check if running
 * val isRunning = PaymentSyncScheduler.isRunning(context)
 * ```
 *
 * **Design Decisions:**
 * - **ExistingPeriodicWorkPolicy.KEEP**: Don't restart if already running
 * - **CONNECTED network constraint**: Only sync when network is available
 * - **15 minute interval**: Standard for POS offline queue (Square=15min, Toast=15min)
 * - **KEEP policy**: Avoid restarting sync on every login (preserves backoff state)
 *
 * **Why Static Object?**
 * - Payment sync scheduling doesn't hold state
 * - Simple API matching Android best practices (like NotificationManager)
 * - Avoids DI complexity for utility class
 *
 * **World-Class References:**
 * - Square Terminal: OfflineSyncScheduler with 15-min interval
 * - Toast POS: PaymentQueueScheduler with 15-min interval
 * - Stripe Terminal: TransactionSyncScheduler with exponential backoff
 */
object PaymentSyncScheduler {

    private const val PAYMENT_SYNC_WORK_NAME = "payment_sync_worker"
    private const val PAYMENT_SYNC_INTERVAL_MINUTES = 15L // Standard: Square=15min, Toast=15min

    /**
     * Start payment sync monitoring
     *
     * **When to call:**
     * - After successful login
     * - After app restart (if user already logged in)
     * - After first payment is queued
     *
     * **Behavior:**
     * - KEEPS existing worker if already running (KEEP policy)
     * - Starts after 15 minutes, then repeats every 15 minutes
     * - Runs in background even when app is closed
     *
     * **Constraints:**
     * - Requires network connection (CONNECTED)
     * - No battery/charging constraints (payments must sync ASAP)
     *
     * **Important:** Using KEEP policy prevents restarting worker on every login.
     * This preserves WorkManager's exponential backoff state if worker is retrying.
     *
     * @param context Application or Activity context
     */
    fun start(context: Context) {
        Timber.d("🚀 Starting payment sync scheduler (interval: ${PAYMENT_SYNC_INTERVAL_MINUTES}min)")

        // Define constraints: Only run when network is available
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Don't run when offline
            .build()

        // Build periodic work request
        val paymentSyncWorkRequest = PeriodicWorkRequestBuilder<PaymentSyncWorker>(
            repeatInterval = PAYMENT_SYNC_INTERVAL_MINUTES,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(PAYMENT_SYNC_WORK_NAME)
            .build()

        // Enqueue work (KEEP policy preserves existing worker)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PAYMENT_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing work if already running
            paymentSyncWorkRequest
        )

        Timber.d("✅ Payment sync scheduler started")
    }

    /**
     * Stop payment sync monitoring
     *
     * **When to call:**
     * - On logout
     * - When terminal is deactivated
     *
     * **Behavior:**
     * - Cancels all pending and running payment sync workers
     * - Gracefully stops background execution
     *
     * **Important:** This does NOT delete queued payments from Room database.
     * Payments remain in queue and will sync when worker is restarted.
     *
     * @param context Application or Activity context
     */
    fun stop(context: Context) {
        Timber.d("🛑 Stopping payment sync scheduler")

        WorkManager.getInstance(context).cancelUniqueWork(PAYMENT_SYNC_WORK_NAME)

        Timber.d("✅ Payment sync scheduler stopped")
    }

    /**
     * Check if payment sync is currently running
     *
     * **Use cases:**
     * - Debugging: Verify sync started after login
     * - UI indicators: Show "Syncing..." badge on PaymentSuccessScreen
     * - Health checks: Ensure sync running in production
     *
     * @param context Application or Activity context
     * @return true if payment sync worker is enqueued or running
     */
    suspend fun isRunning(context: Context): Boolean {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PAYMENT_SYNC_WORK_NAME)
            .get()

        val isRunning = workInfos.isNotEmpty() && workInfos.any { workInfo ->
            !workInfo.state.isFinished
        }

        Timber.d("Payment sync running: $isRunning")
        return isRunning
    }

    /**
     * Force immediate sync execution (for testing and manual retry)
     *
     * **Use cases:**
     * - Debugging: Test sync logic immediately
     * - User action: "Sync Now" button in UI
     * - After queueing payment: Trigger immediate sync attempt
     *
     * WorkManager doesn't guarantee immediate execution,
     * but this requests it to run ASAP (usually within 10 seconds).
     *
     * **Important:** This creates a ONE-TIME work request (doesn't affect periodic worker)
     *
     * @param context Application or Activity context
     */
    fun runNow(context: Context) {
        Timber.d("🔥 Requesting immediate payment sync execution")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateWorkRequest = androidx.work.OneTimeWorkRequestBuilder<PaymentSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(immediateWorkRequest)

        Timber.d("✅ Immediate payment sync requested")
    }

    /**
     * Get count of pending payments (for UI badge)
     *
     * **Use cases:**
     * - Show "3 payments pending" badge in app
     * - Display alert if failed payments > 0
     *
     * **Note:** This requires injecting PaymentQueueRepository.
     * For now, this is a placeholder for future implementation.
     *
     * Alternative: Use LiveData/Flow from repository in ViewModel layer.
     */
    // TODO: Implement getPendingCount() using repository (requires DI)
    // suspend fun getPendingCount(context: Context): Int { ... }
}
