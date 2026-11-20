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
                if (iniciadas >= CONSULTAS_POR_CORRIDA) break@paginas
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
                        // C3: la marca queda como «respondida con fallo técnico» (ya no en vuelo): frena hasta el techo o la firma; se sigue preguntando.
                        is VerificacionDelCobro.NoSePudoVerificar -> dao.pausarConsultaSinGastarTurno(
                            row.attemptId, venueId, ownedLease, terminadaEn + pausa(row.historyChecks))
                        // 🔴 Codex plan A #2 (AngelPay, punto 31): la venta está en el historial sin aprobar (`05AP`: llegó al procesador y
                        // puede aprobarse tarde). No es «no aparece»: la marca se queda —frena su tarjeta aunque la ventana del SERVIDOR la
                        // suelte— y se vuelve a preguntar sin gastar turno. La resuelven el aprobado (arriba) o una PERSONA: si ya la firmó,
                        // cuenta como «no aparece» y su tarjeta sigue lo que decidió. Nunca el silencio. Si la VENTANA del SERVIDOR ya la
                        // soltó, sigue siendo firmable en «Por revisar» (F1 · 05AP, 20-nov): el SERVIDOR guarda la firma sin cambiar el estado
                        // financiero, su comprobante la deja «cerrada por una persona» y la libreta pide esta pasada al vencer la reserva.
                        // 🔴 Techo del 05AP (Codex final r6 #1 y #2): pasado [PaymentAttemptEntity.TECHO_DEL_05AP_MS] desde el intento, también
                        // cuenta como «no aparece»; antes, la marca queda como «contestó 05AP» y frena su tarjeta sólo hasta el techo.
                        is VerificacionDelCobro.EnElProcesador ->
                            if (PaymentAttemptEntity.cerradaPorUnaPersona(dao.getById(row.attemptId)) ||
                                terminadaEn - row.createdAt >= PaymentAttemptEntity.TECHO_DEL_05AP_MS) dao.terminarConsultaSinCoincidencia(
                                row.attemptId, venueId, ownedLease, terminadaEn + pausa(row.historyChecks + 1))
                            else dao.pausarTras05AP(row.attemptId, venueId, ownedLease, terminadaEn + pausa(row.historyChecks))
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
        const val FILAS_POR_PAGINA = 50
    }
}
