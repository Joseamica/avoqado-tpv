package com.jaac.avoqado_tpv.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ConnectivityObserver - Observes network connectivity changes
 *
 * **Use Case:** Auto-retry pattern for offline-first apps
 * When connection is lost → Show "Trabajando sin conexión" banner
 * When connection restored → Auto-retry failed requests
 *
 * **Architecture Pattern:**
 * - Singleton service (Hilt injected)
 * - Emits Flow<NetworkStatus> for reactive UI updates
 * - Uses Android ConnectivityManager.NetworkCallback
 *
 * **Usage in ViewModel:**
 * ```kotlin
 * @HiltViewModel
 * class ReportsViewModel @Inject constructor(
 *     private val connectivityObserver: ConnectivityObserver
 * ) : ViewModel() {
 *
 *     init {
 *         viewModelScope.launch {
 *             connectivityObserver.observe().collect { status ->
 *                 when (status) {
 *                     NetworkStatus.Available -> {
 *                         // Connection restored - retry failed requests
 *                         if (_state.value is State.Error) {
 *                             loadReports()
 *                         }
 *                     }
 *                     NetworkStatus.Unavailable -> {
 *                         // Connection lost - show offline banner
 *                         _isOffline.value = true
 *                     }
 *                 }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * **UI Pattern:**
 * ```kotlin
 * val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
 *
 * if (isOffline) {
 *     OfflineBanner("Trabajando sin conexión - Las ventas se guardarán localmente")
 * }
 * ```
 *
 * @see NetworkStatus
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Observe network connectivity changes as Flow.
     *
     * Android llama `onAvailable`/`onLost` por CADA red que cumple la petición. Se lleva el conjunto de redes validadas y se
     * emite Unavailable sólo cuando queda vacío (D18, PAX 16-nov: al volver el WiFi llegan dos redes y ~10 s después un
     * «Network LOST» de la vieja; antes eso decía «sin red» con la otra viva y el `distinctUntilChanged` se tragaba el corte
     * real siguiente, que el latido descubría hasta ~33 s después).
     *
     * @return Flow<NetworkStatus> with distinct values (no duplicates)
     */
    fun observe(): Flow<NetworkStatus> = callbackFlow {
        val validadas = mutableSetOf<Network>()

        // Bajo el candado: las emisiones salen en el mismo orden que los cambios del conjunto.
        fun cambiar(network: Network, validada: Boolean, motivo: String) = synchronized(validadas) {
            if (validada) validadas.add(network) else validadas.remove(network)
            val status = if (validadas.isEmpty()) NetworkStatus.Unavailable else NetworkStatus.Available
            Timber.i("🌐 [Connectivity] $motivo ($network) — redes validadas=${validadas.size} → $status")
            trySend(status)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                cambiar(network, validada = true, motivo = "Network AVAILABLE")
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                cambiar(network, validada = false, motivo = "Network LOST")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val validada = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                cambiar(network, validada, motivo = "Network capabilities changed")
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Estado inicial: si los avisos de las redes ya llegaron, mandan ellos; si no, la red activa (sus `onAvailable` vienen
        // en camino y la confirman).
        synchronized(validadas) {
            val currentStatus = if (validadas.isNotEmpty()) NetworkStatus.Available else getCurrentNetworkStatus()
            trySend(currentStatus)
            Timber.d("🌐 [Connectivity] Initial status: $currentStatus")
        }

        awaitClose {
            Timber.d("🌐 [Connectivity] Unregistering network callback")
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged() // Prevent duplicate emissions

    /**
     * Get current network status (synchronous).
     *
     * Used for initial state check.
     *
     * @return NetworkStatus.Available or NetworkStatus.Unavailable
     */
    fun getCurrentNetworkStatus(): NetworkStatus {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        return if (capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            NetworkStatus.Available
        } else {
            NetworkStatus.Unavailable
        }
    }
}

/**
 * Network connectivity status
 *
 * - Available: Internet connection available and validated
 * - Unavailable: No internet connection
 */
sealed interface NetworkStatus {
    data object Available : NetworkStatus
    data object Unavailable : NetworkStatus
}
