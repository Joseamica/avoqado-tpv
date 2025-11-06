package com.jaac.avoqado_tpv.core.domain

import com.jaac.avoqado_tpv.BuildConfig

/**
 * Runtime configuration for terminal/device identification
 *
 * **Purpose:** Enables dynamic serial number switching for multi-merchant support
 *
 * **Background:**
 * BuildConfig.TERMINAL_SERIAL is a compile-time constant (immutable).
 * Multi-merchant payment routing requires changing the serial at runtime to:
 * 1. Authenticate with different Blumon merchant accounts
 * 2. Download DUKPT keys for the target merchant
 * 3. Process payments under correct merchant credentials
 *
 * **Usage:**
 * ```kotlin
 * // Default (uses BuildConfig value)
 * TerminalConfig.serialNumber // "2841548417"
 *
 * // Switch to different merchant
 * TerminalConfig.serialNumber = "2841548418"
 * initializationManager.forceReinitialize()  // Re-init SDK with new serial
 *
 * // Reset to default
 * TerminalConfig.reset()
 * ```
 *
 * **CRITICAL:**
 * All SDK initialization code MUST use `TerminalConfig.serialNumber`
 * instead of `BuildConfig.TERMINAL_SERIAL` for multi-merchant to work.
 *
 * Files using this:
 * - InitializationManager.kt
 * - BlumonAuthManager.kt
 * - BlumonInitializer.kt
 * - MultiMerchantSDKManager.kt (switches serial)
 *
 * @see MultiMerchantSDKManager.switchMerchant
 */
object TerminalConfig {
    /**
     * Current terminal serial number
     *
     * Default: BuildConfig.TERMINAL_SERIAL ("2841548417")
     * Can be changed at runtime for multi-merchant switching
     */
    var serialNumber: String = BuildConfig.TERMINAL_SERIAL

    /**
     * Terminal brand (e.g., "PAX")
     *
     * Currently not changed dynamically, but available for future use
     */
    var brand: String = BuildConfig.TERMINAL_BRAND

    /**
     * Terminal model (e.g., "A910S")
     *
     * Currently not changed dynamically, but available for future use
     */
    var model: String = BuildConfig.TERMINAL_MODEL

    /**
     * Reset all configuration to BuildConfig defaults
     *
     * Useful for:
     * - Testing (reset to known state)
     * - Error recovery (rollback to default merchant)
     * - App logout (clear merchant selection)
     */
    fun reset() {
        serialNumber = BuildConfig.TERMINAL_SERIAL
        brand = BuildConfig.TERMINAL_BRAND
        model = BuildConfig.TERMINAL_MODEL
    }

    /**
     * Get current configuration as readable string (for logging/debugging)
     */
    override fun toString(): String {
        return "TerminalConfig(serial=$serialNumber, brand=$brand, model=$model)"
    }
}
