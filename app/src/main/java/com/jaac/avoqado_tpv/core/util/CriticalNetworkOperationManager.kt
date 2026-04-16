package com.jaac.avoqado_tpv.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks critical payment operations during which network failover must not run.
 *
 * Guarded operations:
 * - Card/refund payment flow in progress
 * - Merchant switching in progress
 * - Blumon SDK initialization/re-initialization in progress
 */
@Singleton
class CriticalNetworkOperationManager @Inject constructor() {

    private val _state = MutableStateFlow(CriticalNetworkOperationState())
    val state: StateFlow<CriticalNetworkOperationState> = _state.asStateFlow()

    fun setPaymentFlowInProgress(inProgress: Boolean) {
        _state.value = _state.value.copy(paymentFlowInProgress = inProgress)
    }

    fun setMerchantSwitchInProgress(inProgress: Boolean) {
        _state.value = _state.value.copy(merchantSwitchInProgress = inProgress)
    }

    fun setSdkInitializationInProgress(inProgress: Boolean) {
        _state.value = _state.value.copy(sdkInitializationInProgress = inProgress)
    }

    fun isAnyCriticalOperationInProgress(): Boolean {
        val current = _state.value
        return current.paymentFlowInProgress ||
            current.merchantSwitchInProgress ||
            current.sdkInitializationInProgress
    }
}

data class CriticalNetworkOperationState(
    val paymentFlowInProgress: Boolean = false,
    val merchantSwitchInProgress: Boolean = false,
    val sdkInitializationInProgress: Boolean = false
)
