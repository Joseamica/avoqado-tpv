package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.jaac.avoqado_tpv.features.ordering.data.api.DiscountApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyCouponRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyManualDiscountRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyPredefinedDiscountRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ValidateCouponRequest
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toCouponCode
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toDiscount
import com.jaac.avoqado_tpv.features.ordering.domain.CouponCode
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderDiscount
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DiscountRepositoryImpl - Implementation of DiscountRepository
 *
 * Responsibilities:
 * - Fetch available discounts from backend
 * - Apply predefined, manual, and coupon discounts
 * - Validate coupon codes
 * - Handle errors and return Result<T>
 *
 * Backend Integration:
 * - Uses DiscountApiService for all operations
 * - Endpoints: /tpv/venues/{venueId}/orders/{orderId}/discounts/...
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
@Singleton
class DiscountRepositoryImpl @Inject constructor(
    private val apiService: DiscountApiService
) : DiscountRepository {

    // ==========================================
    // DISCOUNT OPERATIONS
    // ==========================================

    override suspend fun getAvailableDiscounts(
        venueId: String,
        orderId: String,
        customerId: String?
    ): Result<List<Discount>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🏷️ [Discount] Getting available discounts (venueId=$venueId, orderId=$orderId)")

            val response = apiService.getAvailableDiscounts(venueId, orderId, customerId)

            if (response.isSuccessful && response.body() != null) {
                val discounts = response.body()!!.data.map { it.toDiscount() }
                Timber.i("✅ Got ${discounts.size} available discounts")
                Result.success(discounts)
            } else {
                val error = "Failed to get discounts: HTTP ${response.code()}"
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error getting discounts")
            Result.failure(e)
        }
    }

    override suspend fun applyPredefinedDiscount(
        venueId: String,
        orderId: String,
        discountId: String,
        authorizedById: String?
    ): Result<OrderDiscount> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🏷️ [Discount] Applying predefined discount: $discountId to order: $orderId")

            val request = ApplyPredefinedDiscountRequest(
                discountId = discountId,
                authorizedById = authorizedById
            )

            val response = apiService.applyPredefinedDiscount(venueId, orderId, request)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.data != null) {
                    // Backend returns { amount, newOrderTotal } - create placeholder OrderDiscount
                    val discount = OrderDiscount(
                        id = discountId,  // Use discountId as placeholder
                        orderId = orderId,
                        discountId = discountId,
                        couponCodeId = null,
                        name = "Descuento aplicado",
                        type = DiscountType.PERCENTAGE,  // Will be updated by socket event
                        value = BigDecimal.ZERO,
                        amount = BigDecimal.valueOf(body.data.amount),
                        reason = null,
                        appliedBy = "",
                        appliedAt = java.time.Instant.now()
                    )
                    Timber.i("✅ Discount applied: -$${body.data.amount}, new total: $${body.data.newOrderTotal}")
                    Result.success(discount)
                } else {
                    val error = body.error ?: "Failed to apply discount"
                    Timber.e("❌ $error")
                    Result.failure(Exception(error))
                }
            } else {
                val error = when (response.code()) {
                    400 -> response.body()?.error ?: "Invalid discount or conditions not met"
                    404 -> "Order or discount not found"
                    409 -> "Order was modified. Please refresh and try again."
                    else -> "Failed to apply discount: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error applying predefined discount")
            Result.failure(e)
        }
    }

    override suspend fun applyManualDiscount(
        venueId: String,
        orderId: String,
        type: DiscountType,
        value: Double,
        reason: String?,
        authorizedById: String?
    ): Result<OrderDiscount> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🏷️ [Discount] Applying manual discount: $type $value to order: $orderId")

            // Map DiscountType to backend enum value
            val backendType = when (type) {
                DiscountType.PERCENTAGE -> "PERCENTAGE"
                DiscountType.FIXED -> "FIXED_AMOUNT"
            }

            val request = ApplyManualDiscountRequest(
                type = backendType,
                value = value,
                reason = reason,
                authorizedById = authorizedById
            )

            val response = apiService.applyManualDiscount(venueId, orderId, request)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.data != null) {
                    // Backend returns { amount, newOrderTotal } - create placeholder OrderDiscount
                    val discountName = when (type) {
                        DiscountType.PERCENTAGE -> "Descuento $value%"
                        DiscountType.FIXED -> "Descuento $$value"
                    }
                    val discount = OrderDiscount(
                        id = "manual_${System.currentTimeMillis()}",
                        orderId = orderId,
                        discountId = null,
                        couponCodeId = null,
                        name = discountName,
                        type = type,
                        value = BigDecimal.valueOf(value),
                        amount = BigDecimal.valueOf(body.data.amount),
                        reason = reason,
                        appliedBy = "",
                        appliedAt = Instant.now()
                    )
                    Timber.i("✅ Manual discount applied: -$${body.data.amount}, new total: $${body.data.newOrderTotal}")
                    Result.success(discount)
                } else {
                    val error = body.error ?: "Failed to apply manual discount"
                    Timber.e("❌ $error")
                    Result.failure(Exception(error))
                }
            } else {
                val error = when (response.code()) {
                    400 -> response.body()?.error ?: "Invalid discount value"
                    403 -> "Manager authorization required"
                    404 -> "Order not found"
                    409 -> "Order was modified. Please refresh and try again."
                    else -> "Failed to apply discount: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error applying manual discount")
            Result.failure(e)
        }
    }

    override suspend fun removeDiscount(
        venueId: String,
        orderId: String,
        orderDiscountId: String,
        expectedVersion: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🗑️ [Discount] Removing discount: $orderDiscountId from order: $orderId (version=$expectedVersion)")

            val response = apiService.removeDiscount(venueId, orderId, orderDiscountId, expectedVersion)

            if (response.isSuccessful) {
                Timber.i("✅ Discount removed successfully")
                Result.success(Unit)
            } else {
                val error = when (response.code()) {
                    404 -> "Order or discount not found"
                    409 -> "Order was modified. Please refresh and try again."
                    else -> "Failed to remove discount: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error removing discount")
            Result.failure(e)
        }
    }

    // ==========================================
    // COUPON OPERATIONS
    // ==========================================

    override suspend fun validateCoupon(
        venueId: String,
        code: String,
        orderTotal: Double?
    ): Result<CouponCode> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🎟️ [Coupon] Validating code: $code (venueId=$venueId)")

            val request = ValidateCouponRequest(
                code = code,
                orderTotal = orderTotal
            )

            val response = apiService.validateCoupon(venueId, request)

            if (response.isSuccessful && response.body() != null) {
                val coupon = response.body()!!.data.toCouponCode()
                if (coupon.isValid) {
                    Timber.i("✅ Coupon valid: ${coupon.name} (${coupon.formattedValue})")
                } else {
                    Timber.w("⚠️ Coupon invalid: ${coupon.invalidReason}")
                }
                Result.success(coupon)
            } else {
                val error = when (response.code()) {
                    404 -> "Coupon code not found"
                    else -> "Failed to validate coupon: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error validating coupon")
            Result.failure(e)
        }
    }

    override suspend fun applyCoupon(
        venueId: String,
        orderId: String,
        code: String
    ): Result<OrderDiscount> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🎟️ [Coupon] Applying code: $code to order: $orderId")

            val request = ApplyCouponRequest(couponCode = code)
            val response = apiService.applyCoupon(venueId, orderId, request)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.data != null) {
                    // Backend returns { couponId, discountName, amount, newOrderTotal }
                    val discount = OrderDiscount(
                        id = body.data.couponId,
                        orderId = orderId,
                        discountId = null,
                        couponCodeId = body.data.couponId,
                        name = "${body.data.discountName} ($code)",
                        type = DiscountType.PERCENTAGE,  // Will be updated by socket event
                        value = BigDecimal.ZERO,
                        amount = BigDecimal.valueOf(body.data.amount),
                        reason = null,
                        appliedBy = "",
                        appliedAt = Instant.now()
                    )
                    Timber.i("✅ Coupon applied: ${body.data.discountName} (-$${body.data.amount}), new total: $${body.data.newOrderTotal}")
                    Result.success(discount)
                } else {
                    val error = body.error ?: "Failed to apply coupon"
                    Timber.e("❌ $error")
                    Result.failure(Exception(error))
                }
            } else {
                // Parse error body to get specific error message from backend
                val errorBody = response.errorBody()?.string()
                val backendError = try {
                    errorBody?.let {
                        val json = Gson().fromJson(it, JsonObject::class.java)
                        json.get("error")?.asString
                    }
                } catch (e: Exception) {
                    Timber.w("Failed to parse error body: $errorBody")
                    null
                }

                val error = when (response.code()) {
                    400 -> backendError ?: "Coupon already applied or invalid"
                    404 -> backendError ?: "Coupon code not found"
                    409 -> backendError ?: "Order was modified. Please refresh and try again."
                    else -> backendError ?: "Failed to apply coupon: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error applying coupon")
            Result.failure(e)
        }
    }
}
