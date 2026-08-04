package com.jaac.avoqado_tpv.features.tables.data.api.dto

import java.math.BigDecimal

/**
 * "Dar de cortesía" — `POST /tpv/venues/{venueId}/orders/{orderId}/comp`
 * (`orderController.compItems` → `order.tpv.service.ts::compItems`, permiso
 * `orders:comp`). Verificado 2026-08-03 contra `tpv.routes.ts:4266` y
 * `compItemsSchema` (`src/schemas/tpv.schema.ts:499`).
 *
 * 🔴 `itemIds` vacío = cortesía de TODA la cuenta; no vacío = solo esas líneas
 * (ya enviadas a cocina). Las dos formas comparten esta MISMA ruta online —
 * pero **solo la forma "toda la cuenta" tiene equivalente offline**
 * ([com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes.COMP_ORDER],
 * que delega en `compWholeOrder` — el reducer del server, `sync.mobile.service.ts`,
 * NUNCA lee `itemIds`). Cortesía de un artículo puntual YA enviado es
 * intencionalmente online-only — regla explícita de
 * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §5 ("cortesía de
 * UN item ya enviado" es de la lista online-only). [TablesRepository.compOrder]
 * es quien aplica esta bifurcación — este DTO no sabe ni le importa por cuál
 * camino llegó.
 */
data class CompOrderRequest(
    val itemIds: List<String> = emptyList(),
    val reason: String,
    val staffId: String,
    val notes: String? = null,
)

/**
 * `{ success, data: Order&{tableName}, message }`. El controller regresa el
 * `Order` de Prisma completo (no el `OrderDetail` armado por `getOrder`) — nos
 * quedamos SOLO con el subconjunto de campos que existen verbatim en ambos
 * shapes (`id`/`total`/`version`) para no fingir que tenemos `items`/`payments`
 * que esta respuesta no trae. El caller (`TablesRepository.compOrder`) llama
 * `getOrder` después para refrescar el detalle completo.
 */
data class CompOrderResponse(
    val success: Boolean = true,
    val data: CompOrderResult? = null,
    val message: String? = null,
)

data class CompOrderResult(
    val id: String = "",
    val total: BigDecimal = BigDecimal.ZERO,
    val version: Int = 1,
)
