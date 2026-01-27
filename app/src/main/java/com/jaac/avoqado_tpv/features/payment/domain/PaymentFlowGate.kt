package com.jaac.avoqado_tpv.features.payment.domain

import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentFeature
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings

/**
 * Centralized decision-maker for pre-payment flow steps.
 *
 * Keeps rating/tip/verification gating consistent across entry points.
 */
enum class PrePaymentNextStep {
    COLLECT_RATING,
    COLLECT_TIP,
    VERIFY_PRE_PAYMENT,
    SELECT_MERCHANT
}

object PaymentFlowGate {
    fun nextAfterAmount(settings: TpvSettings): PrePaymentNextStep {
        return when {
            settings.showReviewScreen -> PrePaymentNextStep.COLLECT_RATING
            settings.showTipScreen -> PrePaymentNextStep.COLLECT_TIP
            settings.showVerificationScreen -> PrePaymentNextStep.VERIFY_PRE_PAYMENT
            else -> PrePaymentNextStep.SELECT_MERCHANT
        }
    }

    fun nextAfterAmount(features: Set<PaymentFeature>): PrePaymentNextStep {
        return when {
            PaymentFeature.RATING_COLLECTION in features -> PrePaymentNextStep.COLLECT_RATING
            PaymentFeature.TIP_COLLECTION in features -> PrePaymentNextStep.COLLECT_TIP
            PaymentFeature.PRE_VERIFICATION in features -> PrePaymentNextStep.VERIFY_PRE_PAYMENT
            else -> PrePaymentNextStep.SELECT_MERCHANT
        }
    }

    fun nextAfterRating(settings: TpvSettings): PrePaymentNextStep {
        return when {
            settings.showTipScreen -> PrePaymentNextStep.COLLECT_TIP
            settings.showVerificationScreen -> PrePaymentNextStep.VERIFY_PRE_PAYMENT
            else -> PrePaymentNextStep.SELECT_MERCHANT
        }
    }

    fun nextAfterRating(features: Set<PaymentFeature>): PrePaymentNextStep {
        return when {
            PaymentFeature.TIP_COLLECTION in features -> PrePaymentNextStep.COLLECT_TIP
            PaymentFeature.PRE_VERIFICATION in features -> PrePaymentNextStep.VERIFY_PRE_PAYMENT
            else -> PrePaymentNextStep.SELECT_MERCHANT
        }
    }

    fun nextAfterTip(settings: TpvSettings): PrePaymentNextStep {
        return if (settings.showVerificationScreen) {
            PrePaymentNextStep.VERIFY_PRE_PAYMENT
        } else {
            PrePaymentNextStep.SELECT_MERCHANT
        }
    }

    fun nextAfterTip(features: Set<PaymentFeature>): PrePaymentNextStep {
        return if (PaymentFeature.PRE_VERIFICATION in features) {
            PrePaymentNextStep.VERIFY_PRE_PAYMENT
        } else {
            PrePaymentNextStep.SELECT_MERCHANT
        }
    }
}
