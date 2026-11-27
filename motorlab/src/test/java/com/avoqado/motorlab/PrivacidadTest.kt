package com.avoqado.motorlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Bloque 4-L · L-c: una GUARDIA DE PATRONES sobre los `.kt` y `.java` propios de `:motorlab` (`src/main`). El laboratorio maneja la banda
 * (57, con el PAN) y el criptograma, y este código no escribe al log (tampoco con el `LogUtils` de las bibliotecas de PAX) ni a
 * la consola, no enseña el mensaje ni la traza de una excepción, no usa lo que `Lectura` deja ver hacia afuera (bin, últimos 4,
 * país) ni las huellas ni el KSN de la libreta, ni toca la banda 2 del kernel. Lee los fuentes como texto. No prueba que las bibliotecas de PAX o de Blumon no escriban:
 * ésas sí escriben en `debug`.
 *
 * ponytail: es una heurística de texto y NO ve `"$it"` dentro de un `getOrElse { }` / `onFailure { }`, `"${e.cause}"`, `"" + e`
 * ni `e.toString()`. `it` no puede entrar a la lista: `src/main` tiene ~25 usos legítimos de `$it`. Si eso llega a importar, el
 * arreglo es un analizador de verdad (detekt), no más regex.
 */
class PrivacidadTest {
    @get:Rule val carpeta = TemporaryFolder()

    private val prohibido = Regex(
        """\bLog\.[a-z]+\(|\bLogUtils\.[a-z]+\(|\bprint(ln)?\(|printStackTrace|stackTraceToString|\bTimber\b|System\.(out|err)|""" +
            """\.message\b|localizedMessage|\b(bin|ultimos4|paisEmisor|huellaTarjeta|huellaLectura|ksnBase)\b|[tT]rack2|""" +
            // Una excepción interpolada (`"$e"`, `"$e."`, `"${e}"`) trae su mensaje; `"${e.javaClass.simpleName}"` sigue permitido.
            """\${'$'}(e|ex|error|excepcion|throwable|causa)(?!\w)|\${'$'}\{(e|ex|error|excepcion|throwable|causa)(?![\w.])""",
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

    /** D14: las bibliotecas de PAX (`emv`, `commonlib`) escriben datos de la tarjeta al registro del aparato si este interruptor está prendido. */
    @Test fun `el interruptor de registro de las bibliotecas de PAX esta apagado`() {
        val campo = com.pax.commonlib.utils.LogUtils::class.java.getDeclaredField("IS_DEBUG").apply { isAccessible = true }
        assertEquals(false, campo.getBoolean(null))
    }

    /**
     * La medición de chip (26-nov) corre el kernel de contacto: sólo le pide 9F34 y 95 (códigos del CVM y del TVR), nunca la
     * banda, el PAN ni un criptograma; y la guardia de arriba la cubre (vive en `src/main/java`).
     */
    @Test fun `la medicion de chip solo pide al kernel 9F34 y 95`() {
        val fuentes = File("src/main/java").walk().filter { it.extension == "kt" }.toList()
        assertTrue("la guardia no ve MedicionDeChip.kt", fuentes.any { it.name == "MedicionDeChip.kt" })
        val pedidos = fuentes.flatMap { f -> Regex("""tagDelChip\(([^)]*)\)""").findAll(f.readText()).map { it.groupValues[1] }.toList() }
        assertEquals(setOf("0x9F34", "0x95", "tag: Int"), pedidos.toSet())
    }

    @Test fun `la guardia ve nombres sueltos excepciones interpoladas y archivos java`() {
        val raiz = carpeta.root
        File(raiz, "A.kt").writeText("with(l) { val x = bin }\n")
        File(raiz, "B.kt").writeText("val s = \"fallo: \$e\"\n")
        File(raiz, "C.java").writeText("Log.d(\"x\", \"y\");\n")
        // Lo permitido no se marca: la clase de la excepción y un nombre que sólo empieza como uno de la lista.
        File(raiz, "D.kt").writeText("val a = \"\${e.javaClass.simpleName}\"\nval b = \"\$estado\"\nval c = \"\${estado}\"\n")
        File(raiz, "E.kt").writeText("val s = \"fallo: \$e.\"\n")
        // Entre llaves con un operador detrás también se interpola la excepción entera.
        File(raiz, "F.kt").writeText("val a = \"\${e!!}\"\nval b = \"\${e }\"\nval c = \"\${e ?: \"x\"}\"\n")
        assertEquals(
            listOf("A.kt:1", "B.kt:1", "C.java:1", "E.kt:1", "F.kt:1", "F.kt:2", "F.kt:3"),
            hallazgos(raiz).sorted(),
        )
    }
}
