package com.jaac.avoqado_tpv.features.tables.domain.model

/**
 * Elemento decorativo del plano (pared, barra, área de servicio, etiqueta,
 * puerta) — espejo EXACTO de `FloorElementResponse` en el server
 * (`src/services/tpv/floor-element.tpv.service.ts::getFloorElements`), lo que
 * `GET /tpv/venues/{venueId}/floor-elements` devuelve envuelto en
 * `{ success, data }`.
 *
 * NO listado en el archivo del plan (`DiningTable, TableOrder, OrderDetail`) —
 * pero `TablesApiService.getFloorElements` (Task 2 Step 4 del plan) necesita un
 * tipo de respuesta, y el canvas del plano (Task 6) no puede dibujar paredes ni
 * barras sin él. Se agrega aquí como el 4to modelo de dominio de esta tarea.
 */
data class FloorElement(
    val id: String,
    /** WALL | BAR_COUNTER | SERVICE_AREA | LABEL | DOOR (Prisma `FloorElementType`). */
    val type: String,
    /** Coordenadas GLOBALES 0.0–1.0, mismo sistema que `Table.positionX/Y`. */
    val positionX: Float,
    val positionY: Float,
    /** Para elementos rectangulares (BAR_COUNTER, SERVICE_AREA). */
    val width: Float? = null,
    val height: Float? = null,
    /** 0, 90, 180, 270. */
    val rotation: Int = 0,
    /** Para WALL: línea de (positionX,positionY) a (endX,endY). */
    val endX: Float? = null,
    val endY: Float? = null,
    val label: String? = null,
    /** Color hex, p.ej. "#424242". */
    val color: String? = null,
    val areaId: String? = null,
    val active: Boolean = true,
)
