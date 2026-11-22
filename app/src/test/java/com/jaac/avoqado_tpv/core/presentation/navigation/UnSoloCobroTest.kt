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
import com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * D671 (21-nov) · TPV SOLA · COBRAR y VENTA DE SIMs. El hueco de D667 en las otras dos entradas que esperan al lector: con la PAX
 * preparándolo con la app abierta (renovación de las 23 h, reparación) cada toque arrancaba su espera y, al estar listo, los dos
 * navegaban: dos pantallas de cobro, la segunda con los argumentos escritos en la entrada de la primera. Mesas ya tenía su reserva
 * (D9, `TableCheckoutViewModelTest.d9_con_TARJETA_abriendose_el_efectivo_no_cobra`).
 *
 * NavController real (Robolectric) con las MISMAS rutas de la app, como [PagoRapidoUnEnvioTest]. La espera del lector es un
 * [CompletableDeferred]; se llama a [abrirUnSoloCobro] con los MISMOS escritores de argumentos que usa `AppNavigation`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class UnSoloCobroTest {

    @Navigator.Name("prueba")
    private class NavegadorDePrueba : Navigator<NavDestination>() {
        override fun createDestination(): NavDestination = NavDestination(this)
    }

    /** Pila `[origen]`, con estado como el NavHost para leer el `savedStateHandle` de cada entrada. */
    private fun en(origen: NavRoute): NavHostController {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        nav.setLifecycleOwner(object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        })
        nav.setViewModelStore(ViewModelStore())
        val navegador = NavegadorDePrueba()
        nav.navigatorProvider.addNavigator(navegador)
        val grafo = NavGraph(nav.navigatorProvider.getNavigator(NavGraphNavigator::class.java))
        listOf(NavRoute.Checkout, NavRoute.SerializedSale, NavRoute.Payment)
            .forEach { ruta -> grafo.addDestination(navegador.createDestination().apply { route = ruta.route }) }
        grafo.setStartDestination(origen.route)
        nav.graph = grafo
        return nav
    }

    private fun NavHostController.cobrosEnLaPila(): Int =
        listOfNotNull(currentBackStackEntry, previousBackStackEntry).count { it.destination.route == NavRoute.Payment.route }

    /** Lo mismo que `onNavigateToPayment` de Cobrar. */
    private fun EnvioDePagoRapido.cobrar(
        scope: CoroutineScope,
        nav: NavHostController,
        payload: PaymentNavigationPayload,
        listo: CompletableDeferred<Boolean>,
        soloEfectivo: Boolean = false,
    ) = abrirUnSoloCobro(this, scope, nav, NavRoute.Payment.route, soloEfectivo, lectorListo = { listo.await() }) {
        escribirArgumentosDeCobrar(it, payload, soloEfectivo)
    }

    /** Lo mismo que `onNavigateToPayment` de la Venta de SIMs. */
    private fun EnvioDePagoRapido.vender(scope: CoroutineScope, nav: NavHostController, orden: String, serie: String, listo: CompletableDeferred<Boolean>) =
        abrirUnSoloCobro(this, scope, nav, NavRoute.Payment.route, soloEfectivo = false, lectorListo = { listo.await() }) {
            escribirArgumentosDeVentaSerializada(it, orden, "F-$orden", "150.00", isPortabilidad = false, serialNumber = serie, categoryName = "SIM")
        }

    private fun orden(id: String, importe: String) =
        PaymentNavigationPayload.Order(amountPesosString = importe, orderId = id, orderNumber = "N-$id", wasPayLaterOrder = false)

    @Test
    fun `P1 Cobrar dos toques mientras se prepara el lector abren un solo cobro con el primero`() = runTest {
        val nav = en(NavRoute.Checkout)
        val envio = EnvioDePagoRapido(NavRoute.Checkout.route)
        val lector = CompletableDeferred<Boolean>()

        assertTrue(envio.cobrar(this, nav, orden("o-1", "100.00"), lector))
        advanceUntilIdle() // el primero espera a la PAX
        val segundo = envio.cobrar(this, nav, orden("o-2", "250.00"), lector)
        lector.complete(true)
        advanceUntilIdle()

        assertEquals("Debe haber UNA sola pantalla de cobro en la pila", 1, nav.cobrosEnLaPila())
        assertFalse("Un segundo «Cobrar» mientras el primero espera debe ignorarse", segundo)
        val cobrar = nav.getBackStackEntry(NavRoute.Checkout.route).savedStateHandle
        assertEquals("o-1", cobrar.get<String>("orderId"))
        assertEquals("100.00", cobrar.get<String>("initialAmount"))
        assertEquals("checkout", cobrar.get<String>("entryPoint"))
        assertFalse(
            "La entrada del cobro no debe recibir argumentos de un segundo envío",
            nav.currentBackStackEntry!!.savedStateHandle.contains("orderId"),
        )
    }

    @Test
    fun `P1 Venta de SIMs dos ventas mientras se prepara el lector abren un solo cobro con la primera`() = runTest {
        val nav = en(NavRoute.SerializedSale)
        val envio = EnvioDePagoRapido(NavRoute.SerializedSale.route)
        val lector = CompletableDeferred<Boolean>()

        assertTrue(envio.vender(this, nav, "o-1", "8952-A", lector))
        advanceUntilIdle()
        val segunda = envio.vender(this, nav, "o-2", "8952-B", lector)
        lector.complete(true)
        advanceUntilIdle()

        assertEquals("Debe haber UNA sola pantalla de cobro en la pila", 1, nav.cobrosEnLaPila())
        assertFalse("Una segunda venta mientras la primera espera debe ignorarse", segunda)
        val sims = nav.getBackStackEntry(NavRoute.SerializedSale.route).savedStateHandle
        assertEquals("o-1", sims.get<String>("orderId"))
        assertEquals("8952-A", sims.get<String>("serialNumber"))
        assertEquals(true, sims.get<Boolean>("skipLocalOrderValidation"))
        assertFalse(nav.currentBackStackEntry!!.savedStateHandle.contains("orderId"))
    }

    @Test
    fun `P1 la pregunta previa dice espera mientras uno espera al lector y se suelta al abrir, al fallar y al salir`() = runTest {
        val nav = en(NavRoute.SerializedSale)
        val envio = EnvioDePagoRapido(NavRoute.SerializedSale.route)
        assertFalse(envio.esperando)

        // Al abrir.
        val lector = CompletableDeferred<Boolean>()
        assertTrue(envio.vender(this, nav, "o-1", "A", lector))
        advanceUntilIdle()
        assertTrue("Mientras espera al lector, la Venta de SIMs no debe vender otra SIM", envio.esperando)
        lector.complete(true)
        advanceUntilIdle()
        assertFalse("Al abrir el cobro se suelta", envio.esperando)
        assertTrue(nav.popBackStack())

        // Al fallar el lector.
        val falla = CompletableDeferred<Boolean>()
        assertTrue(envio.vender(this, nav, "o-2", "B", falla))
        advanceUntilIdle()
        assertTrue(envio.esperando)
        falla.complete(false) // «Sistema de pagos no inicializado»
        advanceUntilIdle()
        assertFalse("Si el lector falla se suelta", envio.esperando)
        assertEquals(0, nav.cobrosEnLaPila())

        // Al salir de la pantalla.
        val pantalla = CoroutineScope(StandardTestDispatcher(testScheduler))
        val nunca = CompletableDeferred<Boolean>()
        assertTrue(envio.vender(pantalla, nav, "o-3", "C", nunca))
        advanceUntilIdle()
        assertTrue(envio.esperando)
        pantalla.cancel()
        advanceUntilIdle()
        assertFalse("Al salir de la pantalla se suelta", envio.esperando)

        assertTrue(envio.vender(this, nav, "o-4", "D", CompletableDeferred(true)))
        advanceUntilIdle()
        assertEquals(1, nav.cobrosEnLaPila())
        assertEquals("o-4", nav.getBackStackEntry(NavRoute.SerializedSale.route).savedStateHandle.get<String>("orderId"))
    }

    @Test
    fun `P1 Cobrar sin red el efectivo abre ya aunque otro espere al lector y el que esperaba ya no apila otro`() = runTest {
        val nav = en(NavRoute.Checkout)
        val envio = EnvioDePagoRapido(NavRoute.Checkout.route)
        val lector = CompletableDeferred<Boolean>()

        assertTrue(envio.cobrar(this, nav, orden("o-1", "100.00"), lector))
        advanceUntilIdle()
        val local = PaymentNavigationPayload.VentaLocal(amountPesosString = "80.00", ventaLocalJson = "{\"llave\":\"v-1\"}")
        val efectivo = envio.cobrar(this, nav, local, CompletableDeferred(), soloEfectivo = true) // nunca espera al lector
        advanceUntilIdle()

        assertTrue("La venta local en sólo efectivo no espera al lector: no se ignora", efectivo)
        assertEquals(1, nav.cobrosEnLaPila())
        val cobrar = nav.getBackStackEntry(NavRoute.Checkout.route).savedStateHandle
        assertEquals("80.00", cobrar.get<String>("initialAmount"))
        assertEquals(true, cobrar.get<Boolean>("soloEfectivo"))
        assertEquals("{\"llave\":\"v-1\"}", cobrar.get<String>("ventaLocalJson"))

        assertTrue(nav.popBackStack()) // regresa a Cobrar antes de que el lector termine
        lector.complete(true)
        advanceUntilIdle()
        assertEquals("El envío viejo que esperaba al lector no debe abrir un cobro sin otro toque", 0, nav.cobrosEnLaPila())
    }

    @Test
    fun `P1 desde una pantalla que ya no esta arriba no se abre otro cobro`() = runTest {
        val nav = en(NavRoute.Checkout)
        val envio = EnvioDePagoRapido(NavRoute.Checkout.route)

        assertTrue(envio.cobrar(this, nav, orden("o-1", "100.00"), CompletableDeferred(true)))
        advanceUntilIdle()
        assertEquals(1, nav.cobrosEnLaPila())
        // Un toque que cae en la animación de salida de Cobrar: ya no hay espera, pero Cobrar no está arriba.
        envio.cobrar(this, nav, orden("o-1", "100.00"), CompletableDeferred(true))
        advanceUntilIdle()

        assertEquals(1, nav.cobrosEnLaPila())
        assertFalse(nav.currentBackStackEntry!!.savedStateHandle.contains("orderId"))
    }

    private fun texto(ruta: String) = File("src/main/java/com/jaac/avoqado_tpv/$ruta").readText()

    private fun bloque(texto: String, desde: String, hasta: String): String {
        val inicio = texto.indexOf(desde)
        require(inicio >= 0) { "No encontré «$desde»" }
        val fin = texto.indexOf(hasta, inicio + desde.length)
        require(fin >= 0) { "No encontré «$hasta» después de «$desde»" }
        return texto.substring(inicio, fin)
    }

    @Test
    fun `P1 Cobrar y Venta de SIMs abren el cobro por la pieza de D667 y SIMs pregunta antes de vender`() {
        val navegacion = texto("core/presentation/navigation/AppNavigation.kt")
        for ((pantalla, fin, origen) in listOf(
            Triple("composable(NavRoute.Checkout.route)", "// 🪑 Mesas — Plan C, Task 6", "NavRoute.Checkout.route"),
            Triple("composable(NavRoute.SerializedSale.route)", "composable(NavRoute.SerializedInventoryRegister.route)", "NavRoute.SerializedSale.route"),
        )) {
            val cuerpo = bloque(navegacion, pantalla, fin)
            assertTrue("«$pantalla» debe crear su candado con SU ruta de origen", cuerpo.contains("val envio = remember { EnvioDePagoRapido($origen) }"))
            assertTrue("«$pantalla» debe abrir el cobro con abrirUnSoloCobro(envio, …)", Regex("""abrirUnSoloCobro\(\s*envio,""").containsMatchIn(cuerpo))
            assertTrue(
                "«$pantalla» debe esperar al lector dentro de abrirUnSoloCobro",
                Regex("""lectorListo = \{[^}]*?awaitPaxPaymentReady\(""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(cuerpo),
            )
            assertTrue("«$pantalla» debe avisar cuando se ignora", cuerpo.contains("if (!lanzado) avisarQueYaSeAbreUnCobro(context)"))
            assertFalse("«$pantalla» no debe navegar al cobro por fuera del candado", cuerpo.contains("navController.navigate(getPaymentRoute())"))
        }
        // Codex r1 #1: Cobrar NO pregunta antes de preparar la venta: con el SERVIDOR caído ese segundo toque pasa a venta local en
        // sólo efectivo. La Venta de SIMs sí (vender no tiene camino sin red y una venta ignorada dejaría su orden sin cobro).
        assertFalse(
            "Cobrar no debe pasarle a CheckoutScreen una pregunta previa (le cerraría el efectivo con el SERVIDOR caído)",
            bloque(navegacion, "composable(NavRoute.Checkout.route)", "// 🪑 Mesas — Plan C, Task 6").contains("puedeCobrar"),
        )
        assertTrue(
            "La Venta de SIMs debe pasarle a su pantalla la pregunta previa",
            bloque(navegacion, "composable(NavRoute.SerializedSale.route)", "composable(NavRoute.SerializedInventoryRegister.route)")
                .contains("puedeCobrar = { (!envio.esperando)"),
        )
        val ayudante = bloque(navegacion, "internal fun abrirUnSoloCobro(", "\n}\n")
        assertTrue(ayudante.contains("envio.lanzar(sinEspera = soloEfectivo) { turno ->"))
        assertTrue(ayudante.contains("if (!soloEfectivo && !lectorListo()) return@launch"))
        assertTrue(ayudante.contains("envio.abrir(turno, navController, rutaDelCobro, escribir)"))

        // SIMs: cada venta pregunta ANTES de onConfirmSale (que vende la SIM en el servidor).
        val sims = texto("features/serialized_sale/presentation/SerializedSaleScreen.kt")
        val ventas = Regex("""viewModel\.onConfirmSale \{""").findAll(sims).map { it.range.first }.toList()
        assertEquals("La Venta de SIMs tiene dos botones que venden («Vender» y «Sí, regalar»)", 2, ventas.size)
        for (venta in ventas) {
            val antes = sims.substring(maxOf(0, venta - 600), venta)
            assertTrue("Cada onConfirmSale debe ir después de `if (!puedeCobrar())`", antes.contains("if (!puedeCobrar())"))
        }
    }
}
