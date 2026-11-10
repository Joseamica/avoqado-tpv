package com.jaac.avoqado_campo.cola

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Quién despierta al [Subidor]: después de cada encolado ([ahora]), cada 15 min ([programar]) y al volver la red. */
object Disparador {
    private val conRed = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ahora(context: Context) {
        val w = OneTimeWorkRequestBuilder<TrabajoDeSubida>()
            .setConstraints(conRed)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("subir-cola", ExistingWorkPolicy.APPEND_OR_REPLACE, w)
    }

    fun programar(context: Context) {
        val w = PeriodicWorkRequestBuilder<TrabajoDeSubida>(15, TimeUnit.MINUTES).setConstraints(conRed).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("subir-cola-cada-15", ExistingPeriodicWorkPolicy.KEEP, w)
    }

    /** Una vez, al arrancar la app: cada vez que hay red, un vaciado de inmediato. */
    fun alVolverLaRed(context: Context) {
        val app = context.applicationContext
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = ahora(app)
            },
        )
    }
}
