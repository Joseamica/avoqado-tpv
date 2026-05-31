package com.jaac.avoqado_tpv.features.referrals.data.api

import com.jaac.avoqado_tpv.features.referrals.data.dto.ReferralCaptureRequest
import com.jaac.avoqado_tpv.features.referrals.data.dto.ReferralCaptureResponse
import com.jaac.avoqado_tpv.features.referrals.data.dto.ReferralForceOverrideRequest
import com.jaac.avoqado_tpv.features.referrals.data.dto.ReferralValidateRequest
import com.jaac.avoqado_tpv.features.referrals.data.dto.ReferralValidateResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit service for the C2C referral program endpoints (Plan 5A).
 *
 * **Base URL:** Inherits `/api/v1/` from the configured Retrofit instance.
 *
 * **Auth:** Bearer token (added by AuthInterceptor).
 *
 * **Endpoints:**
 * 1. POST /dashboard/venues/{venueId}/referrals/validate — no side effects
 * 2. POST /dashboard/venues/{venueId}/referrals/capture — creates PENDING Referral
 * 3. POST /dashboard/venues/{venueId}/referrals/force-override — manager override
 *
 * See backend router: avoqado-server/src/routes/dashboard/referrals.routes.ts
 */
interface ReferralsApiService {

    /**
     * Validates a referral code without persisting anything. Used by the
     * TPV to give the operator immediate feedback before charging.
     *
     * **Success codes:**
     * - 200: `valid=true` with `referrer` + `discountPercent`, or `valid=false` + `reason`.
     *
     * **Error codes:**
     * - 401: auth missing/expired
     * - 403: missing `referral:read` permission
     * - 404: venue not found
     */
    @POST("dashboard/venues/{venueId}/referrals/validate")
    suspend fun validate(
        @Path("venueId") venueId: String,
        @Body request: ReferralValidateRequest,
    ): Response<ReferralValidateResponse>

    /**
     * Creates a PENDING Referral row. Backend re-runs validation; if it
     * fails, returns 400 with `{ valid:false, reason:<ValidationReason> }`.
     *
     * **Success codes:**
     * - 201: Referral persisted; response body is the new row.
     *
     * **Error codes:**
     * - 400: validation failed (parsable via [ReferralValidateResponse]).
     * - 401/403: auth/permission.
     */
    @POST("dashboard/venues/{venueId}/referrals/capture")
    suspend fun capture(
        @Path("venueId") venueId: String,
        @Body request: ReferralCaptureRequest,
    ): Response<ReferralCaptureResponse>

    /**
     * Manager override path for the EXISTING_CUSTOMER reject case. Requires
     * `referral:override-existing-customer` permission server-side.
     */
    @POST("dashboard/venues/{venueId}/referrals/force-override")
    suspend fun forceOverride(
        @Path("venueId") venueId: String,
        @Body request: ReferralForceOverrideRequest,
    ): Response<ReferralCaptureResponse>
}
