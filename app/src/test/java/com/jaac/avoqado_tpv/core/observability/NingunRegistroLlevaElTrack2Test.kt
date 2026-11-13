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

    /** Una llamada a Timber o a android.util.Log. */
    private val llamadaDeRegistro = Regex("""\b(Timber(\.tag\([^)]*\))?|Log)\.[vdiwe]\(""")

    /** Lo que se interpola en un texto: `$x` o `${x.y}` (hasta el primer carácter que no es nombre ni punto). */
    private val interpolacion = Regex("""\$\{?\s*([A-Za-z_][\w.]*)""")

    /** Escribe el CONTENIDO del Track2 (no sólo su largo) en una línea de registro. */
    private fun escribeElTrack2(linea: String): Boolean =
        llamadaDeRegistro.containsMatchIn(linea) &&
            interpolacion.findAll(linea).any { m ->
                val expr = m.groupValues[1]
                expr.contains("track2", ignoreCase = true) && !expr.endsWith(".length")
            }

    @Test
    fun `el guard reconoce la fuga y deja pasar el largo`() {
        assertTrue(escribeElTrack2("""Timber.i("   ✅ Track2 extracted: ${'$'}{track2Value.take(16)}... (length: ${'$'}{track2Value.length})")"""))
        assertTrue(escribeElTrack2("""Timber.d("   Track2: ${'$'}{track2.take(16)}...")"""))
        assertTrue(escribeElTrack2("""Timber.tag("X").w("t2=${'$'}track2")"""))
        assertTrue(escribeElTrack2("""Log.d(TAG, "t2=${'$'}{cardData.track2}")"""))
        assertTrue(!escribeElTrack2("""Timber.i("   ✅ Track2 extracted (length: ${'$'}{track2Value.length})")"""))
        assertTrue(!escribeElTrack2("""Timber.w("   ⚠️ Track2 not found")"""))
    }

    @Test
    fun `ninguna linea de registro interpola el Track2`() {
        raices.forEach { assertTrue("No existe $it: el guard no vigila nada", it.exists()) }
        val culpables = raices.flatMap { raiz ->
            raiz.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { archivo ->
                archivo.readLines().mapIndexedNotNull { i, linea ->
                    if (escribeElTrack2(linea)) "${archivo.path}:${i + 1}: ${linea.trim()}" else null
                }
            }
        }
        assertTrue("Líneas de registro que escriben el Track2 (número de tarjeta):\n" + culpables.joinToString("\n"), culpables.isEmpty())
    }
}
