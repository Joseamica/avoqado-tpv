package com.jaac.avoqado_tpv.features.referrals.domain.repository

import com.jaac.avoqado_tpv.features.referrals.domain.model.ValidationResult

/**
 * Domain-side gateway to the referrals capture endpoints (Plan 5A).
 *
 * Uses Kotlin stdlib `Result<T>` rather than the core/domain/models/Result
 * sealed class — this matches the pattern in CustomerRepository and the
 * coupon validation path in CheckoutViewModel.
 */
interface ReferralsRepository {

    /**
     * No-side-effect validation of [referralCode] for [newCustomerId] in
     * [venueId]. Returns a [ValidationResult] inside [kotlin.Result] so
     * network/HTTP failures surface separately from the validation reason.
     */
    suspend fun validate(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
    ): Result<ValidationResult>

    /**
     * Persists a PENDING Referral row tying [newCustomerId] to the
     * referrer who owns [referralCode]. [intendedOrderId] is optional —
     * pass the order id if known so the qualifying-order webhook can flip
     * the status without a separate lookup.
     *
     * The backend re-runs validation. Validation failures arrive as a
     * `Result.failure(ReferralValidationException(reason))` so callers
     * can switch on the reason without parsing message strings.
     */
    suspend fun capture(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
        capturedByStaffVenueId: String,
        intendedOrderId: String? = null,
    ): Result<String>

    /**
     * Manager-authorized override for the EXISTING_CUSTOMER reject case.
     * Requires `referral:override-existing-customer` permission server-side.
     */
    suspend fun forceOverride(
        venueId: String,
        referralCode: String,
        existingCustomerId: String,
        capturedByStaffVenueId: String,
        reason: String,
    ): Result<String>
}

/**
 * Thrown by `capture`/`forceOverride` when the backend rejects the request
 * with a structured validation reason. Lets callers do `when (reason)` on
 * the strongly-typed enum instead of parsing message strings.
 */
class ReferralValidationException(
    val reason: ValidationResult.Reason,
) : RuntimeException("Referral validation rejected: $reason")
