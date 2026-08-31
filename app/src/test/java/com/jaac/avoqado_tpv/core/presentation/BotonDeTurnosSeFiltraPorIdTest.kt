package com.jaac.avoqado_tpv.core.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El botón de turnos del menú se arma con un texto y se RECORTA en otro sitio cuando el venue tiene los
 * turnos apagados. Mientras ese recorte comparaba el TEXTO (`it.label == "Turnos"`), renombrar el botón
 * en un solo lado lo dejaba visible en venues con turnos APAGADOS: nada fallaba, nada avisaba, y el
 * cajero entraba a una pantalla que no le toca. Lo señaló Codex al renombrar a «Turnos de caja»
 * (2026-08-27) y se cerró dándole al botón una llave estable.
 *
 * Esta prueba es ESTÁTICA a propósito: no comprueba un valor, comprueba que nadie devuelva el filtro al
 * texto. Un test de comportamiento no cazaría el renombre de un solo lado.
 */
class BotonDeTurnosSeFiltraPorIdTest {

    private val welcomeScreen = File(
        "src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/WelcomeScreen.kt",
    ).readText()

    @Test
    fun `el recorte del boton de turnos NO compara textos`() {
        val porTexto = Regex("""removeAll\s*\{\s*it\.label\s*==""").findAll(welcomeScreen).count()
        assertEquals(
            "El menú volvió a recortarse por el TEXTO del botón: renombrarlo lo deja visible en un venue con los turnos apagados. Fíltralo por `it.id`.",
            0,
            porTexto,
        )
    }

    @Test
    fun `el boton de turnos se crea y se recorta con la MISMA llave`() {
        assertTrue(
            "El botón de turnos debe llevar `id = BOTON_TURNOS_DE_CAJA` al crearse.",
            welcomeScreen.contains("id = BOTON_TURNOS_DE_CAJA"),
        )
        assertTrue(
            "El recorte debe usar `it.id == BOTON_TURNOS_DE_CAJA`.",
            welcomeScreen.contains("it.id == BOTON_TURNOS_DE_CAJA"),
        )
    }
}
