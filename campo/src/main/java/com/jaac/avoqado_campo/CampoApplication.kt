package com.jaac.avoqado_campo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.vigilarElSeguimiento
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

    override fun onCreate() {
        super.onCreate() // Hilt inyecta aquí: WorkManager ya tiene su fábrica
        // Un borrado que quedó a medias se termina ANTES de que WorkManager arranque (se inicia a pedido en `programar`) y antes
        // de cualquier pantalla. ponytail: bloquea el arranque sólo en ese caso raro (borrar tablas y archivos: < 1 s).
        // r5: y la checada que quedó esperando el GPS cuando Android mató la app se completa sin ubicación (antes de cualquier
        // pantalla, así nada nuevo se aparta mientras tanto). ponytail: una consulta a Room en el arranque, casi siempre vacía.
        val completadas = runBlocking { borrado.terminarSiQuedoAMedias(); encolador.completarApartadas() }
        if (completadas > 0) Disparador.ahora(this)
        // G: el GPS del turno sigue el ajuste de SU sucursal también cuando un latido o un `entrar` lo cambian (r3 #3).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).vigilarElSeguimiento(quienSoy, gps)
        Disparador.programar(this)
        Disparador.alVolverLaRed(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
