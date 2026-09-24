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
 * Una pasada con consultas SIN respuesta (red, tope) se REPITE — la recuperación absorbe esos fallos por candidata para seguir
 * con las demás, así que el worker decide por `Resultado.sinRespuesta`, no por una excepción (Codex, código, P2-1). Desde el
 * 23-sep se repite con su PROPIO seguimiento a 31 s y devuelve `success()`: el `retry()` de WorkManager dejaba la cadena entera
 * detrás de un backoff de horas (medido en una N86).
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

            // 🔴 Hardware (23-sep): nunca `retry()`. WorkManager duplica la espera en cada reintento (hasta 5 h) y toda la
            // recuperación va en UNA cadena: un «corre ya» (arranque, reconexión, duda nueva) quedaba detrás, BLOQUEADO. Medido
            // en una N86: 9 reintentos al frente, 10 pasadas bloqueadas y un cobro de $9 sin liberar con la red de vuelta. La
            // pasada sin respuesta se repite igual —con su propio seguimiento, acotado—, y la cadena nunca queda en backoff.
            val sinRespuesta = debeReintentar(r) || (bandeja?.sinRespuesta ?: 0) > 0
            val seguir = listOfNotNull(segundosParaSeguir(r), SEGUNDOS_TRAS_SIN_RESPUESTA.takeIf { sinRespuesta }).minOrNull()
            seguir?.let { LedgerSweepScheduler.runServerRecoveryNow(applicationContext, initialDelaySeconds = it) }
            if (sinRespuesta) Timber.w("🔎 [LedgerServer] %d consultas sin respuesta en la pasada — otra pasada en %d s", r.sinRespuesta, seguir)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            Timber.w(error, "🔎 [LedgerServer] sin red durante la pasada — otra pasada en %d s", SEGUNDOS_TRAS_SIN_RESPUESTA)
            LedgerSweepScheduler.runServerRecoveryNow(applicationContext, initialDelaySeconds = SEGUNDOS_TRAS_SIN_RESPUESTA)
            Result.success()
        } catch (error: Exception) {
            Timber.e(error, "🔎 [LedgerServer] la pasada falló — la siguiente corrida cubre")
            Result.success()
        }
    }

    companion object {
        /** Codex (código, P2-1): una consulta sin respuesta HTTP en la pasada ⇒ la pasada se REPITE (hoy con su seguimiento). */
        fun debeReintentar(r: LedgerServerRecovery.Resultado): Boolean = r.sinRespuesta > 0

        /** Hardware (23-sep): la pasada sin respuesta vuelve a los 31 s, el mismo reintento corto de cada duda sin respuesta. */
        val SEGUNDOS_TRAS_SIN_RESPUESTA = LedgerServerRecovery.REINTENTO_SIN_RESPUESTA_MS / 1000 + 1

        /**
         * Ronda 21: una duda local que esta pasada vio por primera vez todavía no cumple la espera del aviso del banco (se mide
         * en el reloj monotónico). Sin un seguimiento, esperaría al siguiente disparo (reconexión, otra duda, el periódico).
         * 🔴 Hardware (23-sep): con TOPE de 31 s. El seguimiento es la cabeza de la cadena, y todo «corre ya» espera detrás de
         * ella: uno a 10 min (una duda sin aviso comprobado) retrasaba 10 min la liberación de la siguiente. Las dudas que aún
         * esperan se saltan en memoria sin tocar la red, así que volver cada 31 s cuesta una lectura de la base.
         */
        fun segundosParaSeguir(r: LedgerServerRecovery.Resultado): Long? =
            r.proximaLiberacionSolaEnMs?.let { (minOf(it, LedgerServerRecovery.REINTENTO_SIN_RESPUESTA_MS) + 999) / 1000 + 1 }
    }
}
