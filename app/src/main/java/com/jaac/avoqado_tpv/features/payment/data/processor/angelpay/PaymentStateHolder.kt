package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton holder for in-flight payment state. `AngelPayPaymentViewModel`
 * writes (`setCharging(true)` around its Charging state, `setCharging(false)`
 * on completion/error). [AngelPayMerchantRepository] reads via the
 * [PaymentStateProvider] interface.
 *
 * AtomicBoolean is sufficient — no need for a coroutine-aware StateFlow
 * because the reader (Mutex-guarded switch path) only needs a current snapshot.
 */
@Singleton
class PaymentStateHolder @Inject constructor() : PaymentStateProvider {
    private val charging = AtomicBoolean(false)
    override fun isCharging(): Boolean = charging.get()
    fun setCharging(value: Boolean) { charging.set(value) }

    // Non-resolved (working) AngelPay screen state — see [PaymentStateProvider.isChargeAttemptActive].
    // Published by AngelPayPaymentViewModel from its own state stream (single funnel).
    private val chargeAttemptActive = AtomicBoolean(false)
    override fun isChargeAttemptActive(): Boolean = chargeAttemptActive.get()
    fun setChargeAttemptActive(value: Boolean) { chargeAttemptActive.set(value) }
}
