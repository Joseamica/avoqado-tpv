package com.jaac.avoqado_campo.red

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** ¿Hay red AHORA? Se lee de la red por default del sistema en cada pregunta (nunca una bandera guardada). */
@Singleton
open class Conectividad @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    open fun hayRed(): Boolean = runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.conInternet() == true }.getOrDefault(false)

    /** Para la UI: cada cambio de la red por default (sólo mientras alguien escucha). No es la escucha del Disparador. */
    open val cambios: Flow<Boolean> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(caps.conInternet()) }
            override fun onLost(network: Network) { trySend(false) }
        }
        trySend(hayRed())
        val registrada = runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess
        awaitClose { if (registrada) runCatching { cm.unregisterNetworkCallback(cb) } }
    }.distinctUntilChanged()

    private fun NetworkCapabilities.conInternet() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
