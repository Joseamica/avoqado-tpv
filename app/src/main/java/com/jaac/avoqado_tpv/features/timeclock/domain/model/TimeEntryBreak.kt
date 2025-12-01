package com.jaac.avoqado_tpv.features.timeclock.domain.model

import java.time.LocalDateTime

/**
 * Represents a break period within a time entry
 */
data class TimeEntryBreak(
    val id: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime? // null = break still active
)
