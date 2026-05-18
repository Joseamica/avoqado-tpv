package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

/**
 * Read-only view of "is a payment currently being processed?". Implemented by
 * [PaymentStateHolder] (singleton) and observed by [AngelPayMerchantRepository]
 * to reject switch attempts during in-flight charges (D2 race protection,
 * spec §18.1).
 *
 * Defined as an interface so the merchant repository doesn't take a direct
 * dependency on AngelPayPaymentViewModel (which lives in the presentation
 * layer — would create a Clean Architecture inversion).
 */
interface PaymentStateProvider {
    fun isCharging(): Boolean
}
