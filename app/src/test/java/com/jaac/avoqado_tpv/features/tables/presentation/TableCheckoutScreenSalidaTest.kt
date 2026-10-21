package com.jaac.avoqado_tpv.features.tables.presentation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🪑 Pantalla en blanco tras cobrar una mesa en EFECTIVO (QA en la N86, 20-oct; ya había pasado en la PAX en agosto).
 *
 * El cobro que salda la cuenta deja el recibo en pantalla y la salida al plano espera a que el mesero lo cierre. Mientras
 * tanto `releaseTable()` limpia la sesión (tras el HTTP de clearTable): si el guard «sin sesión» de esta pantalla navega,
 * el de `TableOrderScreen` navega 135 ms después, el antirrebote GLOBAL de `SafeNavigationHelper` se traga ese segundo
 * paso y la cuenta queda vacía, sin salida. Y el recibo desaparece con la pantalla.
 *
 * Prueba ESTÁTICA, como `AngelPayPaymentScreenSalidaTest`: las pruebas de JVM del repo no montan Compose. El recorrido real
 * se hace en la terminal (cobrar en efectivo una mesa ⇒ recibo ⇒ «Cerrar» ⇒ plano).
 */
class TableCheckoutScreenSalidaTest {

    private val pantalla = File(
        "src/main/java/com/jaac/avoqado_tpv/features/tables/presentation/TableCheckoutScreen.kt",
    ).readText()

    private val cuerpo: String by lazy {
        val inicio = pantalla.indexOf("fun TableCheckoutScreen(")
        val fin = pantalla.indexOf("private fun TableCheckoutScreenContent(")
        require(inicio >= 0 && fin > inicio) { "No encontré el cuerpo de TableCheckoutScreen" }
        pantalla.substring(inicio, fin)
    }

    @Test
    fun `el recibo se compone antes del guard sin sesion - sobrevive a que releaseTable limpie la sesion`() {
        val recibo = cuerpo.indexOf("CashReceiptDialog(")
        val guard = cuerpo.indexOf("if (activeSession == null)")
        assertTrue("No encontré el recibo o el guard en TableCheckoutScreen", recibo >= 0 && guard >= 0)
        assertTrue(
            "El recibo debe componerse ANTES del guard «sin sesión»: después de él desaparece en cuanto releaseTable limpia la " +
                "sesión, y el mesero no ve que cobró.",
            recibo < guard,
        )
    }

    @Test
    fun `sin sesion la pantalla sale al plano de UN salto y con la mesa liberada espera a que se cierre el recibo`() {
        assertTrue(
            "Sin sesión, el guard debe salir AL PLANO de un salto (onPaymentFinished), nunca un paso con onNavigateBack(): un " +
                "paso compite con el guard de la pantalla de abajo y el antirrebote global deja la pantalla en blanco (también al " +
                "regresar de tarjeta sin cobrar, Codex r1). Con la mesa liberada no navega: espera el recibo.",
            Regex("""if \(activeSession == null\) \{[^}]*?if \(!state\.tableReleased\) LaunchedEffect\(Unit\) \{ onPaymentFinished\(\) }""")
                .containsMatchIn(cuerpo),
        )
        assertTrue(
            "La salida al plano tras saldar la cuenta sigue siendo UN salto (onPaymentFinished) al cerrar el recibo.",
            Regex("""LaunchedEffect\(state\.tableReleased, state\.cashReceipt\) \{\s*if \(state\.tableReleased && state\.cashReceipt == null\) \{\s*onPaymentFinished\(\)""")
                .containsMatchIn(cuerpo),
        )
    }
}
