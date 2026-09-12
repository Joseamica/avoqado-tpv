package com.jaac.avoqado_tpv.core.remotepayment

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
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
enum class RemotePaymentAdmission { READY, NOT_READY, NOT_CLAIMABLE, VENUE_CHANGED }

@Singleton
class RemotePaymentCoordinator @Inject constructor(
    private val remotePaymentInbox: RemotePaymentInbox,
) {

    // Channel, no SharedFlow: conserva UN request aunque la navegación todavía no
    // esté colectando. La fila Room es la autoridad entre reinicios; este canal sólo
    // cubre la ventana dentro del proceso.
    private val paymentRequestChannel = Channel<RemotePaymentRequest>(capacity = 1)
    val paymentRequests = paymentRequestChannel.receiveAsFlow()
    private val queuedRequestIds = mutableSetOf<String>()

    /**
     * 🔴 PRUEBA DE PROPIEDAD (C.5): solicitudes que ESTE proceso reclamó ([prepareSocketPaymentRequest]).
     *
     * Es lo único que permite aceptar un cancel del POS sobre una solicitud reclamada que todavía NO tiene
     * fila en la libreta (la espera de calificación, propina, método o comercio ocurre sin fila). Vive en
     * memoria a propósito: tras la muerte del proceso nadie puede afirmar qué quedó a medias, así que el
     * cancel contesta ACTIVE y la solicitud pasa a conciliación. No se registra desde
     * [claimSocketPaymentRequest], que no abre ninguna pantalla (sólo lo usan las pruebas).
     */
    private val reclamadasEnEsteProceso = mutableSetOf<String>()

    /**
     * Submit a payment request from Socket.IO (server-routed payment).
     */
    fun submitSocketPaymentRequest(request: RemotePaymentRequest): Boolean {
        Timber.i("📡 [RemotePayment] Forwarding socket payment: ${request.amountCents} cents (requestId=${request.socketRequestId})")
        val requestId = request.socketRequestId ?: return false
        synchronized(queuedRequestIds) {
            if (requestId in queuedRequestIds) return true
            if (!paymentRequestChannel.trySend(request).isSuccess) return false
            queuedRequestIds += requestId
        }
        return true
    }

    /** Claim durable justo antes de navegar/abrir cualquier SDK de cobro. */
    suspend fun claimSocketPaymentRequest(requestId: String): Boolean {
        synchronized(queuedRequestIds) { queuedRequestIds.remove(requestId) }
        return remotePaymentInbox.markProcessing(requestId)
    }

    fun observePendingObligationCount(venueId: String) = remotePaymentInbox.observePendingObligationCount(venueId)

    suspend fun prepareSocketPaymentRequest(
        requestId: String,
        awaitReady: suspend () -> Boolean,
        currentVenueId: () -> String?,
    ): RemotePaymentAdmission {
        // Cancellation can win while SDK initialization is suspended. Claim only once
        // readiness has returned, using the current activation and the durable venue.
        val ready = awaitReady()
        synchronized(queuedRequestIds) { queuedRequestIds.remove(requestId) }
        val venueId = currentVenueId()?.takeIf { it.isNotBlank() }
            ?: return RemotePaymentAdmission.NOT_CLAIMABLE
        if (!remotePaymentInbox.markProcessingForVenue(requestId, venueId)) {
            return RemotePaymentAdmission.NOT_CLAIMABLE
        }
        synchronized(reclamadasEnEsteProceso) { reclamadasEnEsteProceso += requestId }
        // The Room call suspended: activation may have changed while it ran. This
        // collector owns the claim but has not opened any SDK, so it may fail safely.
        if (currentVenueId() != venueId) return RemotePaymentAdmission.VENUE_CHANGED
        return if (ready) RemotePaymentAdmission.READY else RemotePaymentAdmission.NOT_READY
    }

    /** Remote cancellation never destroys the UI owner of a claimed SDK execution. */
    suspend fun cancelSocketPaymentRequest(requestId: String?): RemotePaymentCancelDecision {
        if (requestId.isNullOrBlank()) return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACTIVE)
        val propia = synchronized(reclamadasEnEsteProceso) { requestId in reclamadasEnEsteProceso }
        val decision = remotePaymentInbox.cancel(requestId, propiedadEnEsteProceso = propia)
        if (decision.disposition != RemotePaymentCancelDisposition.ACTIVE) {
            synchronized(queuedRequestIds) { queuedRequestIds.remove(requestId) }
            synchronized(reclamadasEnEsteProceso) { reclamadasEnEsteProceso.remove(requestId) }
        }
        return decision
    }

    /**
     * Sonda del servidor: contesta desde la bandeja durable; nunca entrega ni navega. El `venueId` viene del propio
     * evento (el servidor sólo sonda filas del venue de esta terminal) y es el tenant de la lápida que deja NOT_FOUND.
     */
    suspend fun probeSocketPaymentRequest(requestId: String?, venueId: String): RemotePaymentProbeAnswer {
        // Sin id no hay lápida posible, y sin lápida no hay NOT_FOUND: ACTIVE conserva la reserva (como el cancel sin id).
        if (requestId.isNullOrBlank()) return RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
        val answer = remotePaymentInbox.probe(requestId, venueId)
        if (answer.disposition == RemotePaymentProbeDisposition.RECEIVED_CANCELLED) {
            synchronized(queuedRequestIds) { queuedRequestIds.remove(requestId) }
        }
        return answer
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
