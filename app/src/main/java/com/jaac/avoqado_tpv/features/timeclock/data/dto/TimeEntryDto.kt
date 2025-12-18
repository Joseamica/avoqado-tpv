package com.jaac.avoqado_tpv.features.timeclock.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryBreak
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ========== Response DTOs ==========

data class TimeEntryDto(
    val id: String,
    val staffId: String,
    val venueId: String,
    val clockInTime: String,
    val clockOutTime: String?,
    val jobRole: String?,
    val totalHours: String?,
    val breakMinutes: Int?,
    val status: String,
    val notes: String?,
    val editedBy: String?,
    val checkInPhotoUrl: String?, // Firebase Storage URL of clock-in photo (anti-fraud)
    val staff: StaffInfoDto?,
    val breaks: List<TimeEntryBreakDto>?
)

data class StaffInfoDto(
    val firstName: String?,
    val lastName: String?,
    val employeeCode: String?
)

data class TimeEntryBreakDto(
    val id: String,
    val startTime: String,
    val endTime: String?
)

data class TimeEntryResponseDto(
    val success: Boolean,
    val data: TimeEntryDto?
)

data class TimeEntriesListResponseDto(
    val success: Boolean,
    val data: List<TimeEntryDto>?,
    val meta: PaginationMetaDto?
)

data class PaginationMetaDto(
    val total: Int,
    val limit: Int,
    val offset: Int
)

// ========== Request DTOs ==========

data class ClockInRequestDto(
    val staffId: String,
    val pin: String,
    val jobRole: String? = null,
    val checkInPhotoUrl: String? = null // Firebase Storage URL of clock-in photo (anti-fraud)
)

data class ClockOutRequestDto(
    val staffId: String,
    val pin: String
)

data class PinVerificationRequestDto(
    val pin: String
)

// ========== Mappers ==========

private val isoFormatter = DateTimeFormatter.ISO_DATE_TIME

fun TimeEntryDto.toDomain(): TimeEntry {
    val staffName = if (staff != null) {
        listOfNotNull(staff.firstName, staff.lastName).joinToString(" ").ifEmpty { "Empleado" }
    } else {
        "Empleado"
    }

    return TimeEntry(
        id = id,
        staffId = staffId,
        staffName = staffName,
        venueId = venueId,
        clockInTime = parseDateTime(clockInTime),
        clockOutTime = clockOutTime?.let { parseDateTime(it) },
        jobRole = jobRole,
        totalHours = totalHours?.let { BigDecimal(it) },
        breakMinutes = breakMinutes ?: 0,
        status = parseStatus(status),
        checkInPhotoUrl = checkInPhotoUrl,
        breaks = breaks?.map { it.toDomain() } ?: emptyList()
    )
}

fun TimeEntryBreakDto.toDomain(): TimeEntryBreak {
    return TimeEntryBreak(
        id = id,
        startTime = parseDateTime(startTime),
        endTime = endTime?.let { parseDateTime(it) }
    )
}

private fun parseDateTime(isoString: String): LocalDateTime {
    return try {
        LocalDateTime.parse(isoString, isoFormatter)
    } catch (e: Exception) {
        // Handle formats like "2025-01-22T18:30:00.000Z"
        LocalDateTime.parse(isoString.replace("Z", ""), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }
}

private fun parseStatus(status: String): TimeEntryStatus {
    return when (status.uppercase()) {
        "CLOCKED_IN" -> TimeEntryStatus.CLOCKED_IN
        "ON_BREAK" -> TimeEntryStatus.ON_BREAK
        "CLOCKED_OUT" -> TimeEntryStatus.CLOCKED_OUT
        "ADMIN_EDITED" -> TimeEntryStatus.ADMIN_EDITED
        else -> TimeEntryStatus.CLOCKED_IN
    }
}
