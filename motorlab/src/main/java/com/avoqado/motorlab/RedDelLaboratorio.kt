package com.avoqado.motorlab

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.avoqado.cobropropio.dominio.Red

/**
 * ¿Hay red? (bloque 4-L · L-c): la red activa con INTERNET y VALIDATED (Android ya comprobó que sale), sin ping. La usan el
 * motor antes de pedir la tarjeta y el lector justo antes de sondear. Pide `ACCESS_NETWORK_STATE` (el manifiesto de L-a).
 */
internal class RedDelLaboratorio(contexto: Context) : Red {
    private val conexiones = contexto.getSystemService(ConnectivityManager::class.java)

    override fun hayRed(): Boolean {
        val capacidades = conexiones.activeNetwork?.let { conexiones.getNetworkCapabilities(it) } ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
