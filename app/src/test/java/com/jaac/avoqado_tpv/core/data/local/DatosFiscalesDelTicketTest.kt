package com.jaac.avoqado_tpv.core.data.local

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D50: la razón social y el RFC del ticket viven en UN valor con el negocio adentro (Codex mac r3 y r4 del encargo 1df453). El
 * "disco" de estas pruebas es el mapa que usa Android: cada `apply()` aplica su editor entero, y un "reinicio" es leer el mismo mapa
 * con otras preferencias. `venue_id` lo escriben otros (activación, PIN): aquí se pone a mano, como lo deja `saveVenueId`.
 */
class DatosFiscalesDelTicketTest {

    private class Disco(vararg inicial: Pair<String, String>) {
        val datos = mutableMapOf(*inicial)
        var lecturas = 0
        var ediciones = 0

        fun prefs(): SharedPreferences = mockk {
            every { getString(any(), any()) } answers { lecturas++; datos[firstArg()] ?: secondArg() }
        }

        fun editor(): SharedPreferences.Editor {
            val pendiente = mutableListOf<(MutableMap<String, String>) -> Unit>()
            val e = mockk<SharedPreferences.Editor>()
            every { e.putString(any(), any()) } answers {
                val llave = firstArg<String>()
                val valor = secondArg<String?>()
                pendiente += { m -> if (valor == null) m.remove(llave) else m[llave] = valor }
                e
            }
            every { e.remove(any()) } answers { val llave = firstArg<String>(); pendiente += { it.remove(llave) }; e }
            every { e.apply() } answers { ediciones++; pendiente.forEach { it(datos) }; pendiente.clear() }
            return e
        }

        fun guardar(venueId: String, legalName: String?, rfc: String?, delEmisor: Boolean = true) =
            SecureStorage.escribirDatosFiscales(editor(), venueId, legalName, rfc, delEmisor).apply()

        fun leer(venueIdDelTicket: String?) = SecureStorage.leerDatosFiscales(prefs(), venueIdDelTicket)
    }

    @Test
    fun `P1 D50 r4 fila 1 el emisor de A guardado con la terminal sin negocio no sale en el ticket de B`() {
        // El latido borró la activación (sin venue_id) y el GET de A que estaba en vuelo llegó después y escribió.
        val disco = Disco()
        disco.guardar("venue-A", "Emisor A SA", "AAA010101AA1")
        // Se activa B; su PIN entra pero su GET falla: nadie vuelve a escribir los datos fiscales.
        disco.datos["venue_id"] = "venue-B"

        assertEquals(DatosFiscalesDelTicket.NINGUNO, disco.leer("venue-B"))
    }

    @Test
    fun `P1 D50 r4 fila 1 la lectura es UNA sola y trae el negocio adentro`() {
        val disco = Disco("venue_id" to "venue-A")
        disco.guardar("venue-A", "Emisor A SA", "AAA010101AA1")
        disco.lecturas = 0

        assertEquals(DatosFiscalesDelTicket("Emisor A SA", "AAA010101AA1", delEmisor = true), disco.leer("venue-A"))
        // Con dos lecturas, un refresco entre ellas cruzaba la razón social o el RFC de A con la marca de B.
        assertEquals(1, disco.lecturas)
    }

    @Test
    fun `P1 D50 r4 fila 2 lo que dejo una version anterior sin marca no se imprime`() {
        // main dejaba las columnas sin dueño; un APK del PR 79 pudo dejar un emisor sin marca o con la de otro negocio.
        val deMain = Disco("venue_id" to "venue-B", "venue_legal_name" to "A SA", "venue_rfc" to "AAA010101AA1")
        assertEquals(DatosFiscalesDelTicket.NINGUNO, deMain.leer("venue-B"))

        val delPr79 = Disco(
            "venue_id" to "venue-B", "venue_legal_name" to "Emisor A SA", "venue_rfc" to "AAA010101AA1", "venue_fiscal_de" to "venue-B",
        )
        assertEquals(DatosFiscalesDelTicket.NINGUNO, delPr79.leer("venue-B"))
    }

    @Test
    fun `P1 D50 la escritura es una sola edicion con el negocio adentro y borra las llaves viejas`() {
        val disco = Disco("venue_id" to "venue-A", "venue_legal_name" to "Viejo SA", "venue_rfc" to "VIE010101AA1")

        disco.guardar("venue-A", "Emisor A SA", "AAA010101AA1")

        assertEquals(1, disco.ediciones)
        assertEquals(setOf("venue_id", "datos_fiscales_del_ticket"), disco.datos.keys)
        val guardado = disco.datos.getValue("datos_fiscales_del_ticket")
        assertTrue(guardado, guardado.contains("venue-A") && guardado.contains("AAA010101AA1") && guardado.contains("Emisor A SA"))
    }

    @Test
    fun `P1 D50 A a B con reinicio sin los datos de B el ticket de B sale sin RFC y con ellos sale el de B`() {
        val disco = Disco("venue_id" to "venue-A")
        disco.guardar("venue-A", "Emisor A SA", "AAA010101AA1")
        assertEquals("AAA010101AA1", disco.leer("venue-A").rfc)

        // PIN de B (saveVenueId) y el proceso muere antes del GET; al arrancar, otras preferencias leen el mismo disco.
        disco.datos["venue_id"] = "venue-B"
        assertEquals(DatosFiscalesDelTicket.NINGUNO, disco.leer("venue-B"))

        // Llega el GET de B.
        disco.guardar("venue-B", "Emisor B SA", "BBB010101BB1")
        assertEquals(DatosFiscalesDelTicket("Emisor B SA", "BBB010101BB1", delEmisor = true), disco.leer("venue-B"))
        // Un ticket tardío de A ya no se lleva los de B.
        assertEquals(DatosFiscalesDelTicket.NINGUNO, disco.leer("venue-A"))
    }

    @Test
    fun `control D50 sin negocio del ticket o con el valor roto no se imprime nada`() {
        val disco = Disco("venue_id" to "venue-A")
        disco.guardar("venue-A", "Emisor A SA", "AAA010101AA1")
        assertEquals(DatosFiscalesDelTicket.NINGUNO, disco.leer(null))
        assertEquals(DatosFiscalesDelTicket.NINGUNO, disco.leer(""))

        for (roto in listOf(
            "{", "[]", "{}", """{"legalName":"X","rfc":"Y"}""", """{"venueId":null,"rfc":"Y"}""", "AAA010101AA1",
            """{"venueId":["venue-A"],"rfc":"Y"}""", """{"venueId":{"id":"venue-A"},"rfc":"Y"}""",
        )) {
            val d = Disco("venue_id" to "venue-A", "datos_fiscales_del_ticket" to roto)
            assertEquals(roto, DatosFiscalesDelTicket.NINGUNO, d.leer("venue-A"))
        }
    }

    @Test
    fun `control D50 vacios y nulos se guardan como nulos y los caracteres raros llegan iguales`() {
        val disco = Disco("venue_id" to "venue-A")
        disco.guardar("venue-A", "  ", null)
        assertTrue(disco.leer("venue-A").vacio)

        val raro = "Café \"La Ñ\" & Hijos, S.A. de C.V. \\ {}"
        disco.guardar("venue-A", raro, "AAA010101AA1", delEmisor = false)
        assertEquals(DatosFiscalesDelTicket(raro, "AAA010101AA1", delEmisor = false), disco.leer("venue-A"))
        assertFalse(disco.leer("venue-A").vacio)
        assertNull(Disco().leer("venue-A").rfc)
    }

    @Test
    fun `P1 D50 Codex r1 2 un salto de linea en la razon social no inventa otro renglon de RFC`() {
        val disco = Disco("venue_id" to "venue-A")
        disco.guardar("venue-A", "Mi Negocio SA de CV\nRFC: XAXX010101000", " AAA010101AA1\n")

        val datos = disco.leer("venue-A")
        assertEquals("Mi Negocio SA de CV RFC: XAXX010101000", datos.legalName)
        assertEquals("AAA010101AA1", datos.rfc)
        assertFalse(datos.legalName!!.contains('\n') || datos.rfc!!.contains('\n'))
    }

    @Test
    fun `P1 D50 Codex r2 6 una entrada cifrada danada sale sin RFC y no truena el ticket, y una escritura nueva la repara`() {
        val disco = Disco("venue_id" to "venue-A")
        var danada = true
        val prefs = mockk<SharedPreferences> {
            // EncryptedSharedPreferences lanza al no poder descifrar ESA entrada (las demás se leen bien).
            every { getString("datos_fiscales_del_ticket", any()) } answers {
                if (danada) throw SecurityException("Could not decrypt value") else disco.datos[firstArg()]
            }
        }
        assertEquals(DatosFiscalesDelTicket.NINGUNO, SecureStorage.leerDatosFiscales(prefs, "venue-A"))

        // El refresco ya no truena al leer lo guardado antes de escribir: escribe sin leer, y queda legible.
        disco.guardar("venue-A", "Emisor A SA", "AAA010101AA1")
        danada = false
        assertEquals("AAA010101AA1", SecureStorage.leerDatosFiscales(prefs, "venue-A").rfc)
    }

    @Test
    fun `D50 nadie fuera de SecureStorage toca las llaves fiscales`() {
        val prohibido = listOf(
            "\"datos_fiscales_del_ticket\"", "\"venue_rfc\"", "\"venue_legal_name\"", "\"venue_fiscal_de\"",
            "getVenueRfc(", "getVenueLegalName(", "saveVenueRfc(", "saveVenueLegalName(",
        )
        val fuera = File("src").listFiles().orEmpty().filter { it.name != "test" && it.name != "androidTest" }
            .flatMap { raiz -> raiz.walkTopDown().filter { it.extension == "kt" && it.name != "SecureStorage.kt" }.toList() }
            .filter { archivo -> archivo.readText().let { texto -> prohibido.any(texto::contains) } }
            .map { it.path }
        assertEquals("archivos que leen o escriben los datos fiscales sin pasar por SecureStorage", emptyList<String>(), fuera)
    }
}
