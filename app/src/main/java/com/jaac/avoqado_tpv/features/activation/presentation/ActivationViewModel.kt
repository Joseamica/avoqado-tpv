package com.jaac.avoqado_tpv.features.activation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.usecase.ActivateTerminalUseCase
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
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
    private val deviceInfoManager: DeviceInfoManager
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
                    _state.value = ActivationState.Success(
                        venueId = result.data.venueId,
                        venueName = result.data.venueName
                    )
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

    // ========== Private Methods ==========

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
}
