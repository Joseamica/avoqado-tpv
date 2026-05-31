package com.jaac.avoqado_tpv.features.referrals.data.repository

import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.referrals.data.api.ReferralsApiService
import com.jaac.avoqado_tpv.features.referrals.data.dto.ReferralCaptureRequest
import com.jaac.avoqado_tpv.features.referrals.data.dto.ReferralForceOverrideRequest
import com.jaac.avoqado_tpv.features.referrals.data.dto.ReferralValidateRequest
import com.jaac.avoqado_tpv.features.referrals.data.dto.ReferralValidateResponse
import com.jaac.avoqado_tpv.features.referrals.domain.model.ValidationResult
import com.jaac.avoqado_tpv.features.referrals.domain.repository.ReferralValidationException
import com.jaac.avoqado_tpv.features.referrals.domain.repository.ReferralsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [ReferralsRepository] backed by Retrofit. Maps HTTP responses to
 * the strongly-typed [ValidationResult] sealed class and surfaces
 * structured 400-validation errors as [ReferralValidationException].
 */
@Singleton
class ReferralsRepositoryImpl @Inject constructor(
    private val api: ReferralsApiService,
    private val gson: Gson,
) : ReferralsRepository {

    override suspend fun validate(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
    ): Result<ValidationResult> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.d("🎁 [Referrals] validate code=$referralCode customer=$newCustomerId venue=$venueId")
            val response = api.validate(
                venueId = venueId,
                request = ReferralValidateRequest(
                    referralCode = referralCode.trim().uppercase(),
                    newCustomerId = newCustomerId,
                ),
            )
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code()} validating referral code",
                )
            }
            val body = response.body()
                ?: throw IllegalStateException("Empty validate response body")
            body.toDomain()
        }.onFailure { e ->
            Timber.e(e, "🎁 [Referrals] validate failed")
        }
    }

    override suspend fun capture(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
        capturedByStaffVenueId: String,
        intendedOrderId: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.d("🎁 [Referrals] capture code=$referralCode customer=$newCustomerId order=$intendedOrderId")
            val response = api.capture(
                venueId = venueId,
                request = ReferralCaptureRequest(
                    referralCode = referralCode.trim().uppercase(),
                    newCustomerId = newCustomerId,
                    capturedByStaffVenueId = capturedByStaffVenueId,
                    intendedOrderId = intendedOrderId,
                ),
            )
            if (response.code() == 400) {
                throw parseValidationException(response.errorBody()?.string())
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code()} capturing referral",
                )
            }
            response.body()?.id
                ?: throw IllegalStateException("Empty capture response body")
        }.onFailure { e ->
            Timber.e(e, "🎁 [Referrals] capture failed")
        }
    }

    override suspend fun forceOverride(
        venueId: String,
        referralCode: String,
        existingCustomerId: String,
        capturedByStaffVenueId: String,
        reason: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.d("🎁 [Referrals] forceOverride code=$referralCode customer=$existingCustomerId")
            val response = api.forceOverride(
                venueId = venueId,
                request = ReferralForceOverrideRequest(
                    referralCode = referralCode.trim().uppercase(),
                    existingCustomerId = existingCustomerId,
                    capturedByStaffVenueId = capturedByStaffVenueId,
                    reason = reason,
                ),
            )
            if (response.code() == 400) {
                throw parseValidationException(response.errorBody()?.string())
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code()} forcing referral override",
                )
            }
            response.body()?.id
                ?: throw IllegalStateException("Empty force-override response body")
        }.onFailure { e ->
            Timber.e(e, "🎁 [Referrals] forceOverride failed")
        }
    }

    /**
     * Backend returns `{ valid:false, reason:<ValidationReason> }` on 400.
     * Parse it into a [ReferralValidationException] so callers can switch
     * on the enum.
     */
    private fun parseValidationException(errorBody: String?): ReferralValidationException {
        val reason = errorBody?.let {
            runCatching { gson.fromJson(it, ReferralValidateResponse::class.java) }
                .getOrNull()
                ?.reason
                ?.toReason()
        } ?: ValidationResult.Reason.UNKNOWN
        return ReferralValidationException(reason)
    }
}

private fun ReferralValidateResponse.toDomain(): ValidationResult {
    if (valid) {
        val name = listOfNotNull(referrer?.firstName, referrer?.lastName)
            .joinToString(" ")
            .ifBlank { "Cliente" }
        return ValidationResult.Valid(
            referrerName = name,
            discountPercent = discountPercent ?: 0,
            referrerCustomerId = referrer?.id.orEmpty(),
        )
    }
    return ValidationResult.Invalid(reason.toReason())
}

private fun String?.toReason(): ValidationResult.Reason = when (this) {
    "PROGRAM_INACTIVE" -> ValidationResult.Reason.PROGRAM_INACTIVE
    "CODE_NOT_FOUND" -> ValidationResult.Reason.CODE_NOT_FOUND
    "SELF_REFERRAL" -> ValidationResult.Reason.SELF_REFERRAL
    "EXISTING_CUSTOMER" -> ValidationResult.Reason.EXISTING_CUSTOMER
    else -> ValidationResult.Reason.UNKNOWN
}
