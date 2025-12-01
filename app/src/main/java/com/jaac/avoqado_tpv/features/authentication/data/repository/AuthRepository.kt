package com.jaac.avoqado_tpv.features.authentication.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.authentication.data.dto.toDomain
import com.jaac.avoqado_tpv.features.authentication.data.dto.toDto
import com.jaac.avoqado_tpv.features.authentication.domain.models.AuthResponse
import com.jaac.avoqado_tpv.features.authentication.domain.models.PinLoginRequest
import com.jaac.avoqado_tpv.features.authentication.domain.models.RefreshTokenResponse
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authentication Repository
 *
 * Handles PIN login, token management, and session persistence.
 * Follows offline-first pattern with secure token storage.
 *
 * **Responsibilities:**
 * - Send PIN to backend for authentication
 * - Save JWT tokens and auth context to SecureStorage
 * - Handle network errors gracefully
 * - Provide session state (authenticated/not authenticated)
 *
 * **Security Features:**
 * - Tokens encrypted in SecureStorage (AES256-GCM)
 * - PIN never stored locally (sent directly to backend)
 * - Rate limiting on backend (10 attempts per 15 min)
 *
 * **Usage:**
 * ```kotlin
 * val result = authRepository.loginWithPin("1234", "venue-id")
 * when (result) {
 *     is Result.Success -> {
 *         // Tokens saved, navigate to home
 *         navigateToHome()
 *     }
 *     is Result.Error -> {
 *         // Show error message
 *         showError(result.exception.message)
 *     }
 * }
 * ```
 */
@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage,
    private val tpvSettingsRepository: TpvSettingsRepository
) {

    /**
     * Login with PIN
     *
     * Flow:
     * 1. Send PIN to backend (bcrypt verification)
     * 2. Receive JWT tokens + staff data
     * 3. Save to SecureStorage:
     *    - Access token (for API auth)
     *    - Refresh token (for silent renewal)
     *    - Venue ID (for tenant isolation)
     *    - Staff ID (for user context)
     *    - Staff name (for UI display)
     *    - Permissions (for feature access)
     *
     * **Network Failure Handling:**
     * - Returns Result.Error on network failures
     * - User sees error message, can retry
     * - No local state changed on failure
     *
     * **Success Response:**
     * - Backend confirms authentication
     * - Returns tokens with 24-hour expiration
     * - Includes staff role and permissions
     *
     * @param pin 4-6 digit numeric PIN
     * @param venueId Venue identifier from activation
     * @return Result with auth data or error
     */
    suspend fun loginWithPin(pin: String, venueId: String): Result<AuthResponse> {
        return try {
            Timber.d("📡 Logging in with PIN for venue $venueId")

            // Get serial number from secure storage (set during activation)
            val serialNumber = secureStorage.getSerialNumber()
                ?: return Result.Error(ApiException.ValidationError("El dispositivo debe activarse primero. Por favor, active el terminal con un código de activación."))

            val request = PinLoginRequest(
                pin = pin,
                serialNumber = serialNumber
            ).toDto()
            val response = apiService.loginWithPin(venueId, request)

            if (response.isSuccessful && response.body() != null) {
                val authDto = response.body()!!
                val authResponse = authDto.toDomain()

                // Save session to SecureStorage
                saveSession(authResponse)

                // Refresh TPV settings from backend (Square/Toast pattern)
                // Settings control payment flow screens (tip, review, receipt options)
                tpvSettingsRepository.refreshSettings(authResponse.venueId)

                Timber.d("✅ Login successful: ${authResponse.staff.displayName}")
                Result.Success(authResponse)
            } else {
                Timber.w("⚠️ Login failed: HTTP ${response.code()}")

                // ✅ Read backend error message from errorBody
                val backendMessage = try {
                    val errorBody = response.errorBody()?.string()
                    if (errorBody != null) {
                        val json = JSONObject(errorBody)
                        // Try different common fields for error messages
                        json.optString("message")
                            .ifEmpty { json.optString("error") }
                            .ifEmpty { json.optString("detail") }
                            .ifEmpty { null }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse error body")
                    null
                }

                // Fallback messages if backend doesn't provide one
                val fallbackMessage = when (response.code()) {
                    401 -> "PIN incorrecto. Intenta de nuevo."
                    404 -> "Usuario no encontrado en este venue."
                    429 -> {
                        Timber.w("⚠️ Rate limit exceeded (429) - Backend should have higher limits in DEV")
                        "Demasiados intentos. Por favor espera un momento e intenta nuevamente.\n\n" +
                        "ℹ️ Si estás en desarrollo, el backend debe configurar rate limits más altos para DEV."
                    }
                    else -> "Error al iniciar sesión. Código: ${response.code()}"
                }

                // Use backend message if available, otherwise use fallback
                val userMessage = backendMessage ?: fallbackMessage
                Timber.w("Error message: $userMessage")

                Result.Error(ApiException.HttpError(
                    code = response.code(),
                    errorMessage = "Login failed: HTTP ${response.code()}",
                    customUserMessage = userMessage
                ))
            }
        } catch (e: Exception) {
            // Network errors, JSON parsing errors, etc.
            Timber.w(e, "❌ Login failed")
            Result.Error(ApiException.NetworkError(e))
        }
    }

    /**
     * Save session to SecureStorage
     *
     * Persists all auth context for offline use:
     * - JWT tokens (access + refresh)
     * - Venue ID (for tenant isolation)
     * - Staff info (ID + name for UI)
     * - Permissions (for feature access control)
     *
     * @param authResponse Auth response from backend
     */
    private fun saveSession(authResponse: AuthResponse) {
        Timber.d("💾 Saving session to SecureStorage")

        // Save tokens
        secureStorage.saveToken(authResponse.accessToken)
        secureStorage.saveRefreshToken(authResponse.refreshToken)

        // Save auth context
        secureStorage.saveVenueId(authResponse.venueId)
        secureStorage.saveStaffId(authResponse.staffId)
        secureStorage.saveStaffName(authResponse.staff.displayName)
        secureStorage.savePermissions(authResponse.permissions)

        // Save venue info for UI (logo, name)
        secureStorage.saveVenueLogo(authResponse.venue.logo)
        secureStorage.saveVenueName(authResponse.venue.name)

        Timber.d("✅ Session saved: venueId=${authResponse.venueId}, staffId=${authResponse.staffId}")
    }

    /**
     * Verify PIN and create session for Timeclock
     *
     * Used by Timeclock feature to verify employee identity and create
     * a session so that subsequent API calls (getTimeEntries, clockIn, etc.) work.
     *
     * Flow:
     * 1. Send PIN to backend
     * 2. Receive tokens + staff info
     * 3. Save session to SecureStorage (required for API calls)
     * 4. Return staff info for Timeclock UI
     *
     * Note: Session is created here. When user presses "Done" on Timeclock screen,
     * heartbeat/payment sync are started and user navigates to Home.
     * If user presses "Back", the session should be cleared via logout().
     *
     * @param venueId Venue identifier
     * @param pin 4-6 digit numeric PIN
     * @return Result with staff info or error
     */
    suspend fun verifyPinOnly(venueId: String, pin: String): kotlin.Result<com.jaac.avoqado_tpv.features.timeclock.presentation.StaffInfo> {
        return try {
            Timber.d("📡 Verifying PIN for timeclock at venue $venueId")

            val serialNumber = secureStorage.getSerialNumber()
                ?: return kotlin.Result.failure(Exception("El dispositivo debe activarse primero"))

            val request = PinLoginRequest(
                pin = pin,
                serialNumber = serialNumber
            ).toDto()
            val response = apiService.loginWithPin(venueId, request)

            if (response.isSuccessful && response.body() != null) {
                val authDto = response.body()!!
                val authResponse = authDto.toDomain()

                // Save session so API calls work (getTimeEntries, clockIn, etc.)
                saveSession(authResponse)
                Timber.d("✅ PIN verified and session saved for: ${authResponse.staff.displayName}")

                kotlin.Result.success(
                    com.jaac.avoqado_tpv.features.timeclock.presentation.StaffInfo(
                        id = authResponse.staffId,
                        name = authResponse.staff.displayName
                    )
                )
            } else {
                Timber.w("⚠️ PIN verification failed: HTTP ${response.code()}")

                val errorMessage = when (response.code()) {
                    401 -> "PIN incorrecto"
                    404 -> "Usuario no encontrado"
                    429 -> "Demasiados intentos. Espera un momento."
                    else -> "Error de verificación"
                }

                kotlin.Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.w(e, "❌ PIN verification failed")
            kotlin.Result.failure(Exception("Error de conexión"))
        }
    }

    /**
     * Logout
     *
     * Clears all session data from SecureStorage.
     * Does NOT call backend (backend invalidates token automatically).
     *
     * Flow:
     * 1. Clear SecureStorage
     * 2. Navigate to login screen
     * 3. Stop heartbeat worker
     */
    fun logout() {
        Timber.d("🚪 Logging out")
        secureStorage.clearSession()
        Timber.d("✅ Session cleared")
    }

    /**
     * Check if user is authenticated
     *
     * Validates session by checking if access token exists.
     * Does NOT validate token expiration (backend handles this).
     *
     * @return true if session token exists
     */
    fun isAuthenticated(): Boolean {
        return secureStorage.isAuthenticated()
    }

    /**
     * Get current venue ID
     *
     * Returns the venue ID from secure storage.
     * Used for tenant isolation in all API calls.
     *
     * @return Venue ID or null if not authenticated
     */
    fun getVenueId(): String? {
        return secureStorage.getVenueId()
    }

    /**
     * Get current staff ID
     *
     * @return Staff ID or null if not authenticated
     */
    fun getStaffId(): String? {
        return secureStorage.getStaffId()
    }

    /**
     * Get current staff name
     *
     * Used for UI display (e.g., "Hola, Juan Pérez")
     *
     * @return Staff display name or null if not authenticated
     */
    fun getStaffName(): String? {
        return secureStorage.getStaffName()
    }

    /**
     * Check if staff has specific permission
     *
     * @param permission Permission code (e.g., "orders:create", "payments:record")
     * @return true if permission exists
     */
    fun hasPermission(permission: String): Boolean {
        return secureStorage.hasPermission(permission)
    }

    /**
     * Refresh access token using refresh token
     *
     * Extends session duration without requiring PIN re-entry.
     * Follows Square POS pattern for silent token renewal.
     *
     * Flow:
     * 1. Get refresh token from SecureStorage
     * 2. Call refresh endpoint with refresh token
     * 3. Receive new access token (and optionally new refresh token)
     * 4. Save new tokens to SecureStorage
     * 5. Return success
     *
     * **When to Call:**
     * - When access token expires (AuthInterceptor detects 401)
     * - Proactively before token expires (background refresh)
     *
     * **Error Handling:**
     * - If refresh token invalid/expired → Force re-login with PIN
     * - If network error → Retry with exponential backoff
     *
     * @return Result with new auth data or error
     */
    suspend fun refreshAccessToken(): Result<RefreshTokenResponse> {
        return try {
            Timber.d("🔄 Refreshing access token")

            // Get refresh token from SecureStorage
            val refreshToken = secureStorage.getRefreshToken()
                ?: return Result.Error(ApiException.ValidationError("No refresh token available. Please login again."))

            val venueId = secureStorage.getVenueId()
                ?: return Result.Error(ApiException.ValidationError("No venue ID found. Please login again."))

            // Call refresh endpoint
            val request = com.jaac.avoqado_tpv.features.authentication.domain.models.RefreshTokenRequest(refreshToken).toDto()
            val response = apiService.refreshToken(venueId, request)

            if (response.isSuccessful && response.body() != null) {
                val refreshDto = response.body()!!
                val refreshResponse = refreshDto.toDomain()

                // Save new access token
                secureStorage.saveToken(refreshResponse.accessToken)
                // Note: Backend may optionally return new refresh token
                // If backend returns new refresh token, it would be in AuthResponseDto (not RefreshTokenResponseDto)

                Timber.d("✅ Access token refreshed successfully")
                Result.Success(refreshResponse)
            } else {
                Timber.w("⚠️ Token refresh failed: HTTP ${response.code()}")
                val errorMessage = when (response.code()) {
                    401 -> "Refresh token expired. Please login again."
                    403 -> "Refresh token invalid. Please login again."
                    else -> "Failed to refresh token. Please login again."
                }

                // Clear session on refresh failure (force re-login)
                secureStorage.clearSession()

                Result.Error(ApiException.HttpError(response.code(), errorMessage))
            }
        } catch (e: Exception) {
            Timber.w(e, "❌ Token refresh failed")
            Result.Error(ApiException.NetworkError(e))
        }
    }
}
