package com.jaac.avoqado_tpv.features.ordering.data.api

import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyCouponRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyCouponResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyDiscountResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyManualDiscountRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyPredefinedDiscountRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.AvailableDiscountsResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.CouponValidationResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.RemoveDiscountResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.ValidateCouponRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit service interface for discount and coupon endpoints.
 *
 * **Base URL:** https://api.avoqado.io/api/v1/ (production)
 *              https://humane-immortal-pika.ngrok-free.app/api/v1/ (development)
 *
 * **Authentication:** All requests require Bearer token in header.
 * ```
 * Authorization: Bearer {access_token}
 * ```
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
interface DiscountApiService {

    // ==========================================
    // DISCOUNT ENDPOINTS
    // ==========================================

    /**
     * Get available discounts for an order.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/orders/{orderId}/discounts/available
     *
     * **Backend Behavior:**
     * 1. Returns all eligible discounts for the order
     * 2. Considers order context (items, customer, time)
     * 3. Includes automatic discounts (for backend auto-apply)
     * 4. Includes predefined discounts (for manual selection)
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "data": [
     *     {
     *       "id": "disc_1",
     *       "name": "Happy Hour",
     *       "type": "PERCENTAGE",
     *       "value": 20,
     *       "scope": "ORDER",
     *       "conditions": { "validHoursStart": "14:00", "validHoursEnd": "18:00" },
     *       "active": true,
     *       "requiresAuthorization": false
     *     }
     *   ],
     *   "count": 1
     * }
     * ```
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param customerId Optional customer ID for customer-specific discounts
     * @return Response with available discounts
     */
    @GET("tpv/venues/{venueId}/orders/{orderId}/discounts/available")
    suspend fun getAvailableDiscounts(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Query("customerId") customerId: String? = null
    ): Response<AvailableDiscountsResponse>

    /**
     * Apply a predefined discount to order.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/discounts/apply
     *
     * **Backend Behavior:**
     * 1. Validates discount exists and is active
     * 2. Validates discount conditions (time, amount)
     * 3. Calculates discount amount
     * 4. Creates OrderDiscount record
     * 5. Updates order totals
     * 6. Emits Socket.IO event
     *
     * **Use Case:** Applying venue-defined discounts (Happy Hour, Employee, etc.)
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param request Discount application details
     * @return Response with updated order
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/discounts/apply")
    suspend fun applyPredefinedDiscount(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: ApplyPredefinedDiscountRequest
    ): Response<ApplyDiscountResponse>

    /**
     * Apply a manual discount to order.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/discounts/manual
     *
     * **Backend Behavior:**
     * 1. Validates discount value (0-100% or positive amount)
     * 2. May require manager authorization
     * 3. Creates OrderDiscount record with type=MANUAL
     * 4. Updates order totals
     * 5. Emits Socket.IO event
     *
     * **Use Case:** Manager discretionary discounts
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param request Manual discount details
     * @return Response with updated order
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/discounts/manual")
    suspend fun applyManualDiscount(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: ApplyManualDiscountRequest
    ): Response<ApplyDiscountResponse>

    /**
     * Remove a discount from order.
     *
     * **Endpoint:** DELETE /tpv/venues/{venueId}/orders/{orderId}/discounts/{discountId}
     *
     * **Backend Behavior:**
     * 1. Removes OrderDiscount record
     * 2. Recalculates order totals
     * 3. Emits Socket.IO event
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param orderDiscountId ID of the OrderDiscount to remove
     * @param expectedVersion Current order version
     * @return Response with updated order
     */
    @DELETE("tpv/venues/{venueId}/orders/{orderId}/discounts/{orderDiscountId}")
    suspend fun removeDiscount(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Path("orderDiscountId") orderDiscountId: String,
        @Query("version") expectedVersion: Int
    ): Response<RemoveDiscountResponse>

    // ==========================================
    // COUPON ENDPOINTS
    // ==========================================

    /**
     * Validate a coupon code.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/coupons/validate
     *
     * **Backend Behavior:**
     * 1. Finds coupon by code (case-insensitive)
     * 2. Checks if active and within validity period
     * 3. Checks usage limits
     * 4. Validates against order total if provided
     * 5. Returns isValid + invalidReason if not valid
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "data": {
     *     "id": "coupon_123",
     *     "code": "PROMO10",
     *     "name": "10% Off Promo",
     *     "type": "PERCENTAGE",
     *     "value": 10,
     *     "usesRemaining": 5,
     *     "isValid": true,
     *     "invalidReason": null
     *   }
     * }
     * ```
     *
     * **Invalid Response (200 with isValid=false):**
     * ```json
     * {
     *   "data": {
     *     "code": "EXPIRED",
     *     "isValid": false,
     *     "invalidReason": "Coupon has expired"
     *   }
     * }
     * ```
     *
     * @param venueId ID of the venue
     * @param request Coupon code and optional order total
     * @return Response with coupon validation result
     */
    @POST("tpv/venues/{venueId}/coupons/validate")
    suspend fun validateCoupon(
        @Path("venueId") venueId: String,
        @Body request: ValidateCouponRequest
    ): Response<CouponValidationResponse>

    /**
     * Apply a coupon code to order.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/discounts/coupon
     *
     * **Backend Behavior:**
     * 1. Validates coupon (same as validateCoupon)
     * 2. Creates OrderDiscount record with couponCodeId
     * 3. Decrements coupon usesRemaining (if limited)
     * 4. Updates order totals
     * 5. Emits Socket.IO event
     *
     * **Error Response (400):**
     * ```json
     * {
     *   "message": "Coupon already applied to this order"
     * }
     * ```
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param request Coupon code to apply
     * @return Response with updated order
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/discounts/coupon")
    suspend fun applyCoupon(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: ApplyCouponRequest
    ): Response<ApplyCouponResponse>
}
