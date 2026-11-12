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

    // Codex rediseño r3: un aviso con fondo transparente encima de la selfie quedó en 2.6:1. Con fondo sólido se lee igual
    // sobre la cámara, sobre la pantalla o sobre una tarjeta.
    @Test fun `el fondo de cada aviso es solido`() {
        for (t in Tono.entries) assertTrue("Tono.$t transparente", t.fondo.alpha == 1f)
    }

    // Codex rediseño r3: Material pinta con `primary` el texto de los TextButton y la etiqueta de un campo enfocado.
    @Test fun `el verde que Material usa como texto se lee`() {
        seLee("primary en la pantalla", Esquema.primary, C.Fondo)
        seLee("primary en tarjeta", Esquema.primary, C.Blanco)
        seLee("onPrimary sobre primary", Esquema.onPrimary, Esquema.primary)
    }

    @Test fun `los colores de texto sueltos se leen donde se usan`() {
        for ((donde, base) in listOf("pantalla" to C.Fondo, "tarjeta" to C.Blanco)) {
            seLee("VerdeTexto en $donde", C.VerdeTexto, base)
            seLee("GrisTexto en $donde", C.GrisTexto, base)
        }
        seLee("NaranjaTinta en la opción gris de Ayuda", C.NaranjaTinta, C.Gris100)
        seLee("insignia blanca sobre NaranjaFuerte", C.Blanco, C.NaranjaFuerte)
        seLee("letrero del escáner", C.Gris200, C.Gris800)
    }

    /** Con foco (teclado), Material pone encima una capa del 10 % del color del contenido: sobre el fondo y sobre el texto. */
    private fun conFoco(color: Color, contenido: Color) = contenido.copy(alpha = 0.1f).compositeOver(color)

    // Codex rediseño r4 (pasada exhaustiva): con foco y en las confirmaciones (AlertDialog) los grises y verdes bajaban a 4.2–4.5.
    @Test fun `con foco y en las confirmaciones tambien se lee`() {
        val dialogo = Esquema.surfaceContainerHigh
        seLee("cuerpo de una confirmación", Esquema.onSurfaceVariant, dialogo)
        seLee("botón de una confirmación con foco", Esquema.primary, conFoco(dialogo, Esquema.primary))
        seLee("«Actualizar» de Mis SIMs con foco", Esquema.primary, conFoco(C.Fondo, Esquema.primary))
        seLee("detalle de un mosaico u opción de Ayuda con foco", conFoco(C.GrisTexto, C.Gris900), conFoco(C.Blanco, C.Gris900))
    }

    @Test fun `el resto de la tabla de textos de Codex r4`() {
        seLee("texto principal en la pantalla", C.Gris900, C.Fondo)
        seLee("títulos de sección", C.Gris700, C.Fondo)
        seLee("motivo de rechazo en tarjeta", C.RojoTinta, C.Blanco)
        seLee("nota ámbar en tarjeta", C.AmbarTinta, C.Blanco)
        seLee("palomita verde sobre verde claro", C.VerdeTexto, C.VerdeClaro)
        seLee("«Escanear la SIM»", C.Blanco, C.Gris800)
        seLee("título sobre la cámara (imagen blanca detrás)", C.Blanco, Color.Black.copy(alpha = 0.6f).compositeOver(C.Blanco))
    }
}
