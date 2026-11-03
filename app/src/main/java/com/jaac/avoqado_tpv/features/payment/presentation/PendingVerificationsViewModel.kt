package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.PendingVerificationDto
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableItem
import com.jaac.avoqado_tpv.features.verification.data.ActionableSalesRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableState
import com.jaac.avoqado_tpv.features.verification.data.PhotoCapture
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.verification.presentation.PROOF_FAILED_MESSAGE
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
) : ViewModel() {

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

    /** Encola la foto de [slot] de esa venta (archivo y fila antes de la red). Sin señal se queda «Se subirá sola». */
    fun enqueuePhoto(paymentId: String, slot: ProofSlot, photoPath: String) {
        val venueId = authRepository.getVenueId()
        val staffId = authRepository.getStaffId()
        if (venueId == null || staffId == null) {
            _error.value = PROOF_FAILED_MESSAGE
            return
        }
        val item = state.value.items.firstOrNull { it.paymentId == paymentId }
        viewModelScope.launch {
            _savingPaymentId.value = paymentId
            try {
                val key = queue.enqueuePhoto(
                    PhotoCapture(venueId, authRepository.getVenueSlug(), staffId, paymentId, item?.verificationId, slot, photoPath, null),
                )
                refreshWhenDelivered(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "📋 [Pendientes] No se pudo guardar la foto | paymentId=$paymentId | slot=$slot")
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
    )
}
