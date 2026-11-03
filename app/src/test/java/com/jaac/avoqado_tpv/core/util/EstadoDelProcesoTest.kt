package com.jaac.avoqado_tpv.core.util

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * D1 y D2 («ninguna duda de cobro sin salida», QA en la N86, 2-nov): la app moría con la pantalla del SDK de AngelPay arriba y
 * Android la revivía restaurando «Método de Pago» del MISMO cobro. Esa pantalla no conecta el socket (la terminal no se soltaba
 * sola: D1) y dejaba tocar «Tarjeta» otra vez, abriendo un segundo lector de la misma solicitud con el intento anterior en duda
 * (D2). Un proceso nuevo nunca restaura una pantalla; una recreación dentro del mismo proceso sigue conservando su estado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class EstadoDelProcesoTest {

    private fun guardadoPor(proceso: String?) = Bundle().apply {
        putString("pantalla", "Método de Pago")
        proceso?.let { putString(EstadoDelProceso.CLAVE, it) }
    }

    @Test fun `D1 el estado que guardo otro proceso se descarta`() {
        assertThat(EstadoDelProceso.paraCrear(guardadoPor("proceso-muerto"), esteProceso = "proceso-nuevo")).isNull()
    }

    @Test fun `D1 el estado que guardo este mismo proceso se conserva tal cual`() {
        val guardado = guardadoPor("proceso-vivo")
        assertThat(EstadoDelProceso.paraCrear(guardado, esteProceso = "proceso-vivo")).isSameInstanceAs(guardado)
    }

    @Test fun `D1 sin estado no hay nada que decidir`() {
        assertThat(EstadoDelProceso.paraCrear(null, esteProceso = "proceso-nuevo")).isNull()
    }

    @Test fun `D1 un estado sin firma se descarta - lo guardo una version anterior, que ya no existe en este proceso`() {
        assertThat(EstadoDelProceso.paraCrear(guardadoPor(null), esteProceso = "proceso-nuevo")).isNull()
    }

    /**
     * Por qué se firma y no se cuenta «la primera creación del proceso»: la copia que el launcher de AngelPay crea y cierra enseguida
     * (sin estado) gastaría esa cuenta, y la segunda de dos `MainActivity` apiladas restauraría el cobro. Aquí no hay nada que gastar.
     */
    @Test fun `D2 ni la copia del launcher ni una segunda pantalla apilada dejan restaurar el cobro`() {
        assertThat(EstadoDelProceso.paraCrear(null, esteProceso = "proceso-nuevo")).isNull() // la copia del launcher
        assertThat(EstadoDelProceso.paraCrear(guardadoPor("proceso-muerto"), esteProceso = "proceso-nuevo")).isNull() // la de abajo
        assertThat(EstadoDelProceso.paraCrear(guardadoPor("proceso-muerto"), esteProceso = "proceso-nuevo")).isNull() // otra apilada
    }

    // ── Con una pantalla de verdad: lo que la decisión le hace a `ComponentActivity` ─────────────────────────────────────────

    /** Las mismas tres líneas de `MainActivity`: firma al guardar y crea con lo que decide [EstadoDelProceso]. */
    class PantallaDePrueba : ComponentActivity() {
        var restaurado: String? = null
        var resultadoEntregado: ActivityResult? = null
        val lector = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultadoEntregado = it }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(EstadoDelProceso.paraCrear(savedInstanceState))
            restaurado = savedStateRegistry.consumeRestoredStateForKey("cobro")?.getString("pantalla")
            savedStateRegistry.registerSavedStateProvider("cobro") { Bundle().apply { putString("pantalla", "Método de Pago") } }
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            EstadoDelProceso.firmar(outState)
        }
    }

    /** Abre «la pantalla del SDK» y la guarda como lo hace Android al taparla. Devuelve el estado y el código del lanzamiento. */
    private fun pantallaTapadaPorElSdk(): Pair<Bundle, Int> {
        val pantalla = Robolectric.buildActivity(PantallaDePrueba::class.java).setup()
        pantalla.get().lector.launch(Intent("sdk.PaymentActivity"))
        val codigo = shadowOf(pantalla.get()).nextStartedActivityForResult.requestCode
        val estado = Bundle()
        pantalla.pause().saveInstanceState(estado).stop()
        return estado to codigo
    }

    @Test fun `D1 recreada en el MISMO proceso conserva la pantalla y recibe el resultado del SDK, como hoy`() {
        val (estado, codigo) = pantallaTapadaPorElSdk()

        val recreada = Robolectric.buildActivity(PantallaDePrueba::class.java).setup(estado).get()

        assertThat(recreada.restaurado).isEqualTo("Método de Pago")
        shadowOf(recreada).callOnActivityResult(codigo, Activity.RESULT_OK, Intent())
        assertThat(recreada.resultadoEntregado?.resultCode).isEqualTo(Activity.RESULT_OK)
    }

    @Test fun `D2 en un proceso NUEVO no restaura la pantalla y el resultado que devuelva el SDK no cae en ella ni la tumba`() {
        val (estado, codigo) = pantallaTapadaPorElSdk()
        estado.putString(EstadoDelProceso.CLAVE, "proceso-muerto") // otro proceso: el token de éste ya no es el que firmó

        val nueva = Robolectric.buildActivity(PantallaDePrueba::class.java).setup(estado).get()

        assertThat(nueva.restaurado).isNull()
        // Codex TPV r1 #1: si Android restauró la pantalla del SDK encima, su resultado llega con un código que esta instancia
        // nunca registró. No se entrega ni truena: el intento se queda en duda en la libreta (CancelTrasReclamarRoomTest).
        shadowOf(nueva).callOnActivityResult(codigo, Activity.RESULT_OK, Intent())
        assertThat(nueva.resultadoEntregado).isNull()
    }

    // ── La guardia: `MainActivity` no se puede probar con Hilt aquí (ver NexgoFlavorHiltGraphTest); se lee su fuente ───────────

    private val fuente = File("src/main/java/com/jaac/avoqado_tpv/MainActivity.kt").readText()

    @Test fun `D1 MainActivity nunca crea con el estado crudo - sólo con lo que decide EstadoDelProceso`() {
        assertThat(fuente).doesNotContain("super.onCreate(savedInstanceState)")
        assertThat(fuente).containsMatch("""super\.onCreate\(\w+\)""")
        assertThat(fuente).contains("EstadoDelProceso.paraCrear(savedInstanceState)")
    }

    @Test fun `D1 MainActivity firma lo que guarda - sin firma, su propia recreacion se descartaria`() {
        val inicio = fuente.indexOf("override fun onSaveInstanceState(")
        assertThat(inicio).isAtLeast(0)
        assertThat(fuente.substring(inicio, fuente.indexOf("override fun", inicio + 1))).contains("EstadoDelProceso.firmar(")
    }
}
