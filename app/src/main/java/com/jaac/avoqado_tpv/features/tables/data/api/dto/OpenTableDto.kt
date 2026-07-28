package com.jaac.avoqado_tpv.features.tables.data.api.dto

/**
 * `POST /tpv/venues/{venueId}/tables/{tableId}/open` (`tableController.openTable`,
 * re-export de `table.mobile.controller.ts::openTable`). Body: `{ covers?: number }`
 * — el server usa `covers > 0 ? covers : 1` si se omite.
 */
data class OpenTableRequest(val covers: Int = 1)

/**
 * `{ success: true, data: { order, isNewOrder } }` — `result.order` es el
 * `Order` de Prisma completo (mismo shape que `tableService.assignTable`
 * devuelve), no un `OrderDetail` curado. Se modela minimal (id/orderNumber/
 * version/total) porque es solo lo que `TableSession` necesita para promover
 * el `localOrderId` (Task 5) — Gson ignora el resto de campos del JSON.
 */
data class OpenTableResponse(
    val success: Boolean = true,
    val data: OpenTableResult? = null,
)

data class OpenTableResult(
    val order: OpenedOrder? = null,
    val isNewOrder: Boolean = false,
)

data class OpenedOrder(
    val id: String,
    val orderNumber: String? = null,
    val version: Int = 1,
    val total: Double? = null,
)
