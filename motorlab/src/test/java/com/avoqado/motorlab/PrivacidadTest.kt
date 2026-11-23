package com.avoqado.motorlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bloque 4-L · L-c: una GUARDIA DE PATRONES sobre el Kotlin propio de `:motorlab` (`src/main`). El laboratorio maneja la banda
 * (57, con el PAN) y el criptograma, y este código no escribe al log (tampoco con el `LogUtils` de las bibliotecas de PAX) ni a
 * la consola, no enseña el mensaje ni la traza de una excepción, no usa lo que `Lectura` deja ver hacia afuera (bin, últimos 4,
 * país) ni toca la banda 2 del kernel. Lee los fuentes como texto. No prueba que las bibliotecas de PAX o de Blumon no escriban:
 * ésas sí escriben en `debug`.
 */
class PrivacidadTest {
    @Test fun `src main no escribe al log ni ensena datos de la tarjeta`() {
        val prohibido = Regex(
            """\bLog\.[a-z]+\(|\bLogUtils\.[a-z]+\(|\bprint(ln)?\(|printStackTrace|stackTraceToString|\bTimber\b|System\.(out|err)|""" +
                """\.message\b|localizedMessage|\.bin\(\)|\.ultimos4\(\)|\.paisEmisor\(\)|[tT]rack2""",
        )
        val fuentes = File("src/main/java").walk().filter { it.extension == "kt" }.toList()
        assertTrue("no encontró los fuentes de src/main", fuentes.size >= 4)
        val hallazgos = fuentes.flatMap { f ->
            f.readLines().withIndex().filter { prohibido.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" }
        }
        assertEquals(emptyList<String>(), hallazgos)
    }
}
