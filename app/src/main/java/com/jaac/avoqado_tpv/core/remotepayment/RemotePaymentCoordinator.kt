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
    val socketRequestId: String? = null,
    /** N0: ≥ 1 ⇒ el servidor que entregó esta solicitud contesta el vínculo intento→solicitud (S1); 0 ⇒ camino legacy. */
    val attemptLinkVersion: Int = 0,
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
sealed interface RemotePaymentAdmission {
    data object READY : RemotePaymentAdmission
    data object NOT_READY : RemotePaymentAdmission
    data object NOT_CLAIMABLE : RemotePaymentAdmission
    data object VENUE_CHANGED : RemotePaymentAdmission

    /** D8: la Caja no deja iniciar este cobro (cerrada, sin sesión, sin permiso). Nada se abrió y el POS sigue esperando. */
    data class NotAdmitted(val motivo: String) : RemotePaymentAdmission
}

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
     * memoria a propósito: muere con el proceso. B2 (T3, 22-oct): sin ella y SIN intentos, la solicitud la dejó un
     * proceso muerto que nunca tocó el SDK (la libreta es write-ahead obligatoria), así que el cancel y la sonda la
     * resuelven por `resolverDesenlaceNegativo` (ACCEPTED / RECEIVED_CANCELLED, con la cerca puesta); con intentos
     * pendientes, o en una fila heredada que no nació bajo la barrera durable (D6), la libreta certifica o no el cese. No se registra desde
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

    fun observePendingObligations(venueId: String) = remotePaymentInbox.observePendingObligations(venueId)

    fun observePendingObligationCount(venueId: String) = remotePaymentInbox.observePendingObligationCount(venueId)

    suspend fun prepareSocketPaymentRequest(
        requestId: String,
        awaitReady: suspend () -> Boolean,
        currentVenueId: () -> String?,
        motivoDelCandado: () -> String? = { null },
    ): RemotePaymentAdmission {
        // D669: bloqueada o en mantenimiento desde Avoqado ⇒ se contesta YA, sin esperar ni preparar el lector (Codex r1 #3). Un
        // LOCK nuevo no cae a media admisión (espera a Inicio sin acciones, `beginAction` del colector); UNLOCK sí, y sólo afloja.
        motivoDelCandado()?.let { motivo ->
            synchronized(queuedRequestIds) { queuedRequestIds.remove(requestId) }
            // Sin sede activa (el latido borró la activación) no se cierra nada, como antes (Codex r2 #4).
            if (currentVenueId().isNullOrBlank()) return RemotePaymentAdmission.NOT_CLAIMABLE
            return rechazarPorCandado(requestId, motivo)
        }
        // Cancellation can win while SDK initialization is suspended. Claim only once
        // readiness has returned, using the current activation and the durable venue.
        val ready = awaitReady()
        synchronized(queuedRequestIds) { queuedRequestIds.remove(requestId) }
        val venueId = currentVenueId()?.takeIf { it.isNotBlank() }
            ?: return RemotePaymentAdmission.NOT_CLAIMABLE
        val reclamo = remotePaymentInbox.reclamarParaVenue(requestId, venueId)
        if (reclamo != RemotePaymentAdmission.READY) return reclamo
        synchronized(reclamadasEnEsteProceso) { reclamadasEnEsteProceso += requestId }
        // The Room call suspended: activation may have changed while it ran. This
        // collector owns the claim but has not opened any SDK, so it may fail safely.
        if (currentVenueId() != venueId) return RemotePaymentAdmission.VENUE_CHANGED
        return if (ready) RemotePaymentAdmission.READY else RemotePaymentAdmission.NOT_READY
    }

    /**
     * D669: el «no» por candado se ESCRIBE en la misma operación que lo decide (CAS `RECEIVED → RESOLVED`, el de [RemotePaymentInbox.rejectUnclaimed]),
     * antes de devolver `NotAdmitted`. Si se guardara después (como el de la caja, en la corrutina del socket), una reentrega con la
     * terminal recién desbloqueada se reclamaría y el rechazo atrasado cerraría una solicitud ya reclamada (Codex r1 #1). Lo que no
     * sigue RECEIVED (reclamado, ya resuelto) no se toca. El texto es el mismo que arma la navegación con «En la terminal: » (D8): la
     * navegación sólo reproduce lo guardado.
     */
    private suspend fun rechazarPorCandado(requestId: String, motivo: String): RemotePaymentAdmission {
        val contestar = try {
            remotePaymentInbox.rejectUnclaimed(requestId, errorMessage = "En la terminal: $motivo") != null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Codex r2 #1: callar dejaba al POS esperando hasta 5 min (con el socket vivo el SERVIDOR no sondea). La fila sigue RECEIVED,
            // sin reclamar ni lector: la navegación lo vuelve a intentar por el camino de la caja (D8, `persistResult`).
            Timber.e(error, "❌ [RemotePayment] No se pudo guardar el rechazo por candado de $requestId; se reintenta al contestar")
            true
        }
        // false = la fila ya no está RECEIVED: otro ya la contestó (cancel, sonda) o la reclamó. No se toca.
        return if (contestar) RemotePaymentAdmission.NotAdmitted(motivo) else RemotePaymentAdmission.NOT_CLAIMABLE
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
     * 🔒 D26 (22-nov): la pantalla DUEÑA de [requestId] murió (`onCleared`). La propiedad en memoria ya no tiene quién la respalde: sin
     * soltarla, la sonda seguía contestando «viva» (caso 1 de `RemotePaymentInbox.respuestaEnProceso`) hasta reiniciar la app. Sin
     * propiedad, la bandeja la resuelve como la de un proceso que ya no la ejecuta (caso 2, sólo sin intentos bloqueadores) o le pregunta
     * a la libreta (caso 3). Sólo la llama la pantalla dueña al morir.
     */
    fun soltarPropiedad(requestId: String) {
        synchronized(reclamadasEnEsteProceso) { reclamadasEnEsteProceso.remove(requestId) }
    }

    /**
     * Sonda del servidor: contesta desde la bandeja durable; nunca entrega ni navega. El `venueId` viene del propio
     * evento (el servidor sólo sonda filas del venue de esta terminal) y es el tenant de la lápida que deja NOT_FOUND.
     */
    suspend fun probeSocketPaymentRequest(requestId: String?, venueId: String): RemotePaymentProbeAnswer {
        // Sin id no hay lápida posible, y sin lápida no hay NOT_FOUND: ACTIVE conserva la reserva (como el cancel sin id).
        if (requestId.isNullOrBlank()) return RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
        // La propiedad se mira aquí, igual que en el cancel: una fila reclamada por ESTE proceso sigue
        // viva; una de un proceso anterior no puede estar ejecutándose.
        val propia = synchronized(reclamadasEnEsteProceso) { requestId in reclamadasEnEsteProceso }
        val answer = remotePaymentInbox.probe(requestId, venueId, propiedadEnEsteProceso = propia)
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
     * D669 (Codex r2 #2): `SocketManager.events` reentrega el ÚLTIMO evento a cada suscriptor nuevo (`replay = 1`), así que un
     * `HomeViewModel` nuevo vuelve a recibir la última devolución. Una ya contestada (`opened`, o `rejected` con la terminal bloqueada)
     * no se vuelve a abrir ni a contestar. En memoria, como el replay: los dos viven lo que vive el proceso. Sin id, se atiende siempre.
     * ponytail: el conjunto crece con cada devolución del proceso (pocas al día); un tope si algún día son miles.
     */
    private val devolucionesContestadas = mutableSetOf<String>()

    fun esDevolucionNueva(requestId: String): Boolean =
        requestId.isBlank() || synchronized(devolucionesContestadas) { devolucionesContestadas.add(requestId) }

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
