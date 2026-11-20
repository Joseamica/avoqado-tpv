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
    /**
     * @param reloj monotónico (ms) para [presupuestoMs].
     * @param presupuestoMs F1 · Codex final r8 #7 (C): pasado este tiempo desde que empezó, la pasada ya no INICIA consultas (la que está en
     *   vuelo termina). Sin presupuesto, como siempre (tope de [CONSULTAS_POR_CORRIDA]).
     */
    suspend fun recover(
        venueId: String, now: Long, reloj: () -> Long = { System.nanoTime() / 1_000_000L }, presupuestoMs: Long = Long.MAX_VALUE,
    ): Int {
        val inicio = reloj()
        var confirmed = 0
        // 🔴 Codex final r3 #1: por páginas desde la última fila LEÍDA, y el tope cuenta consultas INICIADAS (tomadas), no filas leídas:
        // una fila que se salta sin tomar turno ya no tapa a las de atrás. ponytail: las que nunca se podrán consultar se releen en cada
        // barrido (O(filas sin afiliación)); si algún día son muchas, sacarlas del SQL.
        var iniciadas = 0
        var trasToque = Long.MIN_VALUE
        var trasId = ""
        paginas@ while (iniciadas < CONSULTAS_POR_CORRIDA) {
            val rows = dao.getUnknownRecoveryCandidates(venueId, now - 120_000L, now, trasToque, trasId, FILAS_POR_PAGINA)
            rows.lastOrNull()?.let { trasToque = it.historyReadAt ?: it.createdAt; trasId = it.attemptId }
            for (row in rows) {
                if (iniciadas >= CONSULTAS_POR_CORRIDA || reloj() - inicio >= presupuestoMs) break@paginas
                if (row.venueId != venueId || row.processor != PaymentAttemptEntity.PROCESSOR_ANGELPAY ||
                    row.kind != PaymentAttemptEntity.KIND_SALE || row.hostApproved != null) continue
                try {
                    // El contexto se valida ANTES de tomar la consulta: un contexto inválido no gasta ningún turno ni deja marca.
                    val consulta = consultaGuardada(row, venueId)
                    if (consulta == null) {
                        // 🔴 Codex plan de rediseño #8: nunca se podrá consultar. Si ya traía una lectura despachada (la pantalla murió a
                        // media consulta), se quita su marca en la escritura que lo decide: si no, frenaba su tarjeta para siempre.
                        row.historyReadAt?.let { dao.soltarLecturaQueNadiePuedeHacer(row.attemptId, venueId, it, now) }
                        continue
                    }
                    val acquiredAt = maxOf(now, System.currentTimeMillis())
                    val ownedLease = acquiredAt + RESERVA_DE_LA_LECTURA_MS
                    if (dao.claimUnknownRecovery(row.attemptId, venueId, acquiredAt, ownedLease) != 1) continue
                    iniciadas++
                    val date = Instant.ofEpochMilli(row.createdAt).atZone(com.jaac.avoqado_tpv.core.util.VenueTimeZone.MEXICO).toLocalDate()
                    // One provider request per durable lease. No inner 3x retry multiplied by the worker.
                    val result = kotlinx.coroutines.withTimeout(240_000L) {
                        verifier.verificar(row.attemptId, consulta.terminal, date, 1, 0, consulta.afiliacion)
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
                        // 🔴 Codex plan A #2 (AngelPay, punto 31): la venta está en el historial sin aprobar (`05AP`: llegó al procesador y
                        // puede aprobarse tarde). No es «no aparece»: la marca se queda y se vuelve a preguntar SIN gastar turno mientras el
                        // intento tenga menos de [SEGUIMIENTO_DEL_SIN_APROBAR_MS] (F1 · Codex r7 #1: aunque la VENTANA del SERVIDOR la haya
                        // soltado; la marca la mantiene entre las candidatas). El seguimiento ya no depende del freno: la tarjeta de su cuenta
                        // sólo espera hasta el techo ([PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS]). Pasados los 7 días cuenta como
                        // «no aparece» y gasta turno; con los turnos acabados, se acaba.
                        // 🔴 F1 · Codex final r8 #3 (E): firmada o no. La firma de una persona decide el FRENO (lo suelta de inmediato), nunca
                        // el seguimiento: antes cada «sin aprobar» de una venta firmada gastaba turno y a las ~30 h dejaba de buscarse; si el
                        // banco la aprobaba después, con su aviso perdido, cero Payment.
                        is VerificacionDelCobro.EnElProcesador ->
                            if (terminadaEn - row.createdAt >= SEGUIMIENTO_DEL_SIN_APROBAR_MS) dao.terminarConsultaSinCoincidencia(
                                row.attemptId, venueId, ownedLease, terminadaEn + pausa(row.historyChecks + 1))
                            else dao.pausarConsultaSinGastarTurno(row.attemptId, venueId, ownedLease, terminadaEn + pausa(row.historyChecks))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // La lectura queda despachada: la siguiente pasada la retoma al vencer su reserva, sin gastar el turno.
                    Timber.w(error, "Saved AngelPay result remains unknown: %s", row.attemptId)
                }
            }
            if (rows.size < FILAS_POR_PAGINA) break
        }
        return confirmed
    }

    private class Consulta(val terminal: String, val afiliacion: String)

    /**
     * Con qué se le pregunta al historial por esta venta, o null si NUNCA se podrá: el contexto se guarda una sola vez, al abrir el
     * intento (sin terminal ni afiliación, ilegible, o que no cuadra con la fila).
     */
    private fun consultaGuardada(row: PaymentAttemptEntity, venueId: String): Consulta? = runCatching {
        val json = JsonParser.parseString(row.paymentContextJson).asJsonObject
        fun text(key: String): String? = json.get(key)?.takeUnless { it.isJsonNull }?.asString
        val terminal = text("deviceSerialNumber")?.takeIf { it.isNotBlank() } ?: return null
        val affiliation = text("processorAffiliation")?.takeIf { it.isNotBlank() } ?: return null
        if (text("venueId") != venueId || text("idempotencyKey") != row.attemptId ||
            json.get("amount")?.asBigDecimal?.movePointRight(2)?.longValueExact() != row.amountCents ||
            json.get("tip")?.asBigDecimal?.movePointRight(2)?.longValueExact() != row.tipCents) return null
        Consulta(terminal, affiliation)
    }.getOrNull()

    private fun pausa(turnos: Int): Long = maxOf(120_000L * (1L shl turnos.coerceIn(0, 4)), 300_000L)

    companion object {
        /** Lo que dura la reserva de una lectura en vuelo (el verificador tiene 4 min de tope, más margen). */
        const val RESERVA_DE_LA_LECTURA_MS = 300_000L
        /** Consultas al historial que un barrido INICIA como máximo (antes, filas leídas: `LIMIT 50`). */
        const val CONSULTAS_POR_CORRIDA = 50
        /**
         * 🔴 F1 · Codex r7 #1 (20-nov): cuánto se sigue buscando, sin gastar turno, el aprobado tardío de una venta que el historial de
         * AngelPay trae «sin aprobar» (`05AP`), contado desde `created_at`. Se pregunta cuando una pasada la encuentra con su pausa vencida
         * (5 min tras la anterior como mínimo): en el barrido de 6 h, al arrancar o entrar, y en las pasadas de seguimiento.
         */
        const val SEGUIMIENTO_DEL_SIN_APROBAR_MS = 7 * 24 * 3_600_000L
        const val FILAS_POR_PAGINA = 50
    }
}
