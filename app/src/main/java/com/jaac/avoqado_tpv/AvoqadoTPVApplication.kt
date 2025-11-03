package com.jaac.avoqado_tpv

import android.app.Application
import com.blumonpay.pax.utils.AppManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application class principal para Avoqado TPV
 *
 * Responsabilidades:
 * - Inicialización de Hilt para inyección de dependencias
 * - Inicialización del SDK BlumonPay
 * - Configuración global de la aplicación
 */
@HiltAndroidApp
class AvoqadoTPVApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Inicializar SDK BlumonPay en background thread
        initializeBlumonSDK()
    }

    /**
     * Inicializa el SDK de BlumonPay en un coroutine IO dispatcher
     * Esto optimiza el startup de la app evitando bloquear el main thread
     */
    private fun initializeBlumonSDK() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppManager.init(this@AvoqadoTPVApplication)
                android.util.Log.d(TAG, "SDK BlumonPay inicializado correctamente")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error al inicializar SDK BlumonPay", e)
            }
        }
    }

    companion object {
        private const val TAG = "AvoqadoTPVApp"
    }
}
