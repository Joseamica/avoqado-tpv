package com.jaac.avoqado_tpv.features.timeclock.data.dto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TimeEntryDtoTest {

    // Helper to access the private function via reflection or just copy logic for verification?
    // Since parseDateTime is private in the file, I can't call it directly.
    // However, I can test it via toDomain().
    
    @Test
    fun `toDomain converts UTC string to local time`() {
        // Arrange
        val utcString = "2025-01-07T12:00:00Z"
        val dto = TimeEntryDto(
            id = "1", staffId = "1", venueId = "1",
            clockInTime = utcString, clockOutTime = null,
            jobRole = null, totalHours = null, breakMinutes = 0,
            status = "CLOCKED_IN", notes = null, editedBy = null,
            checkInPhotoUrl = null, staff = null, breaks = null,
            autoClockOut = null, autoClockOutNote = null
        )

        // Act
        val domain = dto.toDomain()

        // Assert
        // toDomain() convierte con America/Mexico_City FIJO (su propio default, nunca el
        // reloj del aparato — regla del repo). Antes esta aserción recalculaba con
        // ZoneId.systemDefault(): pasaba en una Mac puesta en hora de México por
        // coincidencia, y reventaba en un runner de CI en UTC — verde local, rojo en CI,
        // sin que el código real tuviera nada malo.
        val expectedInstant = Instant.parse(utcString)
        val expectedLocal = LocalDateTime.ofInstant(expectedInstant, ZoneId.of("America/Mexico_City"))

        assertEquals(expectedLocal, domain.clockInTime)
    }

    @Test
    fun `toDomain handles local time string (no Z) by keeping it as is`() {
        // Arrange
        // This simulates a string that failed Instant parsing (no Z) but is valid ISO_LOCAL_DATE_TIME
        val localString = "2025-01-07T12:00:00"
        val dto = TimeEntryDto(
            id = "1", staffId = "1", venueId = "1",
            clockInTime = localString, clockOutTime = null,
            jobRole = null, totalHours = null, breakMinutes = 0,
            status = "CLOCKED_IN", notes = null, editedBy = null,
            checkInPhotoUrl = null, staff = null, breaks = null,
            autoClockOut = null, autoClockOutNote = null
        )

        // Act
        val domain = dto.toDomain()

        // Assert
        val expected = LocalDateTime.parse(localString)
        assertEquals(expected, domain.clockInTime)
    }
}
