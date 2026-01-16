package com.jaac.avoqado_tpv.features.activation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.domain.usecase.ActivateTerminalUseCase
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Activation ViewModel
 *
 * Manages terminal activation state and business logic.
 * Follows Clean Architecture + MVVM pattern.
 *
 * **Responsibilities:**
 * - Execute activation use case
 * - Manage UI state (Idle, Loading, Error, Success)
 * - Provide device serial number to UI
 * - Handle navigation after successful activation
 *
 * **State Management:**
 * - Uses StateFlow for reactive UI updates
 * - Immutable state classes
 * - Single source of truth
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun ActivationScreenContainer(viewModel: ActivationViewModel = hiltViewModel()) {
 *     val state by viewModel.state.collectAsStateWithLifecycle()
 *
 *     LaunchedEffect(state) {
 *         if (state is ActivationState.Success) {
 *             navController.navigate(NavRoute.Login.route)
 *         }
 *     }
 *
 *     ActivationScreen(
 *         serialNumber = viewModel.serialNumber,
 *         onActivate = viewModel::activate,
 *         isLoading = state is ActivationState.Loading,
 *         errorMessage = (state as? ActivationState.Error)?.message
 *     )
 * }
 * ```
 */
@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val activateTerminalUseCase: ActivateTerminalUseCase,
    private val deviceInfoManager: DeviceInfoManager,
    private val terminalConfigRepository: TerminalConfigRepository,
    private val merchantRepository: MerchantRepository
) : ViewModel() {

    // ========== State ==========

    private val _state = MutableStateFlow<ActivationState>(ActivationState.Idle)
    val state: StateFlow<ActivationState> = _state.asStateFlow()

    /**
     * Device serial number (format: AVQD-{androidId})
     * Read-only property for UI
     */
    val serialNumber: String = deviceInfoManager.getSerialNumber()

    // ========== Actions ==========

    /**
     * Activate terminal with activation code
     *
     * Flow:
     * 1. Validate code length (6 characters)
     * 2. Set Loading state
     * 3. Execute use case
     * 4. Handle result:
     *    - Success → Navigate to Login
     *    - Error → Show error message + retry
     *
     * @param activationCode 6-character alphanumeric code
     */
    fun activate(activationCode: String) {
        // Validate input
        if (activationCode.length != 6) {
            _state.value = ActivationState.Error(
                message = "El código debe tener exactamente 6 caracteres"
            )
            return
        }

        if (!activationCode.matches(Regex("^[A-Z0-9]{6}$"))) {
            _state.value = ActivationState.Error(
                message = "El código solo debe contener letras y números"
            )
            return
        }

        // Execute activation
        viewModelScope.launch {
            _state.value = ActivationState.Loading
            Timber.d("Activating terminal with code: $activationCode")

            when (val result = activateTerminalUseCase(serialNumber, activationCode)) {
                is Result.Success -> {
                    Timber.i("✅ Terminal activated successfully: venueId=${result.data.venueId}")

                    // CRITICAL: Fetch terminal config BEFORE allowing navigation to login
                    // This loads merchant accounts with proper CUIDs for payment recording
                    // Without this, payments will fail with 400 error (missing merchantAccountId)
                    // Note: State is already Loading from line 109, no need to set again
                    Timber.d("🔧 [Activation] Fetching terminal config...")

                    val configResult = fetchTerminalConfigAfterActivation()

                    if (configResult.isSuccess) {
                        _state.value = ActivationState.Success(
                            venueId = result.data.venueId,
                            venueName = result.data.venueName
                        )
                    } else {
                        // Config fetch failed - show error with retry option
                        // User cannot proceed until config is loaded (payments would fail)
                        Timber.e("❌ [Activation] Config fetch failed - blocking navigation")
                        _state.value = ActivationState.ConfigError(
                            venueId = result.data.venueId,
                            venueName = result.data.venueName,
                            message = "Terminal activado, pero no se pudo cargar la configuración.\n\n" +
                                    "Los pagos con tarjeta no funcionarán sin esta configuración.\n\n" +
                                    "Verifique su conexión a internet e intente nuevamente."
                        )
                    }
                }

                is Result.Error -> {
                    Timber.w("❌ Activation failed: ${result.exception.message}")
                    val errorMessage = mapErrorToUserMessage(result.exception)
                    _state.value = ActivationState.Error(message = errorMessage)
                }
            }
        }
    }

    /**
     * Reset state to Idle
     * Used when navigating back to activation screen after error
     */
    fun resetState() {
        _state.value = ActivationState.Idle
    }

    /**
     * Process QR code scanned from dashboard activation dialog
     *
     * Supports TWO QR data formats:
     *
     * **Simplified format (recommended - smaller QR, easier to scan):**
     * {
     *   "t": "a",           // type: "a" = avoqado_activation (shortened)
     *   "c": "A3F9K2"       // code: activation code
     * }
     *
     * **Legacy format (backward compatible):**
     * {
     *   "type": "avoqado_activation",
     *   "code": "A3F9K2",
     *   "venueId": "uuid",
     *   "venueName": "Venue Name",
     *   "serialNumber": "AVQD-...",
     *   "expiresAt": "2025-01-20T..."
     * }
     *
     * @param qrData Raw QR code data string
     */
    fun processQrActivation(qrData: String) {
        Timber.d("📱 [Activation] Processing QR data: $qrData")

        try {
            // Parse JSON
            val jsonObject = com.google.gson.JsonParser.parseString(qrData).asJsonObject

            // Validate type - support both "t":"a" (simplified) and "type":"avoqado_activation" (legacy)
            val typeShort = jsonObject.get("t")?.asString
            val typeLong = jsonObject.get("type")?.asString

            val isValidType = typeShort == "a" || typeLong == "avoqado_activation"
            if (!isValidType) {
                Timber.w("📱 [Activation] Invalid QR type: t=$typeShort, type=$typeLong")
                _state.value = ActivationState.Error(
                    message = "Código QR inválido. Escanee un código de activación de Avoqado."
                )
                return
            }

            // Extract activation code - support both "c" (simplified) and "code" (legacy)
            val activationCode = jsonObject.get("c")?.asString
                ?: jsonObject.get("code")?.asString
            if (activationCode.isNullOrBlank()) {
                Timber.w("📱 [Activation] QR missing activation code")
                _state.value = ActivationState.Error(
                    message = "El código QR no contiene un código de activación válido."
                )
                return
            }

            // Optional: Validate serial number matches (if present in QR - only in legacy format)
            val qrSerialNumber = jsonObject.get("serialNumber")?.asString
            if (!qrSerialNumber.isNullOrBlank() && qrSerialNumber != serialNumber) {
                Timber.w("📱 [Activation] Serial number mismatch: QR=$qrSerialNumber, Device=$serialNumber")
                _state.value = ActivationState.Error(
                    message = "Este código QR es para otro terminal.\n\n" +
                            "QR: $qrSerialNumber\n" +
                            "Este dispositivo: $serialNumber"
                )
                return
            }

            Timber.i("📱 [Activation] QR validated - activating with code: $activationCode")

            // Proceed with activation using extracted code
            activate(activationCode)

        } catch (e: com.google.gson.JsonSyntaxException) {
            Timber.e(e, "📱 [Activation] Failed to parse QR JSON")
            _state.value = ActivationState.Error(
                message = "El código QR no tiene el formato esperado. Intente con la entrada manual."
            )
        } catch (e: Exception) {
            Timber.e(e, "📱 [Activation] Error processing QR")
            _state.value = ActivationState.Error(
                message = "Error al procesar el código QR: ${e.message}"
            )
        }
    }

    /**
     * Check if terminal is already activated on backend
     *
     * **Auto-Retry Pattern:**
     * This is called periodically (every 10 seconds) from the ActivationScreen
     * to detect when the server comes back online and the terminal is already activated.
     *
     * **Why needed:**
     * User may arrive at ActivationScreen because:
     * 1. Server was down when app checked activation status → Error fallback sent them here
     * 2. Terminal IS activated on backend, just couldn't reach it
     *
     * This auto-retry allows the user to automatically navigate to Login
     * when the server comes back and confirms activation, without needing to:
     * - Close and reopen the app
     * - Enter an activation code they don't have
     *
     * **Flow:**
     * 1. Check backend activation status
     * 2. If activated → Update state to AlreadyActivated (triggers navigation)
     * 3. If not activated or error → Do nothing (stay on screen)
     *
     * @return true if terminal is activated on backend, false otherwise
     */
    suspend fun checkAlreadyActivatedOnBackend(): Boolean {
        // Don't check if we're already in Loading, Success, or ConfigError state
        val currentState = _state.value
        if (currentState is ActivationState.Loading ||
            currentState is ActivationState.Success ||
            currentState is ActivationState.ConfigError) {
            return false
        }

        return try {
            val result = deviceInfoManager.checkActivationStatusWithBackend()
            when (result) {
                is Result.Success -> {
                    val status = result.data
                    if (status.isActivated) {
                        Timber.i("🔄 [Activation] Auto-retry detected terminal is already activated!")
                        Timber.d("   → venueId: ${status.venueId}")
                        Timber.d("   → venueName: ${status.venueName}")

                        // Update state to trigger navigation
                        _state.value = ActivationState.AlreadyActivated(
                            venueId = status.venueId ?: "",
                            venueName = status.venueName ?: ""
                        )
                        true
                    } else {
                        Timber.d("🔄 [Activation] Auto-retry: terminal not activated yet")
                        false
                    }
                }
                is Result.Error -> {
                    // Server still unreachable - silently ignore (we're already on this screen)
                    Timber.d("🔄 [Activation] Auto-retry: server unreachable (${result.exception?.message})")
                    false
                }
            }
        } catch (e: Exception) {
            Timber.d("🔄 [Activation] Auto-retry error: ${e.message}")
            false
        }
    }

    // ========== Private Methods ==========

    /**
     * Fetch terminal configuration immediately after successful activation
     *
     * **Why this is critical:**
     * - At app startup, terminal was NOT activated → config fetch was skipped
     * - MerchantRepository still has hardcoded fallback accounts
     * - Fallback accounts lack `merchantAccountId` (backend CUID)
     * - Without CUID, backend rejects payment recording with 400 error
     *
     * **What this does:**
     * 1. Fetch terminal config from backend (includes merchant accounts)
     * 2. Replace MerchantRepository fallback accounts with backend data
     * 3. Now merchant accounts have proper CUIDs for payment recording
     *
     * **Error handling:**
     * Returns Result to allow caller to handle success/failure appropriately.
     * Navigation to login is BLOCKED until config is loaded successfully.
     *
     * @return Result.success if config loaded, Result.failure otherwise
     */
    private suspend fun fetchTerminalConfigAfterActivation(): kotlin.Result<Unit> {
        Timber.d("🔧 [Activation] Fetching terminal config after successful activation...")

        // Note: terminalConfigRepository returns kotlin.Result, not our custom Result
        val configResult = terminalConfigRepository.fetchConfig(serialNumber)

        return configResult.fold(
            onSuccess = { (terminalInfo, merchantAccounts) ->
                Timber.i("✅ [Activation] Terminal config loaded successfully")
                Timber.d("   📋 Terminal: ${terminalInfo.brand} ${terminalInfo.model}")
                Timber.d("   🏢 Venue: ${terminalInfo.venueName}")
                Timber.i("   🏪 Merchant accounts: ${merchantAccounts.size}")
                merchantAccounts.forEach { merchant ->
                    Timber.d("      ✅ ${merchant.displayName} (id=${merchant.merchantAccountId}, serial=${merchant.serialNumber})")
                }

                // Replace fallback accounts with backend data (includes merchantAccountId CUIDs)
                merchantRepository.updateMerchants(merchantAccounts)

                kotlin.Result.success(Unit)
            },
            onFailure = { error ->
                Timber.e(error, "❌ [Activation] Failed to load terminal config")
                Timber.d("   ⚠️ User will see error with retry option")
                kotlin.Result.failure(error)
            }
        )
    }

    /**
     * Retry fetching terminal config after a failure
     *
     * Called when user taps "Reintentar" on ConfigError state.
     * Preserves venueId/venueName from previous successful activation.
     */
    fun retryConfigFetch() {
        val currentState = _state.value
        if (currentState !is ActivationState.ConfigError) {
            Timber.w("⚠️ [Activation] retryConfigFetch called in wrong state: $currentState")
            return
        }

        viewModelScope.launch {
            _state.value = ActivationState.Loading
            Timber.d("🔄 [Activation] Retrying config fetch...")

            val configResult = fetchTerminalConfigAfterActivation()

            if (configResult.isSuccess) {
                _state.value = ActivationState.Success(
                    venueId = currentState.venueId,
                    venueName = currentState.venueName
                )
            } else {
                // Still failed - show error again
                _state.value = currentState // Restore ConfigError state
            }
        }
    }

    /**
     * Map API exceptions to user-friendly error messages
     *
     * Backend error responses:
     * - 400: "Activation code expired" or "Terminal already activated"
     * - 401: "Invalid activation code. X attempts remaining" or "Terminal locked"
     * - 404: "Terminal not registered"
     */
    private fun mapErrorToUserMessage(exception: ApiException): String {
        return when (exception) {
            is ApiException.HttpError -> {
                when (exception.code) {
                    400 -> {
                        when {
                            exception.errorMessage.contains("expired", ignoreCase = true) ->
                                "Código de activación expirado. Solicite uno nuevo desde el dashboard."

                            exception.errorMessage.contains("already activated", ignoreCase = true) ->
                                "Este terminal ya está activado."

                            else -> exception.errorMessage
                        }
                    }

                    401 -> {
                        when {
                            exception.errorMessage.contains("locked", ignoreCase = true) ->
                                "Terminal bloqueado por demasiados intentos fallidos. Contacte soporte."

                            exception.errorMessage.contains("intento", ignoreCase = true) ->
                                exception.errorMessage // Already has "X intento(s) restantes"

                            else -> "Código de activación incorrecto. Verifique e intente nuevamente."
                        }
                    }

                    404 -> "Terminal no registrado. Contacte al administrador."

                    else -> exception.userMessage
                }
            }

            is ApiException.NetworkError ->
                "Error de conexión. Verifique su internet e intente nuevamente."

            is ApiException.SessionExpired ->
                "Sesión expirada. Por favor inicie sesión nuevamente."

            is ApiException.PermissionDenied ->
                "No tiene permisos para realizar esta acción."

            is ApiException.ParseError ->
                "Error al procesar respuesta del servidor."

            is ApiException.Unknown ->
                "Error desconocido. Por favor intente nuevamente."

            is ApiException.ValidationError ->
                exception.userMessage
        }
    }
}

/**
 * Activation UI State
 *
 * Sealed class representing all possible states of activation screen
 */
sealed class ActivationState {
    /**
     * Idle state - Waiting for user input
     */
    data object Idle : ActivationState()

    /**
     * Loading state - Activation in progress
     */
    data object Loading : ActivationState()

    /**
     * Error state - Activation failed
     * @param message User-friendly error message
     */
    data class Error(val message: String) : ActivationState()

    /**
     * Success state - Terminal activated successfully
     * @param venueId Venue UUID (stored in SecureStorage)
     * @param venueName Human-readable venue name
     */
    data class Success(
        val venueId: String,
        val venueName: String
    ) : ActivationState()

    /**
     * Config error state - Terminal activated but config fetch failed
     *
     * Terminal IS activated (stored in SecureStorage), but merchant accounts
     * with backend CUIDs couldn't be loaded. Payments will fail without this.
     *
     * User must retry config fetch before proceeding to login.
     *
     * @param venueId Venue UUID (from successful activation)
     * @param venueName Human-readable venue name
     * @param message User-friendly error message with retry instructions
     */
    data class ConfigError(
        val venueId: String,
        val venueName: String,
        val message: String
    ) : ActivationState()

    /**
     * Already activated state - Backend confirmed terminal is already activated
     *
     * This state is triggered by the auto-retry mechanism when the backend
     * becomes reachable and confirms the terminal is already activated.
     *
     * This handles the case where:
     * 1. Server was down when app started
     * 2. App sent user to Activation screen as fallback
     * 3. Server came back online
     * 4. Auto-retry detected terminal is already activated
     * 5. Navigate to Login without requiring activation code
     *
     * @param venueId Venue UUID (from backend status response)
     * @param venueName Human-readable venue name
     */
    data class AlreadyActivated(
        val venueId: String,
        val venueName: String
    ) : ActivationState()
}
