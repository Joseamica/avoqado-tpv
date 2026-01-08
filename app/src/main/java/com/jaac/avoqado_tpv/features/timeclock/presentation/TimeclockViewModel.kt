package com.jaac.avoqado_tpv.features.timeclock.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
import com.jaac.avoqado_tpv.core.location.LocationResult
import com.jaac.avoqado_tpv.core.location.LocationService
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
    private val verificationUploadManager: VerificationUploadManager,
    private val locationService: LocationService,
    private val tpvSettingsRepository: TpvSettingsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val venueId: String = savedStateHandle.get<String>("venueId") ?: ""
    private val pin: String = savedStateHandle.get<String>("pin") ?: ""

    private val _state = MutableStateFlow<TimeclockState>(TimeclockState.Loading)
    val state: StateFlow<TimeclockState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TimeclockEvent>()
    val events: SharedFlow<TimeclockEvent> = _events.asSharedFlow()

    // Store staff info after verification
    private var currentStaffId: String? = null
    private var currentStaffName: String? = null

    // Photo flow state (anti-fraud)
    private var pendingClockInPhotoUrl: String? = null
    private var pendingClockOutPhotoUrl: String? = null
    private var isClockOutPhotoFlow: Boolean = false // Tracks if we're in clock-out photo flow

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
            // Get today's date range for filtering
            val today = LocalDate.now()
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
                        totalHoursToday = totalHours
                    )
                },
                onFailure = { error ->
                    // If no entries found, still show Ready state (not clocked in)
                    _state.value = TimeclockState.Ready(
                        staffId = staffId,
                        staffName = staffName,
                        currentEntry = null,
                        recentEntries = emptyList(),
                        totalHoursToday = BigDecimal.ZERO
                    )
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading timeclock status")
            _state.value = TimeclockState.Error("Error cargando estado")
        }
    }

    /**
     * Clock in the current staff member.
     *
     * **Photo Verification Flow (anti-fraud):**
     * 1. Check if TpvSettings requires clock-in photo (requireClockInPhoto)
     * 2. If required and no photo captured → Show RequiresPhoto state
     * 3. If required and user is ADMIN/MANAGER/OWNER → Allow skip option
     * 4. After photo captured or skipped → Proceed with actual clock-in
     * 5. GPS is automatically captured when photo is required (no separate toggle)
     */
    fun clockIn() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            // Check TpvSettings for clock-in photo requirement (configured via Dashboard)
            val settings = tpvSettingsRepository.getCurrentSettings()
            val requirePhoto = settings.requireClockInPhoto

            // Debug logging
            Timber.d("⏱️ [CLOCK-IN] TpvSettings check:")
            Timber.d("   - requireClockInPhoto: ${settings.requireClockInPhoto}")
            Timber.d("   - requirePhoto (final): $requirePhoto")

            if (requirePhoto && pendingClockInPhotoUrl == null) {
                // Photo required but not yet captured
                // Check if current user can skip (only ADMIN/OWNER/SUPERADMIN - not MANAGER or below)
                val currentRole = authRepository.getRole()
                val canSkip = currentRole in listOf(
                    StaffRole.ADMIN,
                    StaffRole.OWNER,
                    StaffRole.SUPERADMIN
                )

                Timber.d("⏱️ Photo required for clock-in (TpvSettings) | canSkip=$canSkip | role=$currentRole")
                _state.value = TimeclockState.RequiresPhoto(
                    staffId = staffId,
                    staffName = staffName,
                    canSkip = canSkip,
                    isClockOut = false
                )
                return@launch
            }

            // Proceed with clock-in (with or without photo)
            performClockIn(staffId, staffName, pendingClockInPhotoUrl)
        }
    }

    /**
     * Start camera capture for clock-in photo.
     * Called when user clicks "Take Photo" button.
     */
    fun startPhotoCapture() {
        val staffId = currentStaffId ?: return
        Timber.d("📸 Starting clock-in photo capture for staff: $staffId")
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
        val isClockOut = isClockOutPhotoFlow

        val actionType = if (isClockOut) "clock-out" else "clock-in"
        Timber.d("📸 Photo captured for $actionType, showing preview: $localPath")

        // Show photo preview for confirmation
        _state.value = TimeclockState.PhotoPreview(
            staffId = staffId,
            staffName = staffName,
            localPath = localPath,
            isClockOut = isClockOut
        )
    }

    /**
     * Confirm the captured photo and proceed with upload.
     * Called when user clicks "Confirmar" on the preview screen.
     */
    fun confirmPhoto() {
        val previewState = _state.value as? TimeclockState.PhotoPreview ?: return
        val localPath = previewState.localPath
        val staffId = previewState.staffId
        val staffName = previewState.staffName
        val isClockOut = previewState.isClockOut

        viewModelScope.launch {
            val actionType = if (isClockOut) "clock-out" else "clock-in"
            Timber.d("📸 Photo confirmed for $actionType, starting upload: $localPath")
            _state.value = TimeclockState.UploadingPhoto(localPath, 0f)

            // Get venue slug for Firebase Storage path
            // Use venueId as fallback (at least it's unique and identifiable)
            val venueSlug = authRepository.getVenueSlug()
            if (venueSlug == null) {
                Timber.w("⚠️ Venue slug not available, using venueId as fallback for storage path")
            }
            val storagePath = venueSlug ?: authRepository.getVenueId() ?: "unknown-venue"
            val timestamp = System.currentTimeMillis()

            // Use appropriate upload method based on flow type
            val uploadResult = if (isClockOut) {
                verificationUploadManager.uploadClockOutPhoto(
                    localPath = localPath,
                    venueSlug = storagePath,
                    staffId = staffId,
                    timestamp = timestamp,
                    onProgress = { progress ->
                        _state.value = TimeclockState.UploadingPhoto(localPath, progress)
                    }
                )
            } else {
                verificationUploadManager.uploadClockInPhoto(
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
                    Timber.i("📸 $actionType photo uploaded: $downloadUrl")

                    // Clean up local file after successful upload (storage optimization for PAX devices)
                    try {
                        java.io.File(localPath).delete()
                        Timber.d("📸 Cleaned up local photo file")
                    } catch (e: Exception) {
                        Timber.w(e, "📸 Failed to clean up local photo file")
                    }

                    // Auto-proceed with clock-in/out after successful upload
                    if (isClockOut) {
                        pendingClockOutPhotoUrl = downloadUrl
                        performClockOut(staffId, staffName, downloadUrl)
                    } else {
                        pendingClockInPhotoUrl = downloadUrl
                        performClockIn(staffId, staffName, downloadUrl)
                    }
                },
                onFailure = { error ->
                    Timber.e(error, "📸 Failed to upload $actionType photo")
                    _events.emit(TimeclockEvent.Error("Error al subir foto: ${error.message}"))
                    // Go back to RequiresPhoto state so user can retry
                    val canSkip = authRepository.getRole() in listOf(
                        StaffRole.ADMIN,
                        StaffRole.OWNER,
                        StaffRole.SUPERADMIN
                    )
                    _state.value = TimeclockState.RequiresPhoto(staffId, staffName, canSkip, isClockOut)
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
     * Proceeds with clock-in/out without photo.
     */
    fun skipPhoto() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return
        val isClockOut = isClockOutPhotoFlow

        val actionType = if (isClockOut) "clock-out" else "clock-in"
        Timber.d("⏱️ Skipping $actionType photo verification (admin override)")

        viewModelScope.launch {
            if (isClockOut) {
                performClockOut(staffId, staffName, null)
            } else {
                performClockIn(staffId, staffName, null)
            }
        }
    }

    /**
     * Cancel photo capture and go back to Ready state.
     */
    fun cancelPhotoCapture() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        val actionType = if (isClockOutPhotoFlow) "clock-out" else "clock-in"
        Timber.d("⏱️ $actionType photo capture cancelled")

        // Clear photo state
        if (isClockOutPhotoFlow) {
            pendingClockOutPhotoUrl = null
            isClockOutPhotoFlow = false
        } else {
            pendingClockInPhotoUrl = null
        }

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
     * @param photoUrl Optional Firebase Storage URL of clock-in photo
     */
    private suspend fun performClockIn(staffId: String, staffName: String, photoUrl: String?) {
        _state.value = TimeclockState.Processing("Registrando entrada...")

        // GPS is automatically captured when photo is required (no separate toggle)
        val settings = tpvSettingsRepository.getCurrentSettings()
        val captureGps = settings.requireClockInPhoto

        // Capture GPS if photo verification is enabled
        var location: LocationResult? = null
        if (captureGps) {
            Timber.d("📍 GPS auto-capture for clock-in (photo enabled)...")
            
            // BEST EFFORT NON-BLOCKING STRATEGY
            // Try to get location for max 3 seconds. If not found, proceed without it.
            try {
                // Use a short timeout for the UI blocking part
                kotlinx.coroutines.withTimeout(3000) {
                    // Internal timeout slightly shorter to allow clean return
                    location = locationService.getCurrentLocation(timeoutMs = 2500)
                }
            } catch (e: Exception) {
                Timber.w("📍 GPS capture timed out (3s), proceeding without location")
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
            clockInAccuracy = location?.accuracy
        )

        result.fold(
            onSuccess = { entry ->
                Timber.i("✅ Clock in successful | hasPhoto=${photoUrl != null} | hasGps=${location != null}")
                pendingClockInPhotoUrl = null // Clear for next clock-in
                _events.emit(TimeclockEvent.ClockInSuccess(entry))
                loadTimeclockStatus(staffId, staffName)
            },
            onFailure = { error ->
                Timber.e("❌ Clock in failed: ${error.message}")
                pendingClockInPhotoUrl = null // Clear on failure
                _events.emit(TimeclockEvent.Error(error.message ?: "Error al registrar entrada"))
                // Reload status to refresh UI
                loadTimeclockStatus(staffId, staffName)
            }
        )
    }

    /**
     * Clock out the current staff member.
     *
     * **Photo Verification Flow (anti-fraud):**
     * 1. Check if TpvSettings requires clock-out photo (requireClockOutPhoto)
     * 2. If required and no photo captured → Show RequiresPhoto state (isClockOut=true)
     * 3. If required and user is ADMIN/MANAGER/OWNER → Allow skip option
     * 4. After photo captured or skipped → Proceed with actual clock-out
     * 5. GPS is automatically captured when photo is required (no separate toggle)
     */
    fun clockOut() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            // Check TpvSettings for clock-out photo requirement (configured via Dashboard)
            val settings = tpvSettingsRepository.getCurrentSettings()
            val requirePhoto = settings.requireClockOutPhoto

            // Debug logging
            Timber.d("⏱️ [CLOCK-OUT] TpvSettings check:")
            Timber.d("   - requireClockOutPhoto: ${settings.requireClockOutPhoto}")
            Timber.d("   - requirePhoto (final): $requirePhoto")

            if (requirePhoto && pendingClockOutPhotoUrl == null) {
                // Photo required but not yet captured
                // Check if current user can skip (only ADMIN/OWNER/SUPERADMIN - not MANAGER or below)
                val currentRole = authRepository.getRole()
                val canSkip = currentRole in listOf(
                    StaffRole.ADMIN,
                    StaffRole.OWNER,
                    StaffRole.SUPERADMIN
                )

                Timber.d("⏱️ Photo required for clock-out (TpvSettings) | canSkip=$canSkip | role=$currentRole")
                isClockOutPhotoFlow = true
                _state.value = TimeclockState.RequiresPhoto(
                    staffId = staffId,
                    staffName = staffName,
                    canSkip = canSkip,
                    isClockOut = true
                )
                return@launch
            }

            // Proceed with clock-out (with or without photo)
            performClockOut(staffId, staffName, pendingClockOutPhotoUrl)
        }
    }

    /**
     * Perform the actual clock-out API call.
     *
     * **GPS Logic:** GPS is automatically captured when photo is required.
     * No separate GPS toggle - if photo enabled, GPS is captured with it.
     *
     * @param staffId Staff member ID
     * @param staffName Staff member name (for reloading status)
     * @param photoUrl Optional Firebase Storage URL of clock-out photo
     */
    private suspend fun performClockOut(staffId: String, staffName: String, photoUrl: String?) {
        _state.value = TimeclockState.Processing("Registrando salida...")

        // GPS is automatically captured when photo is required (no separate toggle)
        val settings = tpvSettingsRepository.getCurrentSettings()
        val captureGps = settings.requireClockOutPhoto

        // Capture GPS if photo verification is enabled
        var location: LocationResult? = null
        if (captureGps) {
            Timber.d("📍 GPS auto-capture for clock-out (photo enabled)...")
            
            // BEST EFFORT NON-BLOCKING STRATEGY
            try {
                kotlinx.coroutines.withTimeout(3000) {
                    location = locationService.getCurrentLocation(timeoutMs = 2500)
                }
            } catch (e: Exception) {
                Timber.w("📍 GPS capture timed out (3s), proceeding without location")
            }
            
            if (location != null) {
                Timber.d("📍 GPS captured: ${location?.latitude}, ${location?.longitude}")
            }
        }

        val result = timeEntryRepository.clockOut(
            venueId = venueId,
            staffId = staffId,
            pin = pin,
            checkOutPhotoUrl = photoUrl,
            clockOutLatitude = location?.latitude,
            clockOutLongitude = location?.longitude,
            clockOutAccuracy = location?.accuracy
        )

        result.fold(
            onSuccess = { entry ->
                Timber.i("✅ Clock out successful, hours: ${entry.totalHours} | hasPhoto=${photoUrl != null} | hasGps=${location != null}")
                pendingClockOutPhotoUrl = null // Clear for next clock-out
                isClockOutPhotoFlow = false
                _events.emit(TimeclockEvent.ClockOutSuccess(entry, entry.totalHours))
                loadTimeclockStatus(staffId, staffName)
            },
            onFailure = { error ->
                Timber.e("❌ Clock out failed: ${error.message}")
                pendingClockOutPhotoUrl = null
                isClockOutPhotoFlow = false
                _events.emit(TimeclockEvent.Error(error.message ?: "Error al registrar salida"))
                loadTimeclockStatus(staffId, staffName)
            }
        )
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
