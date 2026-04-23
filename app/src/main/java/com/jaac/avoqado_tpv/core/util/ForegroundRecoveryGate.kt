package com.jaac.avoqado_tpv.core.util

import android.os.SystemClock
import timber.log.Timber

/**
 * Temporary gate to keep app foregrounded after sensitive external flows.
 *
 * Used for AngelPay/Nexgo edge case where system may unexpectedly dispatch HOME
 * right after returning a successful payment result.
 */
object ForegroundRecoveryGate {

    @Volatile
    private var armedUntilElapsedMs: Long = 0L
    @Volatile
    private var activeSequence: Long = 0L
    @Volatile
    private var consumedSequence: Long = 0L

    @Synchronized
    fun arm(durationMs: Long = 4000L, reason: String) {
        activeSequence += 1L
        consumedSequence = 0L
        armedUntilElapsedMs = SystemClock.elapsedRealtime() + durationMs
        Timber.w("🛡️ [ForegroundRecovery] Armed for ${durationMs}ms | reason=$reason | seq=$activeSequence")
    }

    @Synchronized
    fun disarm(reason: String) {
        if (armedUntilElapsedMs != 0L) {
            Timber.d("🛡️ [ForegroundRecovery] Disarmed | reason=$reason")
        }
        armedUntilElapsedMs = 0L
        activeSequence = 0L
        consumedSequence = 0L
    }

    @Synchronized
    fun isArmed(): Boolean {
        val isArmed = SystemClock.elapsedRealtime() < armedUntilElapsedMs
        if (!isArmed) {
            armedUntilElapsedMs = 0L
            activeSequence = 0L
            consumedSequence = 0L
        }
        return isArmed
    }

    /**
     * Consume the active arm only once per sequence.
     * Returns sequence token if caller should schedule recovery, null otherwise.
     */
    @Synchronized
    fun tryConsumeArm(): Long? {
        if (!isArmed()) return null
        if (activeSequence == 0L || consumedSequence == activeSequence) return null
        consumedSequence = activeSequence
        return activeSequence
    }

    @Synchronized
    fun isCurrentArm(sequence: Long): Boolean {
        return isArmed() && activeSequence == sequence
    }
}
