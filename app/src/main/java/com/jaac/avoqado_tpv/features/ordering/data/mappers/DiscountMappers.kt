package com.jaac.avoqado_tpv.features.ordering.data.mappers

import com.jaac.avoqado_tpv.features.ordering.data.dto.CouponCodeDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.DiscountConditionsDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.DiscountDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.OrderDiscountDto
import com.jaac.avoqado_tpv.features.ordering.domain.CouponCode
import com.jaac.avoqado_tpv.features.ordering.domain.DayOfWeek
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountConditions
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderDiscount
import java.math.BigDecimal
import java.time.Instant

/**
 * Discount DTO to Domain Mappers
 *
 * Converts between backend DTOs and domain models.
 * Handles null safety, type conversions, and enum parsing.
 */

// ==========================================
// DTO → DOMAIN
// ==========================================

/**
 * Convert DiscountDto to Discount domain model
 */
fun DiscountDto.toDiscount(): Discount {
    return Discount(
        id = id,
        name = name,
        type = DiscountType.fromString(type),
        value = BigDecimal.valueOf(value),
        scope = DiscountScope.fromString(scope),
        conditions = conditions?.toDiscountConditions(),
        active = active,
        requiresAuthorization = requiresAuthorization
    )
}

/**
 * Convert DiscountConditionsDto to DiscountConditions domain model
 */
fun DiscountConditionsDto.toDiscountConditions(): DiscountConditions {
    return DiscountConditions(
        minOrderAmount = minOrderAmount?.let { BigDecimal.valueOf(it) },
        maxOrderAmount = maxOrderAmount?.let { BigDecimal.valueOf(it) },
        validDays = validDays?.mapNotNull { day ->
            try {
                DayOfWeek.valueOf(day.uppercase())
            } catch (e: Exception) {
                null
            }
        },
        validHoursStart = validHoursStart,
        validHoursEnd = validHoursEnd
    )
}

/**
 * Convert CouponCodeDto (validation response) to CouponCode domain model
 */
fun CouponCodeDto.toCouponCode(): CouponCode {
    return CouponCode(
        id = "",  // Not provided in validation response
        code = "",  // Not provided in validation response
        name = discountName ?: "",
        type = discountType?.let { parseDiscountTypeNullable(it) } ?: DiscountType.PERCENTAGE,
        value = BigDecimal.valueOf(discountValue ?: 0.0),
        usesRemaining = null,
        validFrom = null,
        validUntil = null,
        isValid = valid,
        invalidReason = if (!valid) message else null,
        estimatedSavings = estimatedSavings?.let { BigDecimal.valueOf(it) }
    )
}

/**
 * Convert OrderDiscountDto to OrderDiscount domain model
 */
fun OrderDiscountDto.toOrderDiscount(): OrderDiscount {
    return OrderDiscount(
        id = id,
        orderId = orderId,
        discountId = discountId,
        couponCodeId = couponCodeId,
        name = name,
        type = parseDiscountType(type),
        value = BigDecimal.valueOf(value),
        amount = BigDecimal.valueOf(amount),
        reason = reason,
        appliedBy = appliedBy,
        appliedAt = parseInstant(appliedAt) ?: Instant.now()
    )
}

/**
 * Convert list of DiscountDto to list of Discount domain models
 */
fun List<DiscountDto>.toDiscounts(): List<Discount> {
    return map { it.toDiscount() }
}

/**
 * Convert list of OrderDiscountDto to list of OrderDiscount domain models
 */
fun List<OrderDiscountDto>.toOrderDiscounts(): List<OrderDiscount> {
    return map { it.toOrderDiscount() }
}

// ==========================================
// HELPER FUNCTIONS
// ==========================================

/**
 * Parse ISO 8601 timestamp to Instant
 */
private fun parseInstant(isoString: String): Instant? {
    return try {
        Instant.parse(isoString)
    } catch (e: Exception) {
        null
    }
}

/**
 * Parse discount type string to enum
 * Handles MANUAL and COUPON types that don't exist in DiscountType enum
 */
private fun parseDiscountType(typeString: String): DiscountType {
    return when (typeString.uppercase()) {
        "PERCENTAGE" -> DiscountType.PERCENTAGE
        "FIXED", "FIXED_AMOUNT" -> DiscountType.FIXED
        "MANUAL" -> DiscountType.FIXED  // Manual discounts are typically fixed amounts
        "COUPON" -> DiscountType.PERCENTAGE  // Default for coupon
        "COMP" -> DiscountType.PERCENTAGE  // Comp is 100% off
        else -> DiscountType.PERCENTAGE
    }
}

/**
 * Parse discount type string to enum (nullable-safe version)
 */
private fun parseDiscountTypeNullable(typeString: String): DiscountType {
    return parseDiscountType(typeString)
}

// ==========================================
// DOMAIN → DTO (for requests)
// ==========================================

/**
 * Convert Discount to DiscountDto
 */
fun Discount.toDto(): DiscountDto {
    return DiscountDto(
        id = id,
        name = name,
        type = type.name,
        value = value.toDouble(),
        scope = scope.name,
        conditions = conditions?.toDto(),
        active = active,
        requiresAuthorization = requiresAuthorization
    )
}

/**
 * Convert DiscountConditions to DiscountConditionsDto
 */
fun DiscountConditions.toDto(): DiscountConditionsDto {
    return DiscountConditionsDto(
        minOrderAmount = minOrderAmount?.toDouble(),
        maxOrderAmount = maxOrderAmount?.toDouble(),
        validDays = validDays?.map { it.name },
        validHoursStart = validHoursStart,
        validHoursEnd = validHoursEnd
    )
}
