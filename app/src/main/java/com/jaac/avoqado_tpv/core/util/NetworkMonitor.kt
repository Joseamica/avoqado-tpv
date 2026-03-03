package com.jaac.avoqado_tpv.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network Monitor
 *
 * Monitors network connectivity and quality for adaptive heartbeat intervals.
 * Follows Shopify POS pattern for network-aware operations.
 *
 * **Features:**
 * - Real-time network state changes (Flow-based)
 * - Network type detection (WiFi, Ethernet, Cellular)
 * - Metered network detection (to avoid large syncs on cellular)
 * - Signal strength estimation
 *
 * **Usage:**
 * ```kotlin
 * // Get current state
 * val networkInfo = networkMonitor.getCurrentNetworkInfo()
 *
 * // Observe changes
 * networkMonitor.networkStateFlow.collect { networkInfo ->
 *     if (networkInfo.type == NetworkType.WIFI) {
 *         // Increase heartbeat frequency
 *     }
 * }
 * ```
 *
 * **Adaptive Behavior (Shopify pattern):**
 * - WiFi + charging → 15s heartbeat
 * - Cellular → 60s heartbeat (conserve data)
 * - No network → Exponential backoff
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Get current network information snapshot
     */
    fun getCurrentNetworkInfo(): NetworkInfo {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        return if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            NetworkInfo(
                type = getNetworkType(capabilities),
                isMetered = connectivityManager.isActiveNetworkMetered,
                isConnected = true,
                signalStrength = getSignalStrength(capabilities)
            )
        } else {
            NetworkInfo(
                type = NetworkType.NONE,
                isMetered = false,
                isConnected = false,
                signalStrength = null
            )
        }
    }

    /**
     * Observe network state changes as Flow
     *
     * Emits new NetworkInfo whenever network state changes.
     * Useful for triggering immediate heartbeat when reconnecting.
     */
    val networkStateFlow: Flow<NetworkInfo> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentNetworkInfo())
                Timber.d("Network available: ${getCurrentNetworkInfo().type}")
            }

            override fun onLost(network: Network) {
                trySend(getCurrentNetworkInfo())
                Timber.d("Network lost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(getCurrentNetworkInfo())
                Timber.d("Network capabilities changed: ${getCurrentNetworkInfo().type}")
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Emit current state immediately
        trySend(getCurrentNetworkInfo())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    /**
     * Determine network type from capabilities
     *
     * Priority order (matching Android system priority):
     * 1. Ethernet (fastest, most reliable)
     * 2. WiFi (fast, usually unlimited)
     * 3. Cellular (slower, often metered)
     */
    private fun getNetworkType(capabilities: NetworkCapabilities): NetworkType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.OTHER
        }
    }

    /**
     * Get signal strength estimation (0-4 scale)
     *
     * Based on network capabilities signal strength.
     * Used to adjust retry logic (weak signal = longer backoff).
     *
     * 0 = Very poor
     * 1 = Poor
     * 2 = Fair
     * 3 = Good
     * 4 = Excellent
     */
    private fun getSignalStrength(capabilities: NetworkCapabilities): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val signalStrength = capabilities.signalStrength
                // SIGNAL_STRENGTH_UNSPECIFIED = Integer.MIN_VALUE — not available for this transport
                if (signalStrength == Int.MIN_VALUE) return null
                when {
                    signalStrength >= -50 -> 4 // Excellent
                    signalStrength >= -60 -> 3 // Good
                    signalStrength >= -70 -> 2 // Fair
                    signalStrength >= -80 -> 1 // Poor
                    else -> 0 // Very poor
                }
            } else {
                // On older Android, assume good if connected
                3
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get signal strength")
            null // Unknown
        }
    }

    /**
     * Check if network is suitable for large data transfers
     *
     * Returns true if:
     * - Connected to WiFi or Ethernet
     * - Network is not metered
     *
     * Use this to decide whether to sync large datasets.
     */
    fun isGoodForLargeSync(): Boolean {
        val info = getCurrentNetworkInfo()
        return info.isConnected &&
               (info.type == NetworkType.WIFI || info.type == NetworkType.ETHERNET) &&
               !info.isMetered
    }

    /**
     * Check if currently connected to any network
     */
    fun isConnected(): Boolean {
        return getCurrentNetworkInfo().isConnected
    }
}

/**
 * Network information data class
 */
data class NetworkInfo(
    val type: NetworkType,
    val isMetered: Boolean,          // True for cellular or limited WiFi
    val isConnected: Boolean,
    val signalStrength: Int?         // 0-4 scale, null if unknown
) {
    /**
     * Get recommended heartbeat interval in seconds
     *
     * Based on Shopify/Toast adaptive patterns:
     * - Ethernet: 15s (most reliable)
     * - WiFi: 30s (standard)
     * - Cellular: 60s (conserve data)
     * - No network: 120s (exponential backoff applied separately)
     */
    fun getRecommendedHeartbeatInterval(
        batteryLevel: Int,
        batteryCharging: Boolean
    ): Long {
        return when {
            // No network → Long backoff (handled by WorkManager retry)
            !isConnected -> 120L

            // Low battery + not charging → Conserve power
            batteryLevel < 20 && !batteryCharging -> 120L

            // Optimal conditions (Ethernet/WiFi + charging) → Aggressive monitoring
            batteryCharging && (type == NetworkType.ETHERNET || type == NetworkType.WIFI) -> 15L

            // Cellular → Conserve data
            type == NetworkType.CELLULAR -> 60L

            // Weak signal → Reduce attempts
            signalStrength != null && signalStrength < 2 -> 60L

            // Default: Standard monitoring
            else -> 30L
        }
    }
}

/**
 * Network type enumeration
 */
enum class NetworkType {
    WIFI,
    ETHERNET,
    CELLULAR,
    OTHER,
    NONE
}
