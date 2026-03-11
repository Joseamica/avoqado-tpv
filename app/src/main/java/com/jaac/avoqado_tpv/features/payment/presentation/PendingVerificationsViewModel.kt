package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
import com.jaac.avoqado_tpv.core.data.network.PendingVerificationDto
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.SaleVerificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the "Pendientes Verificacion" screen.
 *
 * Loads pending sale verifications that require proof-of-sale photos,
 * handles photo upload to Firebase Storage, and updates the backend.
 *
 * Flow:
 * 1. Load pending verifications from backend
 * 2. User selects a verification and takes a photo
 * 3. Photo is uploaded to Firebase Storage via VerificationUploadManager
 * 4. Backend is notified with the download URL
 * 5. Pending list is refreshed
 */
@HiltViewModel
class PendingVerificationsViewModel @Inject constructor(
    private val repository: SaleVerificationRepository,
    private val uploadManager: VerificationUploadManager,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _pendingVerifications = MutableStateFlow<List<PendingVerificationDto>>(emptyList())
    val pendingVerifications: StateFlow<List<PendingVerificationDto>> = _pendingVerifications.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _uploadingId = MutableStateFlow<String?>(null)
    val uploadingId: StateFlow<String?> = _uploadingId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadPending()
    }

    /**
     * Fetch pending verifications from the backend and update state.
     */
    fun loadPending() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            repository.getPendingVerifications()
                .onSuccess { verifications ->
                    Timber.d("Loaded ${verifications.size} pending verifications")
                    _pendingVerifications.value = verifications
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to load pending verifications")
                    _error.value = e.message ?: "Error al cargar verificaciones pendientes"
                }

            _isLoading.value = false
        }
    }

    /**
     * Upload a proof-of-sale photo for a pending verification.
     *
     * Flow:
     * 1. Set uploadingId to indicate which verification is in progress
     * 2. Upload photo to Firebase Storage via VerificationUploadManager
     * 3. On success, notify backend with the download URL
     * 4. Reload the pending list
     * 5. Clear uploadingId
     *
     * @param verificationId The ID of the pending verification
     * @param paymentId The payment ID associated with the verification
     * @param label Photo label (e.g., "linea", "portabilidad")
     * @param photoPath Local file path of the captured photo
     */
    fun uploadPhoto(
        verificationId: String,
        paymentId: String,
        label: String,
        photoPath: String,
        replaceIndex: Int? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uploadingId.value = verificationId
            _error.value = null

            try {
                val venueSlug = authRepository.getVenueSlug()
                    ?: throw IllegalStateException("Venue slug not available")

                // Find the verification to get order number and amount for the storage path
                val verification = _pendingVerifications.value.find { it.id == verificationId }
                val orderNumber = verification?.orderNumber ?: "unknown"
                val amount = verification?.amount?.toString() ?: "0"

                // Step 1: Upload photo to Firebase Storage
                val uploadResult = uploadManager.uploadProofOfSale(
                    localPath = photoPath,
                    venueSlug = venueSlug,
                    orderNumber = orderNumber,
                    amount = amount,
                    photoLabel = label
                )

                uploadResult.fold(
                    onSuccess = { downloadUrl ->
                        Timber.d("Photo uploaded to Firebase: $downloadUrl")

                        // Step 2: Notify backend with the download URL
                        repository.uploadPhoto(
                            verificationId = verificationId,
                            paymentId = paymentId,
                            photoUrls = listOf(downloadUrl),
                            replaceIndex = replaceIndex,
                            photoLabel = label
                        ).onSuccess {
                            Timber.d("Backend notified of photo upload for verification $verificationId")
                            // Step 3: Reload pending list
                            loadPendingInternal()
                        }.onFailure { e ->
                            Timber.e(e, "Failed to notify backend of photo upload")
                            _error.value = e.message ?: "Error al registrar foto en servidor"
                        }
                    },
                    onFailure = { e ->
                        Timber.e(e, "Failed to upload photo to Firebase")
                        _error.value = e.message ?: "Error al subir foto"
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Error in uploadPhoto")
                _error.value = e.message ?: "Error al subir foto"
            } finally {
                _uploadingId.value = null
            }
        }
    }

    /**
     * Internal reload without toggling isLoading (used after upload).
     */
    private suspend fun loadPendingInternal() {
        repository.getPendingVerifications()
            .onSuccess { verifications ->
                _pendingVerifications.value = verifications
            }
            .onFailure { e ->
                Timber.e(e, "Failed to reload pending verifications")
            }
    }

    /**
     * Dismiss the current error.
     */
    fun dismissError() {
        _error.value = null
    }
}
