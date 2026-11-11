package com.jaac.avoqado_campo

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.vigilarElSeguimiento
import com.jaac.avoqado_campo.identidad.Arranque
import com.jaac.avoqado_campo.identidad.ArranqueSinGuardar
import com.jaac.avoqado_campo.identidad.BorradoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class CampoApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var borrado: BorradoDelCelular
    @Inject lateinit var quienSoy: QuienSoy
    @Inject lateinit var gps: ControlDeGps
    @Inject lateinit var encolador: Encolador
    @Inject lateinit var arranque: Arranque

    override fun onCreate() {
        super.onCreate() // Hilt inyecta aquí: WorkManager ya tiene su fábrica
        // Un borrado que quedó a medias se termina ANTES de que WorkManager arranque (se inicia a pedido en `programar`) y antes
        // de cualquier pantalla. ponytail: bloquea el arranque sólo en ese caso raro (borrar tablas y archivos: < 1 s).
        // r5: y la checada que quedó esperando el GPS cuando Android mató la app se completa sin ubicación (antes de cualquier
        // pantalla, así nada nuevo se aparta mientras tanto). ponytail: una consulta a Room en el arranque, casi siempre vacía.
        // r9 · R-C: si Room o los archivos fallan aquí, la app abre igual (sin cerrarse en cada arranque): lo que quede (una marca
        // o una APARTADA) se reintenta en el siguiente arranque y nada se borra por el error. Mientras quede la marca, activar se
        // niega. Una falla del almacén cifrado detiene la app (R-D) y se vuelve a intentar al abrirla.
        // r12 (R-D en el arranque): si una escritura del almacén no se guarda aquí, la app queda DETENIDA (sólo su pantalla) y
        // no arranca WorkManager, ni el GPS, ni la escucha de la red.
        runBlocking {
            arrancar(arranque, recuperar = { borrado.terminarSiQuedoAMedias(); encolador.completarApartadas() }) { completadas ->
                if (completadas > 0) Disparador.ahora(this@CampoApplication)
                // G: el GPS del turno sigue el ajuste de SU sucursal también cuando un latido o un `entrar` lo cambian (r3 #3).
                CoroutineScope(SupervisorJob() + Dispatchers.IO).vigilarElSeguimiento(quienSoy, gps)
                Disparador.programar(this@CampoApplication)
                Disparador.alVolverLaRed(this@CampoApplication)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}

/**
 * El arranque, antes de cualquier pantalla: [recuperar] (terminar un borrado a medias, completar las checadas apartadas) y,
 * sólo si no quedó DETENIDO (r12), [seguir] con lo demás. Una falla de Room o de archivos no detiene (R-C): se reintenta en
 * el siguiente arranque y nada se borra por el error.
 */
internal suspend fun arrancar(arranque: Arranque, recuperar: suspend () -> Int, seguir: (completadas: Int) -> Unit) {
    val completadas = arranque.recuperar {
        try {
            recuperar()
        } catch (e: ArranqueSinGuardar) {
            throw e
        } catch (e: Exception) {
            Log.w("CampoApplication", "terminar lo que quedó a medias", e)
            0
        }
    } ?: return
    seguir(completadas)
}
