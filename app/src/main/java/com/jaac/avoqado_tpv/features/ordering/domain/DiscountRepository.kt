package com.jaac.avoqado_tpv.features.ordering.domain

/**
 * DiscountRepository - Repository interface for discount and coupon operations
 *
 * Responsible for:
 * - Fetching available discounts for an order
 * - Applying predefined, manual, and coupon discounts
 * - Validating coupon codes
 * - Removing discounts from orders
 *
 * Clean Architecture:
 * - Domain layer interface
 * - Implemented in data layer (DiscountRepositoryImpl)
 * - Consumed by MenuViewModel/ActionsTab for discount operations
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
/**
 * D16 v4 (hz:e82938e1:D16): lo que contesta el SERVIDOR al aplicar un descuento. La lista de la pantalla NO sale de aquí (la escribe la
 * lectura final de la fila); esto sólo sirve para el total mientras esa lectura no llegue.
 */
data class AcuseDeDescuento(
    /** La fila creada (id real; vacío con un SERVIDOR anterior a D16). */
    val fila: OrderDiscount,
    /** `newOrderTotal` del SERVIDOR (null si no vino). */
    val totalDeLaCuenta: java.math.BigDecimal?,
    /** D21: el SERVIDOR ya tenía este envío (misma llave) y no creó otra fila. */
    val repetida: Boolean = false,
)

interface DiscountRepository {

    // ==========================================
    // DISCOUNT OPERATIONS
    // ==========================================

    /**
     * Get available discounts for an order
     *
     * Returns all eligible discounts that can be applied to the order.
     * Backend considers order context (items, customer, time).
     *
     * @param venueId Venue identifier
     * @param orderId Order identifier
     * @param customerId Optional customer ID for customer-specific discounts
     * @return Result with list of available discounts
     */
    suspend fun getAvailableDiscounts(
        venueId: String,
        orderId: String,
        customerId: String? = null
    ): Result<List<Discount>>

    /**
     * Apply a predefined discount to order
     *
     * Applies a venue-defined discount (Happy Hour, Employee, etc.)
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/discounts/apply
     *
     * @param venueId Venue identifier
     * @param orderId Order identifier
     * @param discountId Discount identifier
     * @param authorizedById Optional staff who authorized (for discounts requiring approval)
     * @return Result with applied discount details
     */
    suspend fun applyPredefinedDiscount(
        venueId: String,
        orderId: String,
        discountId: String,
        authorizedById: String? = null,
        /** D21: llave del envío (la misma en el reintento). */
        idempotencyKey: String? = null,
    ): Result<AcuseDeDescuento>

    /**
     * Apply a manual discount to order
     *
     * Applies a manager discretionary discount.
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/discounts/manual
     *
     * @param venueId Venue identifier
     * @param orderId Order identifier
     * @param type Discount type (PERCENTAGE, FIXED_AMOUNT, or COMP)
     * @param value Discount value (0-100 for percentage, amount for fixed)
     * @param reason Reason for discount (required for audit)
     * @param authorizedById Optional staff who authorized (for discounts requiring approval)
     * @return Result with applied discount details
     */
    suspend fun applyManualDiscount(
        venueId: String,
        orderId: String,
        type: DiscountType,
        value: Double,
        reason: String?,
        authorizedById: String? = null,
        overrideToken: String? = null,
        /** D21: llave del envío (la misma en el reintento y en el reenvío con PIN forzado). */
        idempotencyKey: String? = null,
    ): Result<AcuseDeDescuento>

    /**
     * Remove a discount from order
     * Backend: DELETE /tpv/venues/{venueId}/orders/{orderId}/discounts/{discountId}
     *
     * @param venueId Venue identifier
     * @param orderId Order identifier
     * @param orderDiscountId OrderDiscount identifier to remove
     * @param expectedVersion Order version for optimistic locking
     * @return Result with success or error
     */
    suspend fun removeDiscount(
        venueId: String,
        orderId: String,
        orderDiscountId: String,
        expectedVersion: Int
    ): Result<java.math.BigDecimal?>  // D16 v4: el total de la cuenta según el SERVIDOR (null si no vino)

    // ==========================================
    // COUPON OPERATIONS
    // ==========================================

    /**
     * Validate a coupon code
     *
     * Checks if coupon is valid (active, within dates, has uses remaining).
     * Does NOT apply the coupon.
     * Backend: POST /tpv/venues/{venueId}/coupons/validate
     *
     * @param venueId Venue identifier
     * @param code Coupon code to validate
     * @param orderTotal Optional order total for condition validation
     * @return Result with coupon details (includes isValid and invalidReason)
     */
    suspend fun validateCoupon(
        venueId: String,
        code: String,
        orderTotal: Double?,
        customerId: String? = null,
    ): Result<CouponCode>

    /**
     * Apply a coupon code to order
     *
     * Validates and applies the coupon, decrementing usage count.
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/discounts/coupon
     *
     * @param venueId Venue identifier
     * @param orderId Order identifier
     * @param code Coupon code to apply
     * @return Result with applied discount details
     */
    suspend fun applyCoupon(
        venueId: String,
        orderId: String,
        code: String
    ): Result<AcuseDeDescuento>
}
