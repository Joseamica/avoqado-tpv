package com.jaac.avoqado_tpv.features.refund.domain

import java.math.BigDecimal

/** The amount sent to the processor and the optional backend bookkeeping override. */
internal data class RefundTipSelection(
    val amount: BigDecimal,
    val tipRefundCents: Int?,
)

/**
 * Keeping the staff tip requires a processor that supports a partial refund and an exact
 * sale-side balance. The TPV currently receives only the aggregate amount already refunded,
 * not its sale/tip split, so after the first refund it must use the backend default.
 */
internal fun canKeepStaffTip(
    originalTotalAmount: BigDecimal,
    originalTipAmount: BigDecimal,
    alreadyRefundedAmount: BigDecimal,
    supportsSaleOnlyRefund: Boolean,
): Boolean =
    supportsSaleOnlyRefund &&
        originalTipAmount > BigDecimal.ZERO &&
        originalTotalAmount > originalTipAmount &&
        alreadyRefundedAmount.compareTo(BigDecimal.ZERO) == 0

/**
 * Converts the operator's choice into a request the processor can actually honor.
 *
 * `tipRefundCents = 0` is sent only when the refund amount fits entirely inside the original
 * sale. Otherwise the override is omitted and the backend uses its proportional default.
 */
internal fun resolveRefundTipSelection(
    requestedAmount: BigDecimal,
    originalTotalAmount: BigDecimal,
    originalTipAmount: BigDecimal,
    alreadyRefundedAmount: BigDecimal,
    includeTip: Boolean,
    supportsSaleOnlyRefund: Boolean,
): RefundTipSelection {
    val canKeepTip = canKeepStaffTip(
        originalTotalAmount = originalTotalAmount,
        originalTipAmount = originalTipAmount,
        alreadyRefundedAmount = alreadyRefundedAmount,
        supportsSaleOnlyRefund = supportsSaleOnlyRefund,
    )
    if (includeTip || !canKeepTip) {
        return RefundTipSelection(amount = requestedAmount, tipRefundCents = null)
    }

    val saleAmount = (originalTotalAmount - originalTipAmount).coerceAtLeast(BigDecimal.ZERO)
    return RefundTipSelection(
        amount = requestedAmount.min(saleAmount),
        tipRefundCents = 0,
    )
}
