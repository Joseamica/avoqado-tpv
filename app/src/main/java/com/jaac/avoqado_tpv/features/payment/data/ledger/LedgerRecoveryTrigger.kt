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
    }

    companion object {
        const val RESPALDO_MINUTOS = 2L
    }
}
