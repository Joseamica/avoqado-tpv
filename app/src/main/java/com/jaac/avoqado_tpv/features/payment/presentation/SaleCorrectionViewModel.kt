package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.serialized_sale.presentation.RejectionReason
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.modules.domain.repository.linkingNumberEnabled
import com.jaac.avoqado_tpv.features.verification.data.ActionableSalesRepository
import com.jaac.avoqado_tpv.features.verification.data.NumberCapture
import com.jaac.avoqado_tpv.features.verification.data.PhotoCapture
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.verification.data.remote.ProofOfSaleRemote
import com.jaac.avoqado_tpv.features.verification.presentation.NumberCaptureController
import com.jaac.avoqado_tpv.features.verification.presentation.PROOF_FAILED_MESSAGE
import com.jaac.avoqado_tpv.features.verification.presentation.PROOF_SAVE_FAILED_MESSAGE
import com.jaac.avoqado_tpv.features.verification.presentation.PROOF_SAVE_NUMBER_FAILED_MESSAGE
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * UI state for the sale-correction screen.
 *
 * The promoter lands here from "Mis Ventas" after the back-office rejected a
 * sale's documentation. They re-take the photos on the SAME verification —
 * the backend resets the verification to PENDING so the back-office re-reviews.
 */
data class SaleCorrectionUiState(
    val isLoading: Boolean = true,
    val detail: VerificationDetailDto? = null,
    // Rejection verdict captured on first load. Kept visible for the whole
    // session as a checklist — even after the re-upload flips the verification
    // back to PENDING and the backend clears the verdict server-side.
    val rejectionReasons: List<RejectionReason> = emptyList(),
    val reviewNotes: String? = null,
    /** Guardando la foto en la terminal (copiar el archivo e insertar la fila): milisegundos. */
    val isUploading: Boolean = false,
    val resubmitted: Boolean = false, // true once the verification left FAILED state
    val error: String? = null,
    /** Sin señal y la venta no está guardada en la terminal: texto neutro, nunca un error. */
    val offlineNotice: String? = null,
    /** Las capturas de esta venta en la cola, la más nueva primero. */
    val queued: List<ProofOfSaleQueueEntity> = emptyList(),
    val deliveredSlots: Set<ProofSlot> = emptySet(),
)

const val CORRECTION_NOT_SAVED_OFFLINE = "Sin conexión: esta venta no está guardada en la terminal; ábrela cuando vuelva la señal"

/**
 * ViewModel for [SaleCorrectionScreen], también sin señal.
 *
 * 1. El detalle sale del servidor o, sin señal, de la lista guardada ([ActionableSalesRepository.detail]).
 * 2. Muestra los motivos y las notas del back-office.
 * 3. La foto se ENCOLA ([ProofOfSaleQueueRepository]); la sube `PaymentSyncWorker` y el servidor regresa la venta a
 *    revisión. Al entregarse, se vuelve a pedir el detalle.
 */
@HiltViewModel
class SaleCorrectionViewModel @Inject constructor(
    private val sales: ActionableSalesRepository,
    private val queue: ProofOfSaleQueueRepository,
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle,
    // C7: el interruptor del número (features.linkingNumber) y su pregunta de repetido. Hilt siempre los da.
    modulesRepository: ModulesRepository? = null,
    proofOfSaleRemote: ProofOfSaleRemote? = null,
) : ViewModel() {

    val linkingNumberEnabled: StateFlow<Boolean> = (modulesRepository?.modules?.map { it.linkingNumberEnabled() } ?: flowOf(false))
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val numberCapture: NumberCaptureController? = proofOfSaleRemote?.let { NumberCaptureController(it) }

    private val verificationId: String = savedStateHandle.get<String>("verificationId").orEmpty()

    private val _uiState = MutableStateFlow(SaleCorrectionUiState())
    val uiState: StateFlow<SaleCorrectionUiState> = _uiState.asStateFlow()

    private var verdictCaptured = false

    init {
        load()
        // Las capturas de ESTA venta en la cola, al día: cada una con su estado y, si ya no se sube sola, «Descartar».
        authRepository.getStaffId()?.let { staffId ->
            viewModelScope.launch {
                combine(sales.observe(staffId), _uiState.map { it.detail?.paymentId }.distinctUntilChanged()) { state, paymentId ->
                    state.items.firstOrNull { it.paymentId == paymentId }
                }.collect { item ->
                    _uiState.update { it.copy(queued = item?.queued.orEmpty(), deliveredSlots = item?.deliveredSlots.orEmpty()) }
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, offlineNotice = null) }
            val result = sales.detail(verificationId)
            val detail = result.getOrNull()
            if (detail == null) {
                Timber.e(result.exceptionOrNull(), "📸 [SaleCorrection] Sin detalle del servidor ni guardado | id=$verificationId")
                // Sin señal no es un error: la venta no está en la terminal y se abre cuando vuelva la señal (neutro).
                _uiState.update {
                    if (result.exceptionOrNull() is IOException) it.copy(isLoading = false, offlineNotice = CORRECTION_NOT_SAVED_OFFLINE)
                    else it.copy(isLoading = false, error = "Error al cargar la venta")
                }
                return@launch
            }
            if (!verdictCaptured) {
                // Capture the rejection verdict once — it stays visible
                // as a checklist for the rest of the correction session.
                _uiState.update {
                    it.copy(rejectionReasons = RejectionReason.parseList(detail.rejectionReasons), reviewNotes = detail.reviewNotes)
                }
                verdictCaptured = true
            }
            _uiState.update { it.copy(isLoading = false, detail = detail, resubmitted = detail.status != "FAILED") }
        }
    }

    /** Encola la foto retomada de [slot], con el número de su ranura si el interruptor está encendido. */
    fun enqueuePhoto(slot: ProofSlot, photoPath: String, number: String? = null, onSaved: () -> Unit = {}) {
        save(PROOF_SAVE_FAILED_MESSAGE, onSaved) { detail, venueId, staffId ->
            queue.enqueuePhoto(
                PhotoCapture(venueId, authRepository.getVenueSlug(), staffId, detail.paymentId, detail.id, slot, photoPath, number),
            )
        }
    }

    /** «Corregir número»: sólo el número de [slot], sin volver a tomar la foto (fila `NUMBER_ONLY`). */
    fun correctNumber(slot: ProofSlot, number: String) {
        save(PROOF_SAVE_NUMBER_FAILED_MESSAGE) { detail, venueId, staffId ->
            queue.enqueueNumberOnly(NumberCapture(venueId, staffId, detail.paymentId, detail.id, slot, number))
        }
    }

    /** Archivo y fila antes de la red; al entregarse, vuelve a pedir el detalle. */
    private fun save(
        failureMessage: String, onSaved: () -> Unit = {},
        enqueue: suspend (detail: VerificationDetailDto, venueId: String, staffId: String) -> String,
    ) {
        val detail = _uiState.value.detail ?: return
        val venueId = authRepository.getVenueId()
        val staffId = authRepository.getStaffId()
        if (venueId == null || staffId == null) {
            _uiState.update { it.copy(error = PROOF_FAILED_MESSAGE) }
            return
        }
        if (_uiState.value.isUploading) return // un segundo toque a «Confirmar» mientras guarda no duplica la captura
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, error = null) }
            try {
                val key = enqueue(detail, venueId, staffId)
                _uiState.update { it.copy(isUploading = false) }
                onSaved()
                val result = queue.awaitResult(key, Long.MAX_VALUE)
                if (result?.outcome == "APPLIED" || result?.outcome == "SUPERSEDED_BY_NEWER") load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "📸 [SaleCorrection] No se pudo guardar la foto | id=${detail.id}")
                _uiState.update { it.copy(isUploading = false, error = failureMessage) }
            }
        }
    }

    fun discard(key: String) {
        viewModelScope.launch { queue.discard(key) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
