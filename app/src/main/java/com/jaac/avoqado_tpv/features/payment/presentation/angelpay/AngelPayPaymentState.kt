package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Intent
import com.angelpay.angelpaysdk.models.PaymentRequest
import com.jaac.avoqado_tpv.features.payment.domain.AuthWatchdogLevel
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
        // Aviso del vigilante de autorizacion (Task 3, riel AngelPay/NEXGO). Por defecto
        // NONE: en el camino feliz nadie lo ve y el comportamiento queda byte-identico.
        // El vigilante NUNCA cancela la autorizacion, solo publica este aviso.
        val watchdogLevel: AuthWatchdogLevel = AuthWatchdogLevel.NONE,
    ) : AngelPayPaymentState()

    /** Cash payment being recorded to backend. */
    data class ProcessingCash(
        val message: String = "Registrando pago en efectivo...",
    ) : AngelPayPaymentState()

    /**
     * 🪙 CRYPTO: Generating QR from B4Bit (loading).
     * SelectingMerchant → [tap "Cripto"] → GeneratingCryptoQR → AwaitingCryptoPayment
     */
    data class GeneratingCryptoQR(
        val subtotal: String,
        val tipAmount: String,
        val totalAmount: String,
        val rating: Int?,
    ) : AngelPayPaymentState()

    /**
     * 🪙 CRYPTO: Awaiting customer payment via QR. Waits for Socket.IO event.
     */
    data class AwaitingCryptoPayment(
        val requestId: String,
        val paymentId: String,
        val paymentUrl: String,
        val subtotal: String,
        val tipAmount: String,
        val totalAmount: String,
        val rating: Int?,
        val expiresAt: String,
        val expiresInSeconds: Int,
        val cryptoAddress: String? = null,
        val cryptoSymbol: String? = null,
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

    /**
     * La tarjeta SÍ cobró; solo el registro en Avoqado quedó pendiente de sincronizar.
     *
     * Es un ÉXITO con matiz, NO un error: se renderiza en verde/ámbar. Antes esto
     * devolvía [Error] y el cajero veía pantalla roja en una operación que había
     * salido bien — y volvía a cobrar por miedo. Ver spec §4.2 F-1.
     *
     * [Error] queda reservado para cuando el encolado TAMBIÉN falla, que sí requiere
     * intervención humana.
     */
    data class Queued(
        val message: String,
        val authCode: String,
        val amount: String,
        val tipAmount: String? = null,
        val referenceNumber: String? = null,
        val orderId: String? = null,
        val orderNumber: String? = null,
    ) : AngelPayPaymentState()

    /** Payment failed with optional retry. */
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val showOpenShiftButton: Boolean = false,
    ) : AngelPayPaymentState()

    /** User cancelled the payment in AngelPay app. */
    data object Cancelled : AngelPayPaymentState()

    /**
     * D2 (spec §18.1): a merchant switch is in flight. The payment-time guard in
     * [AngelPayPaymentViewModel.startCardPayment] waits in this state until
     * `AngelPayMerchantRepository.activeAngelPayMerchantId` reaches
     * [targetMerchantId]. If the switch never settles within 8s the state moves
     * to [Error] so the cashier can retry.
     */
    data class Switching(
        val targetMerchantId: Int,
        val previousMerchantId: Int?,
    ) : AngelPayPaymentState()

    /**
     * D2 (spec §18.1): a payment is in flight. `PaymentStateHolder.isCharging()`
     * is `true` while this state is active so
     * `AngelPayMerchantRepository.switchActiveMerchant` rejects new switches
     * with `SwitchBlockedDuringChargeError`. Always paired with a `finally`
     * that flips `setCharging(false)` regardless of payment outcome.
     */
    data class Charging(
        val merchantId: Int,
        val startedAt: Long,
    ) : AngelPayPaymentState()
}
