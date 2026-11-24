package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.domain.ReintentoDeEfectivo
import com.jaac.avoqado_tpv.features.payment.domain.RetryContext
import org.junit.Test
import java.io.File

/**
 * 🔴 1164aa: a dónde lleva «Cancelar» en la pantalla de error de la PAX. Tras un error de REGISTRO del efectivo (el dinero ya está en
 * el cajón), regresar a «Método de Pago» dejaba la TARJETA a un toque por el mismo importe: cobro doble. Ahí «Cancelar» sale del cobro.
 */
class CancelarDesdeElErrorTest {
    private fun contexto(reintento: ReintentoDeEfectivo? = null, comercio: String? = "m1") = RetryContext(
        amount = "100.00", tipAmount = "0.00", rating = null, merchantAccountId = comercio, reintentoDeEfectivo = reintento,
    )

    private fun error(context: RetryContext? = contexto(), canRetry: Boolean = true, abrirCaja: Boolean = false) =
        PaymentState.Error(message = "x", context = context, canRetry = canRetry, showOpenShiftButton = abrirCaja)

    private fun vuelve(e: PaymentState.Error, reembolso: Boolean = false, delPos: Boolean = false) =
        cancelarDesdeElErrorVuelveAMetodos(e, esReembolso = reembolso, esCobroDelPos = delPos)

    @Test fun `P1 un error de registro del efectivo no regresa a metodos, sale del cobro`() {
        assertThat(vuelve(error(contexto(ReintentoDeEfectivo.EFECTIVO, comercio = null)))).isFalse()
    }

    @Test fun `P1 el efectivo confirmado del kiosco tampoco regresa a metodos`() {
        assertThat(vuelve(error(contexto(ReintentoDeEfectivo.KIOSCO, comercio = null)))).isFalse()
    }

    @Test fun `una tarjeta con reintento sigue regresando a metodos, como hoy`() {
        assertThat(vuelve(error())).isTrue()
    }

    @Test fun `lo que ya salia del cobro sigue saliendo`() {
        assertThat(vuelve(error(), reembolso = true)).isFalse()
        assertThat(vuelve(error(), delPos = true)).isFalse()
        assertThat(vuelve(error(canRetry = false))).isFalse()
        assertThat(vuelve(error(context = null))).isFalse()
        assertThat(vuelve(error(abrirCaja = true))).isFalse()
    }

    /** El CABLEADO: la pantalla decide con esta función, no con su propia condición (se lee la fuente, como `NoSeCobroCableadoTest`). */
    @Test fun `P1 PaymentScreen decide Cancelar con la misma funcion`() {
        val pantalla = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentScreen.kt").readText()
        val cancelar = pantalla.substring(pantalla.indexOf("onCancel = {", pantalla.indexOf("} else PaymentErrorContent(")))
            .substringBefore("onCashFallback")
        assertThat(cancelar).contains("cancelarDesdeElErrorVuelveAMetodos(")
        assertThat(cancelar).contains("viewModel.returnToSelectingMerchantFromError(currentState.context)")
    }
}
