package com.jaac.avoqado_tpv.features.payment.domain.model

import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType

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
 * - posId is Momentum API position ID (CRITICAL for payment routing)
 * - SDK must re-initialize when switching accounts (3-5 seconds)
 * - DUKPT keys are unique per serial number
 *
 * **Example:**
 * ```kotlin
 * val accountA = MerchantAccount(
 *     id = "merchant_001",
 *     serialNumber = "2841548417",
 *     posId = "376",
 *     displayName = "Main Restaurant",
 *     environment = MerchantEnvironment.SANDBOX
 * )
 * ```
 *
 * @property id Unique identifier (from backend database)
 * @property serialNumber Blumon device serial (acts as OAuth username)
 * @property posId Momentum API position ID (CRITICAL - must match serial)
 * @property displayName User-friendly name shown in UI
 * @property description Optional description for clarification
 * @property environment SANDBOX or PRODUCTION
 * @property isActive Whether account is currently selectable
 * @property availableMsiMonths MSI options enabled for this merchant, if any
 */
data class MerchantAccount(
    val id: String,  // Local ID (e.g., "merchant_sandbox_a") - kept for backwards compatibility
    val merchantAccountId: String? = null,  // 🆕 Backend CUID (e.g., "cmi1yg8mw...") - PRIMARY for API calls
    val serialNumber: String,
    val posId: String? = null,
    val displayName: String,
    val description: String? = null,
    val environment: MerchantEnvironment = MerchantEnvironment.SANDBOX,
    val isActive: Boolean = true,
    val processorType: ProcessorType = ProcessorType.BLUMON,
    val availableMsiMonths: List<Int> = emptyList(),
    // 🆕 Task 24 — additive AngelPay fields (purely additive; Blumon callers unaffected)
    val externalMerchantId: String? = null,       // AngelPay: numeric string parseable to Int; Blumon: serial/posId equivalent
    val angelpayAffiliation: String? = null,
    val angelpayMerchantName: String? = null
) {
    /**
     * AngelPay merchant ID as Int — only call when processorType == ANGELPAY.
     * Throws if externalMerchantId is null or non-numeric (malformed config).
     */
    fun requireAngelpayMerchantId(): Int =
        externalMerchantId?.toIntOrNull()
            ?: error("Malformed externalMerchantId for ANGELPAY merchant $merchantAccountId: '$externalMerchantId'")

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
         * ⚠️ DEPRECATED: Hardcoded sandbox accounts (FALLBACK ONLY)
         *
         * **Current Usage:**
         * Used ONLY as fallback when backend is unreachable.
         * MerchantRepository replaces these on app startup with dynamic config from backend.
         *
         * **Migration Path:**
         * 1. Superadmin creates merchant accounts in backend
         * 2. Assigns merchants to terminal via POST /superadmin/terminals/:id/merchants
         * 3. Android fetches config via GET /tpv/terminals/:serial/config on startup
         * 4. These hardcoded accounts are replaced with backend config
         *
         * **When These Are Used:**
         * - Backend is unreachable (network error, server down)
         * - Terminal not yet configured by superadmin
         * - Development/testing without backend connection
         *
         * **Future Action:**
         * These will be removed in v2.0 when backend config is mandatory.
         *
         * @see com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
         * @see com.jaac.avoqado_tpv.features.payment.data.MerchantRepositoryImpl
         */
        @Deprecated(
            message = "Use MerchantRepository.getMerchants() to get dynamic config from backend. " +
                    "These hardcoded accounts are FALLBACK ONLY.",
            replaceWith = ReplaceWith(
                "merchantRepository.getMerchants().first()",
                "com.jaac.avoqado_tpv.features.payment.data.MerchantRepositoryImpl"
            ),
            level = DeprecationLevel.WARNING
        )
        val SANDBOX_ACCOUNT_A = MerchantAccount(
            id = "merchant_sandbox_a",
            serialNumber = "2841548417",
            posId = "376",
            displayName = "Account A (Fallback)",
            description = "Hardcoded fallback - replaced by backend config",
            environment = MerchantEnvironment.SANDBOX
        )

        @Deprecated(
            message = "Use MerchantRepository.getMerchants() to get dynamic config from backend. " +
                    "These hardcoded accounts are FALLBACK ONLY.",
            replaceWith = ReplaceWith(
                "merchantRepository.getMerchants().first()",
                "com.jaac.avoqado_tpv.features.payment.data.MerchantRepositoryImpl"
            ),
            level = DeprecationLevel.WARNING
        )
        val SANDBOX_ACCOUNT_B = MerchantAccount(
            id = "merchant_sandbox_b",
            serialNumber = "2841548418",
            posId = "378",
            displayName = "Account B (Fallback)",
            description = "Hardcoded fallback - replaced by backend config",
            environment = MerchantEnvironment.SANDBOX
        )

        /**
         * Get default sandbox accounts for testing (FALLBACK ONLY)
         *
         * ⚠️ **DEPRECATED**: Use MerchantRepository to fetch dynamic config from backend.
         *
         * **Current Behavior:**
         * MerchantRepository initializes with these accounts, then replaces them
         * on app startup with config fetched from backend.
         *
         * **Fallback Scenario:**
         * If backend is unreachable, these accounts remain available for payment processing.
         *
         * **Production Behavior:**
         * In production, backend config is fetched successfully, and these accounts
         * are never used (replaced immediately on startup).
         *
         * @return List of 2 sandbox accounts (Account A + Account B)
         */
        @Deprecated(
            message = "Use MerchantRepository.getMerchants() to get dynamic config from backend. " +
                    "This method returns FALLBACK accounts only.",
            replaceWith = ReplaceWith(
                "merchantRepository.getMerchants().first()",
                "com.jaac.avoqado_tpv.features.payment.data.MerchantRepositoryImpl"
            ),
            level = DeprecationLevel.WARNING
        )
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
