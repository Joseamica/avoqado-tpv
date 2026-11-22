package com.jaac.avoqado_tpv.core.presentation.navigation

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavBackStackEntry
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D666 (hz:b433ec6d:D666, 21-nov). «Abrir caja» desde el error «No hay turno abierto» de la pantalla de cobro abría Turnos
 * ENCIMA del cobro: la entrada del cobro (y su ViewModel) seguía viva debajo y escribía las banderas «cobro en curso» y
 * «ventana del dinero» de [com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder], que deben
 * tener UN solo escritor: el ViewModel viejo podía apagarlas a media venta nueva y dejar que un cobro del POS le quitara la
 * pantalla.
 *
 * Ahora la entrada vieja sale de la pila; si el cobro era de esta terminal, una entrada NUEVA de la misma venta (mismos
 * argumentos) queda debajo de Turnos sin componerse, y al volver se retoma como antes (Codex r1 #2: la venta serializada ya
 * está vendida en el servidor y no se puede volver a escanear). Un cobro del POS no se retoma: ya recibió su desenlace.
 *
 * Se prueba con un NavController real (Robolectric) y destinos de mentira con las MISMAS rutas de la app, como
 * [VolverALaCuentaDeLaMesaTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class TurnosDesdeElCobroTest {

    // Turnos se toca PRIMERO, antes que cualquier argumento de una prueba: si `Home` o `Checkout` fueran la primera ruta que se toca
    // en el proceso, el companion de NavRoute la leería todavía en null y tronaría (ver VolverALaCuentaDeLaMesaTest). La corrida
    // roja del 21-nov tronó así en `controlador(NavRoute.Checkout)`, que evalúa su argumento antes de entrar.
    init {
        NavRoute.Shifts.route
    }

    @Navigator.Name("prueba")
    private class NavegadorDePrueba : Navigator<NavDestination>() {
        override fun createDestination(): NavDestination = NavDestination(this)
    }

    /** La pila `[Inicio, …pila]`. */
    private fun controlador(vararg pila: NavRoute): NavHostController {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        // Sin esto, el SavedStateHandle de una entrada truena (Codex r2 #4): la app lo da con su Activity.
        nav.setViewModelStore(ViewModelStore())
        val navegador = NavegadorDePrueba()
        nav.navigatorProvider.addNavigator(navegador)
        val grafo = NavGraph(nav.navigatorProvider.getNavigator(NavGraphNavigator::class.java))
        listOf(NavRoute.Shifts, NavRoute.Payment, NavRoute.AngelPayPayment, NavRoute.SerializedSale, NavRoute.Checkout, NavRoute.Home)
            .forEach { ruta -> grafo.addDestination(navegador.createDestination().apply { route = ruta.route }) }
        grafo.setStartDestination(NavRoute.Home.route)
        nav.graph = grafo
        pila.forEach { nav.navigate(it.route) }
        return nav
    }

    /** Abre el cobro como la app: el lanzador deja los argumentos y la entrada del cobro los congela en SU handle. */
    private fun NavHostController.abrirCobro(ruta: NavRoute, vararg argumentos: Pair<String, Any?>): NavBackStackEntry {
        val lanzador = checkNotNull(currentBackStackEntry).savedStateHandle
        argumentos.forEach { (clave, valor) -> lanzador[clave] = valor }
        navigate(ruta.route)
        val entrada = checkNotNull(currentBackStackEntry)
        congelarArgumentosDeCobro(propio = entrada.savedStateHandle, lanzador = lanzador)
        return entrada
    }

    private fun NavController.rutas(): List<String?> =
        currentBackStack.value.mapNotNull { it.destination.route }

    private fun NavController.sigueEnLaPila(entrada: NavBackStackEntry): Boolean =
        currentBackStack.value.any { it.id == entrada.id }

    @Test
    fun `P1 PAX abrir caja desde el cobro de Cobrar no deja vivo el cobro viejo y al volver retoma la misma venta`() {
        val nav = controlador(NavRoute.Checkout)
        val viejo = nav.abrirCobro(NavRoute.Payment, "initialAmount" to "150.00", "orderId" to "orden-1", "entryPoint" to "checkout")

        nav.irATurnosDesdeElCobro(NavRoute.Payment.route, viejo.savedStateHandle, retomar = true)

        assertEquals(NavRoute.Shifts.route, nav.currentDestination?.route)
        assertFalse("La entrada del cobro viejo sigue en la pila: su ViewModel vive y escribe las banderas", nav.sigueEnLaPila(viejo))
        assertEquals(listOf(NavRoute.Home.route, NavRoute.Checkout.route, NavRoute.Payment.route, NavRoute.Shifts.route), nav.rutas())

        assertTrue(nav.popBackStack())
        val nuevo = checkNotNull(nav.currentBackStackEntry)
        assertEquals(NavRoute.Payment.route, nuevo.destination.route)
        assertNotEquals(viejo.id, nuevo.id)
        val argumentos = congelarArgumentosDeCobro(nuevo.savedStateHandle, nav.previousBackStackEntry?.savedStateHandle)
        assertEquals("orden-1", argumentos.get<String>("orderId"))
        assertEquals("150.00", argumentos.get<String>("initialAmount"))
        assertEquals("checkout", argumentos.get<String>("entryPoint"))
        assertFalse("El lanzador no debe recibir argumentos de cobro", nav.getBackStackEntry(NavRoute.Checkout.route).savedStateHandle.contains("orderId"))
    }

    @Test
    fun `P1 Nexgo abrir turno desde el cobro no deja vivo el cobro viejo y al volver retoma la misma venta`() {
        val nav = controlador(NavRoute.Checkout)
        val viejo = nav.abrirCobro(NavRoute.AngelPayPayment, "initialAmount" to "80.00", "orderId" to "orden-2", "entryPoint" to "checkout")

        nav.irATurnosDesdeElCobro(NavRoute.AngelPayPayment.route, viejo.savedStateHandle, retomar = true)

        assertFalse(nav.sigueEnLaPila(viejo))
        assertEquals(listOf(NavRoute.Home.route, NavRoute.Checkout.route, NavRoute.AngelPayPayment.route, NavRoute.Shifts.route), nav.rutas())
        assertTrue(nav.popBackStack())
        val nuevo = checkNotNull(nav.currentBackStackEntry)
        assertNotEquals(viejo.id, nuevo.id)
        assertEquals("orden-2", congelarArgumentosDeCobro(nuevo.savedStateHandle, null).get<String>("orderId"))
    }

    @Test
    fun `P1 venta serializada - al volver de Turnos sigue el mismo folio, no el escaner`() {
        val nav = controlador(NavRoute.SerializedSale)
        val viejo = nav.abrirCobro(
            NavRoute.Payment,
            "initialAmount" to "100.00", "orderId" to "orden-sim", "serialNumber" to "8952000000000000001", "skipLocalOrderValidation" to true,
        )

        nav.irATurnosDesdeElCobro(NavRoute.Payment.route, viejo.savedStateHandle, retomar = true)
        assertTrue(nav.popBackStack())

        val argumentos = congelarArgumentosDeCobro(checkNotNull(nav.currentBackStackEntry).savedStateHandle, null)
        assertEquals(NavRoute.Payment.route, nav.currentDestination?.route)
        assertEquals("orden-sim", argumentos.get<String>("orderId"))
        assertEquals("8952000000000000001", argumentos.get<String>("serialNumber"))
        assertEquals(true, argumentos.get<Boolean>("skipLocalOrderValidation"))
    }

    @Test
    fun `P1 Nexgo tras un intento no se retoma con una pantalla nueva - regresa a donde se lanzo`() {
        val nav = controlador(NavRoute.Checkout)
        val viejo = nav.abrirCobro(NavRoute.AngelPayPayment, "initialAmount" to "80.00", "orderId" to "orden-3", "entryPoint" to "checkout")

        nav.irATurnosDesdeElCobro(NavRoute.AngelPayPayment.route, viejo.savedStateHandle, retomar = false)

        assertEquals(listOf(NavRoute.Home.route, NavRoute.Checkout.route, NavRoute.Shifts.route), nav.rutas())
        assertTrue(nav.popBackStack())
        assertEquals(NavRoute.Checkout.route, nav.currentDestination?.route)
    }

    @Test
    fun `P1 un cobro del POS no se retoma - Turnos se abre sin pantalla de cobro debajo`() {
        val nav = controlador(NavRoute.Checkout)
        val viejo = nav.abrirCobro(NavRoute.Payment, "initialAmount" to "50.00", "paymentSource" to "SOCKET", "socketRequestId" to "sol-1")

        nav.irATurnosDesdeElCobro(NavRoute.Payment.route, viejo.savedStateHandle, retomar = true)

        assertEquals("el origen del POS manda, aunque quien llama pida retomar", listOf(NavRoute.Home.route, NavRoute.Checkout.route, NavRoute.Shifts.route), nav.rutas())
    }

    @Test
    fun `P2 un cobro del POS abierto encima de Turnos no deja dos Turnos seguidos`() {
        val nav = controlador(NavRoute.Shifts)
        val viejo = nav.abrirCobro(NavRoute.Payment, "initialAmount" to "50.00", "paymentSource" to "SOCKET", "socketRequestId" to "sol-2")

        nav.irATurnosDesdeElCobro(NavRoute.Payment.route, viejo.savedStateHandle, retomar = true)

        assertEquals(listOf(NavRoute.Home.route, NavRoute.Shifts.route), nav.rutas())
    }

    // ── Estáticas: AppNavigation usa ESTE camino ─────────────────────────────────────────────────────────────────────

    private val navegacion = File(
        "src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt",
    ).readText()

    private fun saltoPax(): String = navegacion.substring(navegacion.indexOf("composable(NavRoute.Payment.route)"))
        .substringAfter("onNavigateToShifts = {").substringBefore("onNavigateToNewOrder = {")

    private fun saltoNexgo(): String = navegacion.substring(navegacion.indexOf("composable(NavRoute.AngelPayPayment.route)"))
        .substringAfter("onNavigateToShifts = {").substringBefore("onStartNewPaymentOverride = ")

    @Test
    fun `P1 los dos saltos a Turnos desde un cobro usan irATurnosDesdeElCobro con los argumentos congelados`() {
        assertTrue(saltoPax().contains("navController.irATurnosDesdeElCobro(NavRoute.Payment.route, argumentosDelCobro, retomar = true)"))
        // Codex r2 #1: tras un intento, la pantalla Nexgo guarda la llave del QR de cripto: no se retoma con una nueva.
        assertTrue(saltoNexgo().contains("navController.irATurnosDesdeElCobro(NavRoute.AngelPayPayment.route, argumentosDelCobro, retomar = !angelVm.yaSeIntentoCobrar())"))
        assertFalse(saltoPax().contains("navigate(NavRoute.Shifts.route)"))
        assertFalse(saltoNexgo().contains("navigate(NavRoute.Shifts.route)"))
    }

    @Test
    fun `P1 D36 el salto a Turnos no retira la venta - se retoma al volver y su atras aplica D36`() {
        assertFalse(saltoPax().contains("alRegresarSinCobrar"))
        assertFalse(saltoNexgo().contains("alRegresarSinCobrar"))
    }
}
