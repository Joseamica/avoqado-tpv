package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings

/**
 * DTO for TPV Settings from backend API.
 *
 * All fields are nullable to handle partial updates from backend.
 * Missing fields default to safe values (screens enabled).
 *
 * **Backend Response:**
 * ```json
 * {
 *   "showReviewScreen": true,
 *   "showTipScreen": false,
 *   "showReceiptScreen": true,
 *   "defaultTipPercentage": 15,
 *   "tipSuggestions": [15, 18, 20, 25],
 *   "requirePinLogin": true,
 *   "showVerificationScreen": false,
 *   "requireVerificationPhoto": false,
 *   "requireVerificationBarcode": false
 * }
 * ```
 */
data class TpvSettingsDto(
    @SerializedName("showReviewScreen")
    val showReviewScreen: Boolean?,

    @SerializedName("showTipScreen")
    val showTipScreen: Boolean?,

    @SerializedName("showReceiptScreen")
    val showReceiptScreen: Boolean?,

    @SerializedName("defaultTipPercentage")
    val defaultTipPercentage: Int?,

    @SerializedName("tipSuggestions")
    val tipSuggestions: List<Int>?,

    @SerializedName("requirePinLogin")
    val requirePinLogin: Boolean?,

    // Step 4: Sale Verification (for retail/telecomunicaciones venues)
    @SerializedName("showVerificationScreen")
    val showVerificationScreen: Boolean?,

    @SerializedName("requireVerificationPhoto")
    val requireVerificationPhoto: Boolean?,

    @SerializedName("requireVerificationBarcode")
    val requireVerificationBarcode: Boolean?,

    // Venue-level shift system toggle (from VenueSettings)
    @SerializedName("enableShifts")
    val enableShifts: Boolean?,

    // Attendance verification (clock-in/out with selfie + GPS)
    @SerializedName("requireClockInPhoto")
    val requireClockInPhoto: Boolean?,

    @SerializedName("requireClockOutPhoto")
    val requireClockOutPhoto: Boolean?,

    // Session security: require active clock-in to access system
    @SerializedName("requireClockInToLogin")
    val requireClockInToLogin: Boolean?,

    // Kiosk Mode: allows terminal to enter self-service mode
    @SerializedName("kioskModeEnabled")
    val kioskModeEnabled: Boolean?,

    // Kiosk Default Merchant: auto-select this merchant in kiosk payment flow
    @SerializedName("kioskDefaultMerchantId")
    val kioskDefaultMerchantId: String?,

    // Home screen button visibility (controlled from dashboard)
    @SerializedName("showQuickPayment")
    val showQuickPayment: Boolean?,

    @SerializedName("showOrderManagement")
    val showOrderManagement: Boolean?,

    // Crypto payment option (B4Bit integration)
    @SerializedName("showCryptoOption")
    val showCryptoOption: Boolean?
)

/**
 * Convert DTO to domain model with safe defaults.
 */
fun TpvSettingsDto.toDomain(): TpvSettings = TpvSettings(
    showReviewScreen = showReviewScreen ?: true,
    showTipScreen = showTipScreen ?: true,
    showReceiptScreen = showReceiptScreen ?: true,
    defaultTipPercentage = defaultTipPercentage,
    tipSuggestions = tipSuggestions ?: listOf(10, 15, 20),
    requirePinLogin = requirePinLogin ?: true,
    // Step 4: Verification defaults to disabled
    showVerificationScreen = showVerificationScreen ?: false,
    requireVerificationPhoto = requireVerificationPhoto ?: false,
    requireVerificationBarcode = requireVerificationBarcode ?: false,
    // Shift system defaults to enabled
    enableShifts = enableShifts ?: true,
    // Attendance verification defaults to disabled
    requireClockInPhoto = requireClockInPhoto ?: false,
    requireClockOutPhoto = requireClockOutPhoto ?: false,
    // Session security defaults to disabled
    requireClockInToLogin = requireClockInToLogin ?: false,
    // Kiosk Mode defaults to disabled
    kioskModeEnabled = kioskModeEnabled ?: false,
    // Kiosk Default Merchant (null = show selection)
    kioskDefaultMerchantId = kioskDefaultMerchantId,
    // Home screen buttons default to enabled
    showQuickPayment = showQuickPayment ?: true,
    showOrderManagement = showOrderManagement ?: true,
    showCryptoOption = showCryptoOption ?: false
)

/**
 * Convert domain model to DTO for API requests.
 */
fun TpvSettings.toDto(): TpvSettingsDto = TpvSettingsDto(
    showReviewScreen = showReviewScreen,
    showTipScreen = showTipScreen,
    showReceiptScreen = showReceiptScreen,
    defaultTipPercentage = defaultTipPercentage,
    tipSuggestions = tipSuggestions,
    requirePinLogin = requirePinLogin,
    showVerificationScreen = showVerificationScreen,
    requireVerificationPhoto = requireVerificationPhoto,
    requireVerificationBarcode = requireVerificationBarcode,
    enableShifts = enableShifts,
    requireClockInPhoto = requireClockInPhoto,
    requireClockOutPhoto = requireClockOutPhoto,
    requireClockInToLogin = requireClockInToLogin,
    kioskModeEnabled = kioskModeEnabled,
    kioskDefaultMerchantId = kioskDefaultMerchantId,
    showQuickPayment = showQuickPayment,
    showOrderManagement = showOrderManagement,
    showCryptoOption = showCryptoOption
)

/**
 * Response from PUT /api/v1/tpv/terminals/:serialNumber/settings
 *
 * Returns the updated TPV settings after save.
 */
data class TpvSettingsUpdateResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: TpvSettingsDto?
)

/**
 * Response from GET /api/v1/tpv/venues/:venueId
 *
 * Contains venue information with embedded TPV settings.
 * @deprecated Use getTerminalConfig() instead - settings are now per-terminal
 */
@Deprecated("Use getTerminalConfig() instead - settings are now per-terminal")
data class VenueWithSettingsResponse(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("tpvSettings")
    val tpvSettings: TpvSettingsDto?
)
