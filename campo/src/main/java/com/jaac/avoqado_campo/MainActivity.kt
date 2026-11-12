package com.jaac.avoqado_campo

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.EstadoDelGps
import com.jaac.avoqado_campo.gps.reanudarSiHayTurno
import com.jaac.avoqado_campo.identidad.Arranque
import com.jaac.avoqado_campo.identidad.BorradoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.latido.Latido
import com.jaac.avoqado_campo.red.Conectividad
import com.jaac.avoqado_campo.ui.Navegacion
import com.jaac.avoqado_campo.ui.PantallaDetenida
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import com.jaac.avoqado_campo.version.Actualizador
import com.jaac.avoqado_campo.version.RevisorDeVersion
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** El borrado a medias ya lo terminó `CampoApplication.onCreate`, antes de que esto se dibuje. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var quienSoy: QuienSoy
    @Inject lateinit var dao: PiezaDao
    @Inject lateinit var conectividad: Conectividad
    @Inject lateinit var borrado: BorradoDelCelular
    @Inject lateinit var estadoDelGps: EstadoDelGps
    @Inject lateinit var gps: ControlDeGps
    @Inject lateinit var latido: Latido
    @Inject lateinit var arranque: Arranque
    @Inject lateinit var revisor: RevisorDeVersion
    @Inject lateinit var actualizador: Actualizador
    @Inject lateinit var copia: RepositorioDeCopia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // r12: el arranque no pudo guardar. Sólo la pantalla honesta: nada más corre ni escribe.
        if (arranque.detenido) {
            setContent { TemaCampo { PantallaDetenida(onReintentar = ::reiniciar) } }
            return
        }
        // Cada vez que la app queda al frente (también al cerrar el diálogo de un permiso): reintentar el GPS del turno.
        cadaVezAlFrente { withContext(Dispatchers.IO) { intentar("GPS del turno") { reanudarSiHayTurno(gps, dao, quienSoy) } } }
        // Mientras se ve (aunque pierda el foco en pantalla dividida): el latido y la versión nueva al abrir y cada 5 min
        // (Latido no manda más de 1/min; la versión se pregunta cada 30 min como mucho).
        cadaVezVisible {
            withContext(Dispatchers.IO) {
                while (true) {
                    intentar("latido") { latido.mandar() }
                    intentar("versión nueva") { revisor.revisar() }
                    delay(CINCO_MIN)
                }
            }
        }
        setContent { TemaCampo { Navegacion(quienSoy, dao, conectividad, borrado, estadoDelGps, revisor, actualizador, copia) } }
    }

    /** Al volver de «Permitir de esta fuente» (con el permiso, instala) o del diálogo de Android que no instaló. */
    override fun onResume() {
        super.onResume()
        if (!arranque.detenido) actualizador.alRegresar()
    }

    /** «Reintentar»: un proceso nuevo, donde memoria = disco, vuelve a correr la recuperación del arranque. */
    private fun reiniciar() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Process.killProcess(Process.myPid())
    }

    private suspend fun intentar(que: String, bloque: suspend () -> Unit) {
        try {
            bloque()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MainActivity", que, e)
        }
    }

    private companion object { const val CINCO_MIN = 5 * 60_000L }
}

/**
 * Corre [bloque] cada vez que la app queda al frente y lo cancela al dejar de estarlo. RESUMED y no STARTED (D2): el diálogo
 * de un permiso de Android sólo PAUSA la app; con STARTED, devolver la ubicación desde «Checar» no revisaba el GPS y la
 * pantalla seguía con «no diste permiso» hasta reabrir la app. Sólo el GPS: el latido y la versión van en [cadaVezVisible].
 */
internal fun LifecycleOwner.cadaVezAlFrente(bloque: suspend CoroutineScope.() -> Unit): Job =
    lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED, bloque) }

/**
 * Corre [bloque] mientras la app se ve (STARTED) y lo cancela al dejar de verse. En pantalla dividida la app sigue visible sin
 * foco: el latido y la revisión de versión no deben cortarse por eso (Codex D2 r1).
 */
internal fun LifecycleOwner.cadaVezVisible(bloque: suspend CoroutineScope.() -> Unit): Job =
    lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED, bloque) }
