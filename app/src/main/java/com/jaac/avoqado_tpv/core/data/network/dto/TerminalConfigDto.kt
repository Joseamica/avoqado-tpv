package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Terminal Configuration DTOs
 *
 * Data transfer objects for terminal configuration fetching.
 * Maps to backend endpoint:
 * - GET /tpv/terminals/{serialNumber}/config
 *
 * **Purpose:**
 * Dynamically fetch terminal configuration and assigned merchant accounts
 * on app startup, eliminating hardcoded credentials and enabling multi-merchant support.
 */

/**
 * Response from GET /tpv/terminals/{serialNumber}/config
 *
 * Contains terminal information and assigned merchant accounts for multi-merchant routing.
 *
 * **Backend Response Example:**
 * ```json
 * {
 *   "success": true,
 *   "data": {
 *     "terminal": {...},
 *     "merchantAccounts": [...]
 *   }
 * }
 * ```
 *
 * @param success API success flag
 * @param data Nested data object with terminal and merchant accounts
 */
data class TerminalConfigResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: TerminalConfigData
)

/**
 * Nested data object containing terminal and merchant accounts
 *
 * @param terminal Terminal information
 * @param merchantAccounts List of merchant accounts assigned to this terminal
 * @param tpvSettings TPV screen configuration settings (per-terminal)
 */
data class TerminalConfigData(
    @SerializedName("terminal")
    val terminal: TerminalDto,

    @SerializedName("merchantAccounts")
    val merchantAccounts: List<MerchantAccountDto>,

    @SerializedName("tpvSettings")
    val tpvSettings: TpvSettingsDto?
)

/**
 * Terminal information DTO
 *
 * @param id Terminal ID (backend UUID)
 * @param serialNumber Terminal serial number (e.g., "2841548417")
 * @param name Terminal name (user-assigned, e.g., "Terminal 1")
 * @param type Terminal type (e.g., "TPV_ANDROID")
 * @param brand Hardware manufacturer (e.g., "PAX", "Ingenico") - optional
 * @param model Hardware model (e.g., "A910S", "D220") - optional
 * @param status Terminal status (ACTIVE, INACTIVE, MAINTENANCE)
 * @param venueId Venue ID this terminal belongs to
 * @param venue Venue information
 */
data class TerminalDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("serialNumber")
    val serialNumber: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("type")
    val type: String,

    @SerializedName("brand")
    val brand: String? = null,

    @SerializedName("model")
    val model: String? = null,

    @SerializedName("status")
    val status: String,

    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("venue")
    val venue: VenueDto?
)

/**
 * Venue information DTO (minimal fields for terminal config)
 *
 * @param id Venue ID
 * @param name Venue name
 * @param type Venue type (RESTAURANT, BAR, CAFE, etc.)
 */
data class VenueDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("type")
    val type: String?,

    @SerializedName("timezone")
    val timezone: String? = null
)

/**
 * Merchant Account DTO with Blumon configuration
 *
 * Contains all information needed to process payments for a specific merchant account.
 *
 * **Blumon Multi-Merchant Routing:**
 * Each merchant account has its own:
 * - Serial number (identifies device to Blumon)
 * - posId (Momentum API position identifier - CRITICAL for routing)
 * - Credentials (OAuth tokens, DUKPT keys)
 *
 * **Example:**
 * Terminal "2841548417" can process payments to:
 * - Merchant A: serial "2841548417", posId "376"
 * - Merchant B: serial "2841548418", posId "378"
 *
 * @param id Merchant account ID (backend UUID)
 * @param displayName User-friendly name (e.g., "Main Restaurant", "Ghost Kitchen")
 * @param serialNumber Blumon device serial number
 * @param posId Momentum API position ID (CRITICAL - must match serial)
 * @param environment Blumon environment (SANDBOX or PRODUCTION)
 * @param merchantId Blumon merchant identifier
 * @param credentials Encrypted credentials (OAuth tokens, DUKPT keys)
 * @param providerConfig Additional provider-specific config
 */
data class MerchantAccountDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("displayName")
    val displayName: String,

    @SerializedName("serialNumber")
    val serialNumber: String,

    @SerializedName("posId")
    val posId: String,

    @SerializedName("environment")
    val environment: String,

    @SerializedName("merchantId")
    val merchantId: String?,

    @SerializedName("credentials")
    val credentials: Map<String, Any>?,

    @SerializedName("providerConfig")
    val providerConfig: Map<String, Any>?
)
