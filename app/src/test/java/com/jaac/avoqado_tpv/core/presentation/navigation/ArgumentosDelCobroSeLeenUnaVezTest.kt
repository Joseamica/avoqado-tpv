package com.jaac.avoqado_tpv.core.presentation.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 D.7 (2026-09-11). Las dos pantallas de cobro (Blumon y AngelPay) leían sus argumentos del
 * `previousBackStackEntry` EN CADA RECOMPOSICIÓN. Ese valor se calcula contra el TOPE actual del
 * NavController, no contra la entrada que lanzó el cobro, así que:
 *
 *  - **Camino 1:** cuando una solicitud remota B tapa la pantalla ya resuelta de A, el colector
 *    escribe los argumentos de B en el handle de Home y empuja otra entrada; la pantalla de A que
 *    va saliendo se recompone, lee los de B y se los pasa a SU ViewModel.
 *  - **Camino 2:** si un cobro remoto se abrió sobre Cobrar o Mesas y se sale con el botón atrás
 *    del sistema, nadie limpia sus argumentos; el siguiente cobro LOCAL desde esa pantalla nace
 *    remoto, con la propina y (en venta rápida) la orden de la solicitud anterior.
 *
 * Esta prueba es ESTÁTICA a propósito, como la de los botones de turnos: no comprueba un valor,
 * comprueba que nadie vuelva a leer los argumentos del lanzador fuera de
 * [congelarArgumentosDeCobro] (que los copia UNA vez a la entrada propia y los borra del lanzador).
 * El comportamiento del congelado se prueba en [PaymentNavigationStateTest].
 */
class ArgumentosDelCobroSeLeenUnaVezTest {

    private val navegacion = File(
        "src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt",
    ).readText()

    private fun cuerpoDe(ruta: String): String {
        val inicio = navegacion.indexOf("composable($ruta)")
        require(inicio >= 0) { "No encontré composable($ruta) en AppNavigation.kt" }
        val fin = navegacion.indexOf("composable(", inicio + 1)
        return navegacion.substring(inicio, if (fin < 0) navegacion.length else fin)
    }

    private val relecturaDelLanzador = Regex("""previousBackStackEntry\?\.savedStateHandle\?\.get""")

    @Test
    fun `P1 la pantalla de cobro Blumon no relee los argumentos del lanzador`() {
        val cuerpo = cuerpoDe("NavRoute.Payment.route")
        assertEquals(
            "La pantalla de cobro Blumon volvió a leer sus argumentos del previousBackStackEntry: la " +
                "pantalla que sale se lleva los del cobro siguiente. Léelos del handle congelado.",
            0,
            relecturaDelLanzador.findAll(cuerpo).count(),
        )
        assertTrue(
            "La pantalla de cobro Blumon debe congelar sus argumentos con congelarArgumentosDeCobro(...).",
            cuerpo.contains("congelarArgumentosDeCobro("),
        )
    }

    @Test
    fun `P1 la pantalla de cobro AngelPay no relee los argumentos del lanzador`() {
        val cuerpo = cuerpoDe("NavRoute.AngelPayPayment.route")
        assertEquals(
            "La pantalla de cobro AngelPay volvió a leer sus argumentos del previousBackStackEntry: la " +
                "pantalla que sale se lleva los del cobro siguiente. Léelos del handle congelado.",
            0,
            relecturaDelLanzador.findAll(cuerpo).count(),
        )
        assertTrue(
            "La pantalla de cobro AngelPay debe congelar sus argumentos con congelarArgumentosDeCobro(...).",
            cuerpo.contains("congelarArgumentosDeCobro("),
        )
    }

    @Test
    fun `toda clave que leen las pantallas de cobro se congela`() {
        val leidas = Regex("""argumentosDelCobro\.get<.+?>\("([^"]+)"\)""")
        listOf("NavRoute.Payment.route", "NavRoute.AngelPayPayment.route").forEach { ruta ->
            val claves = leidas.findAll(cuerpoDe(ruta)).map { it.groupValues[1] }.toList()
            assertTrue("No encontré lecturas de argumentos en composable($ruta)", claves.isNotEmpty())
            assertEquals(
                "composable($ruta) lee claves que congelarArgumentosDeCobro no copia: llegarían siempre nulas.",
                emptyList<String>(),
                claves.filterNot { it in CLAVES_DE_COBRO },
            )
        }
    }
}
