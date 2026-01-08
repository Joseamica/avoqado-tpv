package com.jaac.avoqado_tpv.features.authentication.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.authentication.domain.models.AuthResponse
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import com.jaac.avoqado_tpv.features.timeclock.domain.repository.TimeEntryRepository
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
 * Connects Socket.IO after successful authentication.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val secureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage,
    private val socketManager: SocketManager,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val timeEntryRepository: TimeEntryRepository
) : ViewModel() {

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    // Cached venue logo from last successful login
    private val _venueLogo = MutableStateFlow<String?>(null)
    val venueLogo: StateFlow<String?> = _venueLogo.asStateFlow()

    init {
        // Load cached venue logo on ViewModel creation
        _venueLogo.value = secureStorage.getVenueLogo()
        Timber.d("📷 Cached venue logo: ${_venueLogo.value}")
    }

    /**
     * Login with PIN
     *
     * @param pin 4-10 digit PIN
     * @param venueId Venue ID from activation
     */
    fun loginWithPin(pin: String, venueId: String) {
        viewModelScope.launch {
            _state.value = LoginState.Loading

            val result = authRepository.loginWithPin(pin, venueId)

            _state.value = when (result) {
                is Result.Success -> {
                    Timber.d("✅ Login successful: ${result.data.staff.displayName}")
                    Timber.d("📊 Venue status: ${result.data.venue.status}")

                    // Save venue status for mid-session checks
                    secureStorage.saveVenueStatus(result.data.venue.status)

                    // 🔌 Connect Socket.IO with JWT token
                    connectSocketIO(result.data)

                    // 🕐 Check if clock-in is required to access the system
                    val clockInRequired = tpvSettingsRepository.getCurrentSettings().requireClockInToLogin
                    if (clockInRequired) {
                        Timber.d("🕐 Clock-in required to login - checking status...")
                        checkClockInStatus(result.data)
                    } else {
                        // NOTE: Blumon SDK init moved to HomeViewModel
                        // (LoginViewModel gets destroyed on navigation, cancelling coroutines)
                        LoginState.Success(result.data)
                    }
                }
                is Result.Error -> {
                    // ✅ Use userMessage (user-friendly) instead of message (technical)
                    val userFriendlyMessage = result.exception.userMessage
                    val technicalMessage = result.exception.message ?: "Error desconocido"

                    Timber.w("❌ Login failed: $technicalMessage")
                    Timber.d("📝 User message: $userFriendlyMessage")

                    // Helper to check keywords in both messages
                    fun containsAny(vararg keywords: String): Boolean {
                        return keywords.any { keyword ->
                            technicalMessage.contains(keyword, ignoreCase = true) ||
                            userFriendlyMessage.contains(keyword, ignoreCase = true)
                        }
                    }

                    // Check for specific error types
                    when {
                        // Terminal activation errors
                        containsAny("TERMINAL_NOT_ACTIVATED", "Serial number not found", "must be activated") -> {
                            Timber.w("⚠️ Terminal not activated - requires activation")
                            LoginState.TerminalNotActivated
                        }

                        // Venue status errors (SUSPENDED, CLOSED, etc.)
                        // Check both Spanish and English keywords in BOTH messages
                        containsAny("not operational", "suspended", "suspendido", "cerrado permanentemente") -> {
                            Timber.w("⚠️ Venue not operational: $userFriendlyMessage")
                            LoginState.VenueNotOperational(
                                message = userFriendlyMessage
                            )
                        }

                        // Staff inactive/not found
                        containsAny("no longer active", "ya no está activo") -> {
                            Timber.w("⚠️ Staff no longer active")
                            LoginState.Error(
                                "Tu cuenta ya no está activa en este establecimiento.\n\n" +
                                    "Contacta al administrador."
                            )
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

    /**
     * 🕐 Check if staff has an active clock-in before allowing access
     *
     * Called when TpvSettings.requireClockInToLogin is enabled.
     * Checks the staff's time entry status and returns appropriate state.
     *
     * @param authResponse Authentication response with staff info
     * @return LoginState based on clock-in status
     */
    private suspend fun checkClockInStatus(authResponse: AuthResponse): LoginState {
        val staffId = authResponse.staff.id
        val staffName = authResponse.staff.displayName
        val venueId = authResponse.venueId

        return try {
            val result = timeEntryRepository.findActiveEntryForStaff(venueId, staffId)

            when {
                result.isSuccess -> {
                    val activeEntry = result.getOrNull()

                    when {
                        activeEntry == null -> {
                            // No active clock-in
                            Timber.w("🕐 Staff $staffName has no active clock-in")
                            LoginState.RequiresClockIn(staffName, staffId)
                        }
                        activeEntry.status == TimeEntryStatus.ON_BREAK -> {
                            // Currently on break
                            Timber.w("🕐 Staff $staffName is on break")
                            LoginState.OnBreak(staffName, staffId)
                        }
                        activeEntry.status == TimeEntryStatus.CLOCKED_IN -> {
                            // Actively working - allow login
                            Timber.i("🕐 Staff $staffName is clocked in, allowing access")
                            LoginState.Success(authResponse)
                        }
                        else -> {
                            // Any other status (completed, etc.) - no active clock-in
                            Timber.w("🕐 Staff $staffName has entry with status: ${activeEntry.status}")
                            LoginState.RequiresClockIn(staffName, staffId)
                        }
                    }
                }
                else -> {
                    // Error checking clock-in status - allow login but log warning
                    Timber.w("⚠️ Failed to check clock-in status, allowing access: ${result.exceptionOrNull()?.message}")
                    LoginState.Success(authResponse)
                }
            }
        } catch (e: Exception) {
            // Network error - allow login but log warning
            Timber.w(e, "⚠️ Error checking clock-in status, allowing access")
            LoginState.Success(authResponse)
        }
    }

    /**
     * 🔌 Connect Socket.IO after successful login
     *
     * Establishes real-time connection to backend with JWT authentication.
     * Joins venue room to receive venue-specific events.
     *
     * @param authResponse Authentication response containing JWT token and venue context
     */
    private fun connectSocketIO(authResponse: AuthResponse) {
        viewModelScope.launch {
            try {
                // Use BLUMON_ENV to determine URL (matches NetworkModule logic)
                val socketUrl = if (BuildConfig.BLUMON_ENV == "PROD") {
                    BuildConfig.SOCKET_URL  // Production: https://api.avoqado.io
                } else {
                    BuildConfig.SOCKET_URL_DEV  // Sandbox: ngrok URL
                }

                val jwtToken = authResponse.accessToken
                val venueId = authResponse.venueId

                Timber.d("🔌 [Socket.IO] Connecting to: $socketUrl")
                Timber.d("🔌 [Socket.IO] Venue ID: $venueId")

                // Connect with JWT authentication
                socketManager.connect(
                    url = socketUrl,
                    token = jwtToken,
                    reconnection = true,
                    reconnectionAttempts = 5
                )

                // Wait for connection and join venue room
                viewModelScope.launch {
                    socketManager.isConnected.collect { connected ->
                        if (connected) {
                            Timber.i("✅ [Socket.IO] Connected successfully")

                            // Join venue room to receive venue-specific events
                            socketManager.joinVenueRoom(venueId)
                            Timber.i("✅ [Socket.IO] Joined venue room: $venueId")
                        }
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Socket.IO] Connection failed")
                // Don't block login on socket failure - app can work without real-time events
            }
        }
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

    /**
     * Venue Not Operational State
     *
     * This state occurs when:
     * - Venue is SUSPENDED (payment issues, policy violations)
     * - Venue is ADMIN_SUSPENDED (suspended by Avoqado admin)
     * - Venue is CLOSED (permanently closed)
     *
     * UI should:
     * - Show informative message explaining the venue is not operational
     * - Suggest contacting administrator
     * - NOT allow login until venue status changes
     */
    data class VenueNotOperational(val message: String) : LoginState()

    /**
     * Clock-In Required State
     *
     * This state occurs when:
     * - TpvSettings.requireClockInToLogin is enabled
     * - Staff authenticated successfully but has no active clock-in
     *
     * UI should:
     * - Show message explaining clock-in is required before accessing the system
     * - Provide button to go to Timeclock screen
     * - NOT allow access to Home until clocked in
     *
     * @param staffName Staff member's name for display
     * @param staffId Staff ID (needed to navigate to timeclock)
     */
    data class RequiresClockIn(
        val staffName: String,
        val staffId: String
    ) : LoginState()

    /**
     * On Break State
     *
     * This state occurs when:
     * - TpvSettings.requireClockInToLogin is enabled
     * - Staff authenticated successfully but is currently on break
     *
     * UI should:
     * - Show message explaining they must end their break to access the system
     * - Provide button to go to Timeclock screen to end break
     * - NOT allow access to Home until break is ended
     *
     * @param staffName Staff member's name for display
     * @param staffId Staff ID (needed to navigate to timeclock)
     */
    data class OnBreak(
        val staffName: String,
        val staffId: String
    ) : LoginState()
}
