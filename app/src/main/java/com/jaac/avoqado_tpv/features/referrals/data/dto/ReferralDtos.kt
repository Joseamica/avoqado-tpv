package com.jaac.avoqado_tpv.features.referrals.data.dto

import com.google.gson.annotations.SerializedName

/**
 * DTOs for the referrals capture flow (Plan 5A — TPV Referral Capture).
 *
 * **Backend contract:**
 * - POST /api/v1/dashboard/venues/{venueId}/referrals/validate
 * - POST /api/v1/dashboard/venues/{venueId}/referrals/capture
 * - POST /api/v1/dashboard/venues/{venueId}/referrals/force-override
 *
 * All endpoints are venue-scoped. Auth is via Bearer token; the backend
 * derives `managerStaffVenueId` from the auth context on `force-override`.
 *
 * Validation/capture both require `newCustomerId` because the program rule
 * "EXISTING_CUSTOMER" is checked against this customer's prior order count
 * in the same venue. The TPV must therefore have a selected Customer
 * before the operator can validate a referral code.
 *
 * See: avoqado-server/src/schemas/dashboard/referrals.schemas.ts
 */
data class ReferralValidateRequest(
    @SerializedName("referralCode")
    val referralCode: String,
    @SerializedName("newCustomerId")
    val newCustomerId: String,
)

/**
 * Mirrors `ValidationResult` in
 * avoqado-server/src/services/referrals/referralCapture.service.ts.
 *
 * Shape:
 * ```json
 * {
 *   "valid": true,
 *   "referrer": { "id": "cust_xxx", "firstName": "Ana", "lastName": "López" },
 *   "discountPercent": 10
 * }
 * ```
 * or, on rejection:
 * ```json
 * { "valid": false, "reason": "EXISTING_CUSTOMER" }
 * ```
 */
data class ReferralValidateResponse(
    @SerializedName("valid")
    val valid: Boolean,
    @SerializedName("reason")
    val reason: String? = null,
    @SerializedName("referrer")
    val referrer: ReferrerDto? = null,
    @SerializedName("discountPercent")
    val discountPercent: Int? = null,
)

data class ReferrerDto(
    @SerializedName("id")
    val id: String,
    @SerializedName("firstName")
    val firstName: String? = null,
    @SerializedName("lastName")
    val lastName: String? = null,
)

data class ReferralCaptureRequest(
    @SerializedName("referralCode")
    val referralCode: String,
    @SerializedName("newCustomerId")
    val newCustomerId: String,
    @SerializedName("capturedByStaffVenueId")
    val capturedByStaffVenueId: String,
    @SerializedName("intendedOrderId")
    val intendedOrderId: String? = null,
)

/**
 * Backend returns a Prisma `Referral` row on 201. The TPV only needs the id
 * (and may later show the status to confirm PENDING). Use `Any` extras to
 * be forward-compatible if backend grows the model.
 */
data class ReferralCaptureResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("referrerCustomerId")
    val referrerCustomerId: String? = null,
    @SerializedName("referredCustomerId")
    val referredCustomerId: String? = null,
)

data class ReferralForceOverrideRequest(
    @SerializedName("referralCode")
    val referralCode: String,
    @SerializedName("existingCustomerId")
    val existingCustomerId: String,
    @SerializedName("capturedByStaffVenueId")
    val capturedByStaffVenueId: String,
    @SerializedName("reason")
    val reason: String,
)
