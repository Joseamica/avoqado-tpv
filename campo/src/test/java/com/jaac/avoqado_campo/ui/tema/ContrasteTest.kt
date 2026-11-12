package com.jaac.avoqado_campo.ui.tema

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.jaac.avoqado_campo.ui.Tono
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jaac.avoqado_campo.ui.tema.ColoresCampo as C

/**
 * El texto chico de la app se lee: 4.5:1 como mínimo (WCAG 1.4.3). El naranja y el verde del rediseño quedaron debajo dos
 * rondas seguidas (Codex rediseño r1 y r2). Fuera de aquí, a propósito: el texto de lo deshabilitado (la norma lo exenta) y
 * el blanco sobre el verde de marca, que comparte la app de la terminal (anotado en docs/audit/deuda-encontrada.md).
 */
class ContrasteTest {
    private fun razon(a: Color, b: Color): Double {
        val (x, y) = a.luminance() to b.luminance()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun seLee(nombre: String, texto: Color, fondo: Color) {
        val r = razon(texto, fondo.compositeOver(C.Blanco))
        assertTrue("$nombre: %.2f:1, mínimo 4.5:1".format(r), r >= 4.5)
    }

    @Test fun `cada aviso se lee sobre la pantalla y sobre una tarjeta`() {
        for (t in Tono.entries) for ((donde, base) in listOf("pantalla" to C.Fondo, "tarjeta" to C.Blanco)) {
            seLee("Tono.$t en $donde", t.tinta, t.fondo.compositeOver(base))
        }
    }

    @Test fun `los colores de texto sueltos se leen donde se usan`() {
        for ((donde, base) in listOf("pantalla" to C.Fondo, "tarjeta" to C.Blanco)) {
            seLee("VerdeTexto en $donde", C.VerdeTexto, base)
            seLee("Gris500 en $donde", C.Gris500, base)
        }
        seLee("NaranjaTinta en la opción gris de Ayuda", C.NaranjaTinta, C.Gris100)
        seLee("insignia blanca sobre NaranjaFuerte", C.Blanco, C.NaranjaFuerte)
        seLee("letrero del escáner", C.Gris200, C.Gris800)
    }
}
