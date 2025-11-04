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
 */
data class ActivateTerminalRequest(
    @SerializedName("serialNumber")
    val serialNumber: String,

    @SerializedName("activationCode")
    val activationCode: String
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
