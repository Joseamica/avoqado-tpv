package com.jaac.avoqado_tpv.core.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jaac.avoqado_tpv.core.data.workers.PromoterLocationWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Promoter Location Scheduler ("cambaceo")
 *
 * Manages the lifecycle of [PromoterLocationWorker] (hourly ping).
 *
 * - Started on login success (like HeartbeatScheduler/PaymentSyncScheduler).
 * - Stopped on logout — UNLIKE the heartbeat, location tracking MUST stop
 *   with the session (privacy: we track the promoter's workday, not the
 *   terminal).
 * - Scheduling unconditionally on login is safe: the worker no-ops outside
 *   [11:00, 18:00) venue-local or when the venue flag is off.
 */
object PromoterLocationScheduler {

    private const val WORK_NAME = "promoter_location_worker"
    private const val INTERVAL_MINUTES = 60L

    fun start(context: Context) {
        Timber.d("🚀 Starting promoter location scheduler (interval: ${INTERVAL_MINUTES}m)")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<PromoterLocationWorker>(
            repeatInterval = INTERVAL_MINUTES,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    fun stop(context: Context) {
        Timber.d("🛑 Stopping promoter location scheduler")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
