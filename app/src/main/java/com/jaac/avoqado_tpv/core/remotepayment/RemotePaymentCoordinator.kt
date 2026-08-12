package com.jaac.avoqado_tpv.core.remotepayment

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Payment source - how the payment request arrived at the TPV.
 *
 * Históricamente existía `BLE` (cobros empujados por Bluetooth desde un iPad conectado
 * directo). Ese transporte se retiró en 2026-08 — 0 usos en prod en los logs — y hoy el
 * ÚNICO camino remoto es SOCKET. Si algún día se agrega otro transporte (p. ej. LAN hub),
 * se agrega aquí como variante nueva; no revivas BLE.
 */
enum class PaymentSource {
    /** Payment received via Socket.IO from backend (iOS/Android POS → backend → socket) */
    SOCKET
}

/**
 * Remote Payment Request — un cobro que OTRO dispositivo le pide a esta terminal.
 *
 * Supports dual-mode payment flow:
 * - Quick Payment (orderId = null): FastPayment flow, no order tracking
 * - Order Payment (orderId set): OrderPayment flow with backend order
 *
 * Amounts are in cents.
 *
 * (Antes se llamaba `BlePaymentRequest` en `core.bluetooth` — mismo shape, el transporte
 * Bluetooth fue retirado y el modelo se conservó porque el flujo SOCKET lo reutiliza.)
 */
data class RemotePaymentRequest(
    val amountCents: Long,
    val tipCents: Long? = null,
    val rating: Int? = null,
    val skipReview: Boolean = false,
    val orderId: String? = null,  // Dual-mode: null = FastPayment, set = OrderPayment
    val processedByStaffId: String? = null,
    val source: PaymentSource = PaymentSource.SOCKET,
    val socketRequestId: String? = null
)

/**
 * Remote Refund Request — otro dispositivo le pide a ESTA terminal que abra la
 * devolución de un cobro con tarjeta.
 *
 * 🔴 Esto NO devuelve dinero: sólo abre la pantalla con ese cobro cargado. La
 * devolución la confirma una persona en el aparato (en Blumon hay que volver a
 * pasar la tarjeta) y el registro en Avoqado lo hace el flujo de reembolso de
 * siempre. Por eso el ACK que se le contesta al server es "abrí la pantalla",
 * nunca "devolví el dinero".
 */
data class RemoteRefundRequest(
    val socketRequestId: String,
    val paymentId: String,
    val maxRefundableCents: Long,
    val reason: String? = null
)

/**
 * RemotePaymentCoordinator
 *
 * Bus singleton entre el listener de Socket.IO (HomeViewModel) y la navegación/pantalla de
 * cobro (AppNavigation → PaymentViewModel): un POS (iOS/Android) le pide a esta PAX que
 * cobre, el request entra por socket y se emite aquí; AppNavigation lo colecta y navega a
 * Payment.
 *
 * Es el sucesor directo de `BluetoothPaymentService` (core.bluetooth): aquella clase
 * administraba además un GATT server BLE, un Foreground Service y el pairing de
 * dispositivos — todo eso murió con el transporte BLE. Lo único que el flujo vivo (SOCKET)
 * usaba era este bus de requests + cancelaciones, y es lo único que se conservó.
 */
@Singleton
class RemotePaymentCoordinator @Inject constructor() {

    private val _paymentRequests = MutableSharedFlow<RemotePaymentRequest>(extraBufferCapacity = 1)
    val paymentRequests: SharedFlow<RemotePaymentRequest> = _paymentRequests.asSharedFlow()

    // Track current socket payment for cancel verification (idempotency)
    @Volatile
    private var currentSocketRequestId: String? = null

    // Cancel events for UI to observe
    private val _paymentCancelRequests = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    val paymentCancelRequests: SharedFlow<String?> = _paymentCancelRequests.asSharedFlow()

    /**
     * Submit a payment request from Socket.IO (server-routed payment).
     */
    fun submitSocketPaymentRequest(request: RemotePaymentRequest) {
        Timber.i("📡 [RemotePayment] Forwarding socket payment: ${request.amountCents} cents (requestId=${request.socketRequestId})")
        currentSocketRequestId = request.socketRequestId
        _paymentRequests.tryEmit(request)
    }

    /**
     * Cancel a socket payment request (idempotent).
     * Only cancels if the requestId matches the current payment being processed.
     */
    fun cancelSocketPaymentRequest(requestId: String?) {
        val currentId = currentSocketRequestId
        if (requestId == null || currentId == null) {
            // No requestId provided or no current payment - emit cancel anyway
            Timber.i("🚫 [RemotePayment] Cancel request (no requestId check)")
            _paymentCancelRequests.tryEmit(requestId)
            currentSocketRequestId = null
        } else if (currentId == requestId) {
            // RequestIds match - this is the correct payment to cancel
            Timber.i("🚫 [RemotePayment] Cancelling payment $requestId (matches current)")
            _paymentCancelRequests.tryEmit(requestId)
            currentSocketRequestId = null
        } else {
            // RequestIds don't match - ignore (different payment already started)
            Timber.w("⚠️ [RemotePayment] Cancel ignored - requestId mismatch. Current=$currentId, Cancel=$requestId")
        }
    }

    /**
     * Clear current socket request ID (called when payment completes)
     */
    fun clearCurrentSocketRequest() {
        currentSocketRequestId = null
    }

    // ========================================
    // Devoluciones pedidas desde otro dispositivo
    // ========================================

    private val _refundRequests = MutableSharedFlow<RemoteRefundRequest>(extraBufferCapacity = 1)
    val refundRequests: SharedFlow<RemoteRefundRequest> = _refundRequests.asSharedFlow()

    /**
     * Un POS pidió abrir aquí la devolución de un cobro.
     *
     * A diferencia del cobro, esto NO lleva estado de "en curso" ni cancelación:
     * abrir una pantalla es idempotente —si el evento llega dos veces, se abre
     * el mismo pago— y nadie devuelve nada sin confirmarlo en el aparato.
     */
    fun submitSocketRefundRequest(request: RemoteRefundRequest) {
        Timber.i("↩️ [RemoteRefund] Abriendo devolución del pago ${request.paymentId} (requestId=${request.socketRequestId})")
        _refundRequests.tryEmit(request)
    }
}
