package com.jaac.avoqado_tpv.core.data.local

import android.content.SharedPreferences
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

    /**
     * Codex mac r3: AuthRepository guardaba razón social, RFC y marca con TRES `apply()` separados; si el proceso moría entre
     * ellos quedaba en disco «RFC de B con la marca de A» (y A podía imprimir el RFC de B). El trío se escribe en UNA sola
     * edición, y nada más escribe esas tres llaves.
     */
    @Test
    fun `P1 Codex mac r3 el trio fiscal se escribe en una sola edicion y nada mas lo escribe`() {
        val fuente = File("src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt").readText()
        for (viejo in listOf("fun saveVenueLegalName(", "fun saveVenueRfc(", "fun saveVenueFiscalDe(")) {
            assertTrue("$viejo sigue existiendo: escribe una llave del trío por separado", !fuente.contains(viejo))
        }
        val guardar = fuente.substringAfter("fun guardarDatosFiscales(").substringBefore("\n    }")
        assertEquals("guardarDatosFiscales hace una sola edición", 1, Regex("""\.edit\(\)""").findAll(guardar).count())
        assertEquals("guardarDatosFiscales confirma una sola vez", 1, Regex("""\.apply\(\)""").findAll(guardar).count())
        // Las únicas escrituras de las 3 llaves están en escribirTrioFiscal (y el clear() de clearAll, que borra todo junto).
        val trio = fuente.substringAfter("fun escribirTrioFiscal(").substringBefore("\n        }")
        for (llave in listOf("KEY_VENUE_LEGAL_NAME", "KEY_VENUE_RFC", "KEY_VENUE_FISCAL_DE")) {
            val escrituras = Regex("""(putString|remove)\(\s*$llave""").findAll(fuente).count()
            val enElTrio = Regex("""(putString|remove)\(\s*$llave""").findAll(trio).count()
            assertTrue("$llave no se escribe en escribirTrioFiscal", enElTrio > 0)
            assertEquals("$llave se escribe fuera de escribirTrioFiscal", enElTrio, escrituras)
        }
        // El negocio de la terminal sólo cambia por escribirVenueId, que en la misma edición olvida el trío del anterior.
        assertEquals(1, Regex("""putString\(\s*KEY_VENUE_ID""").findAll(fuente).count())
        assertTrue(fuente.substringAfter("fun escribirVenueId(").substringBefore("\n        }").contains("putString(KEY_VENUE_ID"))
    }

    /** Un editor de prueba sobre un «disco»: los cambios llegan juntos al disco sólo con apply(), como SharedPreferences. */
    private class EditorDePrueba(private val disco: MutableMap<String, String>) : SharedPreferences.Editor {
        val pendientes = linkedMapOf<String, String?>()
        var confirmaciones = 0
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pendientes[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { pendientes[key] = null; return this }
        override fun apply() {
            confirmaciones++
            pendientes.forEach { (k, v) -> if (v == null) disco.remove(k) else disco[k] = v }
        }
        override fun commit(): Boolean { apply(); return true }
        override fun clear(): SharedPreferences.Editor = error("no se usa")
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = error("no se usa")
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = error("no se usa")
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = error("no se usa")
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = error("no se usa")
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = error("no se usa")
    }

    /** Lo que imprime el ticket con ese disco, como `getVenueRfc()`. */
    private fun rfcDelTicket(disco: Map<String, String>) = datoFiscalDelNegocio(disco["venue_rfc"], disco["venue_fiscal_de"], disco["venue_id"])

    @Test
    fun `P1 Codex mac r3 la marca de A con el RFC de B no puede quedar en disco`() {
        // A tenía su emisor guardado; la sesión ya es de B y llega el GET de B. El proceso puede morir antes o después del apply.
        val disco = mutableMapOf("venue_id" to "venue-B", "venue_legal_name" to "A SA", "venue_rfc" to "AAA010101AA1", "venue_fiscal_de" to "venue-A")
        val editor = EditorDePrueba(disco)
        SecureStorage.escribirTrioFiscal(editor, "B SA", "BBB010101BB1", "venue-B")

        // Muere antes de confirmar: el disco sigue con el trío de A completo → B imprime sin RFC (lado seguro), nunca el de A.
        assertEquals(setOf("venue_legal_name", "venue_rfc", "venue_fiscal_de"), editor.pendientes.keys)
        assertNull(rfcDelTicket(disco))
        // Confirma: el trío de B completo, de una vez.
        editor.apply()
        assertEquals(1, editor.confirmaciones)
        assertEquals("BBB010101BB1", rfcDelTicket(disco))
        assertEquals("venue-B", disco["venue_fiscal_de"])
        // Y si la terminal vuelve a A, A no imprime el RFC de B.
        assertNull(rfcDelTicket(disco + ("venue_id" to "venue-A")))
    }

    @Test
    fun `P1 Codex mac r3 al cambiar de negocio se olvida el trio del anterior en la misma edicion, tambien sin marca`() {
        // Columnas de siempre de A guardadas por una versión anterior (sin marca): antes, B las imprimía.
        val disco = mutableMapOf("venue_id" to "venue-A", "venue_legal_name" to "A SA", "venue_rfc" to "AAA010101AA1")
        val editor = EditorDePrueba(disco)
        SecureStorage.escribirVenueId(editor, anterior = "venue-A", nuevo = "venue-B").apply()

        assertEquals(1, editor.confirmaciones)
        assertEquals("venue-B", disco["venue_id"])
        assertNull(rfcDelTicket(disco))
        assertNull(disco["venue_legal_name"])
    }

    @Test
    fun `control el mismo negocio (otro PIN, el mismo arranque) no toca el trio`() {
        val disco = mutableMapOf("venue_id" to "venue-A", "venue_legal_name" to "A SA", "venue_rfc" to "AAA010101AA1")
        SecureStorage.escribirVenueId(EditorDePrueba(disco), anterior = "venue-A", nuevo = "venue-A").apply()
        assertEquals("AAA010101AA1", rfcDelTicket(disco))
        // Primera activación (sin negocio anterior): tampoco borra nada.
        val nuevo = mutableMapOf<String, String>()
        SecureStorage.escribirVenueId(EditorDePrueba(nuevo), anterior = null, nuevo = "venue-A").apply()
        assertEquals(mapOf("venue_id" to "venue-A"), nuevo)
    }

    @Test
    fun `P1 Codex mac r3 nadie fuera de SecureStorage escribe el trio, y el login lo guarda una sola vez`() {
        val fuera = listOf("src/main/java", "src/sandbox/java", "src/production/java").map(::File).filter { it.exists() }
            .flatMap { raiz -> raiz.walkTopDown().filter { it.extension == "kt" && it.name != "SecureStorage.kt" } }
            .filter { archivo ->
                val texto = archivo.readText()
                listOf("saveVenueLegalName(", "saveVenueRfc(", "saveVenueFiscalDe(", "\"venue_rfc\"", "\"venue_legal_name\"", "\"venue_fiscal_de\"")
                    .any(texto::contains)
            }
            .map { it.path }
        assertEquals("archivos que escriben el trío fiscal fuera de SecureStorage", emptyList<String>(), fuera)
        val login = File("src/main/java/com/jaac/avoqado_tpv/features/authentication/data/repository/AuthRepository.kt").readText()
        assertEquals(1, Regex("""guardarDatosFiscales\(""").findAll(login).count())
    }
}
