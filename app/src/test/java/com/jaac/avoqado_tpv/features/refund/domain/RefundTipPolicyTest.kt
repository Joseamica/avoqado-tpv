package com.jaac.avoqado_tpv.features.refund.domain

import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import org.junit.Test

class RefundTipPolicyTest {

    @Test
    fun `PAX caps a first full refund at the sale amount when staff tip is kept`() {
        val result = resolveRefundTipSelection(
            requestedAmount = BigDecimal("120.00"),
            originalTotalAmount = BigDecimal("120.00"),
            originalTipAmount = BigDecimal("20.00"),
            alreadyRefundedAmount = BigDecimal.ZERO,
            includeTip = false,
            supportsSaleOnlyRefund = true,
        )

        assertThat(result.amount).isEqualTo(BigDecimal("100.00"))
        assertThat(result.tipRefundCents).isEqualTo(0)
    }

    @Test
    fun `PAX keeps a smaller partial amount unchanged when staff tip is kept`() {
        val result = resolveRefundTipSelection(
            requestedAmount = BigDecimal("60.00"),
            originalTotalAmount = BigDecimal("120.00"),
            originalTipAmount = BigDecimal("20.00"),
            alreadyRefundedAmount = BigDecimal.ZERO,
            includeTip = false,
            supportsSaleOnlyRefund = true,
        )

        assertThat(result.amount).isEqualTo(BigDecimal("60.00"))
        assertThat(result.tipRefundCents).isEqualTo(0)
    }

    @Test
    fun `Nexgo always includes tip because AngelPay only supports the full refund`() {
        val result = resolveRefundTipSelection(
            requestedAmount = BigDecimal("120.00"),
            originalTotalAmount = BigDecimal("120.00"),
            originalTipAmount = BigDecimal("20.00"),
            alreadyRefundedAmount = BigDecimal.ZERO,
            includeTip = false,
            supportsSaleOnlyRefund = false,
        )

        assertThat(result.amount).isEqualTo(BigDecimal("120.00"))
        assertThat(result.tipRefundCents).isNull()
    }

    @Test
    fun `sale-only option is unavailable after a prior refund with unknown split`() {
        assertThat(
            canKeepStaffTip(
                originalTotalAmount = BigDecimal("120.00"),
                originalTipAmount = BigDecimal("20.00"),
                alreadyRefundedAmount = BigDecimal("10.00"),
                supportsSaleOnlyRefund = true,
            ),
        ).isFalse()
    }

    @Test
    fun `sale-only option is unavailable when the payment has no sale component`() {
        assertThat(
            canKeepStaffTip(
                originalTotalAmount = BigDecimal("20.00"),
                originalTipAmount = BigDecimal("20.00"),
                alreadyRefundedAmount = BigDecimal.ZERO,
                supportsSaleOnlyRefund = true,
            ),
        ).isFalse()
    }

    @Test
    fun `including tip keeps the requested amount and proportional backend default`() {
        val result = resolveRefundTipSelection(
            requestedAmount = BigDecimal("75.00"),
            originalTotalAmount = BigDecimal("120.00"),
            originalTipAmount = BigDecimal("20.00"),
            alreadyRefundedAmount = BigDecimal.ZERO,
            includeTip = true,
            supportsSaleOnlyRefund = true,
        )

        assertThat(result.amount).isEqualTo(BigDecimal("75.00"))
        assertThat(result.tipRefundCents).isNull()
    }
}
