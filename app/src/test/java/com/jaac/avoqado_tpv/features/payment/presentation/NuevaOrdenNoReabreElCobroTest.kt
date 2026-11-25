package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 🧾 hz:nube0000:nac244bc (25-nov). En la PAX, todos los botones del recibo («Nueva Orden», «Nuevo Pago», «Listo», liberar la mesa…)
 * hacen `resetPayment()` y luego salen a otra pantalla; dos de ellos («Nueva Orden» de Mesas y liberar la mesa) esperan antes a la red.
 * Mientras tanto la pantalla está en `Idle`, y el arranque automático de `Idle` volvía a abrir el cobro de la cuenta YA pagada con sus
 * mismos argumentos (calificación, propina, métodos de pago). Ahora, si esta pantalla ya cobró, `Idle` sólo espera la salida.
 * El kiosco no cambia: su pantalla arranca el siguiente cobro con el mismo ViewModel.
 *
 * La pantalla no se puede ejecutar aquí (`createComposeRule` sólo está en `androidTest`): se lee la fuente, como en
 * `PagoRapidoConservaElMontoTest`. La Nexgo no lo necesita: su arranque automático vive fuera de los estados y no se repite al
 * volver a `Idle` (`AngelPayPaymentScreen`).
 */
class NuevaOrdenNoReabreElCobroTest {
    private val pantalla = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentScreen.kt").readText()

    private fun ramaIdle(): String {
        val inicio = pantalla.indexOf("is PaymentState.Idle -> {")
        require(inicio >= 0) { "No encontré la rama Idle de PaymentScreen" }
        return pantalla.substring(inicio, pantalla.indexOf("is PaymentState.ConfiguringKernel ->", inicio))
    }

    @Test
    fun `la marca de cobro hecho excluye al kiosco`() {
        assertThat(pantalla).contains("val huboCobroEnEstaPantalla = propinaDeMesaCobrada && !isKioskPayment")
    }

    @Test
    fun `tras un cobro hecho Idle no vuelve a arrancar el cobro y sólo espera la salida`() {
        val idle = ramaIdle()
        val arranque = idle.indexOf("LaunchedEffect(")
        val guarda = idle.indexOf("if (huboCobroEnEstaPantalla) return@LaunchedEffect")
        val primeraAccion = idle.indexOf("viewModel.setFolio(folio)")
        assertThat(arranque).isAtLeast(0)
        assertThat(guarda).isGreaterThan(arranque)
        assertThat(guarda).isLessThan(primeraAccion)
        assertThat(idle).contains("if (huboCobroEnEstaPantalla || initialAmount != null || isRefundMode)")
        assertThat(idle).contains("huboCobroEnEstaPantalla -> \"Un momento...\"")
    }
}
