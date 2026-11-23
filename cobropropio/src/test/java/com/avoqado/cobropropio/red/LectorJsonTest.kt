package com.avoqado.cobropropio.red

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El único lector de cuerpos de Blumon: estricto, en streaming, con TODAS las apariciones de cada clave. */
class LectorJsonTest {

    @Test fun `junta todas las apariciones con su ruta`() {
        val a = Apariciones.leer("""{"simulation":false,"dataResponse":{"simulation":true},"extra":[{"simulation":false}]}""")!!
        assertEquals(3, a.de("simulation").size)
        assertTrue((a.en("dataResponse", "simulation") as Escalar.Booleano).v)
        assertFalse(a.soloEn("simulation"))
        assertNull(a.unica("simulation"))
        assertFalse(a.ambigua)
    }

    @Test fun `una clave repetida en cualquier objeto se marca`() {
        assertTrue(Apariciones.leer("""{"a":1,"a":1}""")!!.ambigua)
        assertTrue(Apariciones.leer("""{"x":[{"b":{"c":1,"c":2}}]}""")!!.ambigua)
        assertFalse(Apariciones.leer("""{"x":[{"c":1},{"c":2}]}""")!!.ambigua) // objetos distintos de una lista
    }

    /** R5 familia 1: la ruta es una lista de segmentos; una clave con punto o vacía nunca ocupa la ruta de un anidado. */
    @Test fun `una ruta es una lista de segmentos desde la raiz`() {
        val a = Apariciones.leer("""{"error.code":"TX_014","x":[{"code":"TX_013"}],"error":{"code":"TX_007"}}""")!!
        assertEquals("TX_007", (a.en("error", "code") as Escalar.Texto).v)
        assertEquals("TX_014", (a.en("error.code") as Escalar.Texto).v) // la clave literal, sólo en su propia ruta
        assertEquals(2, a.de("code").size) // la de la lista y la de error, no la clave literal "error.code"
        assertFalse(a.ambigua)
        val vacia = Apariciones.leer("""{"":{"error":{"code":"TX_014"}}}""")!!
        assertTrue(vacia.ambigua)
        assertNull(vacia.en("error", "code"))
    }

    @Test fun `un objeto o una lista quedan como presencia compuesta`() {
        val a = Apariciones.leer("""{"error":{"reference":{"v":"x"}},"amount":[1]}""")!!
        assertEquals(Escalar.Compuesto, a.en("error", "reference"))
        assertEquals(Escalar.Compuesto, a.en("amount"))
    }

    @Test fun `los numeros se guardan como se escribieron`() {
        assertEquals("1.00", (Apariciones.leer("""{"amount":1.00}""")!!.en("amount") as Escalar.Numero).crudo)
    }

    @Test fun `lo que no es UN objeto JSON estricto es null`() {
        for (c in listOf("", "[]", "1", "\"x\"", "{status:true}", "{} {}", "{\"a\":1", "{'a':1}", "{\"a\":NaN}", "<html>")) {
            assertNull(c, Apariciones.leer(c))
        }
    }

    @Test fun `se pasa de los topes es null`() {
        assertNull(Apariciones.leer("{\"a\":".repeat(40) + "1" + "}".repeat(40)))
        assertNull(Apariciones.leer("{\"a\":[" + (1..5_000).joinToString(",") + "]}"))
    }

    @Test fun `los valores no se imprimen`() {
        val a = Apariciones.leer("""{"t":"secreto-1","n":4111111111111111}""")!!
        assertFalse(a.toString().contains("secreto"))
        assertFalse(a.en("t").toString().contains("secreto"))
        assertFalse(a.en("n").toString().contains("4111"))
    }

    /** Bloque 1b · fila 13: la clave en CUALQUIER ruta, con las reglas de vacío de `tieneAlgoBajo`. */
    @Test fun `algo bajo una clave en cualquier ruta`() {
        fun algo(c: String) = Apariciones.leer(c)!!.tieneAlgoBajoEnCualquierRuta("error")
        for (c in listOf(
            """{"error":"x"}""", """{"d":{"error":504}}""", """{"d":{"e":{"error":{"a":null}}}}""", """{"d":[{"error":true}]}""",
            """{"d":{"error":[null]}}""",
        )) assertTrue(c, algo(c))
        for (c in listOf(
            """{}""", """{"error":null}""", """{"d":{"error":{}}}""", """{"d":[{"error":[]}]}""", """{"d":{"errores":"x"}}""",
            """{"d":"error"}""",
        )) assertFalse(c, algo(c))
        // `tieneAlgoBajo` sigue mirando sólo la raíz (P2 lo usa con `dataResponse`).
        assertFalse(Apariciones.leer("""{"d":{"error":"x"}}""")!!.tieneAlgoBajo("error"))
    }
}
