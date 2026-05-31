package com.jaac.avoqado_tpv.features.referrals.domain.usecase

import com.jaac.avoqado_tpv.features.referrals.domain.repository.ReferralsRepository
import javax.inject.Inject

/**
 * Persists a PENDING Referral right before the payment is initiated. Called
 * from the checkout flow only when the operator has a [Valid] validation
 * cached in state.
 *
 * Returns the Referral id on success. Failures bubble through as
 * `Result.failure(ReferralValidationException)` for validation rejects or
 * a generic [Exception] for transport errors.
 */
class CaptureReferralUseCase @Inject constructor(
    private val repository: ReferralsRepository,
) {
    suspend operator fun invoke(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
        capturedByStaffVenueId: String,
        intendedOrderId: String? = null,
    ): Result<String> = repository.capture(
        venueId = venueId,
        referralCode = referralCode,
        newCustomerId = newCustomerId,
        capturedByStaffVenueId = capturedByStaffVenueId,
        intendedOrderId = intendedOrderId,
    )
}
