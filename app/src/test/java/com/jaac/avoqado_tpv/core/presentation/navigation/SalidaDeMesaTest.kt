package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jaac.avoqado_tpv.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 🪑 hz:1450a1e9:D4 (25-nov) · TPV SOLA → MESAS. Tras cobrar una mesa, «Nueva Orden» libera la mesa por red y vuelve al plano. Las dos
 * cosas corrían atadas a la pantalla: si el POS abría una devolución encima, se cancelaban (mesa sin liberar, nadie volvía al plano; la
 * PAX quedaba en «Un momento…» y la Nexgo volvía a ofrecer la cuenta pagada). [salirDeMesaTrasCobrar]: liberar siempre termina; volver
 * al plano espera a que la pantalla de cobro esté arriba y no pasa si ya se cerró.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SalidaDeMesaTest {
    @get:Rule val principal = MainDispatcherRule()

    private class Entrada(estado: Lifecycle.State) : LifecycleOwner {
        val registro = LifecycleRegistry.createUnsafe(this).apply { currentState = estado }
        override val lifecycle: Lifecycle get() = registro
    }

    private val alcance = CoroutineScope(SupervisorJob() + principal.testDispatcher)
    private var liberadas = 0
    private var alPlano = 0

    @Test
    fun `con la pantalla de cobro arriba libera la mesa y vuelve al plano`() = runTest(principal.testDispatcher) {
        salirDeMesaTrasCobrar(alcance, Entrada(Lifecycle.State.RESUMED), liberar = { liberadas++ }, alPlano = { alPlano++ })
        assertEquals(1, liberadas)
        assertEquals(1, alPlano)
    }

    @Test
    fun `con otra pantalla encima libera la mesa ya y vuelve al plano al regresar`() = runTest(principal.testDispatcher) {
        val entrada = Entrada(Lifecycle.State.CREATED)  // Pagos (la devolución del POS) encima
        salirDeMesaTrasCobrar(alcance, entrada, liberar = { liberadas++ }, alPlano = { alPlano++ })
        assertEquals(1, liberadas)
        assertEquals(0, alPlano)

        entrada.registro.currentState = Lifecycle.State.RESUMED  // «atrás» desde Pagos
        assertEquals(1, alPlano)
    }

    @Test
    fun `si el cajero sale antes de que conteste el servidor la mesa se libera igual y no se navega`() = runTest(principal.testDispatcher) {
        val servidor = CompletableDeferred<Unit>()
        val entrada = Entrada(Lifecycle.State.RESUMED)
        salirDeMesaTrasCobrar(alcance, entrada, liberar = { servidor.await(); liberadas++ }, alPlano = { alPlano++ })

        alcance.cancel()  // sale con la flecha: la entrada se cierra y su ViewModel muere
        entrada.registro.currentState = Lifecycle.State.DESTROYED
        servidor.complete(Unit)

        assertEquals(1, liberadas)
        assertEquals(0, alPlano)
    }

    @Test
    fun `si la entrada se cerró mientras se liberaba no vuelve al plano`() = runTest(principal.testDispatcher) {
        val servidor = CompletableDeferred<Unit>()
        val entrada = Entrada(Lifecycle.State.CREATED)
        salirDeMesaTrasCobrar(alcance, entrada, liberar = { servidor.await(); liberadas++ }, alPlano = { alPlano++ })

        entrada.registro.currentState = Lifecycle.State.DESTROYED
        servidor.complete(Unit)

        assertEquals(1, liberadas)
        assertEquals(0, alPlano)
    }

    /** Las dos rutas de pago (PAX y Nexgo) salen de Mesas con esta pieza, en el alcance de su ViewModel y con su propia entrada. */
    @Test
    fun `las dos rutas de pago salen de Mesas con salirDeMesaTrasCobrar`() {
        val navegacion = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
        assertTrue(navegacion.contains("alcance = pagoVm.viewModelScope,"))
        assertTrue(navegacion.contains("alcance = angelVm.viewModelScope,"))
        assertEquals(2, Regex("""salirDeMesaTrasCobrar\(\s+alcance = \w+\.viewModelScope,\s+entrada = backStackEntry,""").findAll(navegacion).count())
        assertEquals("clearTable de Mesas sólo dentro de salirDeMesaTrasCobrar", 2, Regex("""tablesRepository\.clearTable\(venueId, session\.tableId""").findAll(navegacion).count())
    }
}
