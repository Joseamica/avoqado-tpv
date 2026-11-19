package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Only asks about saved AngelPay attempts. Absence never resolves a charge or invokes a sale.
 *
 * 🔴 F1 bloque 4 (I3): una consulta se gasta cuando SU respuesta queda escrita, nunca al tomarla. Orden: validar el contexto →
 * tomar la consulta (queda despachada en la fila, `history_read_at`) → preguntar al historial por la llave original → escribir la
 * respuesta → gastar el turno una vez. Si la app muere en medio, la lectura despachada vuelve primero, aunque haya vencido su reserva
 * o cerrado la ventana. «Cobrado» entra por la única puerta del aprobado ([PaymentAttemptLedger.aprobadoFinalAngelPay]), sin
 * importar el estado ni quién tenga la reserva; «todavía no aparece» gasta un turno; un fallo técnico pausa sin gastarlo y la lectura
 * sigue despachada hasta una respuesta escrita.
 */
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
            if (row.venueId != venueId || row.processor != PaymentAttemptEntity.PROCESSOR_ANGELPAY ||
                row.kind != PaymentAttemptEntity.KIND_SALE || row.hostApproved != null) continue
            try {
                // El contexto se valida ANTES de tomar la consulta: un contexto inválido no gasta ningún turno.
                val json = JsonParser.parseString(row.paymentContextJson).asJsonObject
                fun text(key: String): String? = json.get(key)?.takeUnless { it.isJsonNull }?.asString
                val terminal = text("deviceSerialNumber")?.takeIf { it.isNotBlank() } ?: continue
                val affiliation = text("processorAffiliation")?.takeIf { it.isNotBlank() } ?: continue
                if (text("venueId") != venueId || text("idempotencyKey") != row.attemptId ||
                    json.get("amount")?.asBigDecimal?.movePointRight(2)?.longValueExact() != row.amountCents ||
                    json.get("tip")?.asBigDecimal?.movePointRight(2)?.longValueExact() != row.tipCents) continue
                val acquiredAt = maxOf(now, System.currentTimeMillis())
                val ownedLease = acquiredAt + RESERVA_DE_LA_LECTURA_MS
                if (dao.claimUnknownRecovery(row.attemptId, venueId, acquiredAt, ownedLease) != 1) continue
                val date = Instant.ofEpochMilli(row.createdAt).atZone(com.jaac.avoqado_tpv.core.util.VenueTimeZone.MEXICO).toLocalDate()
                // One provider request per durable lease. No inner 3x retry multiplied by the worker.
                val result = kotlinx.coroutines.withTimeout(240_000L) {
                    verifier.verificar(row.attemptId, terminal, date, 1, 0, affiliation)
                }
                val terminadaEn = maxOf(now, System.currentTimeMillis())
                when (result) {
                    is VerificacionDelCobro.Cobrado -> {
                        val escrito = ledger.aprobadoFinalAngelPay(
                            row.attemptId, venueId, result.referencia, result.authCode, OrigenDelAprobado.HISTORIAL_RECUPERACION,
                        )
                        if (escrito == AprobadoFinal.REGISTRABLE || escrito == AprobadoFinal.REGISTRABLE_TRAS_LA_VENTANA) confirmed++
                    }
                    // Respuesta válida sin coincidencia: «todavía no aparece». Gasta UN turno (y sólo si la reserva sigue siendo ésta).
                    VerificacionDelCobro.NoCobrado -> dao.terminarConsultaSinCoincidencia(
                        row.attemptId, venueId, ownedLease, terminadaEn + pausa(row.historyChecks + 1))
                    // Red, credenciales o lectura ilegible: pausa sin gastar el turno y sin borrar la lectura despachada (Codex final r1 #3).
                    is VerificacionDelCobro.NoSePudoVerificar -> dao.pausarConsultaSinGastarTurno(
                        row.attemptId, venueId, ownedLease, terminadaEn + pausa(row.historyChecks))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // La lectura queda despachada: la siguiente pasada la retoma al vencer su reserva, sin gastar el turno.
                Timber.w(error, "Saved AngelPay result remains unknown: %s", row.attemptId)
            }
        }
        return confirmed
    }

    private fun pausa(turnos: Int): Long = maxOf(120_000L * (1L shl turnos.coerceIn(0, 4)), 300_000L)

    companion object {
        /** Lo que dura la reserva de una lectura en vuelo (el verificador tiene 4 min de tope, más margen). */
        const val RESERVA_DE_LA_LECTURA_MS = 300_000L
    }
}
