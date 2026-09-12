package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentLedgerMode
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * La Libreta — shadow-mode sweep (spec §4.5, Plan 2 / Task 6).
 *
 * Periodic observer of the payment_attempts write-ahead ledger. In SHADOW (and
 * ACTIVE) mode it:
 *  1. Logs every OPEN row older than [LedgerSweepLogic.STALE_AUTHORIZING_MIN]
 *     to observability (tag `LibretaShadowOpenRow`) — the "money may have moved
 *     with no record" telemetry the Mindform incident lacked.
 *  2. Quarantines AUTORIZANDO rows stuck past 10 min → INDETERMINADO
 *     (process died mid-auth; INDETERMINADO is NEVER deleted).
 *  3. Closes REGISTRADO rows after 24h → CERRADA (happy path rests).
 *  4. Prunes CERRADA/DESCARTADA rows after 7 days (mirror of
 *     deleteOldSyncedPayments).
 *
 * First replays durable approvals with their original key through LedgerApprovalRecovery,
 * independently of shadow mode. Never authorizes, never discards by absence, and never
 * competes for queue-owned ENTREGADA_A_COLA rows.
 *
 * Own worker on purpose — spec §4.5 forbids touching PaymentSyncWorker.
 * Scheduling: [LedgerSweepScheduler] (unique periodic 6h + one-shot at login).
 */
@HiltWorker
class LedgerShadowSweepWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val paymentAttemptDao: PaymentAttemptDao,
    private val settingsRepository: TpvSettingsRepository,
    private val secureStorage: SecureStorage,
    private val observability: ObservabilityManager,
    private val approvalRecovery: LedgerApprovalRecovery,
    private val unknownRecovery: LedgerUnknownRecovery
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Financial recovery is mandatory even when shadow telemetry is OFF.
            secureStorage.getVenueId()?.let { venueId ->
                unknownRecovery.recover(venueId, System.currentTimeMillis())
                approvalRecovery.recover(venueId, System.currentTimeMillis())
            }
            val result = LedgerSweepLogic.runGated(
                settingsRepository = settingsRepository,
                secureStorage = secureStorage,
                dao = paymentAttemptDao,
                observability = observability,
                now = System.currentTimeMillis()
            )
            if (result == null) {
                Timber.d("📒 [LedgerSweep] Skipped (mode OFF or no venue)")
            } else {
                Timber.i(
                    "📒 [LedgerSweep] Done | open=%d quarantined=%d closed=%d pruned=%d",
                    result.openRowsLogged, result.quarantined, result.closed, result.pruned
                )
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e // Worker cancelled — not an error
        } catch (e: Exception) {
            // Observational sweep must never retry-storm: the next periodic run
            // (6h) covers whatever this run missed. Success on purpose.
            Timber.e(e, "📒 [LedgerSweep] Sweep failed — will retry on next periodic run")
            Result.success()
        }
    }
}

/**
 * Pure sweep logic — JVM-testable without WorkManager/Robolectric
 * (see LedgerSweepLogicTest). All timestamps are epoch millis passed IN as
 * `now` (never SystemClock — breaks unit tests, repo memory).
 */
object LedgerSweepLogic {

    /** AUTORIZANDO stuck past this = process died mid-auth → quarantine (spec §4.5). Also the open-row log cutoff. */
    const val STALE_AUTHORIZING_MIN = 10L

    /** REGISTRADO rows close silently after a day (spec §4.3). */
    const val CLOSE_RECORDED_HOURS = 24L

    /** CERRADA/DESCARTADA pruned after a week. INDETERMINADO is NEVER pruned (DAO SQL). */
    const val PRUNE_DAYS = 7L

    data class SweepResult(
        val openRowsLogged: Int,
        val quarantined: Int,
        val closed: Int,
        val pruned: Int
    )

    /**
     * The worker's gate, extracted for JVM testing:
     * mode OFF → null with ZERO DAO calls; no venueId → null. Otherwise sweeps.
     */
    suspend fun runGated(
        settingsRepository: TpvSettingsRepository,
        secureStorage: SecureStorage,
        dao: PaymentAttemptDao,
        observability: ObservabilityManager,
        now: Long
    ): SweepResult? {
        if (settingsRepository.getCurrentSettings().paymentLedgerMode == PaymentLedgerMode.OFF) return null
        val venueId = secureStorage.getVenueId() ?: return null
        return sweepOnce(dao, venueId, observability, now)
    }

    /**
     * One sweep pass. Order matters: open rows are OBSERVED FIRST so the log
     * captures each row as found (a stale AUTORIZANDO row is logged as
     * AUTORIZANDO with its signature — not as the INDETERMINADO the quarantine
     * is about to turn it into). Then the three bookkeeping transitions run.
     */
    suspend fun sweepOnce(
        dao: PaymentAttemptDao,
        venueId: String,
        observability: ObservabilityManager,
        now: Long
    ): SweepResult {
        val staleCutoff = now - TimeUnit.MINUTES.toMillis(STALE_AUTHORIZING_MIN)

        // 1. Observe: one log per open row (SHADOW = log only; no reconciliation
        //    here — REGISTRO_FALLIDO recovery is Plan 3).
        val openRows = dao.getOpenOlderThan(venueId, PaymentAttemptEntity.OPEN_STATES, staleCutoff)
        openRows.forEach { row ->
            // Addendum #1 (Tasks 4/5): AUTORIZANDO + operation_id NULL exists by
            // design (Blumon posId-null early-return; AngelPay pre-launch auth/
            // validation failure). Same quarantine either way, but the signature
            // lets ops triage: never_launched = probable config error, low
            // priority; in_flight_evidence = the host may have answered.
            val signature = if (row.operationId == null) "never_launched" else "in_flight_evidence"
            observability.logWarning(
                "LibretaShadowOpenRow",
                "Fila abierta en libreta: ${row.state} (${signature})",
                mapOf(
                    "attemptId" to row.attemptId,
                    "state" to row.state,
                    "processor" to row.processor,
                    "amountCents" to row.amountCents,
                    "ageMinutes" to (now - row.createdAt) / 60_000L,
                    "signature" to signature
                )
            )
        }

        // 2. Bookkeeping transitions (venue-scoped; CAS-safe SQL in the DAO).
        val quarantined = dao.quarantineStaleAuthorizing(venueId, staleCutoff, now) +
            // 🔴 El proceso murió DENTRO del kernel: pudo aprobar. Se cuarentena, nunca se descarta.
            dao.quarantineStaleKernel(venueId, staleCutoff, now)
        // 🔴 Y su contrapeso, de DISPONIBILIDAD: PREPARANDO retiene la terminal, así que una fila
        // huérfana (proceso muerto entre la reserva y el kernel) dejaría la caja sin poder cobrar.
        // Liberarla es seguro por construcción: en PREPARANDO ninguna llamada capaz de autorizar
        // empezó. Es lo ÚNICO del ledger que puede liberarse por tiempo.
        val releasedPreparing = dao.discardStalePreparing(venueId, staleCutoff, now)
        if (releasedPreparing > 0) {
            Timber.w("📒 [Libreta] %d reserva(s) PREPARANDO huérfanas liberadas | venueId=%s", releasedPreparing, venueId)
        }
        val closed = dao.closeRecordedOlderThan(venueId, now - TimeUnit.HOURS.toMillis(CLOSE_RECORDED_HOURS), now)
        val pruned = dao.pruneTerminalOlderThan(venueId, now - TimeUnit.DAYS.toMillis(PRUNE_DAYS))

        return SweepResult(
            openRowsLogged = openRows.size,
            quarantined = quarantined,
            closed = closed,
            pruned = pruned
        )
    }
}
