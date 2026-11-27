package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.content.Context
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checkpoint 2 · N4 (diseño v3, D3): cablea el hook [PaymentAttemptLedger.onUncertaintyBorn]. Al nacer una incertidumbre
 * (INDETERMINADO / REGISTRO_FALLIDO) hace DOS cosas: (a) en el acto, [LedgerServerRecovery.recoverOne] —reaplicar lo
 * guardado sin red y, si hay socket, consultar S6— y (b) encola el worker de red con 2 min de retraso mínimo como respaldo
 * durable (`APPEND_OR_REPLACE`). Vive fuera de la libreta para que ésta no dependa de la recuperación (ciclo) ni de Android.
 */
@Singleton
class LedgerRecoveryTrigger @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val ledger: PaymentAttemptLedger,
    private val serverRecovery: dagger.Lazy<LedgerServerRecovery>,
    private val socketManager: dagger.Lazy<SocketManager>,
    private val secureStorage: SecureStorage,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun instalar() {
        ledger.onUncertaintyBorn = { attemptId ->
            LedgerSweepScheduler.runServerRecoveryNow(appContext, initialDelayMinutes = RESPALDO_MINUTOS)
            val venueId = secureStorage.getVenueId()
            if (venueId != null) {
                scope.launch {
                    runCatching { serverRecovery.get().recoverOne(venueId, attemptId) }
                        .onSuccess { lectura -> lectura.bandejaResueltaJson?.let { socketManager.get().emitDurableTerminalPaymentResult(it) } }
                        .onFailure { Timber.w(it, "🔎 [LedgerServer] recuperación inmediata falló para %s", attemptId) }
                }
            }
        }
        Timber.d("🔎 [LedgerServer] hook de incertidumbre instalado")
        // 🔴 Ronda 20 (founder, 23-sep): al ARRANCAR, lo que un proceso muerto dejó a medias pasa AL INSTANTE a «en duda», y la
        // recuperación se agenda para cuando ya pasó la espera del aviso del banco: la terminal se libera SOLA, sin que nadie
        // abra la pantalla de cobro. Sin red, el worker espera a la red (CONNECTED) y la duda se sigue viendo en el aviso.
        scope.launch {
            val n = ledger.cuarentenaDeHuerfanos()
            // El worker (+12 s) sigue siendo el respaldo DURABLE —y el que consulta S6 por los cobros del POS—, pero no el camino
            // de un Pago rápido: detrás de su cadena podía esperar minutos (Codex r19, P2-2).
            if (n > 0) LedgerSweepScheduler.runServerRecoveryNow(appContext, initialDelaySeconds = SEGUNDOS_TRAS_ARRANCAR)
            val venueId = secureStorage.getVenueId() ?: return@launch
            if (liberarTrasArrancar(venueId)) LedgerSweepScheduler.runServerRecoveryNow(appContext)
        }
    }

    /**
     * 🔴 Ronda 21 (Codex r19, P2-2): libera las dudas LOCALES del arranque EN EL PROCESO — pide ya, espera lo que le falte a la
     * siguiente y vuelve a pedir. La cadena de WorkManager (`APPEND_OR_REPLACE`) podía tener delante un reintento de otra
     * pasada con minutos de backoff: la terminal seguía apartada con el servidor ya disponible. Devuelve `true` si hace falta
     * el respaldo durable (sin red, un fallo, o dudas que siguen esperando tras [VUELTAS_AL_ARRANCAR] vueltas).
     */
    internal suspend fun liberarTrasArrancar(venueId: String): Boolean {
        repeat(VUELTAS_AL_ARRANCAR) {
            val r = try {
                serverRecovery.get().liberarDudasLocales(venueId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "🔎 [LedgerServer] la liberación de arranque falló — queda el worker")
                return true
            }
            if (r.sinRespuesta > 0) return true
            val falta = r.proximaEnMs ?: return false
            kotlinx.coroutines.delay(falta + MARGEN_MS)
        }
        return true
    }

    companion object {
        const val RESPALDO_MINUTOS = 2L
        /** Ronda 20: la espera del aviso del banco (10 s) más un margen para que la cuarentena ya esté escrita. */
        const val SEGUNDOS_TRAS_ARRANCAR = 12L
        /** Ronda 21: tope de vueltas de la liberación de arranque en el proceso; después, el worker. */
        const val VUELTAS_AL_ARRANCAR = 3
        const val MARGEN_MS = 500L
    }
}
