package com.jaac.avoqado_tpv.features.remote_command.domain

import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase

/**
 * D4 (encargo dd4185): bajar de versión borra la base entera (`fallbackToDestructiveMigrationOnDowngrade`), y con ella el
 * efectivo y las rondas de Mesas hechas sin red y las cajas que no han subido. Lo preguntan los dos caminos que instalan o
 * borran: el candado de [CommandExecutor] y el «Actualizar» del diálogo de `REQUEST_UPDATE`, que puede traer una versión
 * anterior (`UpdateRequestManager.acceptUpdate`).
 *
 * Sólo cuenta lo que sube SOLO, de la sucursal activa [venueId] (la única que reproducen la cola de Mesas y Caja). Lo que
 * nadie drena sin una persona —un rechazo en cuarentena, una caja en revisión, lo de otra sucursal tras mover la terminal—
 * frenaría las actualizaciones para siempre (Codex r1). Sin sucursal activa no hay nada que suba: no frena.
 */
suspend fun AvoqadoDatabase.hayMesasOCajasSinSubir(venueId: String?): Boolean {
    val venue = venueId?.takeIf { it.isNotBlank() } ?: return false
    return syncIntentDao().contarSinSubir(venue) > 0 || localShiftSessionDao().contarSinSubir(venue) > 0
}

const val MENSAJE_MESAS_O_CAJAS_SIN_SUBIR = "Hay ventas de mesas o cortes de caja sin subir"
