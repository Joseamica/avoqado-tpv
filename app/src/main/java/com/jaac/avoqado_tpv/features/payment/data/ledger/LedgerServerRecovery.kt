package com.jaac.avoqado_tpv.features.payment.data.ledger

import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checkpoint 2 · N3 (diseño v3, E2/E3): la recuperación por SERVIDOR. Barato y de Avoqado, antes que el procesador.
 *
 * Paso 0 (sin red): reaplica los veredictos FINALES ya guardados cuyo estado local cambió después (E2).
 * Paso 1 (con red): consulta S6 por las candidatas —sin veredicto o con veredicto no final— con avance y espaciado por
 * intento (E3), y aplica lo que conteste ([PaymentAttemptDao.aplicarVeredictoDelServidor]); `NOT_RECORDED`, 404 (intento
 * desconocido o ruta vieja), 401/403/5xx sólo estampan el turno de la fila; sin respuesta HTTP no se gasta el turno.
 *
 * Nunca autoriza nada, nunca discards por ausencia, nunca toca filas heredadas. Idempotente y sin lease propio: S6 es de
 * sólo lectura y toda transición es CAS. El resultado de la bandeja (un `success` durable nuevo) se devuelve para que
 * quien tenga socket lo EMITA después del commit.
 */
@Singleton
class LedgerServerRecovery @Inject constructor(
    private val dao: PaymentAttemptDao,
    private val ledger: PaymentAttemptLedger,
    private val api: TerminalAttemptApiService,
) {
    data class Resultado(
        val reaplicados: Int,
        val consultados: Int,
        val aplicados: Int,
        /** JSON de resultados de bandeja que quedaron RESOLVED `success` en esta pasada: hay que emitirlos al servidor. */
        val bandejasResueltas: List<String>,
        /** Codex (código, P2-1): consultas SIN respuesta HTTP (red, timeout) en esta pasada — el worker reintenta por ellas. */
        val sinRespuesta: Int = 0,
    )

    suspend fun recover(venueId: String, now: Long = System.currentTimeMillis()): Resultado {
        var reaplicados = 0; var consultados = 0; var aplicados = 0; var sinRespuesta = 0
        val bandejas = mutableListOf<String>()
        // Paso 0 · E2: lo ya sabido se aplica sin gastar red.
        for (fila in runCatching { dao.veredictosPendientesDeAplicar(venueId) }.getOrDefault(emptyList())) {
            val r = ledger.reaplicarVeredictoGuardado(fila.attemptId, now) ?: continue
            if (r.transiciono) reaplicados++
            r.bandejaResueltaJson?.let(bandejas::add)
        }
        // Paso 1 · E3: consultar S6 con avance y espaciado.
        val candidatas = runCatching {
            dao.candidatasDeConsultaAlServidor(venueId, now - VIVOS_MS, now, ESPACIADO_BASE_MS, ESPACIADO_TOPE_MS)
        }.getOrDefault(emptyList())
        for (fila in candidatas) {
            try {
                // Codex (código, P2-1, ronda 2): el tope PROPIO de la consulta se expresa con `withTimeoutOrNull` — devuelve null
                // en vez de lanzar una CancellationException indistinguible de la de un padre (worker cancelado, timeout externo),
                // que SÍ tiene que propagarse. Sin respuesta = «no se gasta el turno», la pasada sigue con la siguiente candidata.
                val respuesta = kotlinx.coroutines.withTimeoutOrNull(CONSULTA_TIMEOUT_MS) { api.getAttemptStatus(venueId, fila.attemptId) }
                if (respuesta == null) {
                    sinRespuesta++
                    Timber.w("🔎 [LedgerServer] consulta sin respuesta (tope de %d ms) para %s", CONSULTA_TIMEOUT_MS, fila.attemptId)
                    continue
                }
                consultados++
                val veredicto = if (respuesta.isSuccessful) {
                    respuesta.body()?.let { VeredictoDeIntento.desdeConsultaS6(venueId, fila.attemptId, it) }
                } else {
                    // 404 (intento desconocido, otra terminal, ruta vieja), 401/403, 5xx: nada acredita nada. Sólo el turno.
                    Timber.d("🔎 [LedgerServer] %s ⇒ HTTP %d: la fila se conserva", fila.attemptId, respuesta.code())
                    null
                }
                if (veredicto == null) {
                    dao.estamparConsultaAlServidor(fila.attemptId, now)
                    continue
                }
                val r = ledger.aplicarVeredictoDelServidor(veredicto, now).getOrNull() ?: continue
                if (r.transiciono) aplicados++
                r.bandejaResueltaJson?.let(bandejas::add)
            } catch (cancelled: CancellationException) {
                throw cancelled // cancelación EXTERNA (el worker, un timeout del padre): se propaga, nunca se cuenta como «sin respuesta»
            } catch (error: Exception) {
                // Sin respuesta HTTP (red): no se gasta el turno de la fila; el worker con CONNECTED reintenta por `sinRespuesta`.
                sinRespuesta++
                Timber.w(error, "🔎 [LedgerServer] consulta sin respuesta para %s", fila.attemptId)
            }
        }
        if (reaplicados + consultados + aplicados + sinRespuesta > 0) {
            Timber.i("🔎 [LedgerServer] pasada | reaplicados=%d consultados=%d aplicados=%d sinRespuesta=%d bandejas=%d",
                reaplicados, consultados, aplicados, sinRespuesta, bandejas.size)
        }
        return Resultado(reaplicados, consultados, aplicados, bandejas, sinRespuesta)
    }

    /**
     * D3: UN intento, en el acto — primero lo guardado (E2), después S6 si hace falta. Un fallo es no-op (el worker lo
     * retoma). Devuelve el JSON de bandeja resuelto, si lo hubo, para emitirlo.
     */
    suspend fun recoverOne(venueId: String, attemptId: String, now: Long = System.currentTimeMillis()): String? {
        ledger.reaplicarVeredictoGuardado(attemptId, now)?.let { if (it.transiciono || it.bandejaResueltaJson != null) return it.bandejaResueltaJson }
        val fila = runCatching { dao.getById(attemptId) }.getOrNull() ?: return null
        if (fila.legacyShadow || fila.venueId != venueId || fila.terminalPaymentRequestId == null) return null
        val outcomeGuardado = fila.serverOutcome
        if (outcomeGuardado == PaymentAttemptEntity.SERVER_RECORDED || outcomeGuardado == PaymentAttemptEntity.SERVER_SECOND_CAPTURE_EVIDENCE) return null
        return try {
            val respuesta = kotlinx.coroutines.withTimeoutOrNull(CONSULTA_TIMEOUT_MS) { api.getAttemptStatus(venueId, attemptId) }
            if (respuesta == null) {
                Timber.w("🔎 [LedgerServer] consulta inmediata sin respuesta (tope) para %s", attemptId)
                return null
            }
            val veredicto = if (respuesta.isSuccessful) respuesta.body()?.let { VeredictoDeIntento.desdeConsultaS6(venueId, attemptId, it) } else null
            if (veredicto == null) {
                dao.estamparConsultaAlServidor(attemptId, now)
                null
            } else {
                ledger.aplicarVeredictoDelServidor(veredicto, now).getOrNull()?.bandejaResueltaJson
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "🔎 [LedgerServer] consulta inmediata sin respuesta para %s", attemptId)
            null
        }
    }

    companion object {
        /** Los estados con SDK dentro sólo se consultan pasados 120 s (mismo umbral que la recuperación por historial). */
        const val VIVOS_MS = 120_000L
        const val ESPACIADO_BASE_MS = 10 * 60_000L
        const val ESPACIADO_TOPE_MS = 24 * 60 * 60_000L
        const val CONSULTA_TIMEOUT_MS = 30_000L
    }
}
