package com.jaac.avoqado_tpv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Guarda: nadie vuelve a pedirle al aparato las reglas de México. `"America/Mexico_City"` escrito en el código usa el ICU o
 * la tzdata del aparato (viejos en la PAX A910S y la Nexgo N86 ⇒ +1 h); va por [VenueTimeZone]. Y `getTimeZone(x.id)` con
 * un desfase fijo («-06:00») cae en GMT en silencio: se usa `getTimeZone(zona)`.
 */
class ZonaDeMexicoSinReglasDelAparatoTest {
    private val raiz = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
    private fun esCodigo(linea: String) = linea.trim().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") }

    @Test fun `ninguna zona de Mexico escrita a mano fuera de VenueTimeZone`() {
        val hallazgos = raiz.walkTopDown().filter { it.extension == "kt" && it.name != "VenueTimeZone.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> esCodigo(l) && ("\"America/Mexico_City\"" in l || Regex("""getTimeZone\([^)]*\.id\)""").containsMatchIn(l)) }
                .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }.toList()
        assertEquals(hallazgos.joinToString("\n"), 0, hallazgos.size)
    }
}
