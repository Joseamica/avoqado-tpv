package com.jaac.avoqado_tpv.features.referrals.domain.usecase

import com.jaac.avoqado_tpv.features.referrals.domain.model.ValidationResult
import com.jaac.avoqado_tpv.features.referrals.domain.repository.ReferralsRepository
import javax.inject.Inject

/**
 * Validates a referral code at the cobrar step. No state is persisted —
 * the operator can re-try without side effects until they're ready to charge.
 */
class ValidateReferralUseCase @Inject constructor(
    private val repository: ReferralsRepository,
) {
    suspend operator fun invoke(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
    ): Result<ValidationResult> = repository.validate(
        venueId = venueId,
        referralCode = referralCode,
        newCustomerId = newCustomerId,
    )
}
