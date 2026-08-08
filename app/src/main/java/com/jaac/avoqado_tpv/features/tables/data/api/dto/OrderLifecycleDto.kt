package com.jaac.avoqado_tpv.features.tables.data.api.dto

import java.math.BigDecimal

/**
 * "Separar cuenta" / "Fusionar cuentas" / "Dividir por puesto" — las 3 rutas de
 * ciclo de vida de mesa bajo `/tpv` (`order-table.tpv.controller.ts`,
 * delegando en `order.mobile.service.ts` — mismo servicio que usa `/mobile`,
 * verificado 2026-07-28 en `src/services/mobile/order.mobile.service.ts`).
 */

/** `POST /tpv/venues/{venueId}/orders/{orderId}/split`. Body: `{ itemIds }`. */
data class SplitOrderRequest(val itemIds: List<String>)

/**
 * `{ success, data: { source, created } }` — `orderMobileService.splitOrderItems`.
 * `source` = la cuenta original (con lo que le quedó); `created` = la cuenta
 * NUEVA con los items movidos. Ambas ya vienen con sus totales recalculados.
 */
data class SplitOrderResponse(
    val success: Boolean = true,
    val data: SplitOrderResult? = null,
)

data class SplitOrderResult(
    val source: OrderMoneySummary,
    val created: OrderMoneySummary,
)

/**
 * `POST /tpv/venues/{venueId}/orders/{orderId}/split-by-seat` no lleva body —
 * el servicio agrupa los items existentes por `seat`. No hay clase de request.
 */

/**
 * `{ success, data: { source, created } }` — `orderMobileService.splitOrderBySeat`.
 * `created` es UNA cuenta por asiento movido (el asiento más bajo se queda en
 * `source`, nunca se mueve — así la cuenta original nunca queda vacía).
 */
data class SplitOrderBySeatResponse(
    val success: Boolean = true,
    val data: SplitBySeatResult? = null,
)

data class SplitBySeatResult(
    val source: OrderMoneySummary,
    val created: List<OrderMoneySummary> = emptyList(),
)

/**
 * Resumen de dinero de una cuenta tras un split/merge — los 3 servicios
 * (`splitOrderItems`, `splitOrderBySeat`, `mergeOrders`) NO devuelven el mismo
 * subconjunto de campos exacto (p.ej. `splitOrderBySeat.source` no trae
 * `version`, sí trae `seat`) — todos son nullable/con default para poder
 * reusar una sola clase en las 3 respuestas sin que Gson truene con lo ausente.
 */
data class OrderMoneySummary(
    val id: String,
    val orderNumber: String = "",
    val total: BigDecimal = BigDecimal.ZERO,
    val version: Int? = null,
    /** Solo presente en el resultado de `split-by-seat`. */
    val seat: Int? = null,
)

/** `POST /tpv/venues/{venueId}/orders/{orderId}/merge`. Body: `{ sourceOrderId }`. */
data class MergeOrdersRequest(val sourceOrderId: String)

/**
 * `{ success, data: { target, merged, tableFreed } }` — `orderMobileService.mergeOrders`.
 * `target` = la cuenta que sobrevive (con los items del origen ya volcados);
 * `merged` = resumen de la cuenta origen, ahora `CANCELLED`; `tableFreed` = si
 * la mesa del origen quedó libre (sin otra cuenta abierta que re-apuntar).
 */
data class MergeOrdersResponse(
    val success: Boolean = true,
    val data: MergeOrdersResult? = null,
)

data class MergeOrdersResult(
    val target: OrderMoneySummary,
    val merged: MergedOrderSummary,
    val tableFreed: Boolean = false,
)

data class MergedOrderSummary(
    val id: String,
    val orderNumber: String = "",
    val items: Int = 0,
)

/**
 * `POST /tpv/venues/{venueId}/orders/{orderId}/cancel`. Body: `{ reason? }`
 * (2026-08-07 — antes de esta fecha esta ruta NO existía, `cancelOrder` viajaba
 * SIEMPRE como intent; ver KDoc de
 * [com.jaac.avoqado_tpv.features.tables.data.TablesRepository.cancelOrder]).
 */
data class CancelOrderRequest(val reason: String? = null)

/**
 * `{ success, data: { tableFreed } }` — `orderMobileService.cancelOrder`
 * (mismo servicio que `/mobile` y el reducer offline) seguido de
 * `tableTpvService.reconcileTableAfterOrderRemoved` en
 * `order-table.tpv.controller.ts::cancelOrder`. `tableFreed` = si cancelar
 * esta cuenta liberó su mesa (sin otro cheque abierto que re-apuntar) — mismo
 * campo que [MergeOrdersResult.tableFreed].
 */
data class CancelOrderResponse(
    val success: Boolean = true,
    val data: CancelOrderResult? = null,
)

data class CancelOrderResult(
    val tableFreed: Boolean = false,
)
