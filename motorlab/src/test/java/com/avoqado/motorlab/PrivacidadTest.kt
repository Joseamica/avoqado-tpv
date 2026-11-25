package com.avoqado.motorlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Bloque 4-L · L-c: una GUARDIA DE PATRONES sobre el Kotlin propio de `:motorlab` (`src/main`). El laboratorio maneja la banda
 * (57, con el PAN) y el criptograma, y este código no escribe al log (tampoco con el `LogUtils` de las bibliotecas de PAX) ni a
 * la consola, no enseña el mensaje ni la traza de una excepción, no usa lo que `Lectura` deja ver hacia afuera (bin, últimos 4,
 * país) ni las huellas ni el KSN de la libreta, ni toca la banda 2 del kernel. Lee los fuentes como texto. No prueba que las bibliotecas de PAX o de Blumon no escriban:
 * ésas sí escriben en `debug`.
 */
class PrivacidadTest {
    @get:Rule val carpeta = TemporaryFolder()

    private val prohibido = Regex(
        """\bLog\.[a-z]+\(|\bLogUtils\.[a-z]+\(|\bprint(ln)?\(|printStackTrace|stackTraceToString|\bTimber\b|System\.(out|err)|""" +
            """\.message\b|localizedMessage|\b(bin|ultimos4|paisEmisor|huellaTarjeta|huellaLectura|ksnBase)\b|[tT]rack2|""" +
            // Una excepción interpolada (`"$e"`, `"${e}"`) trae su mensaje; `"${e.javaClass.simpleName}"` sigue permitido.
            """\${'$'}\{?(e|ex|error|excepcion|throwable|causa)\}?(?![\w.])""",
    )

    private fun hallazgos(raiz: File): List<String> =
        raiz.walk().filter { it.extension in setOf("kt", "java") }.toList().flatMap { f ->
            f.readLines().withIndex().filter { prohibido.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" }
        }

    @Test fun `src main no escribe al log ni ensena datos de la tarjeta`() {
        val raiz = File("src/main/java")
        assertTrue("no encontró los fuentes de src/main", raiz.walk().count { it.extension == "kt" } >= 4)
        assertEquals(emptyList<String>(), hallazgos(raiz))
    }

    @Test fun `la guardia ve nombres sueltos excepciones interpoladas y archivos java`() {
        val raiz = carpeta.root
        File(raiz, "A.kt").writeText("with(l) { val x = bin }\n")
        File(raiz, "B.kt").writeText("val s = \"fallo: \$e\"\n")
        File(raiz, "C.java").writeText("Log.d(\"x\", \"y\");\n")
        assertEquals(listOf("A.kt:1", "B.kt:1", "C.java:1"), hallazgos(raiz).sorted())
    }
}
