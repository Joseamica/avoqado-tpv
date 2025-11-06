package com.jaac.avoqado_tpv.features.payment.data

import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of MerchantRepository
 *
 * **Current State (MVP):**
 * Returns hardcoded sandbox merchant accounts for testing.
 * Edgardo has registered 2 devices in Blumon sandbox:
 * - Serial 2841548417 → Account A
 * - Serial 2841548418 → Account B
 *
 * **Future Enhancement:**
 * Integrate with backend API (avoqado-server):
 * ```
 * GET /api/v1/tpv/merchants?terminalId={deviceId}
 * Response:
 * {
 *   "merchants": [
 *     {
 *       "id": "merchant_001",
 *       "serialNumber": "2841548417",
 *       "displayName": "Main Restaurant",
 *       "description": "Primary sales account",
 *       "environment": "SANDBOX",
 *       "isActive": true
 *     },
 *     ...
 *   ]
 * }
 * ```
 *
 * **Data Flow:**
 * ```
 * Backend API
 *     ↓
 * MerchantRepositoryImpl (cache + transform)
 *     ↓
 * GetMerchantsUseCase
 *     ↓
 * PaymentViewModel
 *     ↓
 * MerchantSelectionDialog UI
 * ```
 */
@Singleton
class MerchantRepositoryImpl @Inject constructor(
    // TODO: Inject API service when backend endpoint is ready
    // private val merchantApiService: MerchantApiService
) : MerchantRepository {

    // In-memory cache (StateFlow for reactive updates)
    private val _merchants = MutableStateFlow<List<MerchantAccount>>(emptyList())

    init {
        // Initialize with default sandbox accounts (fallback)
        // These will be replaced when terminal config is fetched from backend
        _merchants.value = MerchantAccount.getDefaultSandboxAccounts()
        Timber.d("📋 [MerchantRepository] Initialized with ${_merchants.value.size} sandbox accounts (fallback)")
        Timber.d("   ⚠️  These will be replaced by backend config on app startup")
    }

    /**
     * Get all merchant accounts (reactive Flow)
     *
     * **Current:** Returns hardcoded sandbox accounts
     * **Future:** Fetch from backend + cache locally
     */
    override fun getMerchants(): Flow<List<MerchantAccount>> {
        return _merchants
    }

    /**
     * Get only active merchant accounts
     *
     * Filters merchants where isActive = true
     */
    override fun getActiveMerchants(): Flow<List<MerchantAccount>> {
        return _merchants.map { merchants ->
            merchants.filter { it.isActive }
        }
    }

    /**
     * Get merchant by ID
     *
     * @param merchantId Unique merchant identifier (e.g., "merchant_sandbox_a")
     * @return Merchant if found, null otherwise
     */
    override suspend fun getMerchantById(merchantId: String): MerchantAccount? {
        return _merchants.value.find { it.id == merchantId }
    }

    /**
     * Get merchant by serial number
     *
     * **Use Case:**
     * Reverse lookup to find which merchant a serial belongs to.
     * Example: "Serial 2841548417 → Account A"
     *
     * @param serialNumber Blumon device serial (e.g., "2841548417")
     * @return Merchant if found, null otherwise
     */
    override suspend fun getMerchantBySerial(serialNumber: String): MerchantAccount? {
        return _merchants.value.find { it.serialNumber == serialNumber }
    }

    /**
     * Refresh merchants from backend
     *
     * **Current:** No-op (sandbox accounts are static)
     * **Future:** Fetch from API, update cache
     *
     * Implementation plan:
     * ```kotlin
     * override suspend fun refreshMerchants(): Result<Unit> {
     *     return try {
     *         val response = merchantApiService.getMerchants(deviceId)
     *         _merchants.value = response.merchants.map { it.toDomain() }
     *         Result.success(Unit)
     *     } catch (e: Exception) {
     *         Timber.e(e, "Failed to refresh merchants")
     *         Result.failure(e)
     *     }
     * }
     * ```
     */
    override suspend fun refreshMerchants(): Result<Unit> {
        // For sandbox: just return current cached data
        Timber.d("📋 [MerchantRepository] Refresh requested (no-op for sandbox)")
        return Result.success(Unit)
    }

    /**
     * Add or update a merchant account (for testing/admin)
     *
     * ⚠️ NOT part of interface (implementation-specific method)
     * Useful for:
     * - Testing new merchant configurations
     * - Admin panel to add merchants without backend
     *
     * @param merchant Merchant account to add/update
     */
    fun addOrUpdateMerchant(merchant: MerchantAccount) {
        val currentList = _merchants.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id == merchant.id }

        if (existingIndex >= 0) {
            // Update existing
            currentList[existingIndex] = merchant
            Timber.d("📋 [MerchantRepository] Updated merchant: ${merchant.displayName}")
        } else {
            // Add new
            currentList.add(merchant)
            Timber.d("📋 [MerchantRepository] Added merchant: ${merchant.displayName}")
        }

        _merchants.value = currentList
    }

    /**
     * Remove a merchant account (for testing/admin)
     *
     * ⚠️ NOT part of interface (implementation-specific method)
     *
     * @param merchantId Merchant ID to remove
     * @return true if removed, false if not found
     */
    fun removeMerchant(merchantId: String): Boolean {
        val currentList = _merchants.value.toMutableList()
        val removed = currentList.removeIf { it.id == merchantId }

        if (removed) {
            _merchants.value = currentList
            Timber.d("📋 [MerchantRepository] Removed merchant: $merchantId")
        }

        return removed
    }
}
