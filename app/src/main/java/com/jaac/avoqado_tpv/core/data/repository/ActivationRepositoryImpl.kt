package com.jaac.avoqado_tpv.core.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.ActivateTerminalRequest
import com.jaac.avoqado_tpv.core.domain.models.ActivationError
import com.jaac.avoqado_tpv.core.domain.models.ActivationResult
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.repository.ActivationRepository
import timber.log.Timber
import javax.inject.Inject

/**
 * Activation Repository Implementation
 *
 * Data layer implementation using ApiService and SecureStorage.
 * Implements domain layer ActivationRepository interface.
 *
 * **Responsibilities:**
 * 1. Call backend API for activation
 * 2. Store venueId in SecureStorage on success
 * 3. Map DTO to domain models
 * 4. Handle errors and convert to domain exceptions
 *
 * **Error Mapping:**
 * - 400 → CodeExpired or AlreadyActivated
 * - 401 → InvalidCode or TerminalLocked
 * - 404 → TerminalNotRegistered
 * - Network error → NetworkError
 */
class ActivationRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage
) : ActivationRepository {

    override suspend fun activateTerminal(
        serialNumber: String,
        activationCode: String
    ): Result<ActivationResult> {
        return try {
            Timber.d("Activating terminal: $serialNumber")

            // Call API
            val request = ActivateTerminalRequest(
                serialNumber = serialNumber,
                activationCode = activationCode,
                environment = com.jaac.avoqado_tpv.BuildConfig.BLUMON_ENV  // "PROD" or "SAND"
            )

            val response = apiService.activateTerminal(request)

            // Handle response
            if (response.isSuccessful && response.body() != null) {
                val dto = response.body()!!
                Timber.i("✅ Terminal activated successfully: venueId=${dto.venueId}")

                // Store venueId, venueSlug AND serialNumber permanently in SecureStorage
                secureStorage.saveVenueId(dto.venueId)
                secureStorage.saveVenueSlug(dto.venueSlug)  // 📸 For Firebase Storage path
                secureStorage.saveSerialNumber(serialNumber)
                Timber.d("VenueId, VenueSlug, and SerialNumber stored in SecureStorage")

                // Map DTO to domain model
                val result = ActivationResult(
                    venueId = dto.venueId,
                    terminalId = dto.terminalId,
                    venueName = dto.venueName,
                    venueSlug = dto.venueSlug,
                    activatedAt = dto.activatedAt
                )

                Result.Success(result)
            } else {
                // Parse error response body to get backend's custom error message
                val errorBody = response.errorBody()?.string()
                val backendMessage = try {
                    // Parse JSON error response: {"message": "...", "errorName": "..."}
                    val jsonPattern = "\"message\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    jsonPattern.find(errorBody ?: "")?.groupValues?.get(1)
                        ?: response.message()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse error body")
                    response.message()
                }

                // Map HTTP errors to domain-specific errors
                val error = mapHttpErrorToActivationError(response.code(), backendMessage, serialNumber)
                Timber.w("❌ Activation failed: ${response.code()} - $backendMessage")
                Result.Error(error)
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Network error during activation")
            val error = ApiException.NetworkError(e)
            Result.Error(error)
        }
    }

    /**
     * Map HTTP error codes to domain-specific activation errors
     *
     * Backend error responses:
     * - 400: "Activation code expired" or "Terminal already activated"
     * - 401: "Invalid activation code. X attempts remaining" or "Terminal locked"
     * - 404: "Terminal not registered"
     *
     * **Special Handling for "Already Activated":**
     * When backend says "Terminal already activated", it means:
     * 1. Terminal exists in database and is activated
     * 2. SerialNumber is the same (deterministic from device ID)
     * 3. User probably reinstalled app or cleared data
     *
     * We save the serialNumber to SecureStorage so the user can proceed to login.
     * However, we DON'T have venueId from the error response, so user must provide it during login.
     *
     * @param code HTTP status code
     * @param message Backend error message
     * @param serialNumber Device serial number (saved if "already activated")
     */
    private fun mapHttpErrorToActivationError(code: Int, message: String, serialNumber: String): ApiException {
        return when (code) {
            400 -> {
                when {
                    message.contains("expired", ignoreCase = true) ->
                        ApiException.HttpError(400, "Código de activación expirado")

                    message.contains("already activated", ignoreCase = true) -> {
                        // ⚠️ SPECIAL CASE: Terminal already activated (rare - backend returns 200 now)
                        // This code path should rarely execute since backend returns success
                        // Kept for backward compatibility with older backend versions
                        Timber.i("⚠️ Terminal already activated - saving serialNumber to SecureStorage")
                        secureStorage.saveSerialNumber(serialNumber)

                        ApiException.HttpError(
                            400,
                            "Terminal ya activado. Intenta nuevamente."
                        )
                    }

                    else ->
                        ApiException.HttpError(400, message)
                }
            }

            401 -> {
                when {
                    message.contains("locked", ignoreCase = true) ->
                        ApiException.HttpError(401, "Terminal bloqueado por demasiados intentos")

                    message.contains("Invalid activation code", ignoreCase = true) -> {
                        // Extract attempts remaining from message
                        // Message format: "Invalid activation code. 4 attempt(s) remaining before lockout."
                        val attemptsMatch = Regex("(\\d+) attempt").find(message)
                        val attempts = attemptsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        ApiException.HttpError(
                            401,
                            "Código incorrecto. $attempts intento(s) restantes"
                        )
                    }

                    else ->
                        ApiException.HttpError(401, "No autorizado")
                }
            }

            404 -> ApiException.HttpError(404, "Terminal no registrado")

            else -> ApiException.HttpError(code, message)
        }
    }
}
