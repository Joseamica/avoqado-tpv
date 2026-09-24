package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Checkpoint 2 · N3/N4 (diseño v3, D2/D3): el worker de RED de la recuperación por servidor. Propio a propósito —
 * `LedgerShadowSweepWorker` (cuarentena, poda, historial) debe correr OFFLINE y sin constraints; éste exige CONNECTED y se
 * encola con `APPEND_OR_REPLACE` (una petición nacida a media corrida encadena otra corrida, no se pierde como con KEEP).
 * Una pasada con consultas SIN respuesta (red, tope) devuelve `retry()` (backoff por defecto de WorkManager) — la recuperación
 * absorbe esos fallos por candidata para seguir con las demás, así que el worker decide por `Resultado.sinRespuesta`, no por
 * una excepción (Codex, código, P2-1); lo demás `success()`.
 */
@HiltWorker
class LedgerServerRecoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val secureStorage: SecureStorage,
    private val serverRecovery: LedgerServerRecovery,
    private val bandejaRecovery: com.jaac.avoqado_tpv.core.remotepayment.BandejaServerRecovery,
    private val socketManager: SocketManager,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val venueId = secureStorage.getVenueId() ?: return Result.success()
        return try {
            val r = serverRecovery.recover(venueId)
            // Un `success` durable de bandeja recién escrito se emite al servidor si hay socket (persistido ANTES de emitir).
            r.bandejasResueltas.forEach(socketManager::emitDurableTerminalPaymentResult)

            // 🔴 Pieza D (22-sep): en la MISMA pasada se concilian las filas de bandeja HUÉRFANAS — las PROCESSING sin
            // ningún intento correlacionado, que ninguna recuperación alcanzaba porque todas preguntan por INTENTO.
            // Ésas son las que dejaban un aviso de «cobro sin confirmar» encendido para siempre (medido: 25 h en una
            // N86, sobre una solicitud que el servidor ya había resuelto). Va DESPUÉS de la recuperación por intento a
            // propósito: si esa pasada acaba de crear o cerrar un intento, esta fila ya no es huérfana y no se toca.
            // Un fallo aquí no puede tumbar la pasada de intentos, que es la que mueve dinero.
            val bandeja = runCatching { bandejaRecovery.conciliar(venueId) }
                .onFailure { if (it is CancellationException) throw it; Timber.w(it, "🧾 [BandejaD] la conciliación falló — la siguiente pasada cubre") }
                .getOrNull()

            segundosParaSeguir(r)?.let { LedgerSweepScheduler.runServerRecoveryNow(applicationContext, initialDelaySeconds = it) }
            if (debeReintentar(r) || (bandeja?.sinRespuesta ?: 0) > 0) {
                Timber.w("🔎 [LedgerServer] %d consultas sin respuesta en la pasada — se reintenta", r.sinRespuesta)
                Result.retry()
            } else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            Timber.w(error, "🔎 [LedgerServer] sin red durante la pasada — se reintenta")
            Result.retry()
        } catch (error: Exception) {
            Timber.e(error, "🔎 [LedgerServer] la pasada falló — la siguiente corrida cubre")
            Result.success()
        }
    }

    companion object {
        /** Codex (código, P2-1): una consulta sin respuesta HTTP en la pasada ⇒ `retry()` (backoff de WorkManager), no `success()`. */
        fun debeReintentar(r: LedgerServerRecovery.Resultado): Boolean = r.sinRespuesta > 0

        /**
         * Ronda 21: una duda local que esta pasada vio por primera vez todavía no cumple la espera del aviso del banco (se mide
         * en el reloj monotónico). Sin un seguimiento, esperaría al siguiente disparo (reconexión, otra duda, el periódico).
         */
        fun segundosParaSeguir(r: LedgerServerRecovery.Resultado): Long? =
            r.proximaLiberacionSolaEnMs?.let { (it + 999) / 1000 + 1 }
    }
}
