package com.jaac.avoqado_campo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.identidad.BorradoDelCelular
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class CampoApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var borrado: BorradoDelCelular

    override fun onCreate() {
        super.onCreate() // Hilt inyecta aquí: WorkManager ya tiene su fábrica
        // Un borrado que quedó a medias se termina ANTES de que WorkManager arranque (se inicia a pedido en `programar`) y antes
        // de cualquier pantalla. ponytail: bloquea el arranque sólo en ese caso raro (borrar tablas y archivos: < 1 s).
        runBlocking { borrado.terminarSiQuedoAMedias() }
        Disparador.programar(this)
        Disparador.alVolverLaRed(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
