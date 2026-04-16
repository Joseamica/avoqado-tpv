package com.jaac.avoqado_tpv.features.timeclock.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.location.LocationResult
import com.jaac.avoqado_tpv.core.location.LocationService
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.authentication.domain.models.StaffRole
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import com.jaac.avoqado_tpv.features.timeclock.domain.repository.TimeEntryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class TimeclockViewModel @Inject constructor(
    private val timeEntryRepository: TimeEntryRepository,
    private val authRepository: AuthRepository,
    private val secureStorage: SecureStorage,
    private val verificationUploadManager: VerificationUploadManager,
    private val locationService: LocationService,
    private val tpvSettingsRepository: TpvSettingsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val venueId: String = savedStateHandle.get<String>("venueId") ?: ""
    private val pin: String = savedStateHandle.get<String>("pin") ?: ""
    private val autoAction: String = savedStateHandle.get<String>("autoAction") ?: ""

    private val _state = MutableStateFlow<TimeclockState>(TimeclockState.Loading)
    val state: StateFlow<TimeclockState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TimeclockEvent>()
    val events: SharedFlow<TimeclockEvent> = _events.asSharedFlow()

    /** True while auto-action hasn't fired yet (screen shows overlay instead of PulseContent) */
    private var autoActionTriggered = false
    val hasAutoAction: Boolean get() = autoAction.isNotEmpty()
    val isAutoActionPending: Boolean get() = hasAutoAction && !autoActionTriggered

    // Store staff info after verification
    private var currentStaffId: String? = null
    private var currentStaffName: String? = null

    // Photo queue state — supports multi-step photo capture
    private var photoQueue: MutableList<PhotoType> = mutableListOf()
    private var currentPhotoType: PhotoType? = null
    private var isClockOutFlow: Boolean = false

    // Pending photo URLs collected during the queue
    private var pendingClockInPhotoUrl: String? = null
    private var pendingFacadePhotoUrl: String? = null
    private var pendingDepositPhotoUrl: String? = null
    private var pendingClockOutSelfieUrl: String? = null
    private var pendingSkipReason: String? = null

    init {
        if (venueId.isNotEmpty() && pin.isNotEmpty()) {
            verifyPinAndLoadStatus()
        } else {
            _state.value = TimeclockState.Error("Datos de sesión inválidos")
        }
    }

    /**
     * Verify PIN by attempting login (without saving tokens)
     * Then load the current timeclock status for this staff member
     */
    private fun verifyPinAndLoadStatus() {
        viewModelScope.launch {
            _state.value = TimeclockState.Loading

            try {
                // Step 1: Verify PIN using auth endpoint
                val authResult = authRepository.verifyPinOnly(venueId, pin)

                authResult.fold(
                    onSuccess = { staffInfo ->
                        currentStaffId = staffInfo.id
                        currentStaffName = staffInfo.name
                        Timber.i("✅ PIN verified for staff: ${staffInfo.name}")

                        // Step 2: Load current timeclock status
                        loadTimeclockStatus(staffInfo.id, staffInfo.name)
                    },
                    onFailure = { error ->
                        Timber.e("❌ PIN verification failed: ${error.message}")
                        _state.value = TimeclockState.InvalidPin(
                            error.message ?: "PIN incorrecto"
                        )
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ Error verifying PIN")
                _state.value = TimeclockState.Error("Error de conexión")
            }
        }
    }

    /**
     * Load current timeclock status for the verified staff member
     */
    private suspend fun loadTimeclockStatus(staffId: String, staffName: String) {
        try {
            // Get TpvSettings to check if clock-in is required for login
            val settings = tpvSettingsRepository.getCurrentSettings()
            val requireClockInToLogin = settings.requireClockInToLogin
            Timber.d("⏱️ [TIMECLOCK] requireClockInToLogin: $requireClockInToLogin")

            // Get today's date range for filtering (venue timezone)
            val today = LocalDate.now(VenueTimeZone.get(secureStorage))
            val startDate = today.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val endDate = today.plusDays(1).atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            // Fetch time entries for this staff member today
            val entriesResult = timeEntryRepository.getTimeEntries(
                venueId = venueId,
                staffId = staffId,
                startDate = startDate,
                endDate = endDate,
                limit = 10
            )

            entriesResult.fold(
                onSuccess = { entries ->
                    // Find active entry (CLOCKED_IN or ON_BREAK)
                    val activeEntry = entries.firstOrNull {
                        it.status == TimeEntryStatus.CLOCKED_IN || it.status == TimeEntryStatus.ON_BREAK
                    }

                    // Calculate total hours worked today
                    val totalHours = entries
                        .filter { it.status == TimeEntryStatus.CLOCKED_OUT }
                        .mapNotNull { it.totalHours }
                        .fold(BigDecimal.ZERO) { acc, hours -> acc + hours }

                    _state.value = TimeclockState.Ready(
                        staffId = staffId,
                        staffName = staffName,
                        currentEntry = activeEntry,
                        recentEntries = entries,
                        totalHoursToday = totalHours,
                        requireClockInToLogin = requireClockInToLogin
                    )
                    maybeAutoTrigger(activeEntry)
                },
                onFailure = { error ->
                    // If no entries found, still show Ready state (not clocked in)
                    _state.value = TimeclockState.Ready(
                        staffId = staffId,
                        staffName = staffName,
                        currentEntry = null,
                        recentEntries = emptyList(),
                        totalHoursToday = BigDecimal.ZERO,
                        requireClockInToLogin = requireClockInToLogin
                    )
                    maybeAutoTrigger(null)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading timeclock status")
            _state.value = TimeclockState.Error("Error cargando estado")
        }
    }

    /**
     * Auto-trigger clock-in or clock-out if autoAction is set.
     * Only fires once — after error recovery the user sees PulseContent for manual retry.
     */
    private suspend fun maybeAutoTrigger(activeEntry: TimeEntry?) {
        if (autoAction.isEmpty() || autoActionTriggered) return
        autoActionTriggered = true

        val status = activeEntry?.status

        when (autoAction) {
            "clockIn" -> {
                if (status == null || status == TimeEntryStatus.CLOCKED_OUT) {
                    Timber.d("⏱️ [AUTO] Auto-triggering clockIn")
                    clockIn()
                } else {
                    Timber.d("⏱️ [AUTO] Already clocked in — skipping auto clockIn")
                    _events.emit(TimeclockEvent.AutoActionSkipped)
                }
            }
            "clockOut" -> {
                if (status == TimeEntryStatus.CLOCKED_IN) {
                    Timber.d("⏱️ [AUTO] Auto-triggering clockOut")
                    clockOut()
                } else {
                    Timber.d("⏱️ [AUTO] Not clocked in — skipping auto clockOut")
                    _events.emit(TimeclockEvent.AutoActionSkipped)
                }
            }
            else -> {
                Timber.w("⏱️ [AUTO] Unknown autoAction: $autoAction")
                _events.emit(TimeclockEvent.AutoActionSkipped)
            }
        }
    }

    /**
     * Clock in the current staff member.
     *
     * **Multi-Step Photo Queue Flow:**
     * 1. Build queue of required photos based on TpvSettings
     * 2. If requireClockInPhoto → add CLOCK_IN_SELFIE
     * 3. If requireFacadePhoto → add FACADE
     * 4. Process queue one photo at a time, then perform clock-in with all URLs
     */
    fun clockIn() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            val settings = tpvSettingsRepository.getCurrentSettings()

            Timber.d("⏱️ [CLOCK-IN] TpvSettings check:")
            Timber.d("   - requireClockInPhoto: ${settings.requireClockInPhoto}")
            Timber.d("   - requireFacadePhoto: ${settings.requireFacadePhoto}")

            // Build photo queue
            isClockOutFlow = false
            photoQueue.clear()
            pendingClockInPhotoUrl = null
            pendingFacadePhotoUrl = null

            if (settings.requireClockInPhoto) {
                photoQueue.add(PhotoType.CLOCK_IN_SELFIE)
            }
            if (settings.requireFacadePhoto) {
                photoQueue.add(PhotoType.FACADE)
            }

            Timber.d("⏱️ [CLOCK-IN] Photo queue: $photoQueue")
            processNextPhotoOrProceed()
        }
    }

    /**
     * Clock out the current staff member.
     *
     * **Photo Queue Flow for Clock-Out:**
     * 1. If requireDepositPhoto → add DEPOSIT_VOUCHER (no selfie at clock-out)
     * 2. Process queue, then perform clock-out with deposit photo URL
     */
    fun clockOut() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            val settings = tpvSettingsRepository.getCurrentSettings()

            Timber.d("⏱️ [CLOCK-OUT] TpvSettings check:")
            Timber.d("   - requireDepositPhoto: ${settings.requireDepositPhoto}")

            // Build photo queue for clock-out:
            // 1. Deposit voucher (if configured)
            // 2. Checkout selfie (always, after deposit — only if deposit was taken)
            isClockOutFlow = true
            photoQueue.clear()
            pendingDepositPhotoUrl = null
            pendingClockOutSelfieUrl = null

            if (settings.requireDepositPhoto) {
                photoQueue.add(PhotoType.DEPOSIT_VOUCHER)
                photoQueue.add(PhotoType.CLOCK_OUT_SELFIE)
            }

            Timber.d("⏱️ [CLOCK-OUT] Photo queue: $photoQueue")
            processNextPhotoOrProceed()
        }
    }

    /**
     * Process the next photo in the queue, or proceed with clock-in/out if queue is empty.
     */
    private suspend fun processNextPhotoOrProceed() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        if (photoQueue.isEmpty()) {
            // All photos captured (or none required) — proceed
            if (isClockOutFlow) {
                performClockOut(staffId, staffName, pendingDepositPhotoUrl, pendingSkipReason)
            } else {
                performClockIn(staffId, staffName, pendingClockInPhotoUrl, pendingFacadePhotoUrl)
            }
            return
        }

        // Pop next photo type from queue
        val nextPhotoType = photoQueue.removeAt(0)
        currentPhotoType = nextPhotoType

        // Check if current user can skip
        val currentRole = authRepository.getRole()
        val canSkip = currentRole in listOf(
            StaffRole.ADMIN,
            StaffRole.OWNER,
            StaffRole.SUPERADMIN
        )

        Timber.d("⏱️ Next photo: $nextPhotoType | canSkip=$canSkip | role=$currentRole")
        _state.value = TimeclockState.RequiresPhoto(
            staffId = staffId,
            staffName = staffName,
            canSkip = canSkip,
            isClockOut = isClockOutFlow,
            photoType = nextPhotoType
        )
    }

    /**
     * Start camera capture for the current photo in the queue.
     * Called when user clicks "Take Photo" button.
     */
    fun startPhotoCapture() {
        val staffId = currentStaffId ?: return
        Timber.d("📸 Starting photo capture for staff: $staffId | type: $currentPhotoType")
        _state.value = TimeclockState.CapturingPhoto(staffId)
    }

    /**
     * Handle captured photo from camera.
     * Shows preview for confirmation before uploading.
     *
     * @param localPath Local file path of the captured photo
     */
    fun onPhotoCaptured(localPath: String) {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return
        val photoType = currentPhotoType ?: PhotoType.CLOCK_IN_SELFIE

        Timber.d("📸 Photo captured for $photoType, showing preview: $localPath")

        // Show photo preview for confirmation
        _state.value = TimeclockState.PhotoPreview(
            staffId = staffId,
            staffName = staffName,
            localPath = localPath,
            isClockOut = isClockOutFlow,
            photoType = photoType
        )
    }

    /**
     * Confirm the captured photo and proceed with upload.
     * After successful upload, saves URL to the correct pending field based on currentPhotoType,
     * then continues processing the queue.
     */
    fun confirmPhoto() {
        val previewState = _state.value as? TimeclockState.PhotoPreview ?: return
        val localPath = previewState.localPath
        val staffId = previewState.staffId
        val staffName = previewState.staffName
        val photoType = previewState.photoType

        viewModelScope.launch {
            Timber.d("📸 Photo confirmed for $photoType, starting upload: $localPath")
            _state.value = TimeclockState.UploadingPhoto(localPath, 0f)

            // Get venue slug for Firebase Storage path
            val venueSlug = authRepository.getVenueSlug()
            if (venueSlug == null) {
                Timber.w("⚠️ Venue slug not available, using venueId as fallback for storage path")
            }
            val storagePath = venueSlug ?: authRepository.getVenueId() ?: "unknown-venue"
            val timestamp = System.currentTimeMillis()

            // Use appropriate upload method based on photo type
            val uploadResult = when (photoType) {
                PhotoType.CLOCK_IN_SELFIE -> verificationUploadManager.uploadClockInPhoto(
                    localPath = localPath,
                    venueSlug = storagePath,
                    staffId = staffId,
                    timestamp = timestamp,
                    onProgress = { progress ->
                        _state.value = TimeclockState.UploadingPhoto(localPath, progress)
                    }
                )
                PhotoType.FACADE -> verificationUploadManager.uploadFacadePhoto(
                    localPath = localPath,
                    venueSlug = storagePath,
                    staffId = staffId,
                    timestamp = timestamp,
                    onProgress = { progress ->
                        _state.value = TimeclockState.UploadingPhoto(localPath, progress)
                    }
                )
                PhotoType.DEPOSIT_VOUCHER -> verificationUploadManager.uploadDepositPhoto(
                    localPath = localPath,
                    venueSlug = storagePath,
                    staffId = staffId,
                    timestamp = timestamp,
                    onProgress = { progress ->
                        _state.value = TimeclockState.UploadingPhoto(localPath, progress)
                    }
                )
                PhotoType.CLOCK_OUT_SELFIE -> verificationUploadManager.uploadClockInPhoto(
                    localPath = localPath,
                    venueSlug = storagePath,
                    staffId = staffId,
                    timestamp = timestamp,
                    onProgress = { progress ->
                        _state.value = TimeclockState.UploadingPhoto(localPath, progress)
                    }
                )
            }

            uploadResult.fold(
                onSuccess = { downloadUrl ->
                    Timber.i("📸 $photoType photo uploaded: $downloadUrl")

                    // Clean up local file after successful upload (storage optimization for PAX devices)
                    try {
                        java.io.File(localPath).delete()
                        Timber.d("📸 Cleaned up local photo file")
                    } catch (e: Exception) {
                        Timber.w(e, "📸 Failed to clean up local photo file")
                    }

                    // Save URL to the correct pending field
                    when (photoType) {
                        PhotoType.CLOCK_IN_SELFIE -> pendingClockInPhotoUrl = downloadUrl
                        PhotoType.FACADE -> pendingFacadePhotoUrl = downloadUrl
                        PhotoType.DEPOSIT_VOUCHER -> pendingDepositPhotoUrl = downloadUrl
                        PhotoType.CLOCK_OUT_SELFIE -> pendingClockOutSelfieUrl = downloadUrl
                    }

                    // Continue processing the queue
                    processNextPhotoOrProceed()
                },
                onFailure = { error ->
                    Timber.e(error, "📸 Failed to upload $photoType photo")
                    _events.emit(TimeclockEvent.Error("Error al subir foto: ${error.message}"))
                    // Go back to RequiresPhoto state so user can retry
                    val canSkip = authRepository.getRole() in listOf(
                        StaffRole.ADMIN,
                        StaffRole.OWNER,
                        StaffRole.SUPERADMIN
                    )
                    _state.value = TimeclockState.RequiresPhoto(
                        staffId, staffName, canSkip, isClockOutFlow, photoType
                    )
                }
            )
        }
    }

    /**
     * Retake the photo - go back to camera preview.
     * Called when user clicks "Volver a tomar" on the preview screen.
     */
    fun retakePhoto() {
        val previewState = _state.value as? TimeclockState.PhotoPreview ?: return

        // Clean up the current photo file
        try {
            java.io.File(previewState.localPath).delete()
            Timber.d("📸 Cleaned up rejected photo file")
        } catch (e: Exception) {
            Timber.w(e, "📸 Failed to clean up photo file")
        }

        // Go back to camera
        Timber.d("📸 Retaking photo for staff: ${previewState.staffId}")
        _state.value = TimeclockState.CapturingPhoto(previewState.staffId)
    }

    /**
     * Skip photo verification (only for ADMIN/OWNER/SUPERADMIN).
     * Skips the current photo in the queue and continues processing.
     */
    fun skipPhoto() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        Timber.d("⏱️ Skipping $currentPhotoType photo verification (admin override)")

        viewModelScope.launch {
            // Don't save URL for skipped photo — proceed to next in queue
            processNextPhotoOrProceed()
        }
    }

    /**
     * Skip the current photo (voucher) with a reason, then continue to next in queue.
     * Only the voucher is skippable — checkout selfie remains mandatory.
     */
    fun skipCheckoutWithReason(reason: CheckoutSkipReason) {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        Timber.d("⏱️ Skipping $currentPhotoType with reason: ${reason.name} | remaining queue=${photoQueue.size}")

        // Store the skip reason for the clock-out call
        pendingSkipReason = reason.displayName
        pendingDepositPhotoUrl = null

        viewModelScope.launch {
            // Continue to next photo in queue (checkout selfie) instead of skipping everything
            processNextPhotoOrProceed()
        }
    }

    /**
     * Cancel photo capture and go back to Ready state.
     * Clears the entire photo queue and all pending URLs.
     */
    fun cancelPhotoCapture() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        Timber.d("⏱️ Photo capture cancelled | type=$currentPhotoType | isClockOut=$isClockOutFlow")

        // Clear all photo state
        photoQueue.clear()
        currentPhotoType = null
        pendingClockInPhotoUrl = null
        pendingFacadePhotoUrl = null
        pendingDepositPhotoUrl = null
        pendingClockOutSelfieUrl = null
        pendingSkipReason = null
        isClockOutFlow = false

        viewModelScope.launch {
            loadTimeclockStatus(staffId, staffName)
        }
    }

    /**
     * Perform the actual clock-in API call.
     *
     * **GPS Logic:** GPS is automatically captured when photo is required.
     * No separate GPS toggle - if photo enabled, GPS is captured with it.
     *
     * @param staffId Staff member ID
     * @param staffName Staff member name (for reloading status)
     * @param photoUrl Optional Firebase Storage URL of clock-in selfie
     * @param facadePhotoUrl Optional Firebase Storage URL of facade photo
     */
    private suspend fun performClockIn(
        staffId: String,
        staffName: String,
        photoUrl: String?,
        facadePhotoUrl: String?
    ) {
        _state.value = TimeclockState.Processing("Registrando entrada...")

        // GPS is automatically captured when photo is required (no separate toggle)
        val settings = tpvSettingsRepository.getCurrentSettings()
        val captureGps = settings.requireClockInPhoto

        // Capture GPS if photo verification is enabled
        var location: LocationResult? = null
        if (captureGps) {
            Timber.d("📍 GPS auto-capture for clock-in (photo enabled)...")

            // GPS COLD START STRATEGY
            // PAX devices need 30-60 seconds for first GPS fix (no Google Play Services)
            // Cold start = no recent fix = must download satellite almanac data
            try {
                kotlinx.coroutines.withTimeout(45000) {
                    location = locationService.getCurrentLocation(timeoutMs = 45000)
                }
            } catch (e: Exception) {
                Timber.w("📍 GPS capture timed out (45s), proceeding without location")
            }

            if (location != null) {
                Timber.d("📍 GPS captured: ${location?.latitude}, ${location?.longitude}")
            }
        }

        val result = timeEntryRepository.clockIn(
            venueId = venueId,
            staffId = staffId,
            pin = pin,
            checkInPhotoUrl = photoUrl,
            clockInLatitude = location?.latitude,
            clockInLongitude = location?.longitude,
            clockInAccuracy = location?.accuracy,
            facadePhotoUrl = facadePhotoUrl
        )

        result.fold(
            onSuccess = { entry ->
                Timber.i("✅ Clock in successful | hasPhoto=${photoUrl != null} | hasFacade=${facadePhotoUrl != null} | hasGps=${location != null}")
                clearPhotoState()
                _events.emit(TimeclockEvent.ClockInSuccess(entry))
                loadTimeclockStatus(staffId, staffName)
            },
            onFailure = { error ->
                Timber.e("❌ Clock in failed: ${error.message}")
                clearPhotoState()
                _events.emit(TimeclockEvent.Error(error.message ?: "Error al registrar entrada"))
                loadTimeclockStatus(staffId, staffName)
            }
        )
    }

    /**
     * Perform the actual clock-out API call.
     *
     * @param staffId Staff member ID
     * @param staffName Staff member name (for reloading status)
     * @param depositPhotoUrl Optional Firebase Storage URL of deposit voucher photo
     */
    private suspend fun performClockOut(
        staffId: String,
        staffName: String,
        depositPhotoUrl: String?,
        skipReason: String? = null
    ) {
        _state.value = TimeclockState.Processing("Registrando salida...")

        // GPS is automatically captured when deposit photo is required
        val settings = tpvSettingsRepository.getCurrentSettings()
        val captureGps = settings.requireDepositPhoto

        // Capture GPS if any photo verification is enabled
        var location: LocationResult? = null
        if (captureGps) {
            Timber.d("📍 GPS auto-capture for clock-out (photo enabled)...")

            // GPS COLD START STRATEGY
            // PAX devices need 30-60 seconds for first GPS fix (no Google Play Services)
            // Cold start = no recent fix = must download satellite almanac data
            try {
                kotlinx.coroutines.withTimeout(45000) {
                    location = locationService.getCurrentLocation(timeoutMs = 45000)
                }
            } catch (e: Exception) {
                Timber.w("📍 GPS capture timed out (45s), proceeding without location")
            }

            if (location != null) {
                Timber.d("📍 GPS captured: ${location?.latitude}, ${location?.longitude}")
            }
        }

        val result = timeEntryRepository.clockOut(
            venueId = venueId,
            staffId = staffId,
            pin = pin,
            checkOutPhotoUrl = pendingClockOutSelfieUrl,
            clockOutLatitude = location?.latitude,
            clockOutLongitude = location?.longitude,
            clockOutAccuracy = location?.accuracy,
            depositPhotoUrl = depositPhotoUrl,
            skipReason = skipReason
        )

        result.fold(
            onSuccess = { entry ->
                Timber.i("✅ Clock out successful, hours: ${entry.totalHours} | hasDeposit=${depositPhotoUrl != null} | hasGps=${location != null}")
                clearPhotoState()
                _events.emit(TimeclockEvent.ClockOutSuccess(entry, entry.totalHours))
                loadTimeclockStatus(staffId, staffName)
            },
            onFailure = { error ->
                Timber.e("❌ Clock out failed: ${error.message}")
                clearPhotoState()
                _events.emit(TimeclockEvent.Error(error.message ?: "Error al registrar salida"))
                loadTimeclockStatus(staffId, staffName)
            }
        )
    }

    /** Clear all photo queue state after clock-in/out completes or fails */
    private fun clearPhotoState() {
        photoQueue.clear()
        currentPhotoType = null
        pendingClockInPhotoUrl = null
        pendingFacadePhotoUrl = null
        pendingDepositPhotoUrl = null
        pendingClockOutSelfieUrl = null
        pendingSkipReason = null
        isClockOutFlow = false
    }

    /**
     * Start a break
     */
    fun startBreak() {
        val currentState = _state.value as? TimeclockState.Ready ?: return
        val timeEntryId = currentState.currentEntry?.id ?: return
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            _state.value = TimeclockState.Processing("Iniciando descanso...")

            val result = timeEntryRepository.startBreak(timeEntryId)

            result.fold(
                onSuccess = { entry ->
                    Timber.i("✅ Break started")
                    _events.emit(TimeclockEvent.BreakStarted(entry))
                    loadTimeclockStatus(staffId, staffName)
                },
                onFailure = { error ->
                    Timber.e("❌ Start break failed: ${error.message}")
                    _events.emit(TimeclockEvent.Error(error.message ?: "Error al iniciar descanso"))
                    loadTimeclockStatus(staffId, staffName)
                }
            )
        }
    }

    /**
     * End a break
     */
    fun endBreak() {
        val currentState = _state.value as? TimeclockState.Ready ?: return
        val timeEntryId = currentState.currentEntry?.id ?: return
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            _state.value = TimeclockState.Processing("Finalizando descanso...")

            val result = timeEntryRepository.endBreak(timeEntryId)

            result.fold(
                onSuccess = { entry ->
                    Timber.i("✅ Break ended")
                    _events.emit(TimeclockEvent.BreakEnded(entry))
                    loadTimeclockStatus(staffId, staffName)
                },
                onFailure = { error ->
                    Timber.e("❌ End break failed: ${error.message}")
                    _events.emit(TimeclockEvent.Error(error.message ?: "Error al finalizar descanso"))
                    loadTimeclockStatus(staffId, staffName)
                }
            )
        }
    }

    /**
     * Navigate to login (Done button)
     */
    fun navigateToLogin() {
        viewModelScope.launch {
            _events.emit(TimeclockEvent.NavigateToLogin)
        }
    }

    /**
     * Retry loading after error
     */
    fun retry() {
        verifyPinAndLoadStatus()
    }
}

/**
 * Staff info returned from PIN verification
 */
data class StaffInfo(
    val id: String,
    val name: String
)
