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

    /**
     * True while the AngelPay payment screen is in ANY non-resolved (working) state —
     * i.e. a charge attempt is genuinely in progress: collecting rating/tip, selecting
     * merchant, launching the SDK, waiting for the result, or recording it. False when the
     * screen is showing a RESOLVED result (Idle / Success / Error / Cancelled), which is NOT
     * a live charge even though the payment route is still on-screen.
     *
     * Consumed by the socket/BLE charge dispatcher ([AppNavigation]) to tell a genuinely
     * busy terminal from a stale result screen: without this, a terminal left showing a
     * decline error rejected EVERY subsequent POS-initiated charge until the app restarted
     * (the route-only guard conflated "on the payment screen" with "busy").
     */
    fun isChargeAttemptActive(): Boolean
}
