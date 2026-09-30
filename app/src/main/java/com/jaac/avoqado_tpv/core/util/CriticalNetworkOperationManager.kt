package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks critical payment operations during which network failover must not run.
 *
 * Guarded operations:
 * - Card/refund payment flow in progress (PAX/Blumon: flags set by PaymentViewModel)
 * - AngelPay (Nexgo) charge or refund in progress (read live from [PaymentStateProvider])
 * - Merchant switching in progress
 * - Blumon SDK initialization/re-initialization in progress
 */
@Singleton
class CriticalNetworkOperationManager @Inject constructor(
    // 🔴 29-sep-2026: sólo la PAX llamaba setPaymentFlowInProgress; un cobro o una devolución de la Nexgo (AngelPay)
    // no contaba, así que cualquier acción que corte la red podía pasar encima. Misma señal que usa AppNavigation
    // para rechazar un cobro remoto.
    private val paymentStateProvider: PaymentStateProvider,
) {

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
            current.sdkInitializationInProgress ||
            paymentStateProvider.isCharging() ||
            paymentStateProvider.isChargeAttemptActive() ||
            paymentStateProvider.isRefundInFlight()
    }
}

data class CriticalNetworkOperationState(
    val paymentFlowInProgress: Boolean = false,
    val merchantSwitchInProgress: Boolean = false,
    val sdkInitializationInProgress: Boolean = false
)
