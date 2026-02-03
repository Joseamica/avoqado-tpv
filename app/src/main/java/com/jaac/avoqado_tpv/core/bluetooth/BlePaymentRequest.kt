package com.jaac.avoqado_tpv.core.bluetooth

/**
 * Payment source - how the payment request arrived at the TPV.
 */
enum class PaymentSource {
    /** Payment received via Bluetooth Low Energy from a connected device */
    BLE,
    /** Payment received via Socket.IO from backend (iOS HTTP → backend → socket) */
    SOCKET
}

/**
 * BLE Payment Request
 *
 * Supports dual-mode payment flow:
 * - Quick Payment (orderId = null): FastPayment flow, no order tracking
 * - Order Payment (orderId set): OrderPayment flow with backend order
 *
 * Supports dual-transport:
 * - BLE: Direct Bluetooth connection from iOS/iPad
 * - SOCKET: Via backend Socket.IO (iOS HTTP → backend → TPV socket)
 *
 * Amounts are in cents.
 */
data class BlePaymentRequest(
    val amountCents: Long,
    val tipCents: Long? = null,
    val rating: Int? = null,
    val skipReview: Boolean = false,
    val orderId: String? = null,  // Dual-mode: null = FastPayment, set = OrderPayment
    val source: PaymentSource = PaymentSource.BLE,
    val socketRequestId: String? = null  // Only set when source == SOCKET
)
