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
     * 🔴 Las terminales Nexgo NO se actualizan por el sistema de Avoqado: su APK firmado se le
     * entrega al equipo de AngelPay y ellos lo despliegan por su TMS. Aceptar una actualización
     * de aquí no les sirve de nada y sí es peligroso.
     *
     * `nexgoProd` NO lleva `applicationIdSuffix`, así que su `APPLICATION_ID` es
     * `com.jaac.avoqado_tpv` — el **mismo package** que las builds de PAX — y por eso
     * `getEnvironment()` las reporta como `PRODUCTION`, el mismo público que TODOS los APK de
     * PAX en `AppUpdate` (publicados con `targetType=ALL`). Hoy no truena sólo porque el
     * `versionCode` más alto publicado empata con el que traen las Nexgo y la comparación es
     * mayor estricto; en cuanto se suba un APK de PAX con versionCode mayor, CADA Nexgo lo
     * vería como actualización y lo instalaría **encima** (mismo package). Una build de PAX en
     * una Nexgo trae `ENABLE_PAX_SDK=true` y sin SDK de AngelPay: terminal que no puede cobrar.
     *
     * El corte va por **procesador** (`ENABLE_PAX_SDK`), no por package —que hoy colisiona— ni
     * por versionCode —que era casualidad—. Y se aplica a las TRES puertas por las que puede
     * entrar una actualización (`checkForUpdates`, el 426 de `VersionGateInterceptor` y el
     * force-update del heartbeat), porque las dos últimas son BLOQUEANTES: dejarlas abiertas
     * significaría una Nexgo atorada en una pantalla de actualización obligatoria apuntando a
     * un APK de PAX.
     */
    private val updatesManagedByAvoqado: Boolean = BuildConfig.ENABLE_PAX_SDK

    /**
     * Check for available updates from Avoqado backend.
     *
     * @param environment "SANDBOX" or "PRODUCTION" based on build variant
     * @return The update info if available, null otherwise
     */
    suspend fun checkForUpdates(environment: String = getEnvironment()): AvoqadoUpdateInfo? {
        // Ver `updatesManagedByAvoqado`: las Nexgo se actualizan por el TMS de AngelPay.
        if (!updatesManagedByAvoqado) {
            Timber.i("⏭️ [UpdateCheck] Terminal Nexgo/AngelPay — se actualiza por el TMS de AngelPay, no por Avoqado")
            return null
        }

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
        // Ver `updatesManagedByAvoqado`: en una Nexgo esto apuntaría a un APK de PAX y la
        // dejaría atorada en una pantalla de actualización obligatoria.
        if (!updatesManagedByAvoqado) {
            Timber.i("⏭️ [UpdateCheck] Force update del heartbeat ignorado — terminal Nexgo/AngelPay")
            return
        }

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
        // Ver `updatesManagedByAvoqado`: en una Nexgo esto apuntaría a un APK de PAX y la
        // dejaría atorada en una pantalla de actualización obligatoria.
        if (!updatesManagedByAvoqado) {
            Timber.i("⏭️ [UpdateCheck] Force update del 426 ignorado — terminal Nexgo/AngelPay")
            return
        }

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
