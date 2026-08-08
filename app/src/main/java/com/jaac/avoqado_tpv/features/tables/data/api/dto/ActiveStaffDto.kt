package com.jaac.avoqado_tpv.features.tables.data.api.dto

/**
 * "Personal asignable" — `GET /tpv/venues/{venueId}/staff/assignable` →
 * `timeEntryController.getAssignableStaff` (`tpv.routes.ts`, permiso
 * `orders:update`). Fuente del picker de
 * [com.jaac.avoqado_tpv.features.tables.presentation.AssignWaiterSheet]
 * (Fase 2, `ASSIGN_ORDER`).
 *
 * 🔴 Historia (Fix 2, "staff picker", 2026-08-07 — server
 * `.superpowers/sdd/2026-07-24-tpv-plan-b-superficie-tpv-server/zombie-table-and-staff-picker.md`):
 * antes apuntaba a `GET /tpv/venues/{venueId}/time-entries/active` →
 * `getCurrentlyClockedInStaff`, gateado `tpv-time-entries:read` (MANAGER+ por
 * default). `ASSIGN_ORDER` en sí sólo exige `orders:update` (WAITER lo tiene
 * por default), así que un mesero que SÍ podía reasignar no podía cargar la
 * lista de a quién. En vez de aflojar esa ruta (es la vista MANAGER del
 * reloj checador — historial de entradas/salidas de TODO el staff), el server
 * agregó esta ruta angosta con el MISMO permiso que la acción, y sin
 * timestamps de entrada/salida/receso — sólo lo que el picker necesita.
 *
 * `photoUrl` e `id` (de la fila `TimeEntry`) YA NO vienen en esta respuesta —
 * no se usaban en [com.jaac.avoqado_tpv.features.tables.data.TablesRepository.getActiveStaff]
 * (nunca se mapeaban a [com.jaac.avoqado_tpv.features.tables.domain.model.ActiveStaffMember]),
 * así que quitarlos del DTO es puramente aditivo/de limpieza.
 */
data class ActiveStaffResponse(
    val success: Boolean = true,
    val data: List<ActiveStaffEntryDto> = emptyList(),
)

data class ActiveStaffEntryDto(
    val staffId: String = "",
    /** CLOCKED_IN | ON_BREAK (Prisma `TimeEntryStatus`) — el server solo lista entradas activas, nunca CLOCKED_OUT. */
    val status: String = "CLOCKED_IN",
    val staff: ActiveStaffPersonDto = ActiveStaffPersonDto(),
)

data class ActiveStaffPersonDto(
    val firstName: String = "",
    val lastName: String = "",
    val employeeCode: String? = null,
)
