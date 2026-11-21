package com.jaac.avoqado_tpv.core.presentation.navigation

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.Navigator
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D667 (21-nov) · TPV · PAGO RÁPIDO. Con la PAX lenta («Preparando sistema de pagos») el cajero ve $0 (Ruling 115), vuelve a
 * teclear y a tocar «Cobrar»: arrancaban dos envíos, los dos navegaban, el segundo escribía su importe en la entrada del PRIMER
 * cobro y apilaba otro encima. Al salir del segundo con «atrás» quedaba el primero listo para cobrar.
 *
 * Se prueba con un NavController real (Robolectric) y destinos de mentira con las MISMAS rutas de la app, como
 * [VolverALaCuentaDeLaMesaTest]. La espera del sistema de pagos es un [CompletableDeferred], igual que en `AppNavigation`:
 * `if (!listo) return@launch` y luego `abrir`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PagoRapidoUnEnvioTest {

    @Navigator.Name("prueba")
    private class NavegadorDePrueba : Navigator<NavDestination>() {
        override fun createDestination(): NavDestination = NavDestination(this)
    }

    /** Pila `[Pago rápido]`, con estado como el NavHost para leer el `savedStateHandle` de cada entrada. Sin `NavRoute.Home`. */
    private fun enPagoRapido(): NavHostController {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        nav.setLifecycleOwner(object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        })
        nav.setViewModelStore(ViewModelStore())
        val navegador = NavegadorDePrueba()
        nav.navigatorProvider.addNavigator(navegador)
        val grafo = NavGraph(nav.navigatorProvider.getNavigator(NavGraphNavigator::class.java))
        listOf(NavRoute.FastPaymentEntry, NavRoute.Payment)
            .forEach { ruta -> grafo.addDestination(navegador.createDestination().apply { route = ruta.route }) }
        grafo.setStartDestination(NavRoute.FastPaymentEntry.route)
        nav.graph = grafo
        return nav
    }

    /** Cobros en lo alto de la pila (aquí nunca hay más de dos): `[Pago rápido]` = 0, `[…, Cobro]` = 1, `[…, Cobro, Cobro]` = 2. */
    private fun NavHostController.cobrosEnLaPila(): Int =
        listOfNotNull(currentBackStackEntry, previousBackStackEntry).count { it.destination.route == NavRoute.Payment.route }

    /** Lo mismo que `onAmountSubmit` con tarjeta: espera al sistema de pagos y, si está listo, abre el cobro. */
    private fun EnvioDePagoRapido.cobrar(scope: CoroutineScope, nav: NavHostController, importe: String, listo: CompletableDeferred<Boolean>) =
        lanzar { turno ->
            scope.launch {
                if (!listo.await()) return@launch
                abrir(turno, nav, NavRoute.Payment.route, importe, soloEfectivo = false)
            }
        }

    /** Lo mismo que `onAmountSubmit` en sólo efectivo (sin red, D7): no espera al lector. */
    private fun EnvioDePagoRapido.cobrarEnEfectivo(scope: CoroutineScope, nav: NavHostController, importe: String) =
        lanzar(sinEspera = true) { turno ->
            scope.launch { abrir(turno, nav, NavRoute.Payment.route, importe, soloEfectivo = true) }
        }

    @Test
    fun `P1 dos Cobrar mientras se prepara el sistema de pagos abren un solo cobro con el primer importe`() = runTest {
        val nav = enPagoRapido()
        val envio = EnvioDePagoRapido(NavRoute.FastPaymentEntry.route)
        val listo = CompletableDeferred<Boolean>()

        assertTrue(envio.cobrar(this, nav, "100.00", listo))
        advanceUntilIdle() // el primero espera a la PAX
        val segundo = envio.cobrar(this, nav, "250.00", listo) // el cajero ve $0, vuelve a teclear y a cobrar
        listo.complete(true)
        advanceUntilIdle()

        assertEquals("Debe haber UNA sola pantalla de cobro en la pila", 1, nav.cobrosEnLaPila())
        assertFalse("Un segundo «Cobrar» mientras el primero espera debe ignorarse", segundo)
        assertEquals(NavRoute.Payment.route, nav.currentDestination?.route)
        val pagoRapido = nav.getBackStackEntry(NavRoute.FastPaymentEntry.route).savedStateHandle
        assertEquals("El cobro abre con el importe del primer «Cobrar»", "100.00", pagoRapido.get<String>("initialAmount"))
        assertFalse(
            "La entrada del cobro no debe recibir argumentos de un segundo envío",
            nav.currentBackStackEntry!!.savedStateHandle.contains("initialAmount"),
        )
    }

    @Test
    fun `P1 al abrir el cobro se suelta el candado pero no se apila otro cobro encima`() = runTest {
        val nav = enPagoRapido()
        val envio = EnvioDePagoRapido(NavRoute.FastPaymentEntry.route)
        val listo = CompletableDeferred(true)

        assertTrue(envio.cobrar(this, nav, "100.00", listo))
        advanceUntilIdle()
        assertEquals(1, nav.cobrosEnLaPila())

        // Un toque en la pantalla que se está yendo (la animación de salida): ya no hay espera, pero Pago rápido no está arriba.
        assertTrue("Tras abrir el cobro el candado queda suelto", envio.cobrar(this, nav, "250.00", listo))
        advanceUntilIdle()

        assertEquals("Desde una pantalla que ya no está arriba no se abre otro cobro", 1, nav.cobrosEnLaPila())
        assertFalse(nav.currentBackStackEntry!!.savedStateHandle.contains("initialAmount"))
    }

    @Test
    fun `P1 si el sistema de pagos falla se suelta el candado y el siguiente Cobrar abre el cobro`() = runTest {
        val nav = enPagoRapido()
        val envio = EnvioDePagoRapido(NavRoute.FastPaymentEntry.route)
        val falla = CompletableDeferred<Boolean>()

        assertTrue(envio.cobrar(this, nav, "100.00", falla))
        advanceUntilIdle()
        falla.complete(false) // «Sistema de pagos no inicializado»
        advanceUntilIdle()
        assertEquals(0, nav.cobrosEnLaPila())

        assertTrue("Tras la falla el candado queda suelto", envio.cobrar(this, nav, "250.00", CompletableDeferred(true)))
        advanceUntilIdle()

        assertEquals(1, nav.cobrosEnLaPila())
        assertEquals("250.00", nav.getBackStackEntry(NavRoute.FastPaymentEntry.route).savedStateHandle.get<String>("initialAmount"))
    }

    @Test
    fun `P1 salir de la pantalla mientras espera suelta el candado y no abre nada`() = runTest {
        val nav = enPagoRapido()
        val envio = EnvioDePagoRapido(NavRoute.FastPaymentEntry.route)
        val pantalla = CoroutineScope(StandardTestDispatcher(testScheduler)) // el rememberCoroutineScope de la pantalla
        val listo = CompletableDeferred<Boolean>()

        assertTrue(envio.cobrar(pantalla, nav, "100.00", listo))
        advanceUntilIdle()
        pantalla.cancel() // «atrás»: la pantalla sale de composición
        listo.complete(true)
        advanceUntilIdle()

        assertEquals("Un envío de una pantalla que se cerró no abre el cobro", 0, nav.cobrosEnLaPila())
        assertTrue("Al salir el candado queda suelto", envio.cobrar(this, nav, "250.00", CompletableDeferred(true)))
        advanceUntilIdle()
        assertEquals(1, nav.cobrosEnLaPila())
    }

    @Test
    fun `P1 si se cae la red mientras uno espera al lector el Cobrar en solo efectivo abre ya y el primero no apila otro`() = runTest {
        // Codex r1 #1: el candado no puede dejar a la caja sin cobrar en efectivo mientras otro «Cobrar» espera al lector.
        val nav = enPagoRapido()
        val envio = EnvioDePagoRapido(NavRoute.FastPaymentEntry.route)
        val lector = CompletableDeferred<Boolean>()

        assertTrue(envio.cobrar(this, nav, "100.00", lector))
        advanceUntilIdle() // espera al lector
        val efectivo = envio.cobrarEnEfectivo(this, nav, "250.00")
        advanceUntilIdle()

        assertTrue("Un «Cobrar» en sólo efectivo no espera al lector: no se ignora", efectivo)
        assertEquals(1, nav.cobrosEnLaPila())
        val pagoRapido = nav.getBackStackEntry(NavRoute.FastPaymentEntry.route).savedStateHandle
        assertEquals("250.00", pagoRapido.get<String>("initialAmount"))
        assertEquals(true, pagoRapido.get<Boolean>("soloEfectivo"))

        lector.complete(true) // el lector terminó de prepararse después
        advanceUntilIdle()

        assertEquals("El envío que esperaba al lector ya no apila otro cobro", 1, nav.cobrosEnLaPila())
        assertFalse(nav.currentBackStackEntry!!.savedStateHandle.contains("initialAmount"))
    }

    @Test
    fun `P1 tras abrir el efectivo y regresar al teclado el envio que esperaba al lector ya no abre nada`() = runTest {
        // Codex r2: el cajero sale del cobro en efectivo (atrás, aun durante la animación) antes de que el lector termine. Antes
        // el envío que esperaba seguía con permiso de abrir y abría solo los $100 sin otro toque.
        val nav = enPagoRapido()
        val envio = EnvioDePagoRapido(NavRoute.FastPaymentEntry.route)
        val lector = CompletableDeferred<Boolean>()

        assertTrue(envio.cobrar(this, nav, "100.00", lector))
        advanceUntilIdle()
        assertTrue(envio.cobrarEnEfectivo(this, nav, "250.00"))
        advanceUntilIdle()
        assertEquals(1, nav.cobrosEnLaPila())
        assertTrue(nav.popBackStack()) // atrás: regresa a Pago rápido con la pantalla (y su scope) vivos
        assertEquals(NavRoute.FastPaymentEntry.route, nav.currentDestination?.route)

        lector.complete(true)
        advanceUntilIdle()

        assertEquals("El envío viejo de $100 no debe abrir un cobro sin otro toque", 0, nav.cobrosEnLaPila())
        assertEquals(NavRoute.FastPaymentEntry.route, nav.currentDestination?.route)

        // Y el candado no se quedó pegado: el siguiente «Cobrar» abre.
        assertTrue(envio.cobrar(this, nav, "300.00", CompletableDeferred(true)))
        advanceUntilIdle()
        assertEquals(1, nav.cobrosEnLaPila())
        assertEquals("300.00", nav.getBackStackEntry(NavRoute.FastPaymentEntry.route).savedStateHandle.get<String>("initialAmount"))
    }

    @Test
    fun `regresion D7 abrir lleva solo efectivo a los argumentos del cobro`() = runTest {
        val nav = enPagoRapido()

        assertTrue(EnvioDePagoRapido(NavRoute.FastPaymentEntry.route).cobrarEnEfectivo(this, nav, "80.00"))
        advanceUntilIdle()
        assertEquals(1, nav.cobrosEnLaPila())

        val pagoRapido = nav.getBackStackEntry(NavRoute.FastPaymentEntry.route).savedStateHandle
        assertEquals("80.00", pagoRapido.get<String>("initialAmount"))
        assertEquals(true, pagoRapido.get<Boolean>("soloEfectivo"))
        assertEquals(false, pagoRapido.get<Boolean>("skipReview"))
    }

    @Test
    fun `P1 las dos entradas de Pago rapido pasan por el candado y abren con abrir`() {
        val navegacion = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
        // (pantalla, dónde empieza, dónde termina el envío, el candado con SU ruta de origen)
        for ((pantalla, inicio, fin, candado) in listOf(
            listOf(
                "composable(NavRoute.Home.route)", "onStartPaymentWithAmount = { amount ->", "onNavigateToShifts = {",
                "val envioDePagoRapido = remember { EnvioDePagoRapido(NavRoute.Home.route) }",
            ),
            listOf(
                "composable(NavRoute.FastPaymentEntry.route)", "onAmountSubmit = { amount ->", "// Unified Checkout",
                "val envio = remember { EnvioDePagoRapido(NavRoute.FastPaymentEntry.route) }",
            ),
        )) {
            val desdePantalla = navegacion.indexOf(pantalla)
            require(desdePantalla >= 0) { "No encontré «$pantalla» en AppNavigation.kt" }
            val desde = navegacion.indexOf(inicio, desdePantalla)
            require(desde >= 0) { "No encontré «$inicio» en AppNavigation.kt" }
            assertTrue(
                "«$pantalla» debe crear su candado con SU ruta de origen (si no, abrir() nunca abre o abre desde otra pantalla)",
                navegacion.substring(desdePantalla, desde).contains(candado),
            )
            val bloque = navegacion.substring(desde, navegacion.indexOf(fin, desde))
            assertTrue(
                "«$inicio» debe arrancar con lanzar(sinEspera = soloEfectivo) { turno -> … } del candado",
                bloque.contains(".lanzar(sinEspera = soloEfectivo) { turno ->"),
            )
            assertTrue(
                "«$inicio» debe abrir el cobro con abrir(turno, …), que revisa el turno y que siga arriba",
                bloque.contains(".abrir(turno, navController"),
            )
            assertFalse("«$inicio» no debe navegar al cobro por fuera del candado", bloque.contains("navController.navigate(getPaymentRoute())"))
        }
    }
}
