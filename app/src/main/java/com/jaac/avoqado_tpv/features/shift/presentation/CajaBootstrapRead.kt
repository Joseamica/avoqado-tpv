package com.jaac.avoqado_tpv.features.shift.presentation

import com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus

/** Lectura de presentación, sin red ni mutaciones. Los callers conservan su cerca de contexto. */
internal data class CajaBootstrapRead(val shift: Shift?, val local: CajaLocalSnapshot?) {
    val presentation: CajaLocalPresentation? get() = local?.let { CajaLocalPresentation(it.row, it.summary) }
    val title: String get() = presentation?.title ?: when {
        shift == null || shift.status != ShiftStatus.OPEN -> "Sin caja abierta"
        shift.financialTotalsKnown == false && shift.localShiftSessionId == null -> "Última caja abierta conocida"
        else -> "Caja abierta"
    }
}

internal suspend fun readCajaBootstrap(
    repository: ShiftRepository,
    venueId: String,
    vigente: () -> Boolean = { true },
): CajaBootstrapRead {
    if (!vigente()) return CajaBootstrapRead(null, null)
    val local = repository.leerCajaLocal(venueId)
    if (!vigente()) return CajaBootstrapRead(null, null)
    if (local != null) return CajaBootstrapRead(local.shift, local)
    val cached = repository.getCachedOpenShift(venueId)
    if (!vigente()) return CajaBootstrapRead(null, null)
    // El cache legacy no contiene fondo ni totales completos. Nunca se altera su JSON/Gson.
    // Room puede haber creado, adoptado o cerrado B durante la lectura; sus hechos prevalecen.
    val fresh = repository.leerCajaLocal(venueId)
    if (!vigente()) return CajaBootstrapRead(null, null)
    return if (fresh != null) CajaBootstrapRead(fresh.shift, fresh)
    else CajaBootstrapRead(cached?.copy(financialTotalsKnown = false), null)
}
