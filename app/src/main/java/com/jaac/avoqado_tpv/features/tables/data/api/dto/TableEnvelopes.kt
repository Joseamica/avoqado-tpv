package com.jaac.avoqado_tpv.features.tables.data.api.dto

import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.FloorElement

/**
 * `GET /tpv/venues/{venueId}/tables` → `tableController.getTables`.
 *
 * `settings`/`viewer` (Plan C, Task 7; server gap fix `a7d2f7b6`, 2026-07-29) —
 * antes ausentes (ver KDoc de [com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable]):
 * `table.mobile.controller.ts::getTables` (reexportado ahora por `/tpv`) agrega
 * `settings.enforceTableOwnership` y `viewer.staffId`/`viewer.canManageAllTables`
 * al SOBRE — el array `data` no cambia de forma. Ambos nullable: un server viejo
 * (o un mock de test que no los mande) deja a [TablesRepository] sin
 * información de propiedad, que [TableOwnership] trata como "no aplica"
 * (`enforced = false`), nunca como "bloqueado".
 */
data class TablesResponse(
    val success: Boolean = true,
    val data: List<DiningTable> = emptyList(),
    val settings: TableOwnershipSettingsDto? = null,
    val viewer: TableViewerDto? = null,
)

/** `settings` del sobre de `GET /tpv/venues/{venueId}/tables` — switch a nivel venue. */
data class TableOwnershipSettingsDto(
    val enforceTableOwnership: Boolean = false,
)

/**
 * `viewer` del sobre de `GET /tpv/venues/{venueId}/tables` — quién soy yo (el
 * staff autenticado en ESTA terminal) para efectos de propiedad de mesa.
 * `canManageAllTables` es un override de rol (gerente/admin) que se salta la
 * regla — mismo campo que evalúa el server al aplicar `TABLE_OWNED_BY_OTHER`.
 */
data class TableViewerDto(
    val staffId: String? = null,
    val canManageAllTables: Boolean = true,
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
