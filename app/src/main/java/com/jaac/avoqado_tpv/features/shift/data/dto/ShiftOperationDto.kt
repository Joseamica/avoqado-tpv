package com.jaac.avoqado_tpv.features.shift.data.dto

import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction

/** Petición canónica local. T2 sólo agrega la pareja acreditada; jamás recalcula fecha, actor o conteo. */
data class LocalCloseShiftRequest(
    val venueId: String,
    val operationId: String,
    val closedAtLocal: String,
    val staffId: String,
    val openingOperationId: String?,
    val cashReconciliationAction: CashReconciliationAction?,
    val countedCash: String?,
    val isOfflineReplay: Boolean = true,
) {
    fun resolve(serverShiftId: String, cashDrawerSessionId: String? = null): CloseShiftRequest {
        require(serverShiftId.isNotBlank() && (cashDrawerSessionId == null || cashDrawerSessionId.isNotBlank()))
        return CloseShiftRequest(venueId, serverShiftId, cashReconciliationAction = cashReconciliationAction,
            countedCash = countedCash, operationId = operationId, closedAtLocal = closedAtLocal,
            staffId = staffId, openingOperationId = openingOperationId, cashDrawerSessionId = cashDrawerSessionId,
            isOfflineReplay = isOfflineReplay)
    }
}

/** GET de OPEN es parcial; ausencia nunca significa cero, ni APPLIED equivale a countApplied. */
data class ShiftOperationDto(
    val operationId: String? = null,
    val kind: String? = null,
    val state: String? = null,
    val shiftId: String? = null,
    val cashDrawerSessionId: String? = null,
    val openingDisposition: String? = null,
    val closedAtLocal: String? = null,
    val submittedCountedCash: String? = null,
    val countApplied: Boolean? = null,
    val pairStatus: String? = null,
)

data class ShiftOperationResponse(
    val success: Boolean,
    val operation: ShiftOperationDto? = null,
    val shift: ShiftOperationShiftDto? = null,
    val reconciliation: CashReconciliationDto? = null,
)

/** Proyección financiera acotada, distinta del DTO completo de historia/current. */
data class ShiftOperationShiftDto(
    val id: String? = null,
    val venueId: String? = null,
    val status: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val startingCash: String? = null,
    val endingCash: String? = null,
    val totalSales: String? = null,
    val totalTips: String? = null,
    val totalOrders: Int? = null,
    val cashDeclared: String? = null,
    val cashDifference: String? = null,
)
