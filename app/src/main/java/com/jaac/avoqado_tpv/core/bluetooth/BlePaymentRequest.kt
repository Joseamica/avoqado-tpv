package com.jaac.avoqado_tpv.core.bluetooth

/**
 * BLE Payment Request
 *
 * Supports dual-mode payment flow:
 * - Quick Payment (orderId = null): FastPayment flow, no order tracking
 * - Order Payment (orderId set): OrderPayment flow with backend order
 *
 * Amounts are in cents.
 */
data class BlePaymentRequest(
    val amountCents: Long,
    val tipCents: Long? = null,
    val rating: Int? = null,
    val skipReview: Boolean = false,
    val orderId: String? = null  // Dual-mode: null = FastPayment, set = OrderPayment
)
