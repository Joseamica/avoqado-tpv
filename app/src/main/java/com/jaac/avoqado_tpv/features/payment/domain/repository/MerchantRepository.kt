package com.jaac.avoqado_tpv.features.payment.domain.repository

import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing merchant accounts
 *
 * **Purpose:**
 * Provides abstraction layer between domain and data layers for merchant account operations.
 * Follows Clean Architecture: domain defines contract, data layer implements it.
 *
 * **Data Sources (implementation responsibility):**
 * 1. Remote: Backend API (future - avoqado-server endpoint)
 * 2. Local: Hardcoded defaults (current - for sandbox testing)
 * 3. Cache: In-memory or SharedPreferences (optional - for offline support)
 *
 * **Usage:**
 * ```kotlin
 * class PaymentViewModel @Inject constructor(
 *     private val merchantRepository: MerchantRepository
 * ) : ViewModel() {
 *     val merchants = merchantRepository.getMerchants()
 *         .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
 * }
 * ```
 */
interface MerchantRepository {

    /**
     * Get all available merchant accounts for this terminal
     *
     * **Current Implementation:**
     * Returns hardcoded sandbox accounts (2841548417, 2841548418)
     *
     * **Future Implementation:**
     * Fetch from backend API:
     * GET /api/v1/tpv/merchants?terminalId={deviceId}
     *
     * @return Flow of merchant accounts (reactive - updates when data changes)
     */
    fun getMerchants(): Flow<List<MerchantAccount>>

    /**
     * Get active merchant accounts only
     *
     * Filters out inactive accounts (isActive = false)
     * Useful for UI merchant selector (show only selectable accounts)
     *
     * @return Flow of active merchant accounts
     */
    fun getActiveMerchants(): Flow<List<MerchantAccount>>

    /**
     * Get a specific merchant account by ID
     *
     * @param merchantId Unique merchant identifier
     * @return Merchant account if found, null otherwise
     */
    suspend fun getMerchantById(merchantId: String): MerchantAccount?

    /**
     * Get a merchant account by serial number
     *
     * Useful for:
     * - Reverse lookup: "Which merchant does serial 2841548417 belong to?"
     * - Validation: "Is this serial registered as a merchant?"
     *
     * @param serialNumber Blumon device serial number
     * @return Merchant account if found, null otherwise
     */
    suspend fun getMerchantBySerial(serialNumber: String): MerchantAccount?

    /**
     * Refresh merchant accounts from backend
     *
     * **Current Implementation:**
     * No-op (returns hardcoded data)
     *
     * **Future Implementation:**
     * Force fetch from backend API, update cache
     *
     * @return Result.success if refresh succeeded
     */
    suspend fun refreshMerchants(): Result<Unit>
}
