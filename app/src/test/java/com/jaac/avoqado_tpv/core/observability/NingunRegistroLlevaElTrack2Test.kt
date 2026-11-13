package com.jaac.avoqado_tpv.core.observability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D64 (13-nov): «✅ Track2 extracted: ${track2Value.take(16)}...» escribía el número completo de la
 * tarjeta en logcat, en las DOS copias del cobro (`src/production` y `src/sandbox`). El Track2 lleva el
 * número, la vigencia y datos de servicio: ninguna línea de registro lo interpola, ni entero ni en pedazos.
 * El árbol de Timber además lo tapa (TapaNumeroDeTarjetaTest); esta prueba evita que la fuga vuelva al código.
 */
class NingunRegistroLlevaElTrack2Test {

    private val raices = listOf(File("src/main/java"), File("src/sandbox/java"), File("src/production/java"))

    /** Una llamada a Timber o a android.util.Log que interpola una variable cuyo nombre contiene «track2». */
    private val registroConTrack2 =
        Regex("""\b(Timber(\.tag\([^)]*\))?|Log)\.[vdiwe]\(.*\$\{?\s*[A-Za-z_.]*[Tt]rack2""")

    @Test
    fun `ninguna linea de registro interpola el Track2`() {
        raices.forEach { assertTrue("No existe $it: el guard no vigila nada", it.exists()) }
        val culpables = raices.flatMap { raiz ->
            raiz.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { archivo ->
                archivo.readLines().mapIndexedNotNull { i, linea ->
                    if (registroConTrack2.containsMatchIn(linea)) "${archivo.path}:${i + 1}: ${linea.trim()}" else null
                }
            }
        }
        assertTrue("Líneas de registro que escriben el Track2 (número de tarjeta):\n" + culpables.joinToString("\n"), culpables.isEmpty())
    }
}
