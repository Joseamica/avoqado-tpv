package com.jaac.avoqado_tpv.features.ordering.data.dto

import com.google.gson.annotations.SerializedName

/**
 * Discount Data Transfer Object
 *
 * Maps backend Discount model (from discount.tpv.service.ts) to Android DTO.
 *
 * Backend structure (AvailableDiscountDto):
 * ```typescript
 * {
 *   id: string
 *   name: string
 *   type: 'PERCENTAGE' | 'FIXED'
 *   value: number
 *   scope: 'ORDER' | 'ITEM' | 'CATEGORY'
 *   conditions: {
 *     minOrderAmount?: number
 *     maxOrderAmount?: number
 *     validDays?: string[]
 *     validHoursStart?: string
 *     validHoursEnd?: string
 *   } | null
 *   active: boolean
 *   requiresAuthorization: boolean
 * }
 * ```
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
data class DiscountDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("type")
    val type: String,  // PERCENTAGE, FIXED

    @SerializedName("value")
    val value: Double,

    @SerializedName("scope")
    val scope: String,  // ORDER, ITEM, CATEGORY

    @SerializedName("conditions")
    val conditions: DiscountConditionsDto?,

    @SerializedName("active")
    val active: Boolean,

    @SerializedName("requiresAuthorization")
    val requiresAuthorization: Boolean
)

/**
 * Discount Conditions DTO
 *
 * Time and amount restrictions for discount application.
 */
data class DiscountConditionsDto(
    @SerializedName("minOrderAmount")
    val minOrderAmount: Double?,

    @SerializedName("maxOrderAmount")
    val maxOrderAmount: Double?,

    @SerializedName("validDays")
    val validDays: List<String>?,  // ["MONDAY", "TUESDAY", ...]

    @SerializedName("validHoursStart")
    val validHoursStart: String?,  // "14:00"

    @SerializedName("validHoursEnd")
    val validHoursEnd: String?  // "18:00"
)

/**
 * Coupon Validation Response DTO
 *
 * Backend structure from validateCoupon endpoint:
 * ```typescript
 * {
 *   valid: boolean
 *   message: string
 *   discountName?: string
 *   discountType?: string
 *   discountValue?: number
 *   estimatedSavings?: number
 * }
 * ```
 */
data class CouponCodeDto(
    @SerializedName("valid")
    val valid: Boolean,

    @SerializedName("message")
    val message: String,

    @SerializedName("discountName")
    val discountName: String?,

    @SerializedName("discountType")
    val discountType: String?,  // PERCENTAGE, FIXED_AMOUNT, COMP

    @SerializedName("discountValue")
    val discountValue: Double?,

    @SerializedName("estimatedSavings")
    val estimatedSavings: Double?
)

/**
 * Order Discount DTO
 *
 * Applied discount on an order (persisted in OrderDiscount table).
 */
data class OrderDiscountDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("orderId")
    val orderId: String,

    @SerializedName("discountId")
    val discountId: String?,

    @SerializedName("couponCodeId")
    val couponCodeId: String?,

    @SerializedName("name")
    val name: String,

    @SerializedName("type")
    val type: String,  // PERCENTAGE, FIXED, MANUAL, COUPON

    @SerializedName("value")
    val value: Double,

    @SerializedName("amount")
    val amount: Double,  // Calculated amount in currency

    @SerializedName("reason")
    val reason: String?,

    // ✅ NULLABLE: backend's new createOrderWithItems endpoint returns
    // OrderDiscount rows with `appliedById` (FK to Staff), and the JSON
    // response leaves the legacy `appliedBy` string slot empty. Older
    // endpoints sometimes populate it with a name. Treat both as optional.
    @SerializedName("appliedBy")
    val appliedBy: String? = null,

    @SerializedName("appliedById")
    val appliedById: String? = null,

    @SerializedName("appliedAt")
    val appliedAt: String? = null,
)

// ==========================================
// REQUEST DTOs
// ==========================================

/**
 * Apply Predefined Discount Request
 *
 * Used when applying a discount from the venue's discount list.
 * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/discounts/apply
 */
data class ApplyPredefinedDiscountRequest(
    @SerializedName("discountId")
    val discountId: String,

    @SerializedName("authorizedById")
    val authorizedById: String? = null
)

/**
 * Apply Manual Discount Request
 *
 * Used for manager discretionary discounts.
 * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/discounts/manual
 */
data class ApplyManualDiscountRequest(
    @SerializedName("type")
    val type: String,  // PERCENTAGE, FIXED_AMOUNT, COMP

    @SerializedName("value")
    val value: Double,

    @SerializedName("reason")
    val reason: String?,

    @SerializedName("authorizedById")
    val authorizedById: String? = null
)

/**
 * Apply Coupon Code Request
 * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/discounts/coupon
 */
data class ApplyCouponRequest(
    @SerializedName("couponCode")
    val couponCode: String
)

/**
 * Validate Coupon Request
 */
data class ValidateCouponRequest(
    @SerializedName("couponCode")
    val code: String,

    @SerializedName("orderTotal")
    val orderTotal: Double?
)

// ==========================================
// RESPONSE DTOs
// ==========================================

/**
 * Available Discounts Response wrapper
 */
data class AvailableDiscountsResponse(
    @SerializedName("success")
    val success: Boolean?,

    @SerializedName("data")
    val data: List<DiscountDto>,

    @SerializedName("count")
    val count: Int?
)

/**
 * Coupon Validation Response
 */
data class CouponValidationResponse(
    @SerializedName("success")
    val success: Boolean?,

    @SerializedName("data")
    val data: CouponCodeDto
)

/**
 * Apply Discount Response (for predefined and manual discounts)
 *
 * Backend returns:
 * {
 *   success: true,
 *   data: { amount: number, newOrderTotal: number },
 *   message: string
 * }
 */
data class ApplyDiscountResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: ApplyDiscountDataDto?,

    @SerializedName("message")
    val message: String?,

    @SerializedName("error")
    val error: String?
)

data class ApplyDiscountDataDto(
    @SerializedName("amount")
    val amount: Double,

    @SerializedName("newOrderTotal")
    val newOrderTotal: Double
)

/**
 * Apply Coupon Response
 *
 * Backend returns:
 * {
 *   success: true,
 *   data: { couponId: string, discountName: string, amount: number, newOrderTotal: number },
 *   message: string
 * }
 */
data class ApplyCouponResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: ApplyCouponDataDto?,

    @SerializedName("message")
    val message: String?,

    @SerializedName("error")
    val error: String?
)

data class ApplyCouponDataDto(
    @SerializedName("couponId")
    val couponId: String,

    @SerializedName("discountName")
    val discountName: String,

    @SerializedName("amount")
    val amount: Double,

    @SerializedName("newOrderTotal")
    val newOrderTotal: Double
)

/**
 * Remove Discount Response
 */
data class RemoveDiscountResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: ApplyDiscountDataDto?,

    @SerializedName("message")
    val message: String?,

    @SerializedName("error")
    val error: String?
)

/**
 * Order DTO for discount responses
 *
 * Renamed from OrderDto to avoid conflict with TableDto.OrderDto
 * When applying discounts, backend returns full Order with updated totals.
 */
data class DiscountOrderDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("orderNumber")
    val orderNumber: String,

    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("tableId")
    val tableId: String?,

    @SerializedName("tableName")
    val tableName: String?,

    @SerializedName("covers")
    val covers: Int,

    @SerializedName("waiterId")
    val waiterId: String?,

    @SerializedName("waiterName")
    val waiterName: String?,

    @SerializedName("customerName")
    val customerName: String?,

    @SerializedName("customerPhone")
    val customerPhone: String?,

    @SerializedName("specialRequests")
    val specialRequests: String?,

    @SerializedName("customerId")
    val customerId: String?,

    @SerializedName("status")
    val status: String,

    @SerializedName("kitchenStatus")
    val kitchenStatus: String,

    @SerializedName("paymentStatus")
    val paymentStatus: String,

    @SerializedName("orderType")
    val orderType: String,

    @SerializedName("subtotal")
    val subtotal: Double,

    @SerializedName("discountAmount")
    val discountAmount: Double,

    @SerializedName("tax")
    val tax: Double,

    @SerializedName("total")
    val total: Double,

    @SerializedName("paidAmount")
    val paidAmount: Double,

    @SerializedName("remainingBalance")
    val remainingBalance: Double,

    @SerializedName("notes")
    val notes: String?,

    @SerializedName("createdAt")
    val createdAt: String,

    @SerializedName("updatedAt")
    val updatedAt: String,

    @SerializedName("version")
    val version: Int,

    @SerializedName("merchantAccountId")
    val merchantAccountId: String?,

    @SerializedName("lastSplitType")
    val lastSplitType: String?,

    @SerializedName("items")
    val items: List<DiscountOrderItemDto>,

    @SerializedName("discounts")
    val discounts: List<OrderDiscountDto>?
)

/**
 * Order Item DTO for discount responses
 *
 * Renamed from OrderItemDto to avoid conflict with TableDto.OrderItemDto
 */
data class DiscountOrderItemDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("orderId")
    val orderId: String,

    @SerializedName("productId")
    val productId: String,

    @SerializedName("productName")
    val productName: String,

    @SerializedName("productSku")
    val productSku: String?,

    @SerializedName("quantity")
    val quantity: Int,

    @SerializedName("unitPrice")
    val unitPrice: Double,

    @SerializedName("totalPrice")
    val totalPrice: Double,

    @SerializedName("notes")
    val notes: String?,

    @SerializedName("kitchenStatus")
    val kitchenStatus: String,

    @SerializedName("createdAt")
    val createdAt: String,

    @SerializedName("sentToKitchenAt")
    val sentToKitchenAt: String?,

    @SerializedName("modifiers")
    val modifiers: List<DiscountOrderItemModifierDto>?
)

/**
 * Order Item Modifier DTO for discount responses
 */
data class DiscountOrderItemModifierDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("priceAdjustment")
    val priceAdjustment: Double
)
