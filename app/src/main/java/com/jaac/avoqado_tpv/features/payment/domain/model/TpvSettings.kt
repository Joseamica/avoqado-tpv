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
 */
data class TpvSettings(
    val showReviewScreen: Boolean = true,
    val showTipScreen: Boolean = true,
    val showReceiptScreen: Boolean = true,
    val defaultTipPercentage: Int? = null,
    val tipSuggestions: List<Int> = listOf(10, 15, 20),
    val requirePinLogin: Boolean = true
) {
    companion object {
        /**
         * Default settings when no backend config is available.
         * All screens enabled, no default tip pre-selected.
         */
        val DEFAULT = TpvSettings()
    }
}
