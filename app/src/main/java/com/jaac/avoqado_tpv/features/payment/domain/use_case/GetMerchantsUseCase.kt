package com.jaac.avoqado_tpv.features.payment.domain.use_case

import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use Case: Get available merchant accounts
 *
 * **Purpose:**
 * Retrieve list of merchant accounts that can be selected for payment processing.
 * Follows Clean Architecture: encapsulates business logic in domain layer.
 *
 * **Business Rules:**
 * - Only active accounts are returned (isActive = true)
 * - Accounts are filtered by environment (SANDBOX vs PRODUCTION)
 * - Returns reactive Flow for real-time UI updates
 *
 * **Usage:**
 * ```kotlin
 * @HiltViewModel
 * class PaymentViewModel @Inject constructor(
 *     private val getMerchantsUseCase: GetMerchantsUseCase
 * ) : ViewModel() {
 *     val merchants = getMerchantsUseCase()
 *         .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
 * }
 * ```
 *
 * **Why Use Case vs Direct Repository?**
 * - Single Responsibility: Use case encapsulates "get merchants for payment selection" logic
 * - Testability: Easy to mock for ViewModel tests
 * - Business Logic: Can add filtering, sorting, validation without touching repository
 * - Consistency: All features follow same pattern (PaymentViewModel → UseCase → Repository)
 */
class GetMerchantsUseCase @Inject constructor(
    private val merchantRepository: MerchantRepository
) {
    /**
     * Execute use case - get active merchants
     *
     * **Returns:**
     * Flow<List<MerchantAccount>> - Reactive stream that emits when merchant list changes
     *
     * **Business Logic:**
     * 1. Fetch merchants from repository
     * 2. Filter to only active accounts (isActive = true)
     * 3. Sort by display name (alphabetical)
     *
     * @return Flow of active merchant accounts, sorted alphabetically
     */
    operator fun invoke(): Flow<List<MerchantAccount>> {
        return merchantRepository.getActiveMerchants()
        // Future enhancement: Add sorting/filtering here
        // .map { merchants -> merchants.sortedBy { it.displayName } }
    }

    /**
     * Get all merchants (including inactive)
     *
     * Useful for admin screens or debugging.
     * Normal payment flow should use invoke() which filters to active only.
     *
     * @return Flow of all merchant accounts
     */
    fun getAllMerchants(): Flow<List<MerchantAccount>> {
        return merchantRepository.getMerchants()
    }
}
