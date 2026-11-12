package com.jaac.avoqado_campo

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D2 (full-testing del bloque 2): tras devolver el permiso de ubicación, «Checar» seguía diciendo «no diste permiso» hasta
 * reabrir la app. El diálogo del permiso de Android sólo PAUSA la app: al cerrarlo también hay que revisar el GPS otra vez.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CadaVezAlFrenteTest {
    private class Duenio : LifecycleOwner {
        val registro = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registro
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `al cerrar el dialogo de un permiso (pausa y vuelve) se revisa otra vez`() {
        val app = Duenio()
        var veces = 0
        app.cadaVezAlFrente { veces++ }
        app.registro.currentState = Lifecycle.State.RESUMED
        assertEquals(1, veces)
        app.registro.currentState = Lifecycle.State.STARTED // el diálogo del permiso encima: la app sólo se pausa
        app.registro.currentState = Lifecycle.State.RESUMED
        assertEquals(2, veces)
    }

    @Test fun `al salir a Ajustes y volver tambien se revisa`() {
        val app = Duenio()
        var veces = 0
        app.cadaVezAlFrente { veces++ }
        app.registro.currentState = Lifecycle.State.RESUMED
        app.registro.currentState = Lifecycle.State.CREATED // otra app encima (Ajustes)
        app.registro.currentState = Lifecycle.State.RESUMED
        assertEquals(2, veces)
    }

    // Codex D2 r1: en pantalla dividida (Android 8.1/9) la app sigue visible sin foco (STARTED): el latido y la revisión de
    // versión no deben cortarse por eso; sólo el GPS se revisa al volver al frente.
    @Test fun `el ciclo de latido y version sigue mientras la app es visible aunque pierda el foco`() {
        val app = Duenio()
        var entradas = 0
        var cortado = false
        app.cadaVezVisible {
            entradas++
            try { kotlinx.coroutines.awaitCancellation() } finally { cortado = true }
        }
        app.registro.currentState = Lifecycle.State.RESUMED
        app.registro.currentState = Lifecycle.State.STARTED // pantalla dividida: tocó la otra app
        app.registro.currentState = Lifecycle.State.RESUMED
        assertEquals(1, entradas)
        assertEquals(false, cortado)
        app.registro.currentState = Lifecycle.State.CREATED // ya no se ve
        assertEquals(true, cortado)
    }
}
