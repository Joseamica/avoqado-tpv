package com.jaac.avoqado_tpv.features.timeclock.presentation

import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import java.math.BigDecimal

/**
 * UI State for the Timeclock screen
 */
sealed class TimeclockState {

    /**
     * Loading state - verifying PIN and fetching current status
     */
    data object Loading : TimeclockState()

    /**
     * Ready state - staff verified, showing current status
     * @param requireClockInToLogin If true and user has no active clock-in, the "LISTO" button
     *        should be hidden to prevent bypassing the clock-in requirement for login.
     */
    data class Ready(
        val staffId: String,
        val staffName: String,
        val currentEntry: TimeEntry?, // null = not clocked in
        val recentEntries: List<TimeEntry>,
        val totalHoursToday: BigDecimal,
        val requireClockInToLogin: Boolean = false
    ) : TimeclockState() {
        val isClockedIn: Boolean get() = currentEntry != null
        val isOnBreak: Boolean get() = currentEntry?.isOnBreak == true
        /** True if the user can proceed to login (has clock-in OR setting is disabled) */
        val canProceedToLogin: Boolean get() = !requireClockInToLogin || isClockedIn
    }

    /**
     * Processing state - executing an action (clock in/out, break)
     */
    data class Processing(val message: String) : TimeclockState()

    /**
     * Photo required - venue requires photo before clocking in/out
     * @param staffId Staff member attempting to clock in/out
     * @param staffName Staff member's name for display
     * @param canSkip Whether current user can skip (ADMIN/MANAGER)
     * @param isClockOut true if this is for clock-out, false for clock-in
     */
    data class RequiresPhoto(
        val staffId: String,
        val staffName: String,
        val canSkip: Boolean = false,
        val isClockOut: Boolean = false
    ) : TimeclockState()

    /**
     * Capturing photo - camera preview is active
     * @param staffId Staff member taking the photo
     */
    data class CapturingPhoto(val staffId: String) : TimeclockState()

    /**
     * Photo preview - showing captured photo for confirmation
     * User can confirm to proceed with upload or retake the photo.
     * @param staffId Staff member who took the photo
     * @param staffName Staff member's name for display
     * @param localPath Local file path of the captured photo
     * @param isClockOut true if this is for clock-out, false for clock-in
     */
    data class PhotoPreview(
        val staffId: String,
        val staffName: String,
        val localPath: String,
        val isClockOut: Boolean = false
    ) : TimeclockState()

    /**
     * Uploading photo - sending captured photo to Firebase Storage
     * @param localPath Local file path of the captured photo
     * @param progress Upload progress (0.0 to 1.0)
     */
    data class UploadingPhoto(
        val localPath: String,
        val progress: Float = 0f
    ) : TimeclockState()

    /**
     * Error state - something went wrong
     */
    data class Error(val message: String) : TimeclockState()

    /**
     * PIN verification failed
     */
    data class InvalidPin(val message: String = "PIN incorrecto") : TimeclockState()
}

/**
 * One-time events from the ViewModel
 */
sealed class TimeclockEvent {
    data class ClockInSuccess(val entry: TimeEntry) : TimeclockEvent()
    data class ClockOutSuccess(val entry: TimeEntry, val hoursWorked: BigDecimal?) : TimeclockEvent()
    data class BreakStarted(val entry: TimeEntry) : TimeclockEvent()
    data class BreakEnded(val entry: TimeEntry) : TimeclockEvent()
    data class Error(val message: String) : TimeclockEvent()
    data object NavigateToLogin : TimeclockEvent()
}
