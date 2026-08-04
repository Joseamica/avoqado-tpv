package com.jaac.avoqado_tpv.features.tables.data.api.dto

/**
 * "Personal en turno" — `GET /tpv/venues/{venueId}/time-entries/active` →
 * `timeEntryController.getCurrentlyClockedInStaff` (`tpv.routes.ts:3495`,
 * permiso `tpv-time-entries:read`). Fuente del picker de
 * [com.jaac.avoqado_tpv.features.tables.presentation.AssignWaiterSheet]
 * (Fase 2, `ASSIGN_ORDER`) — verificado 2026-08-03 contra
 * `time-entry.tpv.service.ts::getCurrentlyClockedInStaff`: cada fila es un
 * `TimeEntry` CLOCKED_IN u ON_BREAK con su `staff` anidado
 * (`{firstName, lastName, employeeCode, photoUrl}`).
 *
 * 🔴 Concern documentado (no un bug de este archivo, ver reporte de Fase 2):
 * `tpv-time-entries:read` es un permiso de MANAGER+ por default
 * (`avoqado-server/src/lib/permissions.ts`) — un WAITER, aunque SÍ puede
 * reproducir `ASSIGN_ORDER` (el gate real del intent es `orders:update`, que
 * WAITER tiene por default), no puede cargar ESTA lista para elegir a quién
 * reasignar. No existe hoy ningún endpoint bajo `/tpv` de personal legible
 * por WAITER — el que sí lo es (`GET /mobile/venues/{venueId}/staff`, vía
 * `teams:read`) vive en el namespace `/mobile`, fuera de alcance para la TPV
 * (regla dura: "La TPV habla solo con /api/v1/tpv"). Se usa este endpoint
 * porque es el ÚNICO ya reachable bajo `/tpv` sin tocar el server.
 */
data class ActiveStaffResponse(
    val success: Boolean = true,
    val data: List<ActiveStaffEntryDto> = emptyList(),
)

data class ActiveStaffEntryDto(
    val id: String = "",
    val staffId: String = "",
    /** CLOCKED_IN | ON_BREAK (Prisma `TimeEntryStatus`) — el server solo lista entradas activas, nunca CLOCKED_OUT. */
    val status: String = "CLOCKED_IN",
    val staff: ActiveStaffPersonDto = ActiveStaffPersonDto(),
)

data class ActiveStaffPersonDto(
    val firstName: String = "",
    val lastName: String = "",
    val employeeCode: String? = null,
    val photoUrl: String? = null,
)
