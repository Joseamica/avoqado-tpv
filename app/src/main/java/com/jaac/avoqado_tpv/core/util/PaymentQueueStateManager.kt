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
    suspend fun refreshCounts(pendingCount: Int, failedCount: Int, pendingRefundCount: Int = 0, failedRefundCount: Int = 0) {
        val newState = QueueState(
            pendingCount = pendingCount,
            failedCount = failedCount,
            pendingRefundCount = pendingRefundCount,
            failedRefundCount = failedRefundCount,
        )
        if (_queueState.value != newState) {
            Timber.d("📊 [PaymentQueue] State updated: pending=$pendingCount, failed=$failedCount, refundsPending=$pendingRefundCount, refundsFailed=$failedRefundCount")
            _queueState.value = newState
        }
    }

    /**
     * Reset to empty state (e.g., on logout)
     */
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
