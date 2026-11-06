package com.jaac.avoqado_tpv.features.checkout.presentation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5 (QA en la N86, 5-nov): con la hoja del carrito abierta (ModalBottomSheet), un cobro que falla —sin red, servidor apagado, cupón que
 * cambió de valor, sin permiso— pintaba su snackbar DEBAJO de la hoja: la cajera no veía nada. Y como `showSnackbar` (Long) suspende ~10 s
 * antes de `isChargeInFlight = false`, el botón se quedaba en «Preparando…» todo ese rato (capturas tpv-53/54/58/59). Lo mismo en
 * «Pagar después».
 *
 * Prueba ESTÁTICA, como `AngelPayPaymentScreenSalidaTest`: el repo no tiene `createComposeRule` en las pruebas de JVM (sólo en
 * `androidTestImplementation`). Fija el cableado: el botón se libera ANTES de avisar y la hoja se cierra ANTES del snackbar.
 */
class CheckoutScreenErrorConLaHojaTest {

    private val pantalla = File("src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/CheckoutScreen.kt").readText()

    /** El bloque desde `inicio` hasta el siguiente `fin`, con su error si el archivo cambió de forma. */
    private fun bloque(inicio: String, fin: String): String {
        val i = pantalla.indexOf(inicio)
        require(i >= 0) { "No encontré «$inicio» en CheckoutScreen.kt" }
        val j = pantalla.indexOf(fin, i)
        require(j > i) { "No encontré «$fin» después de «$inicio» en CheckoutScreen.kt" }
        return pantalla.substring(i, j)
    }

    private fun antes(texto: String, primero: String, despues: String): Boolean {
        val a = texto.indexOf(primero)
        val b = texto.indexOf(despues)
        return a >= 0 && b >= 0 && a < b
    }

    @Test
    fun `P1 Cobrar - el boton queda libre antes de cualquier aviso`() {
        val cobrar = bloque("val triggerCharge: () -> Unit = {", "ModalBottomSheet(")
        assertTrue(
            "triggerCharge debe poner isChargeInFlight = false ANTES del primer showSnackbar (si no, «Preparando…» ~10 s).",
            antes(cobrar, "isChargeInFlight = false", "showSnackbar("),
        )
    }

    @Test
    fun `P1 Cobrar - un error cierra la hoja antes del snackbar, para que se vea`() {
        val error = bloque("onFailure = { error ->\n                            Timber.e(error, \"🛒 prepareForPayment failed\")", "ModalBottomSheet(")
        assertTrue(
            "El error de Cobrar debe cerrar la hoja (showCartDetailsSheet = false) ANTES de showSnackbar: debajo de la hoja no se ve.",
            antes(error, "showCartDetailsSheet = false", "showSnackbar("),
        )
    }

    @Test
    fun `Pagar despues - el boton queda libre y la hoja se cierra antes del aviso de error`() {
        val pagarDespues = bloque("isPayLaterInFlight = true", "isSubmittingPayLater =")
        assertTrue(
            "Pagar después debe poner isPayLaterInFlight = false ANTES del primer showSnackbar.",
            antes(pagarDespues, "isPayLaterInFlight = false", "showSnackbar("),
        )
        val error = pagarDespues.substring(pagarDespues.indexOf("onFailure = { error ->"))
        assertTrue(
            "El error de Pagar después debe cerrar la hoja ANTES de showSnackbar.",
            antes(error, "showCartDetailsSheet = false", "showSnackbar("),
        )
    }
}
