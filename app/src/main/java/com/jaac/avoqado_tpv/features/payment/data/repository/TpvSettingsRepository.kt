package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.toDomain
import com.jaac.avoqado_tpv.core.data.network.dto.toDto
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.plan.data.PlanManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for TPV screen configuration settings.
 *
 * **Per-Terminal Settings:**
 * Each terminal has its own individual settings, configured via:
 * - GET /tpv/terminals/{serialNumber}/config (fetch)
 * - PUT /tpv/terminals/{serialNumber}/settings (update)
 *
 * **Pattern:**
 * - Fetch from backend on app startup / after login
 * - Cache locally for offline access
 * - Allow editing and sync to backend
 *
 * **Usage:**
 * ```kotlin
 * @Inject lateinit var tpvSettingsRepository: TpvSettingsRepository
 *
 * // Observe settings (StateFlow)
 * tpvSettingsRepository.settings.collect { settings ->
 *     if (settings.showTipScreen) { /* show tip */ }
 * }
 *
 * // Refresh from backend
 * tpvSettingsRepository.refreshFromTerminalConfig(serialNumber)
 *
 * // Save changes to backend
 * tpvSettingsRepository.saveSettings(serialNumber, updatedSettings)
 * ```
 */
@Singleton
class TpvSettingsRepository @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage,
    private val planManager: PlanManager
) {
    /**
     * Current TPV settings as StateFlow.
     * Initialized from SecureStorage cache on creation.
     */
    private val _settings = MutableStateFlow(secureStorage.getTpvSettings())
    val settings: StateFlow<TpvSettings> = _settings.asStateFlow()

    /**
     * Refresh settings from terminal config endpoint.
     *
     * Call this after login or when refreshing terminal config.
     * On network error, returns cached settings (offline-first).
     *
     * @param serialNumber Terminal serial number
     * @return Result with settings (success) or cached settings (on error)
     */
    suspend fun refreshFromTerminalConfig(serialNumber: String): Result<TpvSettings> {
        return try {
            Timber.d("🔄 Fetching TPV settings for terminal: $serialNumber")
            val response = apiService.getTerminalConfig(serialNumber)

            if (response.isSuccessful) {
                val configData = response.body()?.data
                val tpvSettingsDto = configData?.tpvSettings

                // Plan-tier gating (additive 2026-06): cache the optional `plan`
                // object alongside the settings. Absent (old server) → null →
                // PlanManager clears its cache and the app FAILS OPEN (no gates).
                // Only updated on SUCCESSFUL fetches — offline keeps the cache.
                planManager.update(configData?.plan)

                var settings = if (tpvSettingsDto != null) {
                    tpvSettingsDto.toDomain()
                } else {
                    // Backend returned config without tpvSettings - use defaults
                    Timber.w("⚠️ Terminal has no tpvSettings configured, using defaults")
                    TpvSettings.DEFAULT
                }

                // Don't let a stale server value clobber an unsynced local-first
                // write. Real bug found on hardware: toggle restaurantModeEnabled
                // ON while offline (updateSettingLocalFirst) → app restarts
                // before the background sync ever gets a chance to run → THIS
                // fetch succeeds (we're online again) but the server still has
                // the OLD value → without this guard the switch silently
                // reverts. Keep the local value and (since we're online right
                // now) opportunistically flush it.
                val restaurantModePending = secureStorage.getRestaurantModePendingSync()
                if (restaurantModePending) {
                    settings = settings.copy(restaurantModeEnabled = _settings.value.restaurantModeEnabled)
                }

                // Cache locally for offline access
                secureStorage.saveTpvSettings(settings)
                _settings.value = settings

                // Sync enableShifts from backend to SecureStorage
                // This allows ShiftRepository.isShiftSystemEnabled() to read the backend value
                secureStorage.setShiftSystemEnabled(settings.enableShifts)
                Timber.i("✅ Synced enableShifts from backend: ${settings.enableShifts}")

                Timber.i("✅ TPV settings loaded: showReview=${settings.showReviewScreen}, showTip=${settings.showTipScreen}, showReceipt=${settings.showReceiptScreen}, enableShifts=${settings.enableShifts}, requireClockInPhoto=${settings.requireClockInPhoto}, requireClockOutPhoto=${settings.requireClockOutPhoto}")

                if (restaurantModePending) {
                    // We're online (this fetch just succeeded) — flush the
                    // pending local-first change now instead of waiting for
                    // the operator to toggle something else.
                    syncSettingsToBackend(settings)
                }

                Result.success(settings)
            } else {
                val errorCode = response.code()
                Timber.w("⚠️ Failed to fetch TPV settings (HTTP $errorCode), using cached")
                Result.success(_settings.value)
            }
        } catch (e: Exception) {
            Timber.w(e, "📴 Offline: Using cached TPV settings")
            Result.success(_settings.value)
        }
    }

    /**
     * Refresh settings from backend (legacy method for backward compatibility).
     * Uses stored serial number from SecureStorage.
     *
     * @param venueId Venue ID (not used, kept for backward compatibility)
     * @return Result with settings
     */
    @Deprecated("Use refreshFromTerminalConfig(serialNumber) instead")
    suspend fun refreshSettings(venueId: String): Result<TpvSettings> {
        val serialNumber = secureStorage.getSerialNumber()
        return if (serialNumber != null) {
            refreshFromTerminalConfig(serialNumber)
        } else {
            Timber.w("⚠️ No serial number available, using cached settings")
            Result.success(_settings.value)
        }
    }

    /**
     * Save settings to backend and update local cache.
     *
     * @param serialNumber Terminal serial number
     * @param settings Updated settings to save
     * @return Result with saved settings or error
     */
    suspend fun saveSettings(serialNumber: String, settings: TpvSettings): Result<TpvSettings> {
        return try {
            Timber.d("💾 Saving TPV settings for terminal: $serialNumber")
            val response = apiService.updateTpvSettings(serialNumber, settings.toDto())

            if (response.isSuccessful && response.body()?.success == true) {
                val savedSettings = response.body()?.data?.toDomain() ?: settings

                // Update local cache
                secureStorage.saveTpvSettings(savedSettings)
                _settings.value = savedSettings

                Timber.i("✅ TPV settings saved: showReview=${savedSettings.showReviewScreen}, showTip=${savedSettings.showTipScreen}, showReceipt=${savedSettings.showReceiptScreen}")
                Result.success(savedSettings)
            } else {
                val errorCode = response.code()
                val errorMessage = when (errorCode) {
                    401 -> "No autorizado. Inicia sesión nuevamente."
                    403 -> "No tienes permiso para modificar esta configuración."
                    404 -> "Terminal no encontrado."
                    else -> "Error al guardar configuración (HTTP $errorCode)"
                }
                Timber.e("❌ Failed to save TPV settings: HTTP $errorCode")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error saving TPV settings")
            Result.failure(Exception("Error de conexión: ${e.message}"))
        }
    }

    /**
     * Update settings locally and sync to backend.
     * Uses stored serial number from SecureStorage.
     *
     * @param settings Updated settings
     * @return Result with saved settings or error
     */
    suspend fun updateSettings(settings: TpvSettings): Result<TpvSettings> {
        val serialNumber = secureStorage.getSerialNumber()
        return if (serialNumber != null) {
            saveSettings(serialNumber, settings)
        } else {
            Timber.e("❌ No serial number available, cannot save settings")
            Result.failure(Exception("Terminal no configurado"))
        }
    }

    /**
     * Update a single setting and sync to backend.
     */
    suspend fun updateSetting(
        showReviewScreen: Boolean? = null,
        showTipScreen: Boolean? = null,
        showReceiptScreen: Boolean? = null,
        defaultTipPercentage: Int? = null,
        tipSuggestions: List<Int>? = null,
        requirePinLogin: Boolean? = null
    ): Result<TpvSettings> {
        val current = _settings.value
        val updated = current.copy(
            showReviewScreen = showReviewScreen ?: current.showReviewScreen,
            showTipScreen = showTipScreen ?: current.showTipScreen,
            showReceiptScreen = showReceiptScreen ?: current.showReceiptScreen,
            defaultTipPercentage = defaultTipPercentage ?: current.defaultTipPercentage,
            tipSuggestions = tipSuggestions ?: current.tipSuggestions,
            requirePinLogin = requirePinLogin ?: current.requirePinLogin
        )
        return updateSettings(updated)
    }

    /**
     * Persist [settings] LOCALLY first — durable to [SecureStorage] AND the
     * in-memory [settings] StateFlow that every screen (Settings, Home/
     * WelcomeScreen) observes — and ONLY THEN does a caller sync to the
     * backend separately via [syncSettingsToBackend].
     *
     * **Why this exists:** [updateSettings]/[saveSettings] are network-only —
     * they write to SecureStorage/StateFlow ONLY inside the HTTP success
     * branch. For a terminal-local display preference (e.g.
     * `restaurantModeEnabled`, which just decides what tile shows on Home)
     * that is the same defect class as the printer-config
     * wipe-on-failed-refresh bug documented in avoqado-server
     * `.claude/rules/offline-first-y-hub-lan.md` §4: a settings write that
     * only "sticks" after a successful round trip fails exactly when this
     * app's whole architecture promises it shouldn't — offline is a normal
     * state, not an error. This app is offline-first; per-terminal display
     * toggles must hold with zero connectivity.
     *
     * This function is synchronous (not `suspend`) on purpose: the caller
     * can invoke it directly from a click handler, with zero risk of the
     * write being lost to a cancelled coroutine (e.g. the user navigates
     * away — and the screen's ViewModel gets cleared — the instant after
     * tapping).
     */
    fun updateSettingLocalFirst(settings: TpvSettings) {
        secureStorage.saveTpvSettings(settings)
        // Mark unsynced BEFORE the (separate, best-effort) network attempt —
        // refreshFromTerminalConfig must protect this value from a racing GET
        // starting the instant this function returns, not just while the sync
        // call is in flight.
        secureStorage.setRestaurantModePendingSync(true)
        _settings.value = settings
        Timber.i("💾 [TpvSettings] Local-first write: restaurantModeEnabled=${settings.restaurantModeEnabled}")
    }

    /**
     * Best-effort background sync of [settings] to the backend, for callers
     * using the [updateSettingLocalFirst] pattern. Never throws and NEVER
     * reverts the local write on failure — a failed/slow/offline sync just
     * means the backend reconciles on the next successful
     * [refreshFromTerminalConfig] or [updateSettings] call. The local state
     * (already written by [updateSettingLocalFirst]) remains the terminal's
     * truth in the meantime.
     */
    suspend fun syncSettingsToBackend(settings: TpvSettings) {
        val serialNumber = secureStorage.getSerialNumber()
        if (serialNumber == null) {
            Timber.w("⚠️ [TpvSettings] No serial number yet — cannot sync local-first setting to backend")
            return
        }
        try {
            val response = apiService.updateTpvSettings(serialNumber, settings.toDto())
            if (response.isSuccessful && response.body()?.success == true) {
                secureStorage.setRestaurantModePendingSync(false)
                Timber.i("✅ [TpvSettings] Local-first setting synced to backend")
            } else {
                Timber.w("⚠️ [TpvSettings] Backend rejected local-first setting sync (HTTP ${response.code()}) — will reconcile on next refresh")
            }
        } catch (e: Exception) {
            Timber.w(e, "📴 [TpvSettings] Offline: local-first setting saved locally, backend sync deferred")
        }
    }

    /**
     * Get current settings synchronously.
     * Useful for PaymentViewModel to check settings without StateFlow.
     */
    fun getCurrentSettings(): TpvSettings = _settings.value

    /**
     * Clear cached settings.
     * Call when switching venues or logging out.
     */
    fun clearCache() {
        secureStorage.clearTpvSettings()
        _settings.value = TpvSettings.DEFAULT
        // Plan info is venue-scoped like the settings — clear it together so a
        // venue switch never gates (or un-gates) with the previous venue's plan.
        planManager.clearCache()
        Timber.d("🗑️ TPV settings cache cleared")
    }
}
