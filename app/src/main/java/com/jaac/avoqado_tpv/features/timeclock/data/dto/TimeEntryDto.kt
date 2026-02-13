package com.jaac.avoqado_tpv.features.timeclock.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryBreak
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
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
    val facadePhotoUrl: String? = null, // Firebase URL: store front photo at clock-in
    val depositPhotoUrl: String? = null, // Firebase URL: bank deposit voucher at clock-out
    val staff: StaffInfoDto?,
    val breaks: List<TimeEntryBreakDto>?,
    val autoClockOut: Boolean?, // Was this entry auto-closed by the system?
    val autoClockOutNote: String? // Reason for auto clock-out (e.g., "Hora de cierre: 03:00")
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
    val checkInPhotoUrl: String? = null, // Firebase Storage URL of clock-in photo (anti-fraud)
    val facadePhotoUrl: String? = null, // Firebase URL: store front photo at clock-in
    // GPS location verification fields
    // Backend expects: latitude, longitude, accuracy (not clockInLatitude, etc.)
    @SerializedName("latitude")
    val clockInLatitude: Double? = null,
    @SerializedName("longitude")
    val clockInLongitude: Double? = null,
    @SerializedName("accuracy")
    val clockInAccuracy: Float? = null
)

data class ClockOutRequestDto(
    val staffId: String,
    val pin: String,
    val checkOutPhotoUrl: String? = null, // Firebase Storage URL of clock-out photo (anti-fraud)
    val depositPhotoUrl: String? = null, // Firebase URL: bank deposit voucher at clock-out
    // GPS location verification fields
    // Backend expects: latitude, longitude, accuracy (not clockOutLatitude, etc.)
    @SerializedName("latitude")
    val clockOutLatitude: Double? = null,
    @SerializedName("longitude")
    val clockOutLongitude: Double? = null,
    @SerializedName("accuracy")
    val clockOutAccuracy: Float? = null
)

data class PinVerificationRequestDto(
    val pin: String
)

// ========== Mappers ==========

private val isoFormatter = DateTimeFormatter.ISO_DATE_TIME

fun TimeEntryDto.toDomain(zoneId: ZoneId = ZoneId.of("America/Mexico_City")): TimeEntry {
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
        clockInTime = parseDateTime(clockInTime, zoneId),
        clockOutTime = clockOutTime?.let { parseDateTime(it, zoneId) },
        jobRole = jobRole,
        totalHours = totalHours?.let { BigDecimal(it) },
        breakMinutes = breakMinutes ?: 0,
        status = parseStatus(status),
        checkInPhotoUrl = checkInPhotoUrl,
        facadePhotoUrl = facadePhotoUrl,
        depositPhotoUrl = depositPhotoUrl,
        breaks = breaks?.map { it.toDomain(zoneId) } ?: emptyList(),
        autoClockOut = autoClockOut ?: false,
        autoClockOutNote = autoClockOutNote
    )
}

fun TimeEntryBreakDto.toDomain(zoneId: ZoneId = ZoneId.of("America/Mexico_City")): TimeEntryBreak {
    return TimeEntryBreak(
        id = id,
        startTime = parseDateTime(startTime, zoneId),
        endTime = endTime?.let { parseDateTime(it, zoneId) }
    )
}

private fun parseDateTime(isoString: String, zoneId: ZoneId = ZoneId.of("America/Mexico_City")): LocalDateTime {
    return try {
        // 1. Try as Instant (standard ISO-8601 with Z) - Converts UTC to venue timezone
        val instant = Instant.parse(isoString)
        LocalDateTime.ofInstant(instant, zoneId)
    } catch (e: Exception) {
        try {
            // 2. Try with Offset (e.g. +05:00)
            val zdt = ZonedDateTime.parse(isoString, DateTimeFormatter.ISO_DATE_TIME)
            zdt.withZoneSameInstant(zoneId).toLocalDateTime()
        } catch (e2: Exception) {
            // 3. Fallback: Treat as Local Time (no conversion)
            val localIso = isoString.replace("Z", "")
            LocalDateTime.parse(localIso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }
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
