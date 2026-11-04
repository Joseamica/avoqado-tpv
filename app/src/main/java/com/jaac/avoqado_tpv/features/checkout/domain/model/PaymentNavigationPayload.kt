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

    /**
     * «Cobrar» sin red: la venta no se pudo mandar al servidor (o no hay red) y se cobra en SÓLO EFECTIVO como una venta local.
     * [ventaLocalJson] es la [com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal] serializada con Gson (viaja por el `SavedStateHandle`, sobrevive a que se recree la
     * pantalla). No hay `orderId`: la orden la crea el servidor cuando la venta sube.
     */
    data class VentaLocal(
        override val amountPesosString: String,
        val ventaLocalJson: String,
    ) : PaymentNavigationPayload() {
        override val origin: PaymentFlowOrigin = PaymentFlowOrigin.FAST
    }

    /**
     * «Cobrar» sin red: un carrito en $0 (cortesía o descuento completo) que no se pudo mandar al servidor YA quedó guardado como
     * fila en la terminal y se sube solo. Como [CompletedFreeCart], NO abre la pantalla de cobro (ni la PAX ni la guarda de Nexgo de
     * monto > 0 intervienen): Cobrar muestra «Venta en $0 guardada» con [venta] para imprimir su ticket.
     */
    data class VentaEnCeroGuardada(
        override val amountPesosString: String,
        val venta: com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal,
    ) : PaymentNavigationPayload() {
        override val origin: PaymentFlowOrigin = PaymentFlowOrigin.FAST
    }
}
