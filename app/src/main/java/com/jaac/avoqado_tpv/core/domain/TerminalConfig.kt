package com.jaac.avoqado_tpv.core.domain

/**
 * Runtime configuration for terminal/device identification
 *
 * **Purpose:** Enables dynamic serial number switching for multi-merchant support
 *
 * **UPDATED (2025-11-05):** Removed BuildConfig dependency
 * Configuration now fetched dynamically from backend via TerminalConfigRepository.
 *
 * **Background:**
 * Multi-merchant payment routing requires changing the serial at runtime to:
 * 1. Authenticate with different Blumon merchant accounts
 * 2. Download DUKPT keys for the target merchant
 * 3. Process payments under correct merchant credentials
 *
 * **Usage:**
 * ```kotlin
 * // Initialize from backend
 * val config = terminalConfigRepository.getConfig()
 * TerminalConfig.serialNumber = config.primaryMerchant.serialNumber
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
 * instead of hardcoded values for multi-merchant to work.
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
     * Default values for development/testing
     *
     * ⚠️ TEMPORARY: These will be replaced by backend-fetched values
     * Once TerminalConfigRepository is implemented, these defaults
     * are only used as fallback if backend fetch fails.
     */
    private const val DEFAULT_SERIAL = "2841548417"  // Sandbox device
    private const val DEFAULT_BRAND = "PAX"
    private const val DEFAULT_MODEL = "A910S"

    /**
     * Current terminal serial number
     *
     * Initially set to DEFAULT_SERIAL, should be updated from backend
     * during app initialization or after terminal activation.
     */
    var serialNumber: String = DEFAULT_SERIAL
        private set

    /**
     * Terminal brand (e.g., "PAX")
     *
     * Fetched from backend terminal config
     */
    var brand: String = DEFAULT_BRAND
        private set

    /**
     * Terminal model (e.g., "A910S")
     *
     * Fetched from backend terminal config
     */
    var model: String = DEFAULT_MODEL
        private set

    /**
     * Initialize terminal config from backend response
     *
     * Should be called during app startup after fetching config
     * from GET /api/v1/tpv/terminals/:serial/config
     *
     * @param serial Terminal serial number
     * @param brand Terminal brand (optional)
     * @param model Terminal model (optional)
     */
    fun initialize(
        serial: String,
        brand: String = DEFAULT_BRAND,
        model: String = DEFAULT_MODEL
    ) {
        this.serialNumber = serial
        this.brand = brand
        this.model = model
    }

    /**
     * Update serial number (used for merchant switching)
     *
     * This method is called by MultiMerchantSDKManager when switching
     * between merchant accounts.
     *
     * @param newSerial New serial number to use
     */
    fun updateSerial(newSerial: String) {
        this.serialNumber = newSerial
    }

    /**
     * Reset all configuration to defaults
     *
     * Useful for:
     * - Testing (reset to known state)
     * - Error recovery (rollback to default merchant)
     * - App logout (clear merchant selection)
     */
    fun reset() {
        serialNumber = DEFAULT_SERIAL
        brand = DEFAULT_BRAND
        model = DEFAULT_MODEL
    }

    /**
     * Get current configuration as readable string (for logging/debugging)
     */
    override fun toString(): String {
        return "TerminalConfig(serial=$serialNumber, brand=$brand, model=$model)"
    }
}
