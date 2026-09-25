package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 Founder, 25-sep-2026: «ninguna transacción en Blumon ni AngelPay puede ser offline, no tienen esa función». Y un
 * reembolso que Blumon no aprobó NUNCA deja la terminal apartada: «nunca trabe nada, sólo avise».
 *
 * Lo que lo originó, medido ese día en la PAX de pruebas con tarjetas reales:
 *  · El chip de una VISA contestó `RESULT_OFFLINE_APPROVED` a un reembolso de $254 y la app lo anotó en Avoqado SIN
 *    llamar a Blumon (auth «OFFLINE»): el dashboard decía «reembolsado» y la tarjeta seguía cobrada.
 *  · Blumon rechazó otro reembolso (HTTP 400, `TX_010 TARJETA INVALIDA`: otra tarjeta) y el intento se quedó en
 *    AUTORIZANDO, apartando la terminal hasta reiniciar la app.
 *
 * Es de FUENTE, como [RechazoContactlessLiberaLaTerminalTest]: las pruebas de comportamiento sólo ejercitan la variante
 * SANDBOX y la de PRODUCCIÓN es la que mueve dinero real. Esto lee las dos.
 */
class NadaEsOfflineEnBlumonTest {

    private val variantes = listOf(
        "sandbox" to File("src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
        "production" to File("src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
    )

    private fun entre(fuente: String, desde: String, hasta: String, nombre: String): String {
        val inicio = fuente.indexOf(desde)
        assertTrue("[$nombre] no se encontró «$desde»", inicio >= 0)
        val fin = fuente.indexOf(hasta, inicio + desde.length)
        assertTrue("[$nombre] no se encontró «$hasta» después de «$desde»", fin > inicio)
        return fuente.substring(inicio, fin)
    }

    private fun ramaOfflineAprobada(funcion: String, nombre: String) =
        entre(funcion, "TransResultEnum.RESULT_OFFLINE_APPROVED ->", "TransResultEnum.RESULT_OFFLINE_DENIED ->", nombre)

    @Test
    fun `el aprobado offline del chip en un REEMBOLSO se manda a Blumon en las dos variantes`() {
        for ((nombre, archivo) in variantes) {
            val funcion = entre(archivo.readText(), "private suspend fun processContactlessRefund(",
                "private suspend fun processContactlessRefundOnlineAuth(", nombre)
            val rama = ramaOfflineAprobada(funcion, nombre)
            assertTrue("[$nombre] el reembolso aprobado offline no va a Blumon (CancelIcc)",
                rama.contains("processContactlessRefundOnlineAuth("))
            assertFalse("[$nombre] el reembolso aprobado offline se anota sin Blumon — VISA $254 del 25-sep",
                rama.contains("handleRefundSuccess("))
            assertFalse("[$nombre] el reembolso aprobado offline canta éxito sin Blumon", rama.contains("PaymentState.Success("))
        }
    }

    @Test
    fun `el aprobado offline del chip en una VENTA se manda a Blumon en las dos variantes`() {
        for ((nombre, archivo) in variantes) {
            val funcion = entre(archivo.readText(), "private suspend fun processContactlessPayment(",
                "private suspend fun processContactlessOnlineAuthorization(", nombre)
            val rama = ramaOfflineAprobada(funcion, nombre)
            assertTrue("[$nombre] la venta aprobada offline no va a Blumon", rama.contains("processContactlessOnlineAuthorization("))
            assertFalse("[$nombre] la venta aprobada offline se anota sin que Blumon la cobre", rama.contains("handlePaymentSuccess("))
            assertFalse("[$nombre] la venta aprobada offline canta éxito sin Blumon", rama.contains("PaymentState.Success("))
        }
    }

    @Test
    fun `un reembolso que Blumon rechaza cierra el intento en las dos variantes`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val cancel = entre(fuente, "[CancelIcc] Refund failed", "RefundAuthorizationResult(response = null, userFriendlyError = userMessage)", nombre)
            assertTrue("[$nombre] el rechazo de CancelIcc no se clasifica", cancel.contains("blumonReembolsoRechazado(failure)"))
            assertTrue("[$nombre] el rechazo de CancelIcc no cierra el intento", cancel.contains("markHostResponded("))
            val preflight = entre(fuente, "[ValidateCancel] Preflight failed", "return RefundAuthorizationResult(", nombre)
            assertTrue("[$nombre] el rechazo del preflight no se clasifica", preflight.contains("blumonReembolsoRechazado(validateFailure)"))
            assertTrue("[$nombre] el rechazo del preflight no cierra el intento", preflight.contains("markHostResponded("))
        }
    }

    @Test
    fun `todo reembolso que termina sin veredicto deja libre la terminal en las dos variantes`() {
        for ((nombre, archivo) in variantes) {
            val funcion = entre(archivo.readText(), "fun startRefund(", "private suspend fun processChipRefund(", nombre)
            val red = funcion.substringAfterLast("} finally {", "")
            assertTrue("[$nombre] startRefund no tiene la red de seguridad del final", red.isNotEmpty())
            assertTrue("[$nombre] la red corre aunque el flujo se haya cancelado (el SDK puede seguir vivo)", red.contains("if (isActive)"))
            assertTrue("[$nombre] la red no pone «en duda» lo que quedó en vuelo", red.contains("markIndeterminate(refundAttemptId"))
            assertTrue("[$nombre] la red no descarta lo que nunca entró al lector", red.contains("markDiscardedBeforeCharge(refundAttemptId"))
        }
    }
}
