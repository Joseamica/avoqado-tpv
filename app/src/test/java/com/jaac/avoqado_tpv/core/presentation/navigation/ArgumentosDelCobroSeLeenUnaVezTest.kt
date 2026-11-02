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

    /**
     * 💵 D2 «Cobrar sin red» (21-oct-2026): la ruta Blumon decidía esperar al lector ANTES de leer sus
     * argumentos, así que no podía saber que el cobro iba en sólo efectivo. Ahora congela primero y la
     * compuerta respeta `soloEfectivo`.
     */
    @Test
    fun `D2 la ruta Blumon congela sus argumentos antes de la compuerta del lector y respeta soloEfectivo`() {
        assertTrue("\"soloEfectivo\" debe estar en CLAVES_DE_COBRO", "soloEfectivo" in CLAVES_DE_COBRO)
        val cuerpo = cuerpoDe("NavRoute.Payment.route")
        val congela = cuerpo.indexOf("congelarArgumentosDeCobro(")
        val compuerta = cuerpo.indexOf("awaitPaxPaymentReady(")
        assertTrue("No encontré la compuerta del lector en la ruta Blumon", compuerta >= 0)
        assertTrue(
            "La ruta Blumon espera al lector antes de leer sus argumentos: no puede saber que el cobro es sólo efectivo.",
            congela in 0 until compuerta,
        )
        assertTrue(
            "La compuerta del lector de la ruta Blumon debe decidirse con rutaDePagoEsperaAlLector(…, soloEfectivo) " +
                "(su comportamiento se prueba en SoloEfectivoSinRedTest).",
            cuerpo.contains("rutaDePagoEsperaAlLector(") && cuerpo.contains("soloEfectivo = soloEfectivo"),
        )
    }

    @Test
    fun `D2 Cobrar usa las reglas puras para no esperar al lector en solo efectivo`() {
        val cuerpo = cuerpoDe("NavRoute.Checkout.route")
        assertTrue(
            "La entrada desde Cobrar debe decidir con cobrarSaltaElLector(…) y la regla de la tarjeta tarjetaSinRed(…) " +
                "(su comportamiento se prueba en SoloEfectivoSinRedTest), y saltarse awaitPaxPaymentReady con soloEfectivo.",
            cuerpo.contains("cobrarSaltaElLector(") && cuerpo.contains("tarjetaSinRed(") &&
                cuerpo.contains("!soloEfectivo && !awaitPaxPaymentReady("),
        )
        assertTrue(
            "La entrada desde Cobrar debe pasarle \"soloEfectivo\" a la pantalla de cobro.",
            cuerpo.contains("set(\"soloEfectivo\", soloEfectivo)"),
        )
    }

    /**
     * 💵 C2b «Cobrar» sin red (21-oct-2026): la venta local va SIEMPRE en sólo efectivo (sin esperar al lector de Blumon, que sin red no
     * termina) y lleva su venta guardada en `"ventaLocalJson"`, que se congela con los demás argumentos del cobro.
     */
    @Test
    fun `C2b la venta local de Cobrar va en sólo efectivo y lleva su venta en ventaLocalJson`() {
        assertTrue("\"ventaLocalJson\" debe estar en CLAVES_DE_COBRO", "ventaLocalJson" in CLAVES_DE_COBRO)
        val cuerpo = cuerpoDe("NavRoute.Checkout.route")
        assertTrue(
            "Una venta local de Cobrar debe ir en sólo efectivo aunque haya lector listo o red: " +
                "`val soloEfectivo = payload is …VentaLocal || cobrarSaltaElLector(…)`.",
            Regex("""val soloEfectivo = payload is [\w.]*PaymentNavigationPayload\.VentaLocal \|\|\s*cobrarSaltaElLector\(""")
                .containsMatchIn(cuerpo),
        )
        assertTrue(
            "La entrada desde Cobrar debe pasarle la venta guardada a la pantalla de cobro en \"ventaLocalJson\".",
            cuerpo.contains("set(\"ventaLocalJson\", payload.ventaLocalJson)"),
        )
        val limpieza = navegacion.substring(navegacion.indexOf("fun clearPaymentArgs("))
        assertTrue(
            "clearPaymentArgs debe quitar \"ventaLocalJson\": un cobro rápido nuevo no puede heredar la venta guardada de otro.",
            limpieza.substring(0, limpieza.indexOf("\n}")).contains("handle.remove<String>(\"ventaLocalJson\")"),
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
