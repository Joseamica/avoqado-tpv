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
 * Recovery is venue-scoped and runs independently of shadow mode. One-shot startup
 * runs after two minutes, allowing live recording to finish and freshly persisted rows
 * to reach the stale cutoff. Failed registration stays durable for bounded later retry.
 */
object LedgerSweepScheduler {

    private const val PERIODIC_WORK_NAME = "ledger_shadow_sweep"
    private const val ONE_SHOT_WORK_NAME = "ledger_shadow_sweep_once"
    private const val SWEEP_INTERVAL_HOURS = 6L
    private const val SERVER_RECOVERY_WORK_NAME = "ledger_server_recovery"

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

    /**
     * Checkpoint 2 · N3/N4 (diseño v3, D2/D3): la recuperación por SERVIDOR, en su propio worker con red.
     * `APPEND_OR_REPLACE`: una petición durante una corrida en curso encadena otra corrida DESPUÉS (KEEP la descartaría y
     * la incertidumbre esperaría al periódico). `CONNECTED` (LTE vale): sin red no hay nada que consultar; el mantenimiento
     * local vive en [LedgerShadowSweepWorker], que sigue sin constraints. [initialDelayMinutes] es un MÍNIMO, no un plazo.
     */
    fun runServerRecoveryNow(context: Context, initialDelayMinutes: Long = 0L) {
        val request = OneTimeWorkRequestBuilder<LedgerServerRecoveryWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .apply { if (initialDelayMinutes > 0) setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES) }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SERVER_RECOVERY_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        Timber.d("🔎 [LedgerServer] recuperación por servidor encolada (APPEND_OR_REPLACE, CONNECTED, +%d min)", initialDelayMinutes)
    }

    /** One-shot catch-up sweep at login/startup (KEEP — repeated starts don't stack). */
    fun runOnceNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<LedgerShadowSweepWorker>()
            .setInitialDelay(2, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        Timber.d("📒 [LedgerSweep] One-shot sweep requested")
    }
}
