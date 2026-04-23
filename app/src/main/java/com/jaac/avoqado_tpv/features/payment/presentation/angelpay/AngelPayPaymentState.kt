package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Intent
import com.angelpay.angelpaysdk.models.PaymentRequest
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt

/**
 * State machine for AngelPay payment flow on Nexgo N86 terminals.
 *
 * Mirrors Blumon's pre-payment UX (Rating → Tip → Merchant → Payment)
 * but stays completely isolated from Blumon PaymentViewModel/PaymentScreen.
 *
 * Flow:
 * Idle → CollectingRating → CollectingTip → SelectingMerchant
 *   ├─ Card → LaunchingAngelPay → WaitingForResult → RecordingPayment → Success
 *   └─ Cash → ProcessingCash → Success
 */
sealed class AngelPayPaymentState {

    data object Idle : AngelPayPaymentState()

    /** Rating screen (1-5 stars). Shown when showReviewScreen=true. */
    data class CollectingRating(
        val amount: String,
        val rating: Int = 0,
    ) : AngelPayPaymentState()

    /** Tip selection screen. Shown when showTipScreen=true. */
    data class CollectingTip(
        val amount: String,
        val rating: Int?,
        val selectedTipPercentage: Int? = null,
        val customTipAmount: String? = null,
        val tipAmount: String = "0",
    ) : AngelPayPaymentState()

    /** Merchant + payment method selection (Card / Cash). */
    data class SelectingMerchant(
        val subtotal: String,
        val tipAmount: String,
        val totalAmount: String,
        val rating: Int?,
    ) : AngelPayPaymentState()

    /** SDK request built, ready for ActivityResult launcher using AngelPayPaymentContract. */
    data class LaunchingAngelPaySdk(
        val request: PaymentRequest,
        val amount: String,
        val tip: String,
        val orderId: String? = null,
        val orderNumber: String? = null,
        val usedQaTipFallback: Boolean = false,
    ) : AngelPayPaymentState()

    /** App-to-app intent fallback, ready for StartActivityForResult launcher. */
    data class LaunchingAngelPay(
        val intent: Intent,
        val amount: String,
        val tip: String,
        val orderId: String? = null,
        val orderNumber: String? = null,
    ) : AngelPayPaymentState()

    /** AngelPay app is processing the payment. */
    data class WaitingForResult(
        val message: String = "Procesando pago en AngelPay...",
    ) : AngelPayPaymentState()

    /** Cash payment being recorded to backend. */
    data class ProcessingCash(
        val message: String = "Registrando pago en efectivo...",
    ) : AngelPayPaymentState()

    /** Card payment result being recorded to backend. */
    data class RecordingPayment(
        val message: String = "Registrando pago...",
    ) : AngelPayPaymentState()

    /** Payment completed successfully. */
    data class Success(
        val authCode: String,
        val amount: String,
        val tipAmount: String? = null,
        val receipt: PaymentReceipt? = null,
        val referenceNumber: String? = null,
        val orderId: String? = null,
        val orderNumber: String? = null,
        val isCash: Boolean = false,
    ) : AngelPayPaymentState()

    /** Payment failed with optional retry. */
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val showOpenShiftButton: Boolean = false,
    ) : AngelPayPaymentState()

    /** User cancelled the payment in AngelPay app. */
    data object Cancelled : AngelPayPaymentState()
}
