package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.PendingVerificationDto
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableItem
import com.jaac.avoqado_tpv.features.verification.data.ActionableSalesRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableState
import com.jaac.avoqado_tpv.features.verification.data.NumberCapture
import com.jaac.avoqado_tpv.features.verification.data.PhotoCapture
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.serialized_sale.presentation.RejectionReason
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.modules.domain.repository.linkingNumberEnabled
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.verification.data.remote.ProofOfSaleRemote
import com.jaac.avoqado_tpv.features.verification.presentation.NumberCaptureController
import com.jaac.avoqado_tpv.features.verification.presentation.PROOF_FAILED_MESSAGE
import com.jaac.avoqado_tpv.features.verification.presentation.canDiscardCapture
import com.jaac.avoqado_tpv.features.verification.presentation.isPhotoCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

/**
 * «Pendientes», también sin señal: la lista guardada en la terminal (servidor + ventas recién cobradas) con las capturas
 * de la cola encima ([ActionableSalesRepository]). Tomar una foto la ENCOLA ([ProofOfSaleQueueRepository]); la sube
 * `PaymentSyncWorker`. La pantalla nunca sube por su cuenta.
 */
@HiltViewModel
class PendingVerificationsViewModel @Inject constructor(
    private val sales: ActionableSalesRepository,
    private val queue: ProofOfSaleQueueRepository,
    private val authRepository: AuthRepository,
    // C7: el interruptor del número (features.linkingNumber) y su pregunta de repetido. Hilt siempre los da.
    modulesRepository: ModulesRepository? = null,
    proofOfSaleRemote: ProofOfSaleRemote? = null,
) : ViewModel() {

    val linkingNumberEnabled: StateFlow<Boolean> = (modulesRepository?.modules?.map { it.linkingNumberEnabled() } ?: flowOf(false))
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val numberCapture: NumberCaptureController? = proofOfSaleRemote?.let { NumberCaptureController(it) }

    val state: StateFlow<ActionableState> = (authRepository.getStaffId()?.let { sales.observe(it) } ?: flowOf(EMPTY))
        .stateIn(viewModelScope, SharingStarted.Eagerly, EMPTY)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** La venta cuya foto se está guardando en la terminal (milisegundos: copiar el archivo e insertar la fila). */
    private val _savingPaymentId = MutableStateFlow<String?>(null)
    val savingPaymentId: StateFlow<String?> = _savingPaymentId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadPending()
    }

    fun loadPending() {
        viewModelScope.launch {
            _isLoading.value = true
            sales.refresh().onFailure(::report)
            _isLoading.value = false
        }
    }

    fun loadMore() {
        if (_isLoadingMore.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            sales.loadMore().onFailure(::report)
            _isLoadingMore.value = false
        }
    }

    /**
     * Encola la foto de [slot] de esa venta, con el número de su ranura si el interruptor está encendido (archivo y fila
     * antes de la red). Sin señal se queda «Se subirá sola».
     */
    fun enqueuePhoto(paymentId: String, slot: ProofSlot, photoPath: String, number: String? = null) {
        val item = state.value.items.firstOrNull { it.paymentId == paymentId }
        save(paymentId, slot) { venueId, staffId ->
            queue.enqueuePhoto(
                PhotoCapture(venueId, authRepository.getVenueSlug(), staffId, paymentId, item?.verificationId, slot, photoPath, number),
            )
        }
    }

    /**
     * «Corregir número»: sólo el número de [slot], sin foto (fila `NUMBER_ONLY`). Necesita la verificación: en una venta que
     * el servidor todavía no registra, la pantalla lo deja en gris («Disponible cuando se registre la venta»).
     */
    fun correctNumber(paymentId: String, slot: ProofSlot, number: String) {
        val verificationId = state.value.items.firstOrNull { it.paymentId == paymentId }?.verificationId ?: return
        save(paymentId, slot) { venueId, staffId ->
            queue.enqueueNumberOnly(NumberCapture(venueId, staffId, paymentId, verificationId, slot, number))
        }
    }

    private fun save(paymentId: String, slot: ProofSlot, enqueue: suspend (venueId: String, staffId: String) -> String) {
        val venueId = authRepository.getVenueId()
        val staffId = authRepository.getStaffId()
        if (venueId == null || staffId == null) {
            _error.value = PROOF_FAILED_MESSAGE
            return
        }
        viewModelScope.launch {
            _savingPaymentId.value = paymentId
            try {
                refreshWhenDelivered(enqueue(venueId, staffId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "📋 [Pendientes] No se pudo guardar en la terminal | paymentId=$paymentId | slot=$slot")
                _error.value = PROOF_FAILED_MESSAGE
            } finally {
                _savingPaymentId.value = null
            }
        }
    }

    /** «Descartar» borra esa captura y su archivo; la tarjeta sólo la quita el servidor. */
    fun discard(key: String) {
        viewModelScope.launch { queue.discard(key) }
    }

    fun dismissError() {
        _error.value = null
    }

    /** Ya entregada, la lista del servidor trae la foto; si no llega, la tarjeta sigue con «foto entregada». */
    private fun refreshWhenDelivered(key: String) {
        viewModelScope.launch {
            val result = queue.awaitResult(key, Long.MAX_VALUE) ?: return@launch
            if (result.outcome == "APPLIED" || result.outcome == "SUPERSEDED_BY_NEWER") sales.refresh()
        }
    }

    /** Sin señal no hay toast: la pantalla ya dice que la lista es la guardada. Un rechazo del servidor sí se avisa. */
    private fun report(e: Throwable) {
        if (e !is IOException) _error.value = "No se pudo actualizar la lista"
    }

    private companion object {
        val EMPTY = ActionableState(emptyList(), isOffline = false, nextCursor = null)
    }
}

/** Lo que el back-office pidió revisar: la venta está en «Revisar» (FAILED). Mismas etiquetas que la corrección. */
data class CardReview(val reasons: List<String>, val notes: String?)

const val REVIEW_CHIP_TEXT = "Revisar"

fun ActionableItem.review(): CardReview? = detail?.takeIf { it.status == "FAILED" }?.let { d ->
    CardReview(RejectionReason.parseList(d.rejectionReasons).map { it.label }, d.reviewNotes?.takeIf { it.isNotBlank() })
}

/** Ranuras requeridas que ya tienen foto (servidor, entregada desde la terminal o en la cola) y si la tarjeta va completa. */
data class CardProgress(val filled: Int, val required: Int, val complete: Boolean)

fun cardProgress(
    card: PendingVerificationDto, queued: List<ProofOfSaleQueueEntity>, delivered: Set<ProofSlot>, review: CardReview?,
): CardProgress {
    val slots = if (card.isPortabilidad) ProofSlot.entries else listOf(ProofSlot.VINCULACION)
    val filled = slots.count { slot ->
        card.photos.getOrNull(slot.ordinal)?.isNotEmpty() == true || slot in delivered ||
            queued.any { it.slot == slot.name && isPhotoCapture(it) && !canDiscardCapture(it) }
    }
    // Una venta en «Revisar» nunca se pinta completa aunque tenga todas sus fotos: el back-office pidió algo.
    return CardProgress(filled, card.requiredPhotos, complete = review == null && filled >= card.requiredPhotos)
}

/** La tarjeta de siempre a partir de lo guardado; de una venta con sólo capturas en la cola salen las ranuras de esas. */
fun ActionableItem.toPendingCard(): PendingVerificationDto {
    val portabilidad = detail?.isPortabilidad ?: queued.any { it.slot == ProofSlot.PORTABILIDAD.name }
    return PendingVerificationDto(
        id = detail?.id.orEmpty(),
        paymentId = paymentId,
        amount = detail?.amount ?: 0.0,
        orderNumber = detail?.orderNumber,
        date = detail?.date ?: Instant.ofEpochMilli(queued.maxOfOrNull { it.createdAt } ?: 0L).toString(),
        serialNumbers = detail?.serialNumbers.orEmpty(),
        isPortabilidad = portabilidad,
        photos = detail?.photos.orEmpty(),
        requiredPhotos = detail?.requiredPhotos ?: if (portabilidad) 2 else 1,
        linkingNumber = detail?.linkingNumber,
        portedNumber = detail?.portedNumber,
        linkingNumberLocked = detail?.linkingNumberLocked,
        portedNumberLocked = detail?.portedNumberLocked,
        alerts = detail?.alerts,
    )
}

/** El número de una ranura en la tarjeta, si lo bloqueó el back-office y si el servidor dice que está repetido. */
data class SlotNumber(val number: String?, val locked: Boolean, val duplicate: Boolean)

/**
 * Bajo cada foto: el número de la captura más nueva que va en camino (aún sin respuesta del servidor), si no el del
 * servidor. El aviso de repetido es del número del servidor: el de una captura en camino todavía no se sabe. Bloqueado
 * (lo corrigió el back-office) manda el del servidor y no se avisa nada: ya lo decidió el back-office.
 */
fun slotNumber(card: PendingVerificationDto, queued: List<ProofOfSaleQueueEntity>, slot: ProofSlot): SlotNumber {
    val ported = slot == ProofSlot.PORTABILIDAD
    val server = if (ported) card.portedNumber else card.linkingNumber
    if ((if (ported) card.portedNumberLocked else card.linkingNumberLocked) == true) return SlotNumber(server, locked = true, duplicate = false)
    // La cola viene la más nueva primero; lo que ya no se sube solo (FAILED, NEEDS_ATTENTION) no cambió el número.
    val onTheWay = queued.asSequence()
        .filter { it.slot == slot.name && !canDiscardCapture(it) }
        .mapNotNull { if (ported) it.portedNumber else it.linkingNumber }
        .firstOrNull()
    if (onTheWay != null) return SlotNumber(onTheWay, locked = false, duplicate = false)
    val alert = if (ported) "PORTED_DUPLICATE" else "LINKING_DUPLICATE"
    return SlotNumber(server, locked = false, duplicate = card.alerts.orEmpty().contains(alert))
}
