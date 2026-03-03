package com.jaac.avoqado_tpv.features.payment.data

import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
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
 * Initializes with hardcoded sandbox fallback accounts. On app startup,
 * MainActivity fetches terminal config and calls updateMerchants() with
 * real backend accounts. If startup fetch fails (network transition),
 * refreshMerchants() can be called later to retry.
 *
 * **Data Flow:**
 * Backend API → TerminalConfigRepository.fetchConfig() → updateMerchants() → Flow
 */
@Singleton
class MerchantRepositoryImpl @Inject constructor(
    private val terminalConfigRepository: TerminalConfigRepository,
    private val deviceInfoManager: DeviceInfoManager
) : MerchantRepository {

    // In-memory cache (StateFlow for reactive updates)
    private val _merchants = MutableStateFlow<List<MerchantAccount>>(emptyList())

    // Whether current merchants are fallback (not from backend)
    @Volatile
    private var _isFallback = true

    init {
        // Initialize with default sandbox accounts (fallback)
        // These will be replaced when terminal config is fetched from backend
        _merchants.value = MerchantAccount.getDefaultSandboxAccounts()
        _isFallback = true
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
     * Refresh merchants from backend via terminal config endpoint.
     *
     * Used when fallback accounts are detected (e.g., network was down during startup).
     * Fetches terminal config, extracts merchant accounts, and replaces fallbacks.
     */
    override suspend fun refreshMerchants(): Result<Unit> {
        return try {
            val serialNumber = deviceInfoManager.getSerialNumber()
            Timber.i("📋 [MerchantRepository] Refreshing merchants from backend (serial=$serialNumber)...")

            val configResult = terminalConfigRepository.fetchConfig(serialNumber)
            configResult.fold(
                onSuccess = { (_, merchantAccounts) ->
                    if (merchantAccounts.isEmpty()) {
                        Timber.w("⚠️ [MerchantRepository] Backend returned 0 merchants")
                        Result.failure(IllegalStateException("Backend returned 0 merchants"))
                    } else {
                        updateMerchants(merchantAccounts)
                        Timber.i("✅ [MerchantRepository] Refreshed ${merchantAccounts.size} merchants from backend")
                        Result.success(Unit)
                    }
                },
                onFailure = { error ->
                    Timber.w(error, "⚠️ [MerchantRepository] Failed to refresh merchants from backend")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ [MerchantRepository] Error refreshing merchants")
            Result.failure(e)
        }
    }

    /**
     * Replace all merchants with new list from backend
     *
     * This REPLACES fallback accounts with dynamic config from backend.
     * Used by MainActivity when fetching terminal config on startup,
     * and by ActivationViewModel after successful terminal activation.
     *
     * @param merchants List of merchant accounts from backend with CUIDs
     */
    override fun isUsingFallback(): Boolean = _isFallback

    override fun updateMerchants(merchants: List<MerchantAccount>) {
        _merchants.value = merchants
        _isFallback = false
        Timber.i("📋 [MerchantRepository] Replaced with ${merchants.size} merchants from backend")
        merchants.forEach { merchant ->
            Timber.d("   ✅ ${merchant.displayName} (${merchant.serialNumber})")
        }
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
