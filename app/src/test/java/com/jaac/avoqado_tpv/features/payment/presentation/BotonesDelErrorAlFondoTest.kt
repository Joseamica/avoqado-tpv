package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * D22 (PAX 3-nov, N86 4-nov): el aviso «Sin conexión» empujaba los botones del error del cobro (120 px en la PAX, 60 en la N86) y un
 * toque pensado para «Cancelar» cayó en «Reintentar». Las dos pantallas pegan los botones al fondo para que el aviso no los mueva.
 */
class BotonesDelErrorAlFondoTest {

    private fun cuerpoDe(archivo: String, funcion: String): String {
        val texto = java.io.File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/$archivo").readText()
        val inicio = texto.indexOf("private fun $funcion(")
        assertWithMessage("$archivo › $funcion").that(inicio).isAtLeast(0)
        return texto.substring(inicio, texto.indexOf("\n}\n", inicio))
    }

    @Test fun `P2 el error de la PAX y el de la N86 pegan sus botones al fondo`() {
        for ((archivo, funcion) in listOf("PaymentScreen.kt" to "PaymentErrorContent", "angelpay/AngelPayPaymentScreen.kt" to "ErrorContent")) {
            val cuerpo = cuerpoDe(archivo, funcion)
            assertWithMessage(archivo).that(cuerpo).contains("verticalArrangement = Arrangement.SpaceBetween")
            assertWithMessage(archivo).that(cuerpo).doesNotContain("verticalArrangement = Arrangement.Center")
        }
    }
}
