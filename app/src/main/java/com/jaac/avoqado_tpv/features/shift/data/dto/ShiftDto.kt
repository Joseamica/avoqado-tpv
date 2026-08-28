package com.jaac.avoqado_tpv.features.shift.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.shift.domain.CashDrawerSummary
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationOutcome
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationResult
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import java.math.BigDecimal

/**
 * Shift DTOs
 *
 * Data transfer objects for shift management endpoints.
 * Maps to backend endpoints:
 * - POST /tpv/venues/:venueId/shifts/open
 * - POST /tpv/venues/:venueId/shifts/:shiftId/close
 * - GET /tpv/venues/:venueId/shift
 */

/**
 * Wrapper for GET /shift endpoint response
 *
 * Backend returns: {"shift": ShiftDto | null}
 * This wrapper handles the case when no shift is active (shift = null)
 */
data class CurrentShiftResponse(
    @SerializedName("shift")
    val shift: ShiftDto?
)

/**
 * Wrapper for POST /shifts/open and POST /shifts/:id/close endpoint responses
 *
 * Backend returns: {"success": true, "data": ShiftDto}
 * This is the standard success response format used by the backend.
 */
data class ShiftResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: ShiftDto,

    /** Additive, request-scoped close result. Missing on open/old-server responses. */
    @SerializedName("reconciliation")
    val reconciliation: CashReconciliationDto? = null
)

data class CashReconciliationDto(
    @SerializedName("outcome")
    val outcome: CashReconciliationOutcome,

    @SerializedName("countedCash")
    val countedCash: String? = null,

    @SerializedName("cashDifference")
    val cashDifference: String? = null,
    /** Fase 5: arqueo del cajón físico que cubrió el turno. Opcional — el server lo omite si no hay caja. */
    @SerializedName("cashDrawer")
    val cashDrawer: CashDrawerSummaryDto? = null
)

data class CashDrawerSummaryDto(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("status") val status: String? = null,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("openedAt") val openedAt: String? = null,
    @SerializedName("closedAt") val closedAt: String? = null,
    @SerializedName("expectedAmount") val expectedAmount: Double,
    @SerializedName("counted") val counted: Boolean,
    @SerializedName("actualAmount") val actualAmount: Double? = null,
    @SerializedName("overShort") val overShort: Double? = null
)

/**
 * Wrapper for GET /shifts endpoint response (shift history)
 *
 * Backend returns: {"success": true, "data": [ShiftDto, ...], "meta": {...}}
 * This handles the paginated list response format.
 */
data class ShiftHistoryResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: List<ShiftDto>,

    @SerializedName("meta")
    val meta: PaginationMeta?
)

/**
 * Pagination metadata for shift history
 */
data class PaginationMeta(
    @SerializedName("totalRecords")
    val totalRecords: Int,

    @SerializedName("totalPages")
    val totalPages: Int,

    @SerializedName("currentPage")
    val currentPage: Int,

    @SerializedName("pageSize")
    val pageSize: Int
)

/**
 * Request to open a new shift
 *
 * @param venueId Venue identifier
 * @param staffId Staff member opening the shift
 * @param startingCash Initial cash amount in drawer
 * @param stationId Optional POS station identifier
 */
data class OpenShiftRequest(
    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("staffId")
    val staffId: String,

    @SerializedName("startingCash")
    val startingCash: Double,

    @SerializedName("stationId")
    val stationId: String? = null
)

/**
 * Request to close an existing shift
 *
 * @param venueId Venue identifier
 * @param shiftId Shift identifier to close
 * @param closeData Legacy declaration payload retained for old callers and integrations
 * @param cashReconciliationAction Explicit additive COUNTED/SKIPPED action; null is legacy
 * @param countedCash Canonical physical drawer total, present only for COUNTED
 */
data class CloseShiftRequest(
    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("shiftId")
    val shiftId: String,

    @SerializedName("closeData")
    val closeData: CloseShiftData? = null,

    /** Null keeps the exact legacy/kiosk wire shape. */
    @SerializedName("cashReconciliationAction")
    val cashReconciliationAction: CashReconciliationAction? = null,

    /** Canonical Decimal(10,2) plain string; present only with COUNTED. */
    @SerializedName("countedCash")
    val countedCash: String? = null
)

/**
 * Legacy close declaration shape. Do not reinterpret these amounts as the new physical count.
 */
data class CloseShiftData(
    @SerializedName("cashDeclared")
    val cashDeclared: Double? = null,

    @SerializedName("cardDeclared")
    val cardDeclared: Double? = null,

    @SerializedName("vouchersDeclared")
    val vouchersDeclared: Double? = null,

    @SerializedName("otherDeclared")
    val otherDeclared: Double? = null,

    @SerializedName("notes")
    val notes: String? = null
)

/**
 * Response DTO for shift data
 *
 * Maps 1:1 with backend Shift model.
 * Contains both legacy fields and new automatic calculation fields.
 */
data class ShiftDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("staffId")
    val staffId: String,

    @SerializedName("startTime")
    val startTime: String,

    @SerializedName("endTime")
    val endTime: String?,

    @SerializedName("status")
    val status: String,

    @SerializedName("startingCash")
    val startingCash: String,  // Decimal as string from Prisma

    @SerializedName("endingCash")
    val endingCash: String?,

    @SerializedName("totalSales")
    val totalSales: String,

    @SerializedName("totalTips")
    val totalTips: String,

    @SerializedName("totalOrders")
    val totalOrders: Int,

    // NEW AUTOMATIC FIELDS (from backend enhancement)
    @SerializedName("totalCashPayments")
    val totalCashPayments: String,

    @SerializedName("totalCardPayments")
    val totalCardPayments: String,

    @SerializedName("totalVoucherPayments")
    val totalVoucherPayments: String,

    @SerializedName("totalOtherPayments")
    val totalOtherPayments: String,

    @SerializedName("totalProductsSold")
    val totalProductsSold: Int,

    // Staff relationship (for display)
    @SerializedName("staff")
    val staff: StaffDto?,

    /** Additive fields: absent on open shifts and old backend versions. */
    @SerializedName("cashDeclared")
    val cashDeclared: String? = null,

    @SerializedName("cashDifference")
    val cashDifference: String? = null
)

/**
 * Simplified Staff DTO for display
 */
data class StaffDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("firstName")
    val firstName: String,

    @SerializedName("lastName")
    val lastName: String
)

/**
 * Mapper: ShiftDto → Shift (domain model)
 */
fun ShiftDto.toDomain(): Shift {
    return Shift(
        id = id,
        venueId = venueId,
        staffId = staffId,
        staffName = staff?.let { "${it.firstName} ${it.lastName}" } ?: "Unknown",
        startTime = startTime,
        endTime = endTime,
        status = when (status.uppercase()) {
            "OPEN" -> ShiftStatus.OPEN
            "CLOSING" -> ShiftStatus.CLOSING
            "CLOSED" -> ShiftStatus.CLOSED
            else -> ShiftStatus.CLOSED
        },
        startingCash = BigDecimal(startingCash),
        endingCash = endingCash?.let { BigDecimal(it) },
        totalSales = BigDecimal(totalSales),
        totalTips = BigDecimal(totalTips),
        totalOrders = totalOrders,
        totalCashPayments = BigDecimal(totalCashPayments),
        totalCardPayments = BigDecimal(totalCardPayments),
        totalVoucherPayments = BigDecimal(totalVoucherPayments),
        totalOtherPayments = BigDecimal(totalOtherPayments),
        totalProductsSold = totalProductsSold,
        durationMinutes = calculateDurationMinutes(startTime, endTime),
        cashDeclared = cashDeclared?.let(::BigDecimal),
        cashDifference = cashDifference?.let(::BigDecimal)
    )
}

fun CashReconciliationDto.toDomain(): CashReconciliationResult = CashReconciliationResult(
    outcome = outcome,
    cashDeclared = countedCash?.let(::BigDecimal),
    cashDifference = cashDifference?.let(::BigDecimal),
    cashDrawer = cashDrawer?.let {
        CashDrawerSummary(
            sessionId = it.sessionId,
            status = it.status,
            deviceName = it.deviceName,
            openedAt = it.openedAt,
            closedAt = it.closedAt,
            expectedAmount = BigDecimal.valueOf(it.expectedAmount),
            counted = it.counted,
            actualAmount = it.actualAmount?.let(BigDecimal::valueOf),
            overShort = it.overShort?.let(BigDecimal::valueOf)
        )
    }
)

/**
 * Calculate shift duration in minutes
 *
 * - If shift is OPEN (endTime = null): Calculate duration from startTime to NOW
 * - If shift is CLOSED (endTime != null): Calculate duration from startTime to endTime
 */
private fun calculateDurationMinutes(startTime: String, endTime: String?): Int? {
    return try {
        val start = java.time.Instant.parse(startTime)
        val end = if (endTime != null) {
            java.time.Instant.parse(endTime)
        } else {
            java.time.Instant.now()  // For open shifts, calculate until now
        }
        val duration = java.time.Duration.between(start, end).toMinutes().toInt()

        // Log warning if duration is 0 or negative (data quality issue)
        if (duration <= 0) {
            timber.log.Timber.w("⚠️ Invalid duration: $duration minutes (start: $startTime, end: ${endTime ?: "NOW"})")
        }

        duration
    } catch (e: Exception) {
        timber.log.Timber.e(e, "❌ Failed to calculate duration (start: $startTime, end: $endTime)")
        null
    }
}
