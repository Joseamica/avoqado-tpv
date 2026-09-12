package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.*
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Replays saved approvals only. No processor authorization is reachable from this class. */
@Singleton
class LedgerApprovalRecovery @Inject constructor(
    private val dao: PaymentAttemptDao,
    private val fast: FastPaymentRecorder,
    private val order: OrderPaymentRecorder,
) {
    suspend fun recover(venueId: String, now: Long): Int {
        var recovered = 0
        // Let a live SDK/recorder finish first; page size is capped by the DAO.
        val rows = dao.getApprovalRecoveryCandidates(venueId, now - 120_000L, now)
        for (row in rows) {
            if (row.venueId != venueId || row.state !in RECOVERABLE ||
                (row.hostApproved != true && row.state != PaymentAttemptEntity.STATE_AUTORIZADO) ||
                row.verifyAttempts >= 5) continue
            try {
                val waitMs = 120_000L * (1L shl row.verifyAttempts.coerceIn(0, 4))
                val acquiredAt = maxOf(now, System.currentTimeMillis())
                val ownedLease = acquiredAt + maxOf(waitMs, 300_000L)
                if (dao.claimRecovery(row.attemptId, venueId, acquiredAt, ownedLease) != 1) continue
                val context = restoreContext(row) ?: continue
                val card = CardDetails(
                    maskedPan = row.maskedPan.orEmpty(),
                    cardBrand = CardBrand.entries.firstOrNull { it.name == row.cardBrand } ?: CardBrand.UNKNOWN,
                    entryMode = CardEntryMode.entries.firstOrNull { it.name == row.entryMode } ?: CardEntryMode.OTHER,
                )
                // Direct recorder = exactly one HTTP attempt. The ledger owns retry/backoff;
                // ENTREGADA_A_COLA is excluded because PaymentSyncWorker already owns it.
                val result = kotlinx.coroutines.withTimeout(240_000L) {
                    if (row.recordingRoute == PaymentAttemptEntity.ROUTE_ORDER)
                        order.recordPayment(context, card, row.authCode.orEmpty(), row.referenceNumber.orEmpty())
                    else fast.recordPayment(context, card, row.authCode.orEmpty(), row.referenceNumber.orEmpty())
                }
                val completedAt = maxOf(now, System.currentTimeMillis())
                val changed = dao.completeRecovery(row.attemptId, venueId, ownedLease,
                    if (result.isSuccess) PaymentAttemptEntity.STATE_REGISTRADO else PaymentAttemptEntity.STATE_REGISTRO_FALLIDO,
                    completedAt, if (result.isSuccess) null else "Cobrado; registro pendiente")
                if (result.isSuccess && changed == 1) recovered++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Saved approval still pending registration: %s", row.attemptId)
            }
        }
        return recovered
    }

    private fun restoreContext(row: PaymentAttemptEntity): PaymentContext? {
        val gson = Gson()
        val operation = row.operationId?.toIntOrNull()
        val context: PaymentContext = when {
            row.processor == PaymentAttemptEntity.PROCESSOR_ANGELPAY ->
                gson.fromJson(row.paymentContextJson, PaymentContext.AngelPayPayment::class.java)
                    .copy(authorizationCode = row.authCode.orEmpty(), referenceNumber = row.referenceNumber.orEmpty())
            row.recordingRoute == PaymentAttemptEntity.ROUTE_ORDER ->
                gson.fromJson(row.paymentContextJson, PaymentContext.OrderPayment::class.java)
                    .copy(blumonOperationNumber = operation)
            row.recordingRoute == PaymentAttemptEntity.ROUTE_FAST ->
                gson.fromJson(row.paymentContextJson, PaymentContext.FastPayment::class.java)
                    .copy(blumonOperationNumber = operation)
            else -> return null
        }
        // Old SHADOW rows have only RetryContext/partial JSON. Never invent the missing
        // staff, venue, merchant or order from today's session; keep those for reconciliation.
        if (context.venueId != row.venueId || context.staffId.isNullOrBlank() ||
            context.idempotencyKey != row.attemptId || context.merchantAccountId.isNullOrBlank() ||
            context.deviceSerialNumber.isNullOrBlank() ||
            context.amount.movePointRight(2).longValueExact() != row.amountCents ||
            context.tip.movePointRight(2).longValueExact() != row.tipCents) return null
        return context
    }

    companion object {
        val RECOVERABLE = listOf(PaymentAttemptEntity.STATE_HOST_RESPONDIO,
            PaymentAttemptEntity.STATE_AUTORIZADO, PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)
    }
}
