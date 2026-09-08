package com.jaac.avoqado_tpv.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Payment Queue State Manager
 *
 * Singleton that bridges PaymentQueueRepository (data layer) with
 * DeviceHealthViewModel (presentation layer) for queue count alerts.
 *
 * **Why Singleton?**
 * - PaymentQueueRepository operations happen in workers and ViewModels
 * - DeviceHealthViewModel needs to observe queue state for alerts
 * - Single source of truth avoids inconsistent counts
 */
@Singleton
class PaymentQueueStateManager @Inject constructor() {

    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    /**
     * Refresh queue counts from repository.
     * Called after enqueue, sync, reset, or reconnection.
     */
    /**
     * Refresca los CUATRO conteos de golpe. Es la forma que usa [com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker],
     * que cuenta las dos colas en la misma pasada.
     *
     * 🔴 Sin valores por default a propósito (QA Nexgo N86, 7-sep-2026): antes `pendingRefundCount`
     * y `failedRefundCount` valían 0 si no se pasaban, y Inicio y Salud del aparato —que sólo
     * cuentan pagos— ponían en CERO las devoluciones que el worker acababa de informar: el aviso
     * «1 devolución rechazada» aparecía tarde y se borraba al refrescar el inicio. Quien sólo
     * conoce una de las dos colas usa [refreshPaymentCounts] o [refreshRefundCounts].
     */
    suspend fun refreshCounts(pendingCount: Int, failedCount: Int, pendingRefundCount: Int, failedRefundCount: Int) {
        publicar(
            QueueState(
                pendingCount = pendingCount,
                failedCount = failedCount,
                pendingRefundCount = pendingRefundCount,
                failedRefundCount = failedRefundCount,
            )
        )
    }

    /** Refresca SÓLO los pagos y conserva las devoluciones tal como estaban. */
    suspend fun refreshPaymentCounts(pendingCount: Int, failedCount: Int) {
        publicar(_queueState.value.copy(pendingCount = pendingCount, failedCount = failedCount))
    }

    /** Refresca SÓLO las devoluciones y conserva los pagos tal como estaban. */
    suspend fun refreshRefundCounts(pendingRefundCount: Int, failedRefundCount: Int) {
        publicar(_queueState.value.copy(pendingRefundCount = pendingRefundCount, failedRefundCount = failedRefundCount))
    }

    private fun publicar(newState: QueueState) {
        if (_queueState.value != newState) {
            Timber.d("📊 [PaymentQueue] State updated: pending=${newState.pendingCount}, failed=${newState.failedCount}, refundsPending=${newState.pendingRefundCount}, refundsFailed=${newState.failedRefundCount}")
            _queueState.value = newState
        }
    }

    fun reset() {
        _queueState.value = QueueState()
    }
}

/**
 * Queue state data class
 */
data class QueueState(
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    /** 💸 Devoluciones sin registrar / rechazadas (auditoría de Codex F10): antes eran invisibles hasta cerrar el turno. */
    val pendingRefundCount: Int = 0,
    val failedRefundCount: Int = 0,
) {
    val hasAnyPayments: Boolean get() = pendingCount > 0 || failedCount > 0 || pendingRefundCount > 0 || failedRefundCount > 0
    val totalCount: Int get() = pendingCount + failedCount + pendingRefundCount + failedRefundCount
}
