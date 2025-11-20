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
     * Emits:
     * - NetworkStatus.Available when connection is restored
     * - NetworkStatus.Unavailable when connection is lost
     *
     * @return Flow<NetworkStatus> with distinct values (no duplicates)
     */
    fun observe(): Flow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Timber.i("🌐 [Connectivity] Network AVAILABLE")
                trySend(NetworkStatus.Available)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Timber.w("⚠️ [Connectivity] Network LOST")
                trySend(NetworkStatus.Unavailable)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val isConnected = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                if (isConnected) {
                    Timber.d("🌐 [Connectivity] Network capabilities changed - CONNECTED")
                    trySend(NetworkStatus.Available)
                } else {
                    Timber.d("⚠️ [Connectivity] Network capabilities changed - DISCONNECTED")
                    trySend(NetworkStatus.Unavailable)
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Emit initial state
        val currentStatus = getCurrentNetworkStatus()
        trySend(currentStatus)
        Timber.d("🌐 [Connectivity] Initial status: $currentStatus")

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
