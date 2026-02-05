package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo
import com.jaac.avoqado_tpv.core.data.network.UpdateMode
import com.jaac.avoqado_tpv.core.data.network.dto.ForceUpdateDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for checking app updates from Avoqado backend.
 *
 * This manager is responsible for:
 * - Checking for available updates on app startup
 * - Exposing update state to the UI layer
 * - Determining if an update should trigger banner or blocking modal
 *
 * Usage:
 * - Call checkForUpdates() after successful login
 * - Observe pendingUpdate and updateMode to show appropriate UI
 */
@Singleton
class UpdateCheckManager @Inject constructor(
    private val apiService: ApiService
) {

    private val _pendingUpdate = MutableStateFlow<AvoqadoUpdateInfo?>(null)
    val pendingUpdate: StateFlow<AvoqadoUpdateInfo?> = _pendingUpdate.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    /**
     * Check for available updates from Avoqado backend.
     *
     * @param environment "SANDBOX" or "PRODUCTION" based on build variant
     * @return The update info if available, null otherwise
     */
    suspend fun checkForUpdates(environment: String = getEnvironment()): AvoqadoUpdateInfo? {
        if (_isChecking.value) {
            Timber.d("🔄 [UpdateCheck] Already checking, skipping...")
            return _pendingUpdate.value
        }

        _isChecking.value = true

        return try {
            val currentVersionCode = BuildConfig.VERSION_CODE
            Timber.i("🔍 [UpdateCheck] Checking for updates... current=$currentVersionCode, env=$environment")

            val response = apiService.checkForAvoqadoUpdate(
                currentVersion = currentVersionCode,
                environment = environment
            )

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.hasUpdate && body.update != null) {
                    val update = body.update
                    Timber.i("✅ [UpdateCheck] Update available: ${update.versionName} (${update.versionCode}), mode=${update.updateMode}")
                    _pendingUpdate.value = update
                    update
                } else {
                    Timber.i("✅ [UpdateCheck] No updates available")
                    _pendingUpdate.value = null
                    null
                }
            } else {
                Timber.w("⚠️ [UpdateCheck] Failed to check: ${response.code()} ${response.message()}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [UpdateCheck] Error checking for updates")
            null
        } finally {
            _isChecking.value = false
        }
    }

    /**
     * Clear the pending update (e.g., after user dismisses banner)
     */
    fun clearPendingUpdate() {
        _pendingUpdate.value = null
    }

    /**
     * Set force update from heartbeat response (Backend Enforcement)
     *
     * **Why this exists:**
     * The initial update check can be dismissed by the user (if mode is BANNER).
     * This method is called on EVERY heartbeat (every 30 seconds) when there's a
     * FORCE update. The user cannot bypass this because it keeps getting set.
     *
     * @param forceUpdate The update info from heartbeat response
     */
    fun setForceUpdateFromHeartbeat(forceUpdate: ForceUpdateDto) {
        // Only set if we don't already have a pending update or if this is a newer version
        val current = _pendingUpdate.value
        if (current == null || forceUpdate.versionCode > current.versionCode) {
            Timber.i("🚨 [UpdateCheck] Force update set from heartbeat: v${forceUpdate.versionCode}")
            _pendingUpdate.value = AvoqadoUpdateInfo(
                id = "heartbeat-force-${forceUpdate.versionCode}",
                versionName = forceUpdate.versionName,
                versionCode = forceUpdate.versionCode,
                downloadUrl = forceUpdate.downloadUrl,
                fileSize = "0",  // Not provided by heartbeat, use placeholder
                checksum = "heartbeat",  // Not provided by heartbeat, use placeholder
                releaseNotes = forceUpdate.releaseNotes,
                updateMode = UpdateMode.FORCE,  // Always FORCE from heartbeat
                minAndroidSdk = 27,  // Minimum SDK from build.gradle (Android 8.1)
                publishedAt = java.time.Instant.now().toString()  // Current timestamp
            )
        }
    }

    /**
     * Check if current update requires blocking (FORCE mode)
     */
    fun isBlockingUpdateRequired(): Boolean {
        return _pendingUpdate.value?.updateMode == UpdateMode.FORCE
    }

    /**
     * Check if current update should show banner (BANNER mode)
     */
    fun shouldShowBanner(): Boolean {
        val update = _pendingUpdate.value ?: return false
        return update.updateMode == UpdateMode.BANNER || update.updateMode == UpdateMode.FORCE
    }

    /**
     * Set force update from HTTP 426 response (API Version Gate)
     *
     * **Square/Toast/Stripe Pattern:**
     * This is called when the backend returns HTTP 426 Upgrade Required.
     * The user cannot use the app until they update - every API call fails.
     *
     * @param update The update info from 426 response
     */
    fun setForceUpdate(update: AvoqadoUpdateInfo) {
        Timber.i("🚨 [UpdateCheck] Force update set from API 426: v${update.versionCode}")
        _pendingUpdate.value = update
    }

    /**
     * Get environment based on build variant
     */
    private fun getEnvironment(): String {
        // BuildConfig.APPLICATION_ID will be:
        // - "com.jaac.avoqado_tpv.sandbox" for sandbox builds
        // - "com.jaac.avoqado_tpv" for production builds
        return if (BuildConfig.APPLICATION_ID.contains("sandbox")) {
            "SANDBOX"
        } else {
            "PRODUCTION"
        }
    }
}
