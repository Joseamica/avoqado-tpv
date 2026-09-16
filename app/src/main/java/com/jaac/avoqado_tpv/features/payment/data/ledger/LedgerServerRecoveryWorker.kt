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
 * Un fallo de red devuelve `retry()` (backoff por defecto de WorkManager); lo demás `success()`.
 */
@HiltWorker
class LedgerServerRecoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val secureStorage: SecureStorage,
    private val serverRecovery: LedgerServerRecovery,
    private val socketManager: SocketManager,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val venueId = secureStorage.getVenueId() ?: return Result.success()
        return try {
            val r = serverRecovery.recover(venueId)
            // Un `success` durable de bandeja recién escrito se emite al servidor si hay socket (persistido ANTES de emitir).
            r.bandejasResueltas.forEach(socketManager::emitDurableTerminalPaymentResult)
            Result.success()
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
}
