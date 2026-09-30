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
}
