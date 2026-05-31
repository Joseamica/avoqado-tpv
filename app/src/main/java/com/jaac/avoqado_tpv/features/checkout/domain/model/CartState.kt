package com.jaac.avoqado_tpv.features.checkout.domain.model

import androidx.compose.runtime.Immutable
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Immutable snapshot of the unified cart.
 *
 * Money lives in integer cents inside the cart (matching avoqado-android).
 * The conversion to TPV's BigDecimal domain happens only when handing the
 * cart off to `OrderRepository` in Phase 5.
 *
 * The "taxable" properties exist because [CartItemType.CustomAmount] entries
 * — which are typically tips, manual adjustments, or legacy "pago rápido"
 * inputs — should not be taxed. Only catalog products are taxable.
 */
@Immutable
data class CartState(
    val items: List<CartItem> = emptyList(),
    val orderDiscount: Discount? = null,
    /**
     * Operator-typed order-level discount in cents. Mutually exclusive with
     * [orderDiscount] (predefined). Used by the Shortcuts → Descuento manual
     * subview when no Discount entity backs the reduction. Travels as
     * `discount` (no `orderDiscountId`) to the new createOrderWithItems
     * endpoint, which accepts freeform amounts.
     */
    val manualDiscountCents: Int = 0,
    val manualDiscountReason: String? = null,
    /**
     * Optional tag identifying who created the manual discount. Used by the
     * referrals capture flow to mark its 10% reduction as
     * `"REFERRAL_NEW_CUSTOMER"`. The backend doesn't read this — it's purely
     * a TPV-side hint to prevent the operator from accidentally stacking a
     * referral discount with a manual one and to enable analytics later.
     */
    val manualDiscountSource: String? = null,
    val orderNote: String? = null,
    val orderTaxPercent: Int? = null,
    val selectedStaffId: String = "",
    val selectedStaffName: String = "Staff",
) {
    val itemCount: Int
        get() = items.sumOf { it.quantity }

    val subtotalCents: Int
        get() = items.sumOf { it.totalPriceCents }

    val taxableSubtotalCents: Int
        get() = items
            .filter { it.type is CartItemType.ProductItem }
            .sumOf { it.totalPriceCents }

    val discountCents: Int
        get() = when {
            orderDiscount != null -> orderDiscount.calculateDiscountCents(subtotalCents)
            manualDiscountCents > 0 -> manualDiscountCents.coerceAtMost(subtotalCents)
            else -> 0
        }

    /**
     * Portion of the discount that "lands on" taxable items, proportional to
     * their share of the subtotal. Used to reduce the taxable base before
     * applying the tax percent — matches avoqado-android tax math exactly.
     */
    val taxableDiscountCents: Int
        get() = if (subtotalCents <= 0 || discountCents <= 0 || taxableSubtotalCents <= 0) {
            0
        } else {
            ((discountCents.toDouble() * taxableSubtotalCents.toDouble()) / subtotalCents.toDouble())
                .toInt()
                .coerceAtMost(taxableSubtotalCents)
        }

    val taxableAmountAfterDiscountCents: Int
        get() = (taxableSubtotalCents - taxableDiscountCents).coerceAtLeast(0)

    val taxCents: Int
        get() {
            val percent = orderTaxPercent ?: return 0
            if (percent <= 0 || taxableAmountAfterDiscountCents <= 0) return 0
            return ((taxableAmountAfterDiscountCents * percent) / 100.0).toInt()
        }

    val totalCents: Int
        get() = (subtotalCents - discountCents + taxCents).coerceAtLeast(0)

    val isEmpty: Boolean
        get() = items.isEmpty()

    val subtotalDisplay: String get() = formatCents(subtotalCents)
    val discountDisplay: String get() = formatCents(discountCents)
    val taxDisplay: String get() = formatCents(taxCents)
    val totalDisplay: String get() = formatCents(totalCents)
}

private fun formatCents(cents: Int): String =
    "$${String.format("%.2f", cents / 100.0)}"

/**
 * Bridges TPV's BigDecimal-based [Discount.calculateAmount] (which works in
 * currency units, e.g. 50.00) with the cart's integer-cents math (5000).
 *
 * Rounding mode matches what `Int(BigDecimal * 100)` would produce in
 * avoqado-android — truncation toward zero — so totals stay byte-identical
 * across both apps.
 */
private fun Discount.calculateDiscountCents(subtotalCents: Int): Int {
    if (subtotalCents <= 0) return 0
    val subtotal = BigDecimal(subtotalCents).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
    val amountUnits = calculateAmount(subtotal)
    val amountCents = amountUnits.multiply(BigDecimal(100)).setScale(0, RoundingMode.DOWN).toInt()
    return amountCents.coerceIn(0, subtotalCents)
}

/**
 * Hint used by callers (e.g. CheckoutViewModel.prepareForPayment) to decide
 * whether to navigate as `PaymentFlowOrigin.FAST` (no items, only manual
 * amounts) or `PaymentFlowOrigin.ORDER` (catalog products exist and an Order
 * must be created in the backend first).
 */
val CartState.hasOnlyCustomAmounts: Boolean
    get() = items.isNotEmpty() && items.all { it.type is CartItemType.CustomAmount }

val CartState.hasProductItems: Boolean
    get() = items.any { it.type is CartItemType.ProductItem }
