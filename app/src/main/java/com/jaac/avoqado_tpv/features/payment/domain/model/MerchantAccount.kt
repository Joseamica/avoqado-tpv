package com.jaac.avoqado_tpv.features.payment.domain.model

/**
 * Domain model representing a merchant account for payment routing
 *
 * **Purpose:**
 * Enables multi-merchant payment processing from a single physical terminal.
 * Each merchant account has unique Blumon credentials and DUKPT keys.
 *
 * **Business Context:**
 * - Restaurant with multiple brands (e.g., Main Restaurant + Ghost Kitchen)
 * - Venue with sub-merchants (e.g., Bar + Restaurant + Shop)
 * - Franchise locations sharing a terminal
 *
 * **Technical Context:**
 * Each merchant account is registered with Blumon as a separate "device":
 * - Serial number acts as device identifier
 * - SDK must re-initialize when switching accounts (3-5 seconds)
 * - DUKPT keys are unique per serial number
 *
 * **Example:**
 * ```kotlin
 * val accountA = MerchantAccount(
 *     id = "merchant_001",
 *     serialNumber = "2841548417",
 *     displayName = "Main Restaurant",
 *     environment = MerchantEnvironment.SANDBOX
 * )
 * ```
 *
 * @property id Unique identifier (from backend database)
 * @property serialNumber Blumon device serial (acts as OAuth username)
 * @property displayName User-friendly name shown in UI
 * @property description Optional description for clarification
 * @property environment SANDBOX or PRODUCTION
 * @property isActive Whether account is currently selectable
 */
data class MerchantAccount(
    val id: String,
    val serialNumber: String,
    val displayName: String,
    val description: String? = null,
    val environment: MerchantEnvironment = MerchantEnvironment.SANDBOX,
    val isActive: Boolean = true
) {
    /**
     * Get formatted display text for UI
     *
     * Example: "Main Restaurant (Sandbox)"
     */
    fun getDisplayText(): String {
        val envSuffix = when (environment) {
            MerchantEnvironment.SANDBOX -> " (Sandbox)"
            MerchantEnvironment.PRODUCTION -> ""
        }
        return "$displayName$envSuffix"
    }

    /**
     * Check if this account matches the current TerminalConfig
     */
    fun isCurrentlyActive(): Boolean {
        return serialNumber == com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber
    }

    companion object {
        /**
         * Default sandbox accounts for testing (Edgardo's registered devices)
         */
        val SANDBOX_ACCOUNT_A = MerchantAccount(
            id = "merchant_sandbox_a",
            serialNumber = "2841548417",
            displayName = "Account A",
            description = "Primary sandbox merchant account",
            environment = MerchantEnvironment.SANDBOX
        )

        val SANDBOX_ACCOUNT_B = MerchantAccount(
            id = "merchant_sandbox_b",
            serialNumber = "2841548418",
            displayName = "Account B",
            description = "Secondary sandbox merchant account",
            environment = MerchantEnvironment.SANDBOX
        )

        /**
         * Get default sandbox accounts for testing
         */
        fun getDefaultSandboxAccounts(): List<MerchantAccount> {
            return listOf(SANDBOX_ACCOUNT_A, SANDBOX_ACCOUNT_B)
        }
    }
}

/**
 * Merchant account environment
 */
enum class MerchantEnvironment {
    /**
     * Sandbox/Test environment
     * - Uses test credentials
     * - No real money transactions
     * - Blumon sandbox backend
     */
    SANDBOX,

    /**
     * Production environment
     * - Real merchant credentials
     * - Processes real payments
     * - Blumon production backend
     */
    PRODUCTION
}
