package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentLedgerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La Libreta — the single write-ahead API every payment path calls (spec §4.4).
 *
 * Guarantees:
 *  - Writes are committed BEFORE returning (Room suspend DAO = committed on return),
 *    so `openAttempt`+`markAuthorizing` form the pre-SDK barrier.
 *  - Post-SDK marks run inside NonCancellable + IO: they survive screen pops and
 *    ViewModel clears (the Mindform window).
 *  - A ledger failure NEVER blocks the charge: every entry point is runCatching;
 *    degradation = "no row", which is exactly today's behavior.
 *  - Gated by paymentLedgerMode (OFF = hard no-op).
 */
@Singleton
class PaymentAttemptLedger @Inject constructor(
    private val dao: PaymentAttemptDao,
    private val settingsRepository: TpvSettingsRepository
) {

    fun isEnabled(): Boolean =
        settingsRepository.getCurrentSettings().paymentLedgerMode != PaymentLedgerMode.OFF

    suspend fun openAttempt(
        attemptId: String,
        venueId: String,
        processor: String,
        amountCents: Long,
        tipCents: Long,
        recordingRoute: String,
        contextJson: String,
        kind: String = PaymentAttemptEntity.KIND_SALE
    ): Boolean {
        if (!isEnabled()) return true
        // Deliberately cancellable (unlike the post-SDK marks): a CancellationException
        // mid-write is caught by runCatching and returns normally (true) with the write
        // aborted. The state machine absorbs the missing/partial row — from-PREPARANDO
        // transitions and the sweep's OPEN_STATES cover it. Never-block-charge by design.
        return runCatching {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val rowId = dao.insert(
                    PaymentAttemptEntity(
                        attemptId = attemptId,
                        venueId = venueId,
                        processor = processor,
                        kind = kind,
                        state = PaymentAttemptEntity.STATE_PREPARANDO,
                        amountCents = amountCents,
                        tipCents = tipCents,
                        recordingRoute = recordingRoute,
                        paymentContextJson = contextJson,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                if (rowId == -1L) {
                    // PK collision: this attemptId already has a live row. This is the
                    // split/kiosk reuse signal (spec §6) — a second charge is about to
                    // ride an idempotency key the backend will dedupe into the FIRST
                    // record. Loud, never silent.
                    Timber.e("📒🚨 [Libreta] attemptId REUSE detected | attemptId=%s — possible dedup-swallowed charge", attemptId)
                    false
                } else {
                    Timber.d("📒 [Libreta] PREPARANDO | attemptId=%s amount=%d+%d", attemptId, amountCents, tipCents)
                    true
                }
            }
        }.getOrElse { e ->
            Timber.e(e, "📒 [Libreta] openAttempt failed — charge continues unledgered")
            true // never block the charge
        }
    }

    /** Pre-SDK barrier: committed before the SDK call is allowed to start. */
    suspend fun markAuthorizing(attemptId: String) = cas(
        attemptId, from = listOf(PaymentAttemptEntity.STATE_PREPARANDO),
        to = PaymentAttemptEntity.STATE_AUTORIZANDO, label = "AUTORIZANDO"
    )

    /**
     * The instant the host answers — BEFORE EMV completion, BEFORE publishing
     * Success. approved=false is an explicit host decline → DESCARTADA (spec §4.2).
     */
    suspend fun markHostResponded(
        attemptId: String,
        approved: Boolean,
        operationId: String?,
        referenceNumber: String?,
        authCode: String?
    ) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val to = if (approved) PaymentAttemptEntity.STATE_HOST_RESPONDIO else PaymentAttemptEntity.STATE_DESCARTADA
                val n = dao.casHostResponded(
                    attemptId,
                    listOf(
                        PaymentAttemptEntity.STATE_AUTORIZANDO,
                        PaymentAttemptEntity.STATE_PREPARANDO,
                        // The live verdict beats the sweep's guess: a charge stuck >quarantine
                        // threshold in AUTORIZANDO (hung-then-recovering SaleIcc, AngelPay D308
                        // relaunch) may get quarantined to INDETERMINADO by the sweep while still
                        // alive — the real host answer (approve OR late explicit decline) must
                        // still land, or the row is a permanent false "money moved, no record".
                        PaymentAttemptEntity.STATE_INDETERMINADO
                    ),
                    to, System.currentTimeMillis(),
                    operationId, referenceNumber, authCode, approved
                )
                logCas(n, attemptId, to)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markHostResponded failed") }
    }

    /** From-PREPARANDO covers contactless offline-approved (no online auth ever runs). */
    suspend fun markAuthorized(attemptId: String, maskedPan: String?, cardBrand: String?, entryMode: String?) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val n = dao.casWithCardDetails(
                    attemptId,
                    listOf(
                        PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                        PaymentAttemptEntity.STATE_AUTORIZANDO,
                        PaymentAttemptEntity.STATE_PREPARANDO,
                        // Live verdict beats the sweep's guess: a still-alive charge quarantined
                        // to INDETERMINADO mid-auth must still be resolvable to AUTORIZADO.
                        PaymentAttemptEntity.STATE_INDETERMINADO
                    ),
                    PaymentAttemptEntity.STATE_AUTORIZADO, System.currentTimeMillis(),
                    maskedPan, cardBrand, entryMode
                )
                logCas(n, attemptId, PaymentAttemptEntity.STATE_AUTORIZADO)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markAuthorized failed") }
    }

    suspend fun markRecorded(attemptId: String) = casNonCancellable(
        attemptId,
        from = listOf(
            PaymentAttemptEntity.STATE_AUTORIZADO,
            PaymentAttemptEntity.STATE_HOST_RESPONDIO,
            // Live verdict beats the sweep's guess: a quarantined-but-alive attempt that
            // goes on to record successfully must close as REGISTRADO, not rot forever.
            PaymentAttemptEntity.STATE_INDETERMINADO
        ),
        to = PaymentAttemptEntity.STATE_REGISTRADO, label = "REGISTRADO"
    )

    suspend fun markRecordFailed(attemptId: String, error: String?) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val n = dao.casWithError(
                    attemptId,
                    listOf(
                        PaymentAttemptEntity.STATE_AUTORIZADO,
                        PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                        // Live verdict beats the sweep's guess: a quarantined-but-alive attempt
                        // whose recording fails must land in REGISTRO_FALLIDO so the queue
                        // handoff (markDeliveredToQueue) can still happen.
                        PaymentAttemptEntity.STATE_INDETERMINADO
                    ),
                    PaymentAttemptEntity.STATE_REGISTRO_FALLIDO, System.currentTimeMillis(), error?.take(500)
                )
                logCas(n, attemptId, PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markRecordFailed failed") }
    }

    /** Once queued, pending_payments owns the money (its idempotency + retry) — the ledger row rests. */
    suspend fun markDeliveredToQueue(attemptId: String) = casNonCancellable(
        attemptId, from = listOf(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO),
        to = PaymentAttemptEntity.STATE_ENTREGADA_A_COLA, label = "ENTREGADA_A_COLA"
    )

    /** ONLY from PREPARANDO: a cancel during AUTORIZANDO has an unknown outcome — the row must live. */
    suspend fun markDiscardedBeforeCharge(attemptId: String, reason: String) {
        if (!isEnabled()) return
        // Deliberately cancellable: pre-charge, so a CancellationException mid-write just
        // leaves the row in PREPARANDO — the sweep (OPEN_STATES) reconciles it later.
        runCatching {
            withContext(Dispatchers.IO) {
                dao.casWithError(
                    attemptId, listOf(PaymentAttemptEntity.STATE_PREPARANDO),
                    PaymentAttemptEntity.STATE_DESCARTADA, System.currentTimeMillis(), reason
                )
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markDiscardedBeforeCharge failed") }
    }

    /**
     * Cancellable CAS (used by markAuthorizing — the pre-SDK barrier). Deliberate:
     * a CancellationException mid-write is swallowed by runCatching and the mark
     * returns normally with the write aborted; the row stays in its previous state
     * and from-PREPARANDO transitions + the sweep's OPEN_STATES absorb it. This is
     * intentional never-block-charge behavior, not an oversight.
     */
    private suspend fun cas(attemptId: String, from: List<String>, to: String, label: String) {
        if (!isEnabled()) return
        runCatching {
            withContext(Dispatchers.IO) {
                logCas(dao.casTransition(attemptId, from, to, System.currentTimeMillis()), attemptId, to)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] mark%s failed", label) }
    }

    private suspend fun casNonCancellable(attemptId: String, from: List<String>, to: String, label: String) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                logCas(dao.casTransition(attemptId, from, to, System.currentTimeMillis()), attemptId, to)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] mark%s failed", label) }
    }

    private fun logCas(updated: Int, attemptId: String, to: String) {
        if (updated == 1) {
            Timber.d("📒 [Libreta] %s | attemptId=%s", to, attemptId)
        } else {
            // Not an error: races (worker vs callback vs manual) resolve by CAS — the loser logs.
            Timber.w("📒 [Libreta] CAS no-match → %s ignored | attemptId=%s", to, attemptId)
        }
    }
}
