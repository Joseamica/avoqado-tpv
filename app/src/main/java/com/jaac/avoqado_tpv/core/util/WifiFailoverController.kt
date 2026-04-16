package com.jaac.avoqado_tpv.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.jaac.avoqado_tpv.BuildConfig
import com.pax.dal.entity.EChannelType
import com.pax.neptunelite.api.NeptuneLiteUser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class WifiToggleResult(
    val requestedEnabled: Boolean,
    val before: Boolean,
    val after: Boolean,
    val requestResult: Boolean,
    val paxChannelAttempted: Boolean,
    val paxChannelError: String?,
    val hasChangeWifiPermission: Boolean
) {
    val success: Boolean get() = after == requestedEnabled
}

/**
 * Centralized WiFi control for failover scenarios.
 *
 * Strategy:
 * 1) Try Android WifiManager API.
 * 2) If blocked, try PAX DAL WiFi channel (when available).
 */
@Singleton
class WifiFailoverController @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    private fun wifiManager(): WifiManager {
        return appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    fun isWifiEnabled(): Boolean = wifiManager().isWifiEnabled

    @Suppress("DEPRECATION")
    suspend fun setWifiEnabled(
        enabled: Boolean,
        source: String
    ): WifiToggleResult {
        val manager = wifiManager()
        val hasChangeWifiPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CHANGE_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val before = manager.isWifiEnabled
        if (before == enabled) {
            return WifiToggleResult(
                requestedEnabled = enabled,
                before = before,
                after = before,
                requestResult = true,
                paxChannelAttempted = false,
                paxChannelError = null,
                hasChangeWifiPermission = hasChangeWifiPermission
            )
        }

        var requestResult = false
        var after = before
        var paxChannelAttempted = false
        var paxChannelError: String? = null

        try {
            requestResult = manager.setWifiEnabled(enabled)
            delay(1500L)
            after = manager.isWifiEnabled
        } catch (securityException: SecurityException) {
            paxChannelError = "SecurityException: ${securityException.message}"
            Timber.w(
                securityException,
                "⚠️ [WifiFailoverController] setWifiEnabled blocked by SecurityException ($source)"
            )
        } catch (exception: Exception) {
            paxChannelError = "${exception.javaClass.simpleName}: ${exception.message}"
            Timber.w(
                exception,
                "⚠️ [WifiFailoverController] setWifiEnabled failed ($source)"
            )
        }

        // Fallback probe: PAX DAL channel-level toggle.
        if (after != enabled && BuildConfig.ENABLE_PAX_SDK) {
            try {
                val dal = NeptuneLiteUser.getInstance().getDal(appContext)
                val wifiChannel = dal?.commManager?.getChannel(EChannelType.WIFI)
                if (wifiChannel != null) {
                    paxChannelAttempted = true
                    if (enabled) wifiChannel.enable() else wifiChannel.disable()
                    delay(1500L)
                    after = manager.isWifiEnabled
                } else if (paxChannelError == null) {
                    paxChannelError = "wifiChannel=null"
                }
            } catch (error: Throwable) {
                paxChannelAttempted = true
                paxChannelError = "${error.javaClass.simpleName}: ${error.message}"
                Timber.w(
                    error,
                    "⚠️ [WifiFailoverController] PAX DAL WiFi toggle failed ($source)"
                )
            }
        }

        val result = WifiToggleResult(
            requestedEnabled = enabled,
            before = before,
            after = after,
            requestResult = requestResult,
            paxChannelAttempted = paxChannelAttempted,
            paxChannelError = paxChannelError,
            hasChangeWifiPermission = hasChangeWifiPermission
        )

        Timber.i(
            "📶 [WifiFailoverController] source=$source setWifiEnabled($enabled) | " +
                "permission=${result.hasChangeWifiPermission} | before=${result.before} | " +
                "requestResult=${result.requestResult} | paxChannelAttempted=${result.paxChannelAttempted} | " +
                "paxChannelError=${result.paxChannelError} | after=${result.after} | success=${result.success}"
        )

        return result
    }
}
