package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 El respaldo SIN RED (founder, 22-sep: «los dos — con internet decide el servidor; si no contesta, el aparato»). El
 * ViewModel ya calculaba `puedeDeclararSinRed` y tenía `declararSinRed()`, pero la pantalla NO pintaba el botón: el
 * cajero no tenía cómo usarlo, y la decisión del founder quedaba en código muerto.
 *
 * Prueba ESTÁTICA, como [AngelPayPaymentScreenSalidaTest] (el repo no tiene infraestructura de Compose en JVM; el porqué
 * está escrito allí): fija el CABLEADO. El comportamiento —cuándo se ofrece, el permiso, el CAS— se prueba con el
 * ViewModel real en `AngelPayPaymentViewModelTest` («respaldo sin red - …»).
 */
class AngelPayPaymentScreenRespaldoSinRedTest {

    private val pantalla = File(
        "src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt",
    ).readText()

    /** La llamada a `ResultadoInciertoContent(` de la rama del `when` principal (no la de los @Preview). */
    private val llamada: String by lazy {
        val inicio = pantalla.indexOf("ResultadoInciertoContent(\n                        state = currentState,")
        require(inicio >= 0) { "No encontré la llamada a ResultadoInciertoContent( con state = currentState" }
        pantalla.substring(inicio, pantalla.indexOf(")\n", inicio) + 2)
    }

    /** El cuerpo del composable `ResultadoInciertoContent`. */
    private val cuerpo: String by lazy {
        val inicio = pantalla.indexOf("private fun ResultadoInciertoContent(")
        require(inicio >= 0) { "No encontré private fun ResultadoInciertoContent(" }
        pantalla.substring(inicio, pantalla.indexOf("private fun ErrorContent(", inicio))
    }

    /** El bloque que se pinta cuando el respaldo está disponible. */
    private val bloqueDelRespaldo: String by lazy {
        val inicio = cuerpo.indexOf("if (state.puedeDeclararSinRed)")
        require(inicio >= 0) { "ResultadoInciertoContent no tiene un bloque `if (state.puedeDeclararSinRed)`: el botón del respaldo no se pinta" }
        cuerpo.substring(inicio, cuerpo.indexOf("} else", inicio))
    }

    @Test
    fun `la pantalla conecta el boton del respaldo con declararSinRed del ViewModel`() {
        assertTrue(
            "La llamada a ResultadoInciertoContent debe pasar onDeclararSinRed = { viewModel.declararSinRed() }: sin eso el " +
                "botón existe y no hace nada.",
            Regex("""onDeclararSinRed = \{ viewModel\.declararSinRed\(\) }""").containsMatchIn(llamada),
        )
    }

    @Test
    fun `el boton del respaldo se pinta con puedeDeclararSinRed, llama al callback y se apaga mientras confirma`() {
        assertTrue("El botón del respaldo debe llamar a onDeclararSinRed.", bloqueDelRespaldo.contains("onClick = onDeclararSinRed"))
        assertTrue(
            "Mientras el CAS de la libreta corre, el botón se apaga (un segundo toque no se traga en silencio).",
            bloqueDelRespaldo.contains("enabled = !state.declarando"),
        )
    }

    @Test
    fun `con el respaldo ofrecido NO se pinta ademas el boton que declara por el servidor`() {
        // El respaldo sólo se ofrece cuando el servidor no contestó en toda la ventana: la declaración por el servidor
        // fallaría. Dos botones que parecen hacer lo mismo, y uno de ellos condenado a fallar, confunden al cajero.
        assertFalse(
            "Dentro del bloque del respaldo no puede estar el botón que declara por el servidor (onDeclarar).",
            bloqueDelRespaldo.contains("onDeclarar("),
        )
    }
}
