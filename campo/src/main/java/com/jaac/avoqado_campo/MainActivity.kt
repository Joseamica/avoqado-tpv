package com.jaac.avoqado_campo

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jaac.avoqado_campo.cola.PiezaDao
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
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // r12: el arranque no pudo guardar. Sólo la pantalla honesta: nada más corre ni escribe.
        if (arranque.detenido) {
            setContent { TemaCampo { PantallaDetenida(onReintentar = ::reiniciar) } }
            return
        }
        // Cada vez que la app se ve: reintentar el GPS del turno, y el latido y la versión nueva al abrir y cada 5 min (Latido no
        // manda más de 1/min; la versión se pregunta cada 30 min como mucho).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) {
                    intentar("GPS del turno") { reanudarSiHayTurno(gps, dao, quienSoy) }
                    while (true) {
                        intentar("latido") { latido.mandar() }
                        intentar("versión nueva") { revisor.revisar() }
                        delay(CINCO_MIN)
                    }
                }
            }
        }
        setContent { TemaCampo { Navegacion(quienSoy, dao, conectividad, borrado, estadoDelGps, revisor, actualizador) } }
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
