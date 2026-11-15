package com.jaac.avoqado_tpv.core.presentation.navigation

import android.app.Application
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.Navigator
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D9 (encargo 49276c, 15-nov). Mesa → «Pagar» → «Tarjeta» con un abono → aprobado → «Continuar pagando» dejaba la
 * pila `[Tables, TableOrder(viejo), TableCheckout(viejo), TableOrder(nuevo)]`: «atrás» en la cuenta nueva caía en la
 * «Cobrar mesa» vieja, con el saldo de ANTES de la tarjeta, y su «Efectivo» cobraba de más.
 *
 * Se prueba con un NavController real (Robolectric) y destinos de mentira con las MISMAS rutas de la app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class VolverALaCuentaDeLaMesaTest {

    @Navigator.Name("prueba")
    private class NavegadorDePrueba : Navigator<NavDestination>() {
        override fun createDestination(): NavDestination = NavDestination(this)
    }

    private fun controladorEnElCobroDeLaMesa(): NavHostController {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        val navegador = NavegadorDePrueba()
        nav.navigatorProvider.addNavigator(navegador)
        val grafo = NavGraph(nav.navigatorProvider.getNavigator(NavGraphNavigator::class.java))
        // Sin `NavRoute.Home`: si es la PRIMERA ruta que se toca en el proceso, el companion de NavRoute (que lee
        // `Home.route`) se inicializa con `Home` todavía en null y truena (ExceptionInInitializerError, CI del PR #52).
        // La pila empieza en el plano de mesas, que es lo que importa aquí.
        listOf(NavRoute.Tables, NavRoute.TableOrder, NavRoute.TableCheckout, NavRoute.Payment)
            .forEach { ruta -> grafo.addDestination(navegador.createDestination().apply { route = ruta.route }) }
        grafo.setStartDestination(NavRoute.Tables.route)
        nav.graph = grafo
        // El camino real: Mesas → cuenta de la mesa → «Cobrar mesa» → «Cobrar» (tarjeta).
        nav.navigate(NavRoute.TableOrder.route)
        nav.navigate(NavRoute.TableCheckout.route)
        nav.navigate(NavRoute.Payment.route)
        return nav
    }

    private fun NavController.tiene(ruta: NavRoute): Boolean =
        runCatching { getBackStackEntry(ruta.route) }.isSuccess

    @Test
    fun `P1 tras continuar pagando no queda ningun cobro de mesa viejo en la pila`() {
        val nav = controladorEnElCobroDeLaMesa()

        nav.volverALaCuentaDeLaMesa()

        assertEquals(NavRoute.TableOrder.route, nav.currentDestination?.route)
        assertFalse("«Cobrar» sigue en la pila", nav.tiene(NavRoute.Payment))
        assertFalse(
            "La «Cobrar mesa» vieja sigue en la pila: «atrás» ofrecería el saldo de antes de la tarjeta",
            nav.tiene(NavRoute.TableCheckout),
        )
    }

    @Test
    fun `P1 atras desde la cuenta nueva regresa al plano de mesas`() {
        val nav = controladorEnElCobroDeLaMesa()

        nav.volverALaCuentaDeLaMesa()
        assertTrue(nav.popBackStack())

        assertEquals(
            "«Atrás» desde la cuenta nueva debe llevar al plano de mesas, no a la cuenta ni al cobro viejos",
            NavRoute.Tables.route,
            nav.currentDestination?.route,
        )
    }

    @Test
    fun `P1 continuar pagando en Mesas usa volverALaCuentaDeLaMesa`() {
        val navegacion = File(
            "src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt",
        ).readText()
        val inicio = navegacion.indexOf("onNavigateToOrder = { continueOrderId, continueTableId ->")
        require(inicio >= 0) { "No encontré onNavigateToOrder del cobro Blumon en AppNavigation.kt" }
        val ramaDeMesas = navegacion.substring(
            navegacion.indexOf("} else if (cameFromTables) {", inicio),
            navegacion.indexOf("} else {", navegacion.indexOf("} else if (cameFromTables) {", inicio)),
        )
        assertTrue(
            "La rama de Mesas de «Continuar pagando» debe regresar con volverALaCuentaDeLaMesa(): sólo quitar " +
                "«Cobrar» deja la «Cobrar mesa» vieja debajo",
            ramaDeMesas.contains("volverALaCuentaDeLaMesa()"),
        )
    }
}
