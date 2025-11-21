package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Activation DTOs
 *
 * Data transfer objects for terminal activation flow.
 * Maps to backend endpoints:
 * - POST /tpv/activate
 */

/**
 * Request to activate a terminal
 *
 * @param serialNumber Device serial number (format: AVQD-{androidId})
 * @param activationCode 6-character alphanumeric code (case-insensitive)
 * @param environment Blumon environment: "PROD" or "SAND" (requires backend schema update)
 */
data class ActivateTerminalRequest(
    @SerializedName("serialNumber")
    val serialNumber: String,

    @SerializedName("activationCode")
    val activationCode: String,

    @SerializedName("environment")
    val environment: String  // "PROD" or "SAND"
)

/**
 * Response from successful terminal activation
 *
 * Contains venue information and activation timestamp.
 * The venueId should be stored permanently in SecureStorage.
 *
 * @param venueId Venue UUID (critical for tenant isolation)
 * @param terminalId Terminal UUID
 * @param venueName Human-readable venue name
 * @param venueSlug URL-friendly venue identifier
 * @param activatedAt ISO 8601 timestamp of activation
 */
data class ActivationResponse(
    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("terminalId")
    val terminalId: String,

    @SerializedName("venueName")
    val venueName: String,

    @SerializedName("venueSlug")
    val venueSlug: String,

    @SerializedName("activatedAt")
    val activatedAt: String
)

/**
 * Response from activation status check
 *
 * Used by SplashScreen to verify backend activation before routing.
 * Prevents routing to LoginScreen when terminal is not activated (activatedAt = NULL).
 *
 * **Square/Toast Pattern:**
 * - SplashScreen calls GET /tpv/terminals/{serialNumber}/activation-status
 * - If `isActivated = false` → route to ActivationScreen
 * - If `isActivated = true` → proceed to login check
 * - If `status = RETIRED` → clear local data and route to ActivationScreen
 *
 * @param isActivated Whether terminal is activated (activatedAt !== null on backend)
 * @param status Current terminal status (ACTIVE, INACTIVE, MAINTENANCE, RETIRED)
 * @param venueId Venue UUID if activated (null otherwise)
 * @param venueName Venue name if activated (null otherwise)
 * @param venueSlug Venue slug if activated (null otherwise)
 * @param activatedAt ISO 8601 timestamp if activated (null otherwise)
 * @param message Spanish user-friendly message explaining status
 */
data class ActivationStatusResponse(
    @SerializedName("isActivated")
    val isActivated: Boolean,

    @SerializedName("status")
    val status: String,

    @SerializedName("venueId")
    val venueId: String?,

    @SerializedName("venueName")
    val venueName: String?,

    @SerializedName("venueSlug")
    val venueSlug: String?,

    @SerializedName("activatedAt")
    val activatedAt: String?,

    @SerializedName("message")
    val message: String?
)
