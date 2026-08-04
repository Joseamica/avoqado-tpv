package com.jaac.avoqado_tpv.features.tables.domain.model

/**
 * Un miembro del staff EN TURNO en este momento (clocked in u on break) — la
 * lista que puebla [com.jaac.avoqado_tpv.features.tables.presentation.AssignWaiterSheet]
 * (Fase 2, `ASSIGN_ORDER`). Ver KDoc de
 * [com.jaac.avoqado_tpv.features.tables.data.api.dto.ActiveStaffResponse]
 * para el origen y el concern de permisos.
 */
data class ActiveStaffMember(
    val staffId: String,
    val name: String,
    val onBreak: Boolean = false,
    val employeeCode: String? = null,
)
