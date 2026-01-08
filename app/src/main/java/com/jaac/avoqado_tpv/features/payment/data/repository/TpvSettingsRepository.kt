package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.toDomain
import com.jaac.avoqado_tpv.core.data.network.dto.toDto
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
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
    private val secureStorage: SecureStorage
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

                val settings = if (tpvSettingsDto != null) {
                    tpvSettingsDto.toDomain()
                } else {
                    // Backend returned config without tpvSettings - use defaults
                    Timber.w("⚠️ Terminal has no tpvSettings configured, using defaults")
                    TpvSettings.DEFAULT
                }

                // Cache locally for offline access
                secureStorage.saveTpvSettings(settings)
                _settings.value = settings

                // Sync enableShifts from backend to SecureStorage
                // This allows ShiftRepository.isShiftSystemEnabled() to read the backend value
                secureStorage.setShiftSystemEnabled(settings.enableShifts)
                Timber.i("✅ Synced enableShifts from backend: ${settings.enableShifts}")

                Timber.i("✅ TPV settings loaded: showReview=${settings.showReviewScreen}, showTip=${settings.showTipScreen}, showReceipt=${settings.showReceiptScreen}, enableShifts=${settings.enableShifts}, requireClockInPhoto=${settings.requireClockInPhoto}, requireClockOutPhoto=${settings.requireClockOutPhoto}")
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
        Timber.d("🗑️ TPV settings cache cleared")
    }
}
