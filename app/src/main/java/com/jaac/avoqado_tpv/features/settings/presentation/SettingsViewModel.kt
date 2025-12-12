package com.jaac.avoqado_tpv.features.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for Settings Screen
 *
 * Manages terminal configuration and TPV settings display/editing.
 * Settings are per-terminal and synced with backend.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val printerManager: PrinterManager
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        loadSettings()
        observeTpvSettings()
    }

    /**
     * Observe TPV settings changes from repository
     */
    private fun observeTpvSettings() {
        viewModelScope.launch {
            tpvSettingsRepository.settings.collect { settings ->
                _state.update { it.copy(tpvSettings = settings) }
            }
        }
    }

    /**
     * Load all settings from local storage and repository
     */
    private fun loadSettings() {
        viewModelScope.launch {
            try {
                // Load terminal info from SecureStorage
                val serialNumber = secureStorage.getSerialNumber()
                val venueName = secureStorage.getVenueName()
                val venueId = secureStorage.getVenueId()
                val venueStatus = secureStorage.getVenueStatus()

                // Load TPV settings from repository
                val tpvSettings = tpvSettingsRepository.getCurrentSettings()

                _state.update {
                    it.copy(
                        serialNumber = serialNumber,
                        venueName = venueName,
                        venueId = venueId,
                        venueStatus = venueStatus,
                        tpvSettings = tpvSettings
                    )
                }

                Timber.d("⚙️ Settings loaded: serial=$serialNumber, venue=$venueName, status=$venueStatus")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load settings")
                _state.update {
                    it.copy(message = "Error al cargar configuración")
                }
            }
        }
    }

    /**
     * Refresh TPV settings from backend
     */
    fun refreshSettings() {
        viewModelScope.launch {
            val serialNumber = _state.value.serialNumber
            if (serialNumber == null) {
                _state.update { it.copy(message = "No hay terminal configurado") }
                return@launch
            }

            _state.update { it.copy(isRefreshing = true) }

            try {
                val result = tpvSettingsRepository.refreshFromTerminalConfig(serialNumber)
                result.onSuccess { settings ->
                    _state.update {
                        it.copy(
                            tpvSettings = settings,
                            isRefreshing = false,
                            message = "Configuración actualizada"
                        )
                    }
                    Timber.i("✅ Settings refreshed from backend")
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            isRefreshing = false,
                            message = "Error al actualizar: ${error.message}"
                        )
                    }
                    Timber.e(error, "Failed to refresh settings")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        message = "Error de conexión"
                    )
                }
                Timber.e(e, "Failed to refresh settings")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TPV SETTINGS TOGGLES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Toggle show review screen setting
     */
    fun toggleShowReviewScreen() {
        val newValue = !_state.value.tpvSettings.showReviewScreen
        updateSetting { it.copy(showReviewScreen = newValue) }
    }

    /**
     * Toggle show tip screen setting
     */
    fun toggleShowTipScreen() {
        val newValue = !_state.value.tpvSettings.showTipScreen
        updateSetting { it.copy(showTipScreen = newValue) }
    }

    /**
     * Toggle show receipt screen setting
     */
    fun toggleShowReceiptScreen() {
        val newValue = !_state.value.tpvSettings.showReceiptScreen
        updateSetting { it.copy(showReceiptScreen = newValue) }
    }

    /**
     * Toggle require PIN login setting
     */
    fun toggleRequirePinLogin() {
        val newValue = !_state.value.tpvSettings.requirePinLogin
        updateSetting { it.copy(requirePinLogin = newValue) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VERIFICATION SETTINGS (Step 4: Sale Verification)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Toggle show verification screen setting
     * When enabled, shows photo/barcode capture screen after payment success
     */
    fun toggleShowVerificationScreen() {
        val newValue = !_state.value.tpvSettings.showVerificationScreen
        updateSetting { it.copy(showVerificationScreen = newValue) }
    }

    /**
     * Toggle require verification photo setting
     * When enabled, at least one photo is required to confirm verification
     */
    fun toggleRequireVerificationPhoto() {
        val newValue = !_state.value.tpvSettings.requireVerificationPhoto
        updateSetting { it.copy(requireVerificationPhoto = newValue) }
    }

    /**
     * Toggle require verification barcode setting
     * When enabled, at least one barcode scan is required to confirm verification
     */
    fun toggleRequireVerificationBarcode() {
        val newValue = !_state.value.tpvSettings.requireVerificationBarcode
        updateSetting { it.copy(requireVerificationBarcode = newValue) }
    }

    /**
     * Update default tip percentage
     */
    fun setDefaultTipPercentage(percentage: Int?) {
        updateSetting { it.copy(defaultTipPercentage = percentage) }
    }

    /**
     * Generic method to update a setting and sync to backend
     */
    private fun updateSetting(transform: (TpvSettings) -> TpvSettings) {
        viewModelScope.launch {
            val currentSettings = _state.value.tpvSettings
            val updatedSettings = transform(currentSettings)

            // Optimistic update - show change immediately
            _state.update { it.copy(tpvSettings = updatedSettings, isSaving = true) }

            try {
                val result = tpvSettingsRepository.updateSettings(updatedSettings)
                result.onSuccess { savedSettings ->
                    _state.update {
                        it.copy(
                            tpvSettings = savedSettings,
                            isSaving = false,
                            message = "Configuración guardada"
                        )
                    }
                    Timber.i("✅ Setting updated and saved")
                }.onFailure { error ->
                    // Revert to previous state on error
                    _state.update {
                        it.copy(
                            tpvSettings = currentSettings,
                            isSaving = false,
                            message = error.message ?: "Error al guardar"
                        )
                    }
                    Timber.e(error, "Failed to save setting")
                }
            } catch (e: Exception) {
                // Revert to previous state on error
                _state.update {
                    it.copy(
                        tpvSettings = currentSettings,
                        isSaving = false,
                        message = "Error de conexión"
                    )
                }
                Timber.e(e, "Failed to save setting")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Print a test receipt to verify printer connection
     */
    fun printTestReceipt() {
        viewModelScope.launch {
            _state.update { it.copy(isPrinting = true) }

            try {
                // Print test receipt using PrinterManager
                val result = printerManager.printTest()

                result.onSuccess {
                    _state.update {
                        it.copy(
                            isPrinting = false,
                            message = "Recibo de prueba impreso"
                        )
                    }
                    Timber.i("🖨️ Test receipt printed")
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            isPrinting = false,
                            message = "Error al imprimir: ${error.message}"
                        )
                    }
                    Timber.e(error, "Failed to print test receipt")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isPrinting = false,
                        message = "Error al imprimir: ${e.message}"
                    )
                }
                Timber.e(e, "Failed to print test receipt")
            }
        }
    }

    /**
     * Clear the message after it's been shown
     */
    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}

/**
 * UI State for Settings Screen
 */
data class SettingsState(
    // Terminal Info
    val serialNumber: String? = null,
    val venueName: String? = null,
    val venueId: String? = null,
    val venueStatus: VenueStatus = VenueStatus.ACTIVE,

    // TPV Settings (from backend, editable)
    val tpvSettings: TpvSettings = TpvSettings.DEFAULT,

    // Loading states
    val isRefreshing: Boolean = false,
    val isPrinting: Boolean = false,
    val isSaving: Boolean = false,

    // Feedback message
    val message: String? = null
)
