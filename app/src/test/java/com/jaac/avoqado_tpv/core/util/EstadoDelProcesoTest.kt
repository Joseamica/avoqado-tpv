package com.jaac.avoqado_tpv.core.util

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.ui.PaymentActivity
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

    @Test fun `D2 en un proceso NUEVO no restaura la pantalla, y el Missing request de la pantalla del SDK no la tumba`() {
        val (estado, codigo) = pantallaTapadaPorElSdk()
        estado.putString(EstadoDelProceso.CLAVE, "proceso-muerto") // otro proceso: el token de éste ya no es el que firmó

        val nueva = Robolectric.buildActivity(PantallaDePrueba::class.java).setup(estado).get()

        assertThat(nueva.restaurado).isNull()
        // Codex TPV r1 #1: si Android restauró la pantalla del SDK encima, ésta ya no cobra (I-1, abajo): lo único que devuelve es
        // «Missing request», con un código que esta instancia nunca registró. Nada que registrar, y no truena: el intento sigue
        // en duda en la libreta (CancelTrasReclamarRoomTest).
        shadowOf(nueva).callOnActivityResult(codigo, Activity.RESULT_OK, Intent())
        assertThat(nueva.resultadoEntregado).isNull()
    }

    // ── I-1 (revisión de D1/D2): la pantalla del SDK tampoco se restaura en un proceso nuevo ───────────────────────────────────
    //
    // Si la pantalla del SDK estaba DETENIDA al morir el proceso (pantalla apagada u Home esperando tarjeta), Android la recrea:
    // su `onCreate` relanza el cobro con `angelpay_req` sin mirar el estado, y era otro lector de la misma solicitud con el intento
    // anterior en duda, cuyo aprobado ya no le llegaba a nadie.

    /** Hace de la pantalla del SDK: igual que `PaymentActivity.onCreate` (AAR 1.0.22), lee el extra DESPUÉS de `super.onCreate`. */
    class PantallaDelSdkDePrueba : ComponentActivity() {
        var abrioElLector = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            abrioElLector = intent.hasExtra(PantallaDelSdkNoSeRestaura.EXTRA_DEL_COBRO)
        }
    }

    private val conElExtra get() = Intent().putExtra(PantallaDelSdkNoSeRestaura.EXTRA_DEL_COBRO, Bundle())


    /** Registra el guardia en la app de la prueba mientras corre [prueba], como lo hace `AvoqadoTPVApplication`. */
    private fun conElGuardia(
        pantalla: String = PantallaDelSdkDePrueba::class.java.name,
        prueba: () -> Unit,
    ) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val guardia = PantallaDelSdkNoSeRestaura(pantalla)
        app.registerActivityLifecycleCallbacks(guardia)
        try {
            prueba()
        } finally {
            app.unregisterActivityLifecycleCallbacks(guardia)
        }
    }

    @Test fun `I-1 restaurada en un proceso NUEVO no abre el lector`() = conElGuardia {
        val deOtroProceso = Bundle().apply { putString(EstadoDelProceso.CLAVE, "proceso-muerto") }

        val pantalla = Robolectric.buildActivity(PantallaDelSdkDePrueba::class.java, conElExtra).setup(deOtroProceso).get()

        assertThat(pantalla.abrioElLector).isFalse()
    }

    @Test fun `I-1 un estado sin firma tampoco abre el lector - lo guardo la version anterior`() = conElGuardia {
        val pantalla = Robolectric.buildActivity(PantallaDelSdkDePrueba::class.java, conElExtra).setup(Bundle()).get()

        assertThat(pantalla.abrioElLector).isFalse()
    }

    @Test fun `I-1 recreada en el MISMO proceso sigue con su cobro, como hoy`() = conElGuardia {
        val original = Robolectric.buildActivity(PantallaDelSdkDePrueba::class.java, conElExtra).setup()
        val estado = Bundle()
        original.pause().saveInstanceState(estado).stop()

        val recreada = Robolectric.buildActivity(PantallaDelSdkDePrueba::class.java, conElExtra).setup(estado).get()

        assertThat(recreada.abrioElLector).isTrue()
    }

    @Test fun `I-1 un cobro nuevo, sin estado, abre el lector`() = conElGuardia {
        val pantalla = Robolectric.buildActivity(PantallaDelSdkDePrueba::class.java, conElExtra).setup().get()

        assertThat(pantalla.abrioElLector).isTrue()
    }

    @Test fun `I-1 no toca el Intent ni el estado de ninguna otra pantalla`() = conElGuardia(pantalla = "otra.Pantalla") {
        val deOtroProceso = Bundle().apply { putString(EstadoDelProceso.CLAVE, "proceso-muerto") }

        val controlador = Robolectric.buildActivity(PantallaDelSdkDePrueba::class.java, conElExtra).setup(deOtroProceso)

        assertThat(controlador.get().abrioElLector).isTrue()
        val estado = Bundle()
        controlador.pause().saveInstanceState(estado).stop()
        assertThat(estado.containsKey(EstadoDelProceso.CLAVE)).isFalse()
    }

    @Test fun `I-1 lo auditado del AAR - subirlo obliga a revisar esta salida otra vez`() {
        // La `PaymentActivity` real no se puede crear aquí (AppCompat pide recursos que estas pruebas no cargan). Lo que el guardia
        // necesita de ella se comprobó con `javap` en el AAR 1.0.22, cuyo hash fija `AngelPaySdk119ReglaTest`:
        //  - `onCreate` llama primero a `FragmentActivity.onCreate`, y ahí corre el guardia;
        //  - después lee `angelpay_req` del Intent, y sin él (`ifnonnull`) arma «Missing request» y sale por `finishWith`
        //    (`setResult(-1, …)` + `finish()`) sin tocar el lector;
        //  - `onDestroy` sólo toca el hardware dentro de un `runCatching`, así que la pantalla sin armar no tumba la app.
        // 🔴 Si esto cae porque llegó un SDK nuevo: NO cambies el número a ciegas. Vuelve a comprobar esos tres puntos con `javap`.
        assertThat(AngelPaySDK.version()).isEqualTo("1.0.22")
        assertThat(PaymentActivity::class.java.name).isEqualTo(PantallaDelSdkNoSeRestaura.NOMBRE)
        // Por reflexión: el compañero del AAR está ofuscado y Kotlin no ve la constante.
        assertThat(PaymentActivity::class.java.getField("EXTRA_REQ").get(null)).isEqualTo(PantallaDelSdkNoSeRestaura.EXTRA_DEL_COBRO)
    }

    @Test fun `I-1 la app registra el guardia`() {
        // Hilt no se levanta en estas pruebas (ver NexgoFlavorHiltGraphTest): se lee la fuente.
        assertThat(File("src/main/java/com/jaac/avoqado_tpv/AvoqadoTPVApplication.kt").readText())
            .contains("registerActivityLifecycleCallbacks(PantallaDelSdkNoSeRestaura())")
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
