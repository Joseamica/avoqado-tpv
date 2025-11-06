package com.jaac.avoqado_tpv.features.payment.domain

sealed class PaymentState {
    data object Idle : PaymentState()
    data object ConfiguringKernel : PaymentState()
    data object DetectingCard : PaymentState()
    data class Processing(val message: String = "Procesando...") : PaymentState()
    data class Success(val authCode: String, val amount: String) : PaymentState()
    data class Error(val message: String, val canRetry: Boolean = true) : PaymentState()
    data object Cancelled : PaymentState()
}
