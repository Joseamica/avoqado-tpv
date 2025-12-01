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
     */
    data class Ready(
        val staffId: String,
        val staffName: String,
        val currentEntry: TimeEntry?, // null = not clocked in
        val recentEntries: List<TimeEntry>,
        val totalHoursToday: BigDecimal
    ) : TimeclockState() {
        val isClockedIn: Boolean get() = currentEntry != null
        val isOnBreak: Boolean get() = currentEntry?.isOnBreak == true
    }

    /**
     * Processing state - executing an action (clock in/out, break)
     */
    data class Processing(val message: String) : TimeclockState()

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
