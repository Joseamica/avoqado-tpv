package com.jaac.avoqado_tpv.features.remote_command.presentation

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D665 (21-nov): con la terminal BLOQUEADA desde Avoqado (o en mantenimiento) el velo oscuro sólo tapaba la vista. Un
 * toque fuera del aviso llegaba a la app de abajo y el cajero seguía usando la terminal («Cobrar») tocando alrededor.
 *
 * Toques de verdad (MotionEvent al AndroidComposeView), no acciones de semántica: lo que se prueba es por dónde pasa el
 * dedo. La pantalla tiene el tamaño de la PAX A910S (360×640 dp, xhdpi). El repo no tiene `createComposeRule` en las
 * pruebas de JVM (ver `AngelPayPaymentScreenSalidaTest`): aquí se monta un `ComponentActivity` con Robolectric y se
 * toca su vista de Compose directamente.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28], qualifiers = "w360dp-h640dp-xhdpi")
class VeloQueDetieneElDedoTest {

    private var toquesDebajo = 0
    private var salidas = 0

    /** La «app de abajo»: toda la pantalla cuenta cada toque que le llega, como harían las tarjetas de Inicio. */
    @Composable
    private fun AppDeAbajo() {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { toquesDebajo++ } })
    }

    private fun montar(bloqueo: Boolean, mantenimiento: Boolean): View {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme {
                // Mismo orden que MainActivity: la app, luego mantenimiento, y el bloqueo hasta arriba.
                Box(Modifier.fillMaxSize()) {
                    AppDeAbajo()
                    MaintenanceOverlay(
                        visible = mantenimiento,
                        maintenanceReason = null,
                        initiatedBy = null,
                        onExitMaintenance = { salidas++ },
                    )
                    LockScreenOverlay(visible = bloqueo, lockReason = null, lockMessage = null, lockedBy = null)
                }
            }
        }
        idle()
        return vistaDeCompose(activity.window.decorView)
            ?: error("No encontré la vista de Compose en la Activity de prueba")
    }

    private fun vistaDeCompose(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) vistaDeCompose(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun tocar(vista: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { accion ->
            val evento = MotionEvent.obtain(t, t + 50, accion, x, y, 0)
            vista.dispatchTouchEvent(evento)
            evento.recycle()
            idle()
        }
    }

    /** Las cuatro esquinas: fuera de la tarjeta del aviso (centrada y con 24 dp de margen) en cualquier tamaño. */
    private fun tocarAlrededorDelAviso(vista: View) {
        val (w, h) = vista.width.toFloat() to vista.height.toFloat()
        listOf(4f to 4f, w - 4f to 4f, 4f to h - 4f, w - 4f to h - 4f).forEach { (x, y) -> tocar(vista, x, y) }
    }

    private fun centroDe(vista: View, texto: String): Offset {
        fun buscar(nodo: SemanticsNode): SemanticsNode? {
            if (nodo.config.getOrNull(SemanticsProperties.Text)?.any { it.text == texto } == true) return nodo
            return nodo.children.firstNotNullOfOrNull { buscar(it) }
        }
        val nodo = buscar((vista as ViewRootForTest).semanticsOwner.rootSemanticsNode)
            ?: error("No encontré «$texto» en la pantalla")
        return nodo.boundsInRoot.center
    }

    @Test
    fun `control - sin velo el toque si llega a la app de abajo`() {
        val vista = montar(bloqueo = false, mantenimiento = false)
        tocarAlrededorDelAviso(vista)
        assertEquals("La prueba no sirve si el toque no llega a la app sin velo", 4, toquesDebajo)
    }

    @Test
    fun `P1 con la terminal bloqueada ningun toque fuera del aviso llega a la app de abajo`() {
        val vista = montar(bloqueo = true, mantenimiento = false)
        tocarAlrededorDelAviso(vista)
        assertEquals("Terminal bloqueada: un toque alrededor del aviso llegó a la app (D665)", 0, toquesDebajo)
    }

    @Test
    fun `P1 en mantenimiento ningun toque fuera del aviso llega a la app de abajo`() {
        val vista = montar(bloqueo = false, mantenimiento = true)
        tocarAlrededorDelAviso(vista)
        assertEquals("Mantenimiento: un toque alrededor del aviso llegó a la app (D665)", 0, toquesDebajo)
        assertEquals(0, salidas)
    }

    @Test
    fun `P1 en mantenimiento el boton Salir de mantenimiento sigue funcionando`() {
        val vista = montar(bloqueo = false, mantenimiento = true)
        val boton = centroDe(vista, "Salir de mantenimiento")
        tocar(vista, boton.x, boton.y)
        assertEquals("«Salir de mantenimiento» dejó de responder", 1, salidas)
        assertEquals(0, toquesDebajo)
    }

    @Test
    fun `P1 bloqueada encima de mantenimiento ni la app ni Salir de mantenimiento reciben el toque`() {
        val vista = montar(bloqueo = true, mantenimiento = true)
        val boton = centroDe(vista, "Salir de mantenimiento")
        tocar(vista, boton.x, boton.y)
        tocarAlrededorDelAviso(vista)
        assertEquals("El bloqueo debe tapar también el botón de mantenimiento", 0, salidas)
        assertEquals(0, toquesDebajo)
    }
}
