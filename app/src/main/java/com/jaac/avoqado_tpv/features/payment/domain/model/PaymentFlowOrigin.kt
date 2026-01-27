package com.jaac.avoqado_tpv.features.payment.domain.model

/**
 * Source flow that started the payment experience.
 * Used to prevent cross-feature navigation contamination.
 */
enum class PaymentFlowOrigin {
    FAST,
    ORDER,
    SERIALIZED,
    KIOSK,
    REFUND
}
