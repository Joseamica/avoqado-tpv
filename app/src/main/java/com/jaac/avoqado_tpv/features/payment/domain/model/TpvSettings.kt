package com.jaac.avoqado_tpv.features.payment.domain.model

enum class CellularFailoverMode {
    OFF,
    MANUAL_TOGGLE,
    AUTO_SHADOW,
    AUTO_ENFORCED;

    companion object {
        fun fromRaw(raw: String?): CellularFailoverMode {
            if (raw.isNullOrBlank()) return OFF
            return entries.firstOrNull { it.name == raw } ?: OFF
        }
    }
}

/**
 * TPV Screen Configuration Settings
 *
 * Configures the payment flow screens based on venue preferences.
 * Fetched from backend after login and cached in SecureStorage for offline access.
 *
 * **Square/Toast Pattern:**
 * - Settings fetched after login (with venue data)
 * - Cached locally for offline support
 * - Used to conditionally skip payment flow screens
 *
 * @param showReviewScreen Whether to show the star rating screen after payment amount entry
 * @param showTipScreen Whether to show the tip selection screen before payment
 * @param showReceiptScreen Whether to show receipt options (QR, email, print) after payment success
 * @param defaultTipPercentage Pre-selected tip percentage (null = no pre-selection)
 * @param tipSuggestions List of tip percentage options to display
 * @param requirePinLogin Whether PIN is required for staff login
 * @param showVerificationScreen Whether to show Step 4 verification screen (photo/barcode capture) after successful payment
 * @param requireVerificationPhoto Whether photo capture is mandatory in verification step
 * @param requireVerificationBarcode Whether barcode scanning is mandatory in verification step
 * @param requireClockInPhoto Whether selfie + GPS is required at clock-in
 * @param requireClockOutPhoto Whether selfie + GPS is required at clock-out
 * @param requireClockInToLogin Whether staff must have an active clock-in to access the system.
 * @param requireFacadePhoto Whether a panoramic photo of the store front is required at clock-in
 * @param requireDepositPhoto Whether a bank deposit voucher photo is required at clock-out
 *                              If enabled, staff on break or not clocked in cannot log in.
 * @param kioskModeEnabled Whether this terminal can enter self-service kiosk mode.
 *                         Controlled from dashboard settings.
 * @param kioskDefaultMerchantId Default merchant account ID for kiosk payments.
 *                               Skips merchant selection in kiosk mode if set.
 * @param showQuickPayment Whether to show "Pago rápido" button on home screen.
 *                         Controlled from dashboard settings.
 * @param showOrderManagement Whether to show "Órdenes" button on home screen.
 *                            Controlled from dashboard settings.
 * @param cellularFailoverMode Cellular failover rollout stage. Default OFF.
 * @param cellularFailoverBadReadingsThreshold Consecutive bad readings required before failover.
 * @param cellularFailoverCooldownSeconds Minimum seconds between network toggles.
 * @param cellularFailoverMinCellHoldSeconds Minimum seconds to stay on cellular before WiFi restore.
 */
data class TpvSettings(
    val showReviewScreen: Boolean = true,
    val showTipScreen: Boolean = true,
    val showReceiptScreen: Boolean = true,
    val defaultTipPercentage: Int? = null,
    val tipSuggestions: List<Int> = listOf(10, 15, 20),
    val requirePinLogin: Boolean = true,
    // Step 4: Sale Verification (for retail/telecomunicaciones venues)
    val showVerificationScreen: Boolean = false,
    val requireVerificationPhoto: Boolean = false,
    val requireVerificationBarcode: Boolean = false,
    // Venue-level shift system toggle (from VenueSettings)
    val enableShifts: Boolean = true,
    // Attendance verification (clock-in/out with selfie + GPS)
    val requireClockInPhoto: Boolean = false,
    val requireClockOutPhoto: Boolean = false,
    // Additional attendance evidence photos
    val requireFacadePhoto: Boolean = false,    // Panoramic store front photo at clock-in
    val requireDepositPhoto: Boolean = false,   // Bank deposit voucher photo at clock-out
    // Session security: require active clock-in to access system
    val requireClockInToLogin: Boolean = false,
    // Kiosk Mode: allows terminal to enter self-service mode (controlled from dashboard)
    val kioskModeEnabled: Boolean = false,
    // Kiosk Default Merchant: auto-select this merchant in kiosk payment flow
    val kioskDefaultMerchantId: String? = null,
    // Home screen button visibility (controlled from dashboard)
    val showQuickPayment: Boolean = true,
    val showOrderManagement: Boolean = true,
    // Unified Checkout (additive rollout — runs in parallel with Pago Rápido + Órdenes)
    val showCheckout: Boolean = true,
    val showReports: Boolean = true,
    val showPayments: Boolean = true,
    val showSupport: Boolean = true,
    val showGoals: Boolean = true,
    // Messages & Trainings visibility (controlled from dashboard)
    val showMessages: Boolean = true,
    val showTrainings: Boolean = true,
    // Crypto payment option (B4Bit integration)
    val showCryptoOption: Boolean = false,
    // AngelPay SDK rollout flags
    val angelPaySdkEnabled: Boolean = true,
    val angelPaySdkFallbackEnabled: Boolean = true,
    // Phase 0: Cellular failover rollout flags (safe defaults)
    val cellularFailoverMode: CellularFailoverMode = CellularFailoverMode.OFF,
    val cellularFailoverBadReadingsThreshold: Int = 3,
    val cellularFailoverCooldownSeconds: Int = 60,
    val cellularFailoverMinCellHoldSeconds: Int = 120
) {
    companion object {
        /**
         * Default settings when no backend config is available.
         * All screens enabled, no default tip pre-selected.
         * Verification disabled by default (only enabled for specific venue types).
         */
        val DEFAULT = TpvSettings()

        /**
         * Venue types that can enable verification screen.
         * Step 4 verification is designed for retail and telecomunicaciones venues.
         */
        val VERIFICATION_APPLICABLE_VENUE_TYPES = listOf(
            "TELECOMUNICACIONES",
            "RETAIL_STORE",
            "ELECTRONICS",
            "PHARMACY",
            "CONVENIENCE_STORE",
            "SUPERMARKET",
            "CLOTHING",
            "JEWELRY",
            "FURNITURE",
            "HARDWARE"
        )
    }
}
