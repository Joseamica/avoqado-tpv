package com.jaac.avoqado_campo.custodia

import com.jaac.avoqado_campo.copia.SimEnCopia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/** «Mis ventas» por mes: del más reciente al más viejo, «Sin fecha» al final, y el mes de hoy (o el último) abierto. */
class VentasPorMesTest {
    private val mx = ZoneId.of("America/Mexico_City")
    private fun ms(s: String, zona: ZoneId = mx) = ZonedDateTime.of(LocalDateTime.parse(s), zona).toInstant().toEpochMilli()
    private fun fila(serie: String, fecha: Long?) =
        FilaSim(SimEnCopia(serie, "SOLD", 1, "Prepago", null, null, null, null, null, emptyList(), null), EstadoFila.VENDIDA, fecha = fecha)

    @Test fun `agrupa del mes mas reciente al mas viejo, con su titulo y cuantas`() {
        val g = porMes(
            listOf(
                fila("A", ms("2026-10-02T10:00")), fila("B", ms("2026-11-20T10:00")), fila("C", ms("2026-11-03T10:00")),
                fila("D", ms("2025-12-31T23:00")),
            ),
            mx,
        )
        assertEquals(listOf("noviembre 2026 (2)", "octubre 2026 (1)", "diciembre 2025 (1)"), g.map { it.titulo })
        assertEquals("dentro del mes, lo más nuevo arriba", listOf("B", "C"), g[0].filas.map { it.sim.serialNumber })
    }

    @Test fun `lo que no trae fecha va al final en «Sin fecha»`() {
        val g = porMes(listOf(fila("A", null), fila("B", ms("2026-11-20T10:00")), fila("C", null)), mx)
        assertEquals(listOf("noviembre 2026 (1)", "Sin fecha (2)"), g.map { it.titulo })
        assertEquals(SIN_FECHA, g.last().clave)
    }

    @Test fun `el mes sale con la zona del celular`() {
        // 1-dic 03:00 UTC = 30-nov 21:00 en CDMX.
        val instante = ZonedDateTime.of(LocalDateTime.parse("2026-12-01T03:00"), ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("noviembre 2026 (1)", porMes(listOf(fila("A", instante)), mx).single().titulo)
        assertEquals("diciembre 2026 (1)", porMes(listOf(fila("A", instante)), ZoneId.of("UTC")).single().titulo)
    }

    @Test fun `abierto de fabrica - el mes actual, o el mas reciente, o «Sin fecha» si es lo unico`() {
        val g = porMes(listOf(fila("A", ms("2026-10-02T10:00")), fila("B", ms("2026-11-20T10:00"))), mx)
        assertEquals("2026-11", abiertoDeFabrica(g, YearMonth.of(2026, 11)))
        assertEquals("sin ventas del mes actual: el más reciente", "2026-11", abiertoDeFabrica(g, YearMonth.of(2027, 1)))
        assertEquals(SIN_FECHA, abiertoDeFabrica(porMes(listOf(fila("A", null)), mx), YearMonth.of(2026, 11)))
        assertNull(abiertoDeFabrica(emptyList(), YearMonth.of(2026, 11)))
    }

    @Test fun `un toque abre un mes cerrado y otro lo cierra, tambien el de fabrica`() {
        assertEquals(true, estaAbierto("2026-11", "2026-11", emptySet()))
        assertEquals(false, estaAbierto("2026-10", "2026-11", emptySet()))
        assertEquals(true, estaAbierto("2026-10", "2026-11", setOf("2026-10")))
        assertEquals(false, estaAbierto("2026-11", "2026-11", setOf("2026-11")))
    }
}
