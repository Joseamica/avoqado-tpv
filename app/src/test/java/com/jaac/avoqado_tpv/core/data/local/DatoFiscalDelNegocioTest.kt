package com.jaac.avoqado_tpv.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * na7522fd · Codex mac r2 #1 (18-nov-2026): la terminal pasa del negocio A al B sin borrar lo guardado; el PIN de B guarda la
 * sesión (venueId = B) y el aparato se reinicia ANTES de que llegue el GET de B. Abre Inicio con la sesión de B y el emisor de A
 * guardado: el ticket de B salía con el RFC y la razón social de A. El candado va al LEER, en el único embudo de los 6 lectores.
 */
class DatoFiscalDelNegocioTest {

    @Test
    fun `P1 de A a B con reinicio entre el PIN de B y el GET, el ticket de B no lleva el RFC de A`() {
        assertNull(datoFiscalDelNegocio("STA241210PW8", fiscalDe = "venue-A", negocioActual = "venue-B"))
        assertNull(datoFiscalDelNegocio("SERVICIOS TECNOLOGICOS AVO", fiscalDe = "venue-A", negocioActual = "venue-B"))
    }

    @Test
    fun `control el emisor de ESTE negocio se lee`() {
        assertEquals("STA241210PW8", datoFiscalDelNegocio("STA241210PW8", fiscalDe = "venue-B", negocioActual = "venue-B"))
    }

    @Test
    fun `control las columnas de siempre (sin marca) se leen como antes, tambien en una terminal actualizada`() {
        assertEquals("VIE010101AB1", datoFiscalDelNegocio("VIE010101AB1", fiscalDe = null, negocioActual = "venue-B"))
        assertNull(datoFiscalDelNegocio(null, fiscalDe = null, negocioActual = "venue-B"))
    }

    @Test
    fun `los dos lectores de SecureStorage pasan por el candado`() {
        val fuente = File("src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt").readText()
        for (lector in listOf("fun getVenueRfc()", "fun getVenueLegalName()")) {
            val cuerpo = fuente.substringAfter(lector).substringBefore("\n    }")
            assertTrue("$lector no pasa por datoFiscalDelNegocio", cuerpo.contains("datoFiscalDelNegocio("))
        }
        // Nadie más lee las llaves a pelo: los 6 lectores del ticket usan esos dos getters.
        assertEquals(1, Regex("""getString\(KEY_VENUE_RFC""").findAll(fuente).count())
        assertEquals(1, Regex("""getString\(KEY_VENUE_LEGAL_NAME""").findAll(fuente).count())
    }
}
