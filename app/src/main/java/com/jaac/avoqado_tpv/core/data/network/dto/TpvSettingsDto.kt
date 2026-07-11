package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.payment.domain.model.CellularFailoverMode
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

    // Venue-level "cambaceo" flag (from VenueSettings via terminal config).
    // Default null so manual constructions (tests) don't need it; old servers omit it.
    @SerializedName("trackPromoterLocation")
    val trackPromoterLocation: Boolean? = null,

    // Configurable "cambaceo" capture window (venue-local hours). Start inclusive,
    // end exclusive; 0/24 = 24h. Default null so manual constructions (tests) don't
    // need them; old servers omit them -> toDomain() falls back to legacy 11/18.
    @SerializedName("promoterLocationStartHour")
    val promoterLocationStartHour: Int? = null,

    @SerializedName("promoterLocationEndHour")
    val promoterLocationEndHour: Int? = null,

    // Card payment server-decoupling kill-switch
    // Default null so manual constructions (tests) don't need to specify it; Gson populates from JSON,
    // and toDomain() coalesces null -> true (legacy/safe). Backend may omit it for old clients.
    @SerializedName("requireAvoqadoServerForCardPayment")
    val requireAvoqadoServerForCardPayment: Boolean? = null,

    // Attendance verification (clock-in/out with selfie + GPS)
    @SerializedName("requireClockInPhoto")
    val requireClockInPhoto: Boolean?,

    @SerializedName("requireClockOutPhoto")
    val requireClockOutPhoto: Boolean?,

    // Additional attendance evidence photos
    @SerializedName("requireFacadePhoto")
    val requireFacadePhoto: Boolean?,

    @SerializedName("requireDepositPhoto")
    val requireDepositPhoto: Boolean?,

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

    // Unified Checkout — additive rollout, default ON when backend doesn't send it
    @SerializedName("showCheckout")
    val showCheckout: Boolean? = null,

    @SerializedName("showReports")
    val showReports: Boolean?,

    @SerializedName("showPayments")
    val showPayments: Boolean?,

    @SerializedName("showSupport")
    val showSupport: Boolean?,

    @SerializedName("showGoals")
    val showGoals: Boolean?,

    // Messages & Trainings visibility (controlled from dashboard)
    @SerializedName("showMessages")
    val showMessages: Boolean?,

    @SerializedName("showTrainings")
    val showTrainings: Boolean?,

    // Crypto payment option (B4Bit integration)
    @SerializedName("showCryptoOption")
    val showCryptoOption: Boolean?,

    // AngelPay SDK rollout flags
    @SerializedName("angelPaySdkEnabled")
    val angelPaySdkEnabled: Boolean? = null,

    @SerializedName("angelPaySdkFallbackEnabled")
    val angelPaySdkFallbackEnabled: Boolean? = null,

    // Phase 0: Cellular failover rollout flags
    @SerializedName("cellularFailoverMode")
    val cellularFailoverMode: String? = null,

    @SerializedName("cellularFailoverBadReadingsThreshold")
    val cellularFailoverBadReadingsThreshold: Int? = null,

    @SerializedName("cellularFailoverCooldownSeconds")
    val cellularFailoverCooldownSeconds: Int? = null,

    @SerializedName("cellularFailoverMinCellHoldSeconds")
    val cellularFailoverMinCellHoldSeconds: Int? = null
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
    // Cambaceo tracking defaults to disabled (venue must opt in)
    trackPromoterLocation = trackPromoterLocation ?: false,
    // Cambaceo capture window defaults to legacy 11:00-18:00 venue-local
    promoterLocationStartHour = promoterLocationStartHour ?: 11,
    promoterLocationEndHour = promoterLocationEndHour ?: 18,
    // Card payment kill-switch: default true (legacy: require backend before charge)
    requireAvoqadoServerForCardPayment = requireAvoqadoServerForCardPayment ?: true,
    // Attendance verification defaults to disabled
    requireClockInPhoto = requireClockInPhoto ?: false,
    requireClockOutPhoto = requireClockOutPhoto ?: false,
    // Additional attendance evidence photos default to disabled
    requireFacadePhoto = requireFacadePhoto ?: false,
    requireDepositPhoto = requireDepositPhoto ?: false,
    // Session security defaults to disabled
    requireClockInToLogin = requireClockInToLogin ?: false,
    // Kiosk Mode defaults to disabled
    kioskModeEnabled = kioskModeEnabled ?: false,
    // Kiosk Default Merchant (null = show selection)
    kioskDefaultMerchantId = kioskDefaultMerchantId,
    // Home screen buttons default to enabled
    showQuickPayment = showQuickPayment ?: true,
    showOrderManagement = showOrderManagement ?: true,
    showCheckout = showCheckout ?: true,
    showReports = showReports ?: true,
    showPayments = showPayments ?: true,
    showSupport = showSupport ?: true,
    showGoals = showGoals ?: true,
    showMessages = showMessages ?: true,
    showTrainings = showTrainings ?: true,
    showCryptoOption = showCryptoOption ?: false,
    angelPaySdkEnabled = angelPaySdkEnabled ?: true,
    angelPaySdkFallbackEnabled = angelPaySdkFallbackEnabled ?: true,
    cellularFailoverMode = CellularFailoverMode.fromRaw(cellularFailoverMode),
    cellularFailoverBadReadingsThreshold = cellularFailoverBadReadingsThreshold ?: 3,
    cellularFailoverCooldownSeconds = cellularFailoverCooldownSeconds ?: 60,
    cellularFailoverMinCellHoldSeconds = cellularFailoverMinCellHoldSeconds ?: 120
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
    trackPromoterLocation = trackPromoterLocation,
    promoterLocationStartHour = promoterLocationStartHour,
    promoterLocationEndHour = promoterLocationEndHour,
    requireAvoqadoServerForCardPayment = requireAvoqadoServerForCardPayment,
    requireClockInPhoto = requireClockInPhoto,
    requireClockOutPhoto = requireClockOutPhoto,
    requireFacadePhoto = requireFacadePhoto,
    requireDepositPhoto = requireDepositPhoto,
    requireClockInToLogin = requireClockInToLogin,
    kioskModeEnabled = kioskModeEnabled,
    kioskDefaultMerchantId = kioskDefaultMerchantId,
    showQuickPayment = showQuickPayment,
    showOrderManagement = showOrderManagement,
    showCheckout = showCheckout,
    showReports = showReports,
    showPayments = showPayments,
    showSupport = showSupport,
    showGoals = showGoals,
    showMessages = showMessages,
    showTrainings = showTrainings,
    showCryptoOption = showCryptoOption,
    angelPaySdkEnabled = angelPaySdkEnabled,
    angelPaySdkFallbackEnabled = angelPaySdkFallbackEnabled,
    cellularFailoverMode = cellularFailoverMode.name,
    cellularFailoverBadReadingsThreshold = cellularFailoverBadReadingsThreshold,
    cellularFailoverCooldownSeconds = cellularFailoverCooldownSeconds,
    cellularFailoverMinCellHoldSeconds = cellularFailoverMinCellHoldSeconds
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
