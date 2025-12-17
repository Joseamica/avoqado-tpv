package com.jaac.avoqado_tpv.features.payment.domain.model

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
    val enableShifts: Boolean = true
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
