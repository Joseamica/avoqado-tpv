package com.jaac.avoqado_tpv.features.checkout.domain.model

import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentFlowOrigin

/**
 * What `CheckoutViewModel.prepareForPayment()` returns to the navigation layer
 * so it knows how to set up `PaymentScreen`.
 *
 * The two variants mirror [PaymentFlowOrigin]:
 *
 * - [Fast]: the cart only has manual `CustomAmount` entries (no catalog
 *   products). No order is created — `PaymentScreen` receives just the
 *   amount, identical to today's `FastPaymentEntry` flow.
 *
 * - [Order]: the cart has product items. A backend `Order` (DINE_IN/TAKEOUT)
 *   is created up-front so the items, modifiers, and notes get persisted
 *   for inventory/analytics; `PaymentScreen` then receives the `orderId`
 *   plus the cart total. This branch matches the existing `MenuScreen → Payment`
 *   pathway already exercised by 8 features in production.
 */
sealed class PaymentNavigationPayload {
    abstract val amountPesosString: String
    abstract val origin: PaymentFlowOrigin

    data class Fast(
        override val amountPesosString: String,
    ) : PaymentNavigationPayload() {
        override val origin: PaymentFlowOrigin = PaymentFlowOrigin.FAST
    }

    data class Order(
        override val amountPesosString: String,
        val orderId: String,
        val orderNumber: String,
        val wasPayLaterOrder: Boolean = false,
    ) : PaymentNavigationPayload() {
        override val origin: PaymentFlowOrigin = PaymentFlowOrigin.ORDER
    }

    data class CompletedFreeCart(
        override val amountPesosString: String,
        val orderId: String,
        val orderNumber: String,
    ) : PaymentNavigationPayload() {
        override val origin: PaymentFlowOrigin = PaymentFlowOrigin.ORDER
    }
}
