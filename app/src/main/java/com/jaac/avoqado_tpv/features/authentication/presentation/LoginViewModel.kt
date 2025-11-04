package com.jaac.avoqado_tpv.features.authentication.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.authentication.domain.models.AuthResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Login ViewModel
 *
 * Handles PIN login state and business logic.
 * Uses StateFlow for reactive UI updates.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    /**
     * Login with PIN
     *
     * @param pin 4-6 digit PIN
     * @param venueId Venue ID from activation
     */
    fun loginWithPin(pin: String, venueId: String) {
        viewModelScope.launch {
            _state.value = LoginState.Loading

            val result = authRepository.loginWithPin(pin, venueId)

            _state.value = when (result) {
                is Result.Success -> {
                    Timber.d("✅ Login successful: ${result.data.staff.displayName}")
                    LoginState.Success(result.data)
                }
                is Result.Error -> {
                    // ✅ Use userMessage (user-friendly) instead of message (technical)
                    val userFriendlyMessage = result.exception.userMessage
                    val technicalMessage = result.exception.message ?: "Error desconocido"

                    Timber.w("❌ Login failed: $technicalMessage")

                    // Check for terminal activation errors (client-side or server-side)
                    when {
                        technicalMessage.contains("TERMINAL_NOT_ACTIVATED", ignoreCase = true) ||
                        technicalMessage.contains("Serial number not found", ignoreCase = true) ||
                        technicalMessage.contains("must be activated", ignoreCase = true) -> {
                            Timber.w("⚠️ Terminal not activated - requires activation")
                            LoginState.TerminalNotActivated
                        }
                        else -> {
                            // ✅ Show backend message to user (e.g., "PIN incorrecto, 3 intentos restantes")
                            LoginState.Error(userFriendlyMessage)
                        }
                    }
                }
            }
        }
    }

    /**
     * Reset state to idle
     */
    fun resetState() {
        _state.value = LoginState.Idle
    }
}

/**
 * Login UI State
 */
sealed class LoginState {
    data object Idle : LoginState()
    data object Loading : LoginState()
    data class Success(val authResponse: AuthResponse) : LoginState()
    data class Error(val message: String) : LoginState()

    /**
     * Terminal Not Activated State
     *
     * This state occurs when:
     * - Admin manually deactivated the terminal (set activatedAt = null)
     * - User tries to login after logout
     * - Backend rejects login with TERMINAL_NOT_ACTIVATED error
     *
     * UI should:
     * - Navigate to activation screen
     * - Show message: "Este terminal ha sido desactivado. Solicita un nuevo código de activación al administrador."
     * - Clear any partial session data
     */
    data object TerminalNotActivated : LoginState()
}
