package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * P4 (tienda híbrida), ESTÁTICA como `BotonesDelErrorAlFondoTest`: lo que las dos pantallas de cobro hacen con el folio.
 *  - R78 (c): la comanda de un folio la imprime el apoyo automático (`ComandaDeApoyo`), así que el detalle del éxito de la PAX
 *    no ofrece «Imprimir Comanda»: saldrían dos.
 *  - El folio se le da al ViewModel justo antes de arrancar cada cobro (también tras un `resetPayment()` que vuelve a Idle), y
 *    con folio el cobro nace ORDER, nunca SERIALIZED (R78 b).
 */
class FolioEnLasPantallasDeCobroTest {

    private fun leer(archivo: String) =
        File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/$archivo").readText()

    private val pax = leer("PaymentScreen.kt")
    private val nexgo = leer("angelpay/AngelPayPaymentScreen.kt")

    @Test
    fun `R78 c el detalle del exito de la PAX esconde Imprimir Comanda cuando el cobro es de un folio`() {
        val boton = pax.indexOf("Text(\"Imprimir Comanda\")")
        assertWithMessage("botón de la comanda").that(boton).isAtLeast(0)
        val guarda = pax.lastIndexOf("if (mostrarComanda)", boton)
        assertWithMessage("el botón vive dentro de if (mostrarComanda)").that(guarda).isAtLeast(0)
        assertWithMessage("la guarda envuelve el botón entero (su onClick y su texto)")
            .that(pax.substring(guarda, boton)).contains("onPrintKitchenTicket()")
        assertWithMessage("la pantalla apaga la comanda con folio").that(pax).contains("mostrarComanda = folio == null")
    }

    @Test
    fun `la PAX le da el folio al ViewModel antes de arrancar el cobro, cada vez que vuelve a Idle`() {
        val idle = pax.indexOf("is PaymentState.Idle ->")
        val arranques = listOf("viewModel.startRefund(", "viewModel.submitAmountWithExternalInputs(", "viewModel.submitAmountDirectToMerchant(", "viewModel.submitAmount(")
            .map { pax.indexOf(it, idle) }
        val folio = pax.indexOf("viewModel.setFolio(folio)", idle)
        assertWithMessage("setFolio dentro del arranque de Idle").that(folio).isGreaterThan(idle)
        assertWithMessage("todos los arranques existen").that(arranques.all { it > idle }).isTrue()
        assertWithMessage("setFolio antes de cualquier arranque").that(folio).isLessThan(arranques.min())
    }

    @Test
    fun `R78 b con folio la PAX nace ORDER aunque la cuenta viva solo en el servidor`() {
        val orden = pax.indexOf("folio != null -> PaymentFlowOrigin.ORDER")
        val serializado = pax.indexOf("skipLocalOrderValidation -> PaymentFlowOrigin.SERIALIZED")
        assertWithMessage("folio → ORDER").that(orden).isAtLeast(0)
        assertWithMessage("antes que la regla de SERIALIZED").that(orden).isLessThan(serializado)
    }

    @Test
    fun `la Nexgo le pasa el folio al arrancar el cobro`() {
        val arranque = nexgo.indexOf("viewModel.initPayment(")
        val fin = nexgo.indexOf(")", nexgo.indexOf("ventaLocalJson = ventaLocalJson", arranque))
        assertWithMessage("initPayment(folio = folio)").that(nexgo.substring(arranque, fin)).contains("folio = folio")
    }
}
