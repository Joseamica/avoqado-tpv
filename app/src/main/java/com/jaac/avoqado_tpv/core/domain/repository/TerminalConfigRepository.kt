package com.jaac.avoqado_tpv.core.domain.repository

import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount

/**
 * Repository for fetching terminal configuration from backend
 *
 * **Purpose:**
 * Provides dynamic terminal configuration on app startup, eliminating hardcoded credentials.
 * Enables multi-merchant support by fetching assigned merchant accounts from backend.
 *
 * **Usage:**
 * ```kotlin
 * // In AvoqadoApp.onCreate() or SplashViewModel
 * val result = terminalConfigRepository.fetchConfig(deviceSerial)
 * if (result.isSuccess) {
 *     val (terminal, merchants) = result.getOrNull()!!
 *     TerminalConfig.initialize(terminal.serialNumber, terminal.brand, terminal.model)
 *     merchantRepository.updateMerchants(merchants)
 * }
 * ```
 */
interface TerminalConfigRepository {

    /**
     * Fetch terminal configuration from backend
     *
     * **API Endpoint:** GET /tpv/terminals/{serialNumber}/config
     *
     * **Returns:**
     * - Success: Pair<TerminalInfo, List<MerchantAccount>>
     * - Failure: Exception with user-friendly error message
     *
     * @param serialNumber Terminal serial number (e.g., "2841548417")
     * @return Result with terminal info and merchant accounts, or error
     */
    suspend fun fetchConfig(serialNumber: String): Result<Pair<TerminalInfo, List<MerchantAccount>>>
}

/**
 * Terminal information (minimal fields needed for TerminalConfig)
 *
 * @property serialNumber Terminal serial number
 * @property brand Terminal brand (e.g., "PAX")
 * @property model Terminal model (e.g., "A910S")
 * @property venueId Venue ID this terminal belongs to
 * @property venueName Venue name
 * @property venueType Venue type (RESTAURANT, BAR, CAFE, FAST_FOOD, RETAIL_STORE, etc.)
 */
data class TerminalInfo(
    val serialNumber: String,
    val brand: String,
    val model: String,
    val venueId: String,
    val venueName: String,
    val venueType: String?
)
