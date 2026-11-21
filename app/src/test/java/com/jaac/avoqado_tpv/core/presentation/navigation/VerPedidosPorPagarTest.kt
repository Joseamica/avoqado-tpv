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
 * D661 · ronda 4 (Important). El «Ver» de la banda de pagados en línea, en el recibo o el rechazo de un cobro con tarjeta, abría
 * «Pedidos por pagar» SIN sacar la entrada del cobro: su ViewModel (uno por entrada) seguía vivo debajo y, con el cobro siguiente,
 * dos escribían las banderas de un solo escritor de `PaymentStateHolder`. Ahora sale al Inicio primero, como toda otra salida del
 * cobro. Se prueba con un NavController real (Robolectric) y destinos de mentira con las MISMAS rutas de la app, como
 * [VolverALaCuentaDeLaMesaTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class VerPedidosPorPagarTest {

    @Navigator.Name("prueba")
    private class NavegadorDePrueba : Navigator<NavDestination>() {
        override fun createDestination(): NavDestination = NavDestination(this)
    }

    /** Arranca en el Inicio, como la app, y recorre [camino]. */
    private fun controlador(vararg camino: NavRoute): NavHostController {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        val navegador = NavegadorDePrueba()
        nav.navigatorProvider.addNavigator(navegador)
        val grafo = NavGraph(nav.navigatorProvider.getNavigator(NavGraphNavigator::class.java))
        listOf(
            NavRoute.Home, NavRoute.FastPaymentEntry, NavRoute.Checkout, NavRoute.Tables,
            NavRoute.Payment, NavRoute.AngelPayPayment, NavRoute.PorPagar,
        ).forEach { ruta -> grafo.addDestination(navegador.createDestination().apply { route = ruta.route }) }
        grafo.setStartDestination(NavRoute.Home.route)
        nav.graph = grafo
        camino.forEach { nav.navigate(it.route) }
        return nav
    }

    private fun NavController.tiene(ruta: NavRoute): Boolean = runCatching { getBackStackEntry(ruta.route) }.isSuccess

    @Test
    fun `P1 Ver desde el recibo o el rechazo de cada riel no deja ninguna entrada de cobro en la pila`() {
        // PAX (Blumon) desde Pago rápido y Nexgo (AngelPay) desde Cobrar, cada riel por una puerta de siempre.
        for ((antes, cobro) in listOf(NavRoute.FastPaymentEntry to NavRoute.Payment, NavRoute.Checkout to NavRoute.AngelPayPayment)) {
            val nav = controlador(antes, cobro)

            nav.verPedidosPorPagar(ventaEnCurso = false)

            assertEquals(NavRoute.PorPagar.route, nav.currentDestination?.route)
            assertFalse("${cobro.route} sigue en la pila: su ViewModel seguiría vivo bajo «Pedidos por pagar»", nav.tiene(cobro))
            assertFalse("${antes.route} sigue en la pila: «atrás» volvería a una venta ya cobrada", nav.tiene(antes))
            assertTrue(nav.popBackStack())
            assertEquals("«Atrás» desde «Pedidos por pagar» regresa al Inicio", NavRoute.Home.route, nav.currentDestination?.route)
        }
    }

    @Test
    fun `P1 con una venta que acaba de arrancar en el cobro Ver no hace nada`() {
        // La banda tarda hasta 250 ms en esconderse cuando arranca una venta; un toque en ese rato no le quita la pantalla al cobro.
        for (cobro in listOf(NavRoute.Payment, NavRoute.AngelPayPayment)) {
            val nav = controlador(NavRoute.FastPaymentEntry, cobro)

            nav.verPedidosPorPagar(ventaEnCurso = true)

            assertEquals(cobro.route, nav.currentDestination?.route)
            assertFalse(nav.tiene(NavRoute.PorPagar))
        }
    }

    @Test
    fun `regresion desde otra pantalla Ver pone Pedidos por pagar encima y atras regresa ahi`() {
        val nav = controlador(NavRoute.Tables)

        nav.verPedidosPorPagar(ventaEnCurso = true) // la venta en curso sólo cuenta en el cobro con tarjeta

        assertEquals(NavRoute.PorPagar.route, nav.currentDestination?.route)
        assertTrue(nav.popBackStack())
        assertEquals(NavRoute.Tables.route, nav.currentDestination?.route)
    }

    @Test
    fun `regresion Ver desde Pedidos por pagar no la apila dos veces`() {
        val nav = controlador(NavRoute.PorPagar)

        nav.verPedidosPorPagar(ventaEnCurso = false)

        assertEquals(NavRoute.PorPagar.route, nav.currentDestination?.route)
        assertTrue(nav.popBackStack())
        assertEquals(NavRoute.Home.route, nav.currentDestination?.route)
    }

    /** El montaje de la banda no se puede ejecutar aquí (`createComposeRule` sólo está en `androidTest`): se lee la fuente. */
    @Test
    fun `P1 el Ver de la banda usa verPedidosPorPagar con la venta en curso leida al tocar`() {
        val navegacion = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
        val ver = navegacion.substringAfter("onVer = {").substringBefore("onEntendido")
        assertTrue(ver, ver.contains("navController.verPedidosPorPagar("))
        assertTrue(ver, ver.contains("paymentStateProvider.isChargeAttemptActive() || paymentStateProvider.isCharging()"))
        assertFalse(ver, ver.contains("navigate(NavRoute.PorPagar.route)"))
    }
}
