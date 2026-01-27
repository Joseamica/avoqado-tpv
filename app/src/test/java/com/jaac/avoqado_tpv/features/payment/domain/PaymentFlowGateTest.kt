package com.jaac.avoqado_tpv.features.payment.domain

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import org.junit.Test

class PaymentFlowGateTest {

    @Test
    fun `nextAfterAmount respects review screen`() {
        val settings = TpvSettings(showReviewScreen = true)
        val step = PaymentFlowGate.nextAfterAmount(settings)
        assertThat(step).isEqualTo(PrePaymentNextStep.COLLECT_RATING)
    }

    @Test
    fun `nextAfterAmount goes to tip when review disabled`() {
        val settings = TpvSettings(showReviewScreen = false, showTipScreen = true)
        val step = PaymentFlowGate.nextAfterAmount(settings)
        assertThat(step).isEqualTo(PrePaymentNextStep.COLLECT_TIP)
    }

    @Test
    fun `nextAfterAmount goes to verification when review and tip disabled`() {
        val settings = TpvSettings(
            showReviewScreen = false,
            showTipScreen = false,
            showVerificationScreen = true
        )
        val step = PaymentFlowGate.nextAfterAmount(settings)
        assertThat(step).isEqualTo(PrePaymentNextStep.VERIFY_PRE_PAYMENT)
    }

    @Test
    fun `nextAfterAmount goes to merchant when all optional screens disabled`() {
        val settings = TpvSettings(
            showReviewScreen = false,
            showTipScreen = false,
            showVerificationScreen = false
        )
        val step = PaymentFlowGate.nextAfterAmount(settings)
        assertThat(step).isEqualTo(PrePaymentNextStep.SELECT_MERCHANT)
    }

    @Test
    fun `nextAfterRating goes to tip when tip enabled`() {
        val settings = TpvSettings(showTipScreen = true)
        val step = PaymentFlowGate.nextAfterRating(settings)
        assertThat(step).isEqualTo(PrePaymentNextStep.COLLECT_TIP)
    }

    @Test
    fun `nextAfterRating goes to verification when tip disabled but verification enabled`() {
        val settings = TpvSettings(showTipScreen = false, showVerificationScreen = true)
        val step = PaymentFlowGate.nextAfterRating(settings)
        assertThat(step).isEqualTo(PrePaymentNextStep.VERIFY_PRE_PAYMENT)
    }

    @Test
    fun `nextAfterRating goes to merchant when tip and verification disabled`() {
        val settings = TpvSettings(showTipScreen = false, showVerificationScreen = false)
        val step = PaymentFlowGate.nextAfterRating(settings)
        assertThat(step).isEqualTo(PrePaymentNextStep.SELECT_MERCHANT)
    }

    @Test
    fun `nextAfterTip goes to verification when enabled`() {
        val settings = TpvSettings(showVerificationScreen = true)
        val step = PaymentFlowGate.nextAfterTip(settings)
        assertThat(step).isEqualTo(PrePaymentNextStep.VERIFY_PRE_PAYMENT)
    }

    @Test
    fun `nextAfterTip goes to merchant when verification disabled`() {
        val settings = TpvSettings(showVerificationScreen = false)
        val step = PaymentFlowGate.nextAfterTip(settings)
        assertThat(step).isEqualTo(PrePaymentNextStep.SELECT_MERCHANT)
    }
}
