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
    val tpvSettings: TpvSettingsDto?,

    @SerializedName("angelpayAuth")
    val angelpayAuth: AngelPayAuthDto? = null,  // present only when terminal.brand == "NEXGO" AND venue has ACTIVE AngelPayUserAccount (FIRST account, backward-compat)

    /**
     * Multi-AngelPay-accounts payload (2026-05-18). Always present (possibly
     * empty []) when terminal.brand == "NEXGO"; null on non-NEXGO terminals.
     *
     * Backend (terminal.tpv.controller.ts) populates this list with EVERY
     * ACTIVE AngelPayUserAccount for the venue. The TPV uses it to call
     * `AngelPayAuthRepository.switchAccount(accountId)` when the cashier
     * selects a merchant owned by a different AngelPay login than the one
     * currently authenticated.
     *
     * The first entry (if any) matches the legacy single-account `angelpayAuth`
     * field exactly, preserving back-compat with code paths that haven't been
     * updated yet.
     */
    @SerializedName("angelpayAccounts")
    val angelpayAccounts: List<AngelPayAuthDto>? = null
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

    @SerializedName("providerCode")
    val providerCode: String? = "BLUMON",  // BLUMON, ANGELPAY, MENTA, etc.

    @SerializedName("serialNumber")
    val serialNumber: String? = null,  // Nullable — AngelPay merchants don't have Blumon serial

    @SerializedName("posId")
    val posId: String? = null,  // Nullable — AngelPay merchants don't have Blumon posId

    @SerializedName("environment")
    val environment: String? = null,  // Nullable — AngelPay merchants don't have Blumon environment

    @SerializedName("merchantId")
    val merchantId: String?,

    @SerializedName("credentials")
    val credentials: Map<String, Any>?,

    @SerializedName("providerConfig")
    val providerConfig: Map<String, Any>?,

    // 🆕 Task 23 — additive fields wiring through backend Task 13 endpoint
    @SerializedName("externalMerchantId")
    val externalMerchantId: String? = null,  // numeric string for AngelPay; Blumon serial/posId equivalent

    @SerializedName("isActive")
    val isActive: Boolean = true,  // used by TPV to filter inactive merchants

    @SerializedName("angelpayAffiliation")
    val angelpayAffiliation: String? = null,

    @SerializedName("angelpayMerchantName")
    val angelpayMerchantName: String? = null,

    /**
     * Multi-AngelPay accounts per venue (2026-05-18). Links this merchant
     * back to the AngelPayUserAccount it was discovered under. Used by the
     * cashier-side picker to call `AngelPayAuthRepository.switchAccount()`
     * before charging. Null for non-AngelPay providers and for legacy
     * AngelPay rows that pre-date the migration backfill.
     */
    @SerializedName("angelpayUserAccountId")
    val angelpayUserAccountId: String? = null
)

/**
 * AngelPay authentication payload (Task 23)
 *
 * Present in the terminal config response only when:
 * - terminal.brand == "NEXGO"
 * - The venue has an ACTIVE AngelPayUserAccount
 *
 * **PIN handling (spec §4.5b):**
 * The `pin` field is delivered in-memory only and MUST NOT be persisted on TPV.
 *
 * @param accountId   Avoqado AngelPayUserAccount CUID
 * @param email       AngelPay portal login email
 * @param pin         AngelPay PIN — in-memory only, never persisted
 * @param environment "QA" or "PRODUCTION"
 */
data class AngelPayAuthDto(
    @SerializedName("accountId")    val accountId: String,
    @SerializedName("email")        val email: String,
    @SerializedName("pin")          val pin: String,
    @SerializedName("environment")  val environment: String
)
