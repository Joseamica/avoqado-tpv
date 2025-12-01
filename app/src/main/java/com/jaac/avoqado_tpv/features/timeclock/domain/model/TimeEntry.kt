package com.jaac.avoqado_tpv.features.timeclock.domain.model

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Represents an employee's clock in/out record
 */
data class TimeEntry(
    val id: String,
    val staffId: String,
    val staffName: String,
    val venueId: String,
    val clockInTime: LocalDateTime,
    val clockOutTime: LocalDateTime?,
    val jobRole: String?,
    val totalHours: BigDecimal?,
    val breakMinutes: Int,
    val status: TimeEntryStatus,
    val breaks: List<TimeEntryBreak>
) {
    /**
     * Check if the employee is currently on a break
     */
    val isOnBreak: Boolean
        get() = status == TimeEntryStatus.ON_BREAK

    /**
     * Check if the employee is currently clocked in (working or on break)
     */
    val isClockedIn: Boolean
        get() = status == TimeEntryStatus.CLOCKED_IN || status == TimeEntryStatus.ON_BREAK

    /**
     * Get the active break if any
     */
    val activeBreak: TimeEntryBreak?
        get() = breaks.find { it.endTime == null }
}
