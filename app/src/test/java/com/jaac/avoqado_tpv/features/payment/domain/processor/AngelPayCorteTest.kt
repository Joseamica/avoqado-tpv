package com.jaac.avoqado_tpv.features.payment.domain.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * El corte de AngelPay (11 pm, hora del centro de México): antes del corte el cobro se CANCELA desde la terminal; después,
 * la devolución la da soporte de AngelPay (Norman, 29-sep-2026). Un cobro hecho después de las 11 pm ya es del lote del
 * día siguiente.
 */
class AngelPayCorteTest {

    /** Una hora local del centro de México (UTC-6, sin horario de verano desde 2022). */
    private fun mx(fechaHora: String): Instant = java.time.OffsetDateTime.parse("$fechaHora-06:00").toInstant()

    @Test fun `P1 un cobro de la tarde se cancela hasta las 22-59 del mismo dia`() {
        val cobro = mx("2026-09-29T17:00:00")
        assertTrue(AngelPayCorte.sePuedeCancelar(cobro, mx("2026-09-29T22:59:59")))
        assertFalse("a las 23:00 ya cerró el lote", AngelPayCorte.sePuedeCancelar(cobro, mx("2026-09-29T23:00:00")))
        assertFalse("al día siguiente tampoco", AngelPayCorte.sePuedeCancelar(cobro, mx("2026-09-30T10:00:00")))
    }

    @Test fun `P1 un cobro despues de las 23-00 es del lote del dia siguiente`() {
        val cobro = mx("2026-09-29T23:30:00")
        assertTrue("sigue abierto su lote", AngelPayCorte.sePuedeCancelar(cobro, mx("2026-09-29T23:40:00")))
        assertTrue("hasta el corte del día siguiente", AngelPayCorte.sePuedeCancelar(cobro, mx("2026-09-30T22:59:00")))
        assertFalse(AngelPayCorte.sePuedeCancelar(cobro, mx("2026-09-30T23:00:00")))
    }

    @Test fun `P1 el corte es a las 23-00 de Mexico sin importar la zona del aparato`() {
        assertEquals(ZoneOffset.ofHours(-6), AngelPayCorte.ZONA_MEXICO)
        // Julio: el ICU viejo de la N86 todavía cree que México tiene horario de verano (UTC-5). El offset fijo no.
        assertEquals(Instant.parse("2026-07-16T05:00:00Z"), AngelPayCorte.corteDeLaVenta(mx("2026-07-15T12:00:00")))
    }

    @Test fun `P1 las devoluciones posteriores siguen apagadas hasta que AngelPay diga otra cosa`() {
        assertFalse(AngelPayCorte.DEVOLUCION_POSTERIOR_HABILITADA)
        assertFalse(AngelPayCorte.sePuedeIntentarDesdeLaTerminal(mx("2026-09-28T12:00:00"), mx("2026-09-29T09:00:00")))
        assertTrue(AngelPayCorte.sePuedeIntentarDesdeLaTerminal(mx("2026-09-29T12:00:00"), mx("2026-09-29T13:00:00")))
    }
}
