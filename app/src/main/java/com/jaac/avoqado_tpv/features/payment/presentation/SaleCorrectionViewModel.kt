package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
import com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.SaleVerificationRepository
import com.jaac.avoqado_tpv.features.serialized_sale.presentation.RejectionReason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
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
    val isUploading: Boolean = false,
    val resubmitted: Boolean = false, // true once the verification left FAILED state
    val error: String? = null,
)

/**
 * ViewModel for [SaleCorrectionScreen].
 *
 * Flow:
 * 1. Load the rejected verification by id (nav arg `verificationId`).
 * 2. Show the back-office rejection reasons + notes.
 * 3. Promoter re-takes a photo → upload to Firebase → notify backend.
 * 4. Backend resets the verification to PENDING (re-enters review queue).
 */
@HiltViewModel
class SaleCorrectionViewModel @Inject constructor(
    private val repository: SaleVerificationRepository,
    private val uploadManager: VerificationUploadManager,
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val verificationId: String = savedStateHandle.get<String>("verificationId").orEmpty()

    private val _uiState = MutableStateFlow(SaleCorrectionUiState())
    val uiState: StateFlow<SaleCorrectionUiState> = _uiState.asStateFlow()

    private var verdictCaptured = false

    init {
        load()
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getVerificationDetail(verificationId)
                .onSuccess { detail ->
                    if (!verdictCaptured) {
                        // Capture the rejection verdict once — it stays visible
                        // as a checklist for the rest of the correction session.
                        _uiState.update {
                            it.copy(
                                rejectionReasons = RejectionReason.parseList(detail.rejectionReasons),
                                reviewNotes = detail.reviewNotes,
                            )
                        }
                        verdictCaptured = true
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            detail = detail,
                            resubmitted = detail.status != "FAILED",
                        )
                    }
                }
                .onFailure { e ->
                    Timber.e(e, "📸 [SaleCorrection] Failed to load verification")
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Error al cargar la venta")
                    }
                }
        }
    }

    /**
     * Upload a re-taken photo: Firebase Storage → backend proof-of-sale endpoint.
     *
     * @param label "Vinculacion" or "Portabilidad" (fixed-slot label).
     * @param photoPath Local file path of the captured photo.
     * @param replaceIndex Index to replace (0=Vinculacion, 1=Portabilidad), or null to append.
     */
    fun uploadPhoto(label: String, photoPath: String, replaceIndex: Int?) {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isUploading = true, error = null) }
            try {
                val venueSlug = authRepository.getVenueSlug()
                    ?: throw IllegalStateException("Venue slug not available")

                val uploadResult = uploadManager.uploadProofOfSale(
                    localPath = photoPath,
                    venueSlug = venueSlug,
                    orderNumber = detail.orderNumber ?: "unknown",
                    amount = detail.amount.toString(),
                    photoLabel = label,
                )

                uploadResult.fold(
                    onSuccess = { downloadUrl ->
                        repository.uploadPhoto(
                            verificationId = detail.id,
                            paymentId = detail.paymentId,
                            photoUrls = listOf(downloadUrl),
                            replaceIndex = replaceIndex,
                            photoLabel = label,
                        ).onSuccess {
                            Timber.d("📸 [SaleCorrection] Photo re-uploaded for ${detail.id}")
                            reload()
                        }.onFailure { e ->
                            Timber.e(e, "📸 [SaleCorrection] Backend rejected photo")
                            _uiState.update {
                                it.copy(isUploading = false, error = e.message ?: "Error al registrar foto")
                            }
                        }
                    },
                    onFailure = { e ->
                        Timber.e(e, "📸 [SaleCorrection] Firebase upload failed")
                        _uiState.update {
                            it.copy(isUploading = false, error = e.message ?: "Error al subir foto")
                        }
                    },
                )
            } catch (e: Exception) {
                Timber.e(e, "📸 [SaleCorrection] uploadPhoto error")
                _uiState.update { it.copy(isUploading = false, error = e.message ?: "Error al subir foto") }
            }
        }
    }

    /** Reload after an upload — keeps the captured verdict, refreshes photos + status. */
    private suspend fun reload() {
        repository.getVerificationDetail(verificationId)
            .onSuccess { detail ->
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        detail = detail,
                        resubmitted = detail.status != "FAILED",
                    )
                }
            }
            .onFailure { e ->
                Timber.e(e, "📸 [SaleCorrection] reload failed")
                _uiState.update { it.copy(isUploading = false) }
            }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
