package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 Founder, 29-sep-2026: «nada puede detener las ventas». La marca de «devolución en curso» sólo existe mientras la
 * devolución habla con AngelPay, y vence sola: aunque algo no la soltara, nunca puede trabar las ventas.
 */
class PaymentStateHolderTest {

    private val segundo = 1_000_000_000L

    @Test fun `P1 la devolucion en curso se ve mientras dura y se suelta al terminar`() {
        val estado = PaymentStateHolder()
        assertFalse(estado.devolucionEnCurso(10 * segundo))

        estado.marcarDevolucionEnCurso(10 * segundo)
        assertTrue("mientras AngelPay contesta", estado.devolucionEnCurso(15 * segundo))

        estado.terminarDevolucionEnCurso()
        assertFalse("en cuanto regresa", estado.devolucionEnCurso(15 * segundo))
    }

    @Test fun `P1 la devolucion en curso vence sola a los 2 minutos aunque nadie la suelte`() {
        val estado = PaymentStateHolder()
        estado.marcarDevolucionEnCurso(10 * segundo)
        assertTrue(estado.devolucionEnCurso(10 * segundo + 119 * segundo))
        assertFalse("nunca puede trabar las ventas", estado.devolucionEnCurso(10 * segundo + 120 * segundo))
    }

    @Test fun `P1 la devolucion en curso cuenta como cobrando para quien lo lee y al soltarla no borra el cobro de una venta`() {
        val estado = PaymentStateHolder()
        estado.marcarDevolucionEnCurso()
        assertTrue("durante la devolución: nadie cambia de sesión ni de comercio", estado.isCharging())
        estado.terminarDevolucionEnCurso()
        assertFalse(estado.isCharging())

        estado.setCharging(true)          // una venta
        estado.marcarDevolucionEnCurso()
        estado.terminarDevolucionEnCurso()
        assertTrue("soltar la devolución no suelta la venta", estado.isCharging())
    }

    @Test fun `la devolucion en curso no toca la bandera de cobro`() {
        val estado = PaymentStateHolder()
        estado.marcarDevolucionEnCurso(10 * segundo)
        assertFalse(estado.isChargeAttemptActive())
    }

    // 🔴 D666 (21-nov): una pantalla de cobro muere ~700 ms después de salir. Si en ese rato nació otra y encendió sus banderas,
    // la vieja no se las apaga.
    @Test fun `P1 la pantalla de cobro que muere no apaga las banderas de una que nacio despues`() {
        val estado = PaymentStateHolder()
        val vieja = estado.entraPantallaDeCobro()
        val nueva = estado.entraPantallaDeCobro()
        estado.setChargeAttemptActive(true)
        estado.setCharging(true)

        assertFalse(estado.salePantallaDeCobro(vieja, soltarVentanaDelDinero = true))
        assertTrue("la venta nueva sigue protegida", estado.isChargeAttemptActive())
        assertTrue(estado.isCharging())

        assertTrue(estado.salePantallaDeCobro(nueva, soltarVentanaDelDinero = true))
        assertFalse(estado.isChargeAttemptActive())
        assertFalse(estado.isCharging())
    }

    @Test fun `P1 si la pantalla nueva ya murio la vieja que muere despues si apaga - nada queda pegado`() {
        val estado = PaymentStateHolder()
        val vieja = estado.entraPantallaDeCobro()
        val nueva = estado.entraPantallaDeCobro()
        assertTrue(estado.salePantallaDeCobro(nueva, soltarVentanaDelDinero = true))
        estado.setChargeAttemptActive(true)   // la vieja siguió trabajando

        assertTrue(estado.salePantallaDeCobro(vieja, soltarVentanaDelDinero = true))
        assertFalse("una terminal con la bandera pegada rechaza todo cobro del POS", estado.isChargeAttemptActive())
    }

    @Test fun `P1 la ultima pantalla de cobro que muere apaga las banderas y respeta un registro en vuelo`() {
        val estado = PaymentStateHolder()
        val unica = estado.entraPantallaDeCobro()
        estado.setChargeAttemptActive(true)
        estado.setCharging(true)

        assertTrue(estado.salePantallaDeCobro(unica, soltarVentanaDelDinero = false))
        assertFalse(estado.isChargeAttemptActive())
        assertTrue("R86: con un registro en vuelo, la ventana del dinero la suelta su finally", estado.isCharging())
    }

    // Codex D666 r2 #2: el `finally` del registro de una tarjeta (R86) sigue tras salir del éxito; si la cajera ya abrió el cobro
    // siguiente, no le apaga sus banderas.
    @Test fun `P1 el registro tardio de una pantalla no apaga las banderas del cobro que nacio despues`() {
        val estado = PaymentStateHolder()
        val conRegistroEnVuelo = estado.entraPantallaDeCobro()
        assertTrue(estado.salePantallaDeCobro(conRegistroEnVuelo, soltarVentanaDelDinero = false))   // salió del éxito
        estado.entraPantallaDeCobro()
        estado.setChargeAttemptActive(true)                                                          // el cobro siguiente, en propina

        assertFalse(estado.soltarBanderasDe(conRegistroEnVuelo, soltarVentanaDelDinero = true))
        assertTrue(estado.isChargeAttemptActive())
    }

    @Test fun `P1 el registro tardio sin otro cobro abierto si suelta las banderas`() {
        val estado = PaymentStateHolder()
        val pantalla = estado.entraPantallaDeCobro()
        estado.setCharging(true)
        estado.setChargeAttemptActive(true)

        assertTrue(estado.soltarBanderasDe(pantalla, soltarVentanaDelDinero = true))
        assertFalse(estado.isCharging())
        assertFalse(estado.isChargeAttemptActive())
    }

    @Test fun `P1 las dos variantes de la PAX se anotan al nacer y al morir sueltan las banderas solo si son la mas nueva`() {
        listOf("sandbox", "production").forEach { variante ->
            val vm = java.io.File("src/$variante/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt").readText()
            assertTrue(variante, vm.contains("private val pantallaDeCobro = paymentStateHolder.entraPantallaDeCobro()"))
            val alMorir = vm.substringAfter("override fun onCleared() {")
            assertTrue(variante, alMorir.contains("paymentStateHolder.salePantallaDeCobro(pantallaDeCobro, soltarVentanaDelDinero = !recordingInFlight)"))
            assertFalse(variante, alMorir.contains("setChargeAttemptActive(false)"))
            assertFalse(variante, alMorir.contains("setCharging(false)"))
            // Los dos `finally` del registro (R86) también preguntan antes de soltar.
            assertTrue(variante, vm.split("paymentStateHolder.soltarBanderasDe(pantallaDeCobro, soltarVentanaDelDinero = true)").size == 3)
            assertFalse(variante, vm.contains("recordingInFlight = false\n                paymentStateHolder.setCharging(false)"))
        }
    }
}
