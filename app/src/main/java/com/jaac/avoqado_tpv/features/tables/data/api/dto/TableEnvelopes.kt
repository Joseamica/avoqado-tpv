package com.jaac.avoqado_tpv.features.tables.data.api.dto

import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.FloorElement

/** `GET /tpv/venues/{venueId}/tables` → `tableController.getTables`. */
data class TablesResponse(
    val success: Boolean = true,
    val data: List<DiningTable> = emptyList(),
)

/** `GET /tpv/venues/{venueId}/floor-elements` → `floorElementController.getFloorElements`. */
data class FloorElementsResponse(
    val success: Boolean = true,
    val data: List<FloorElement> = emptyList(),
)

/**
 * Sobre mínimo `{ success, message }` para mutaciones cuyo payload no se
 * consume — p.ej. `POST tables/{tableId}/clear` (`tableController.clearTable`
 * responde `{ success: true, message: 'Table cleared successfully' }`).
 */
data class SimpleSuccessResponse(
    val success: Boolean = true,
    val message: String? = null,
)
