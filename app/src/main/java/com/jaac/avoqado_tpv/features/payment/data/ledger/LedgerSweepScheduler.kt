package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Scheduler for [LedgerShadowSweepWorker] (La Libreta shadow sweep).
 *
 * Same static-object pattern as PaymentSyncScheduler (which it deliberately
 * does NOT touch — spec §4.5: the sweep is its own worker).
 *
 * - `schedule()`: unique periodic work every 6h, KEEP policy — repeated logins
 *   never reset the cadence.
 * - `runOnceNow()`: unique one-shot catch-up at login/startup, KEEP policy —
 *   repeated starts while one is pending don't duplicate it.
 *
 * No network constraint on purpose: the sweep is pure local Room work
 * (observability logs ship asynchronously through their own pipeline).
 * Gating by paymentLedgerMode/venueId lives INSIDE the worker, so scheduling
 * unconditionally is safe — OFF mode is an immediate no-op success.
 */
object LedgerSweepScheduler {

    private const val PERIODIC_WORK_NAME = "ledger_shadow_sweep"
    private const val ONE_SHOT_WORK_NAME = "ledger_shadow_sweep_once"
    private const val SWEEP_INTERVAL_HOURS = 6L

    /** Enqueue the 6h periodic sweep (KEEP — no-op if already scheduled). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LedgerShadowSweepWorker>(
            repeatInterval = SWEEP_INTERVAL_HOURS,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
            .addTag(PERIODIC_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Timber.d("📒 [LedgerSweep] Periodic sweep scheduled (every %dh, KEEP)", SWEEP_INTERVAL_HOURS)
    }

    /** One-shot catch-up sweep at login/startup (KEEP — repeated starts don't stack). */
    fun runOnceNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<LedgerShadowSweepWorker>().build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        Timber.d("📒 [LedgerSweep] One-shot sweep requested")
    }
}
