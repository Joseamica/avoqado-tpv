package com.jaac.avoqado_campo

import android.os.Bundle
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
import com.jaac.avoqado_campo.identidad.BorradoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.latido.Latido
import com.jaac.avoqado_campo.red.Conectividad
import com.jaac.avoqado_campo.ui.Navegacion
import com.jaac.avoqado_campo.ui.tema.TemaCampo
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cada vez que la app se ve: reintentar el GPS del turno, y el latido al abrir y cada 5 min (Latido no manda más de 1/min).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) {
                    intentar("GPS del turno") { reanudarSiHayTurno(gps, dao, quienSoy) }
                    while (true) {
                        intentar("latido") { latido.mandar() }
                        delay(CINCO_MIN)
                    }
                }
            }
        }
        setContent { TemaCampo { Navegacion(quienSoy, dao, conectividad, borrado, estadoDelGps) } }
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
