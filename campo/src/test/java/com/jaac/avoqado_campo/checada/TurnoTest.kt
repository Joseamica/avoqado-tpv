package com.jaac.avoqado_campo.checada

import com.jaac.avoqado_campo.cola.TurnoLocal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** La hora de corte de la salida olvidada: la misma regla que `limiteDelTurno` del SERVIDOR. */
class TurnoTest {
    private val mx = ZoneId.of("America/Mexico_City")
    private val tijuana = ZoneId.of("America/Tijuana")
    private fun ms(s: String, zona: ZoneId = mx) = ZonedDateTime.of(LocalDateTime.parse(s), zona).toInstant().toEpochMilli()
    private val a22 = ReglaDeSalida(corte = LocalTime.of(22, 0))

    @After fun limpiar() = recordarReglaDeSalida(null, null)

    @Test fun `sin hora de corte es la de hoy - el siguiente 04_00 y nunca antes de 12 h`() {
        assertEquals(ms("2026-11-04T04:00"), limiteDelTurno(ms("2026-11-03T09:00"), mx, ReglaDeSalida.DE_HOY))
        assertEquals(ms("2026-11-04T10:00"), limiteDelTurno(ms("2026-11-03T22:00"), mx, ReglaDeSalida.DE_HOY))
    }

    @Test fun `con 22_00 - la entrada de las 10_00 vence a las 22_00 del mismo dia, sin el minimo de 12 h`() {
        assertEquals(ms("2026-11-03T22:00"), limiteDelTurno(ms("2026-11-03T10:00"), mx, a22))
    }

    @Test fun `con 22_00 - la entrada de las 21_30 vence a las 22_00 del dia siguiente (menos de 1 h)`() {
        assertEquals(ms("2026-11-04T22:00"), limiteDelTurno(ms("2026-11-03T21:30"), mx, a22))
    }

    @Test fun `con 22_00 - la entrada de las 21_00 justas tiene su hora y vence ese dia`() {
        assertEquals(ms("2026-11-03T22:00"), limiteDelTurno(ms("2026-11-03T21:00"), mx, a22))
    }

    @Test fun `cruce de medianoche - corte a las 02_00`() {
        val a2 = ReglaDeSalida(corte = LocalTime.of(2, 0))
        assertEquals("entra 23:30, vence 02:00 del dia siguiente", ms("2026-11-04T02:00"), limiteDelTurno(ms("2026-11-03T23:30"), mx, a2))
        assertEquals("entra 01:30, faltan 30 min: el de manana", ms("2026-11-05T02:00"), limiteDelTurno(ms("2026-11-04T01:30"), mx, a2))
        assertEquals("entra 00:30, vence 02:00 ese mismo dia", ms("2026-11-04T02:00"), limiteDelTurno(ms("2026-11-04T00:30"), mx, a2))
    }

    @Test fun `la hora de corte es la de la zona del celular`() {
        // La misma entrada (10:00 en CDMX = 08:00 en Tijuana): las 22:00 de cada zona son instantes distintos.
        val entrada = ms("2026-11-03T10:00", mx)
        assertEquals(ms("2026-11-03T22:00", mx), limiteDelTurno(entrada, mx, a22))
        assertEquals(ms("2026-11-03T22:00", tijuana), limiteDelTurno(entrada, tijuana, a22))
    }

    @Test fun `el celular espera su margen de 3 h tambien con hora de corte`() {
        assertEquals(ms("2026-11-04T01:00"), limiteEnElCelular(ms("2026-11-03T10:00"), mx, a22))
    }

    @Test fun `con la salida olvidada apagada el turno no vence`() {
        val apagada = ReglaDeSalida(activa = false)
        assertEquals(Long.MAX_VALUE, limiteEnElCelular(ms("2026-11-03T10:00"), mx, apagada))
        val t = TurnoLocal(1, "s1", "k", null, "v1", ms("2026-11-03T10:00"))
        assertTrue(turnoVigente(t, ms("2026-11-10T10:00"), mx, apagada))
    }

    @Test fun `el turno usa la regla que bajo de SU sucursal, y la de otra no le aplica`() {
        val t = TurnoLocal(1, "s1", "k", null, "v1", ms("2026-11-03T10:00"))
        val a1 = ms("2026-11-04T01:00")
        assertTrue("sin regla guardada: la de hoy (04:00 + 3 h)", turnoVigente(t, a1, mx))
        recordarReglaDeSalida("v1", a22)
        assertTrue("con 22:00: a las 23:30 sigue (margen hasta la 01:00)", turnoVigente(t, ms("2026-11-03T23:30"), mx))
        assertFalse("con 22:00: a la 01:00 ya venció", turnoVigente(t, a1, mx))
        recordarReglaDeSalida("v2", a22)
        assertTrue("la regla de otra sucursal no le aplica", turnoVigente(t, a1, mx))
    }

    @Test fun `tiene turno - sólo el suyo, abierto y vigente`() {
        val t = TurnoLocal(1, "s1", "k", null, "v1", ms("2026-11-03T10:00"))
        val ahora = ms("2026-11-03T12:00")
        assertTrue(tieneTurno(t, "s1", ahora))
        assertFalse("de otra persona", tieneTurno(t, "s2", ahora))
        assertFalse("sin turno", tieneTurno(null, "s1", ahora))
        assertFalse("ya marcó salida", tieneTurno(t.copy(llaveEntrada = null), "s1", ahora))
        assertFalse("vencido (salida olvidada)", tieneTurno(t, "s1", ms("2026-11-05T10:00")))
        assertNull(refEntrada(t, "s1", ms("2026-11-05T10:00")))
    }

    @Test fun `la hora de corte llega como HH_mm y una mal escrita es la de hoy`() {
        assertEquals(LocalTime.of(22, 0), reglaDe(true, "22:00").corte)
        assertEquals(ReglaDeSalida.DE_HOY, reglaDe(null, null))
        assertEquals(ReglaDeSalida.DE_HOY, reglaDe(true, "22h"))
        assertEquals("como el SERVIDOR: sólo HH:mm", ReglaDeSalida.DE_HOY, reglaDe(true, "22:00:00"))
        assertEquals(ReglaDeSalida.DE_HOY, reglaDe(true, "7:00"))
        assertEquals(LocalTime.of(0, 0), reglaDe(null, "00:00").corte)
        assertFalse(reglaDe(false, null).activa)
    }
}
