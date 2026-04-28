package com.jaac.avoqado_tpv.features.payment.domain.model

import com.jaac.avoqado_tpv.features.payment.domain.ScannedProduct
import java.math.BigDecimal

/**
 * PaymentSession - Immutable snapshot of payment state.
 *
 * This is the future single source of truth for payment flows
 * (fast, order, refund, kiosk, serialized inventory, split).
 *
 * For now, it is used as a read-only snapshot to avoid regressions
 * while we incrementally migrate PaymentViewModel logic.
 */
data class PaymentSession(
    val amount: BigDecimal,
    val tip: BigDecimal,
    val rating: Int?,
    val mode: PaymentMode,
    val features: Set<PaymentFeature>,
    val flowOrigin: PaymentFlowOrigin,
    val orderContext: OrderContext? = null,
    val splitContext: SplitContext? = null,
    val refundContext: PaymentContext.RefundPayment? = null,
    val verificationContext: VerificationContext? = null,
    val merchantAccountId: String? = null,
    val merchantLocalId: String? = null,
    val isKioskPayment: Boolean = false,
    val kioskStaffId: String? = null,
    val socketProcessedByStaffId: String? = null,
    val skipLocalOrderValidation: Boolean = false,
    val track2: String = "",
    val emvIssuerCountry: String = "",
    val selectedMsiMonths: Int? = null,
    /**
     * 🛡️ IDEMPOTENCY KEY (2026-04-08) — Stripe/Square/Toast pattern.
     *
     * UUID v4 generated ONCE per logical payment attempt. Stored on the session so
     * that **every** coroutine recording the payment reads the SAME value — even if
     * handlePaymentSuccess() is somehow called multiple times (the Testarudo bug).
     *
     * Lifecycle:
     *   - Generated in startPayment() / processCashPayment() / processCryptoPayment()
     *     (i.e., the earliest moment of each payment flow)
     *   - Persisted through the session until the payment succeeds or is reset
     *   - Cleared on resetPayment(), cancelPayment(), or after a successful record
     *
     * Null means: no payment attempt is currently in progress.
     */
    val paymentAttemptId: String? = null,
    /**
     * 🛡️ Refund idempotency key (2026-04-22).
     *
     * UUID v4 generated once per logical refund attempt and reused on retries of the
     * same refund context. This is propagated to backend request header/body so
     * duplicate retries cannot create duplicated refund records.
     */
    val refundAttemptId: String? = null
) {
    companion object {
        fun empty(): PaymentSession = PaymentSession(
            amount = BigDecimal.ZERO,
            tip = BigDecimal.ZERO,
            rating = null,
            mode = PaymentMode.FAST,
            features = emptySet(),
            flowOrigin = PaymentFlowOrigin.FAST
        )
    }
}

enum class PaymentMode {
    FAST,
    ORDER,
    REFUND
}

enum class PaymentFeature {
    KIOSK,
    SERIALIZED_INVENTORY,
    SPLIT,
    PAY_LATER,
    TIP_COLLECTION,
    RATING_COLLECTION,
    PRE_VERIFICATION,
    PROOF_OF_SALE
}

data class OrderContext(
    val orderId: String,
    val orderNumber: String? = null,
    val tableId: String? = null,
    val skipLocalOrderValidation: Boolean = false
)

data class SplitContext(
    val type: SplitType,
    val equalPartsPartySize: Int? = null,
    val equalPartsPayedFor: Int? = null,
    val paidProductIds: List<String> = emptyList()
)

data class VerificationContext(
    val photos: List<String> = emptyList(),
    val barcodes: List<ScannedProduct> = emptyList(),
    val orderReference: String? = null
)
