package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Only asks about saved AngelPay attempts. Absence never resolves a charge or invokes a sale. */
@Singleton
class LedgerUnknownRecovery @Inject constructor(
    private val dao: PaymentAttemptDao,
    private val ledger: PaymentAttemptLedger,
    private val verifier: AngelPayChargeVerifier,
) {
    suspend fun recover(venueId: String, now: Long): Int {
        var confirmed = 0
        val rows = dao.getUnknownRecoveryCandidates(venueId, now - 120_000L, now)
        for (row in rows) {
            // F1 (Codex código r2 #1): la DESCARTADA que trae la consulta es la declarada por una persona (el SQL decide cuál).
            if (row.venueId != venueId || row.processor != PaymentAttemptEntity.PROCESSOR_ANGELPAY ||
                row.state !in listOf(PaymentAttemptEntity.STATE_AUTORIZANDO, PaymentAttemptEntity.STATE_INDETERMINADO,
                    PaymentAttemptEntity.STATE_DESCARTADA) ||
                row.hostApproved != null || row.verifyAttempts >= 5) continue
            try {
                val waitMs = 120_000L * (1L shl row.verifyAttempts.coerceIn(0, 4))
                val acquiredAt = maxOf(now, System.currentTimeMillis())
                val ownedLease = acquiredAt + maxOf(waitMs, 300_000L)
                if (dao.claimUnknownRecovery(row.attemptId, venueId, acquiredAt, ownedLease) != 1) continue
                val json = JsonParser.parseString(row.paymentContextJson).asJsonObject
                fun text(key: String): String? = json.get(key)?.takeUnless { it.isJsonNull }?.asString
                val terminal = text("deviceSerialNumber")?.takeIf { it.isNotBlank() } ?: continue
                val affiliation = text("processorAffiliation")?.takeIf { it.isNotBlank() } ?: continue
                if (text("venueId") != venueId || text("idempotencyKey") != row.attemptId ||
                    json.get("amount")?.asBigDecimal?.movePointRight(2)?.longValueExact() != row.amountCents ||
                    json.get("tip")?.asBigDecimal?.movePointRight(2)?.longValueExact() != row.tipCents) continue
                val date = Instant.ofEpochMilli(row.createdAt).atZone(com.jaac.avoqado_tpv.core.util.VenueTimeZone.MEXICO).toLocalDate()
                // One provider request per durable lease. No inner 3x retry multiplied by the worker.
                val result = kotlinx.coroutines.withTimeout(240_000L) {
                    verifier.verificar(row.attemptId, terminal, date, 1, 0, affiliation)
                }
                if (result is VerificacionDelCobro.Cobrado) {
                    if (dao.completeUnknownRecovery(row.attemptId, venueId, ownedLease,
                            maxOf(now, System.currentTimeMillis()), result.referencia, result.authCode) == 1) confirmed++
                    else conservarElDineroTrasUnCierre(venueId, row.attemptId, result.referencia)
                }
                // Empty, partial, failed or unsupported lookup leaves the same unknown row untouched.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Saved AngelPay result remains unknown: %s", row.attemptId)
            }
        }
        return confirmed
    }

    /**
     * 🔴 F1 (Codex código r1 #1, respaldo desde r2): la fila declarada por una persona ya la reabrió [PaymentAttemptDao.completeUnknownRecovery]
     * con la referencia y la autorización. Esto queda para una DESCARTADA que NO se puede reabrir (rechazo del banco, «no salió al
     * banco», veto, Payment ya en Avoqado) y a la que AngelPay contesta «cobrado»: el aprobado no se descarta, queda la marca
     * «hubo dinero» (`server_processor_evidence`), que aparta la cuenta, la saca en «Por revisar» y la cuenta el cierre de caja.
     * Una fila que sigue en duda (otra recuperación la tiene) se deja a su dueño.
     */
    private suspend fun conservarElDineroTrasUnCierre(venueId: String, attemptId: String, referencia: String) {
        val fila = dao.getById(attemptId) ?: return
        if (fila.venueId != venueId || fila.state != PaymentAttemptEntity.STATE_DESCARTADA || fila.hostApproved == true) return
        Timber.e("🚨 [Libreta] AngelPay aprobó un intento que ya estaba como «no se cobró»: queda marcado con dinero | attemptId=%s ref=%s", attemptId, referencia)
        ledger.marcarEvidenciaPositivaDelServidor(venueId, attemptId)
    }
}
