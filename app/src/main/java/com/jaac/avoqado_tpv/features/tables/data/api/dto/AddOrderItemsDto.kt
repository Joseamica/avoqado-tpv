package com.jaac.avoqado_tpv.features.tables.data.api.dto

/**
 * Una línea de ronda a agregar — espejo de `addOrderItemsSchema.body.items[]`
 * (`avoqado-server src/schemas/tpv.schema.ts:314`).
 *
 * Verificado 2026-07-28: el schema del server NO tiene `productId` nullable
 * (líneas de monto libre / `customName`+`customUnitPriceCents` que sí soporta
 * `order.tpv.service.ts::addItemsToOrder` a nivel de servicio) — el Zod exige
 * `productId` como CUID. Tampoco tiene `course`/`isCortesia`/`cortesiaReason`/
 * `seat` (esos SÍ existen en `avoqado-android`'s `AddOrderItemRequest`, que
 * apunta a `/mobile`, con su propio schema). Este DTO se queda con lo que
 * `addOrderItemsSchema` REALMENTE acepta hoy — mandar un campo extra no truena
 * (Express ignora lo no validado por Zod salvo `strict()`), pero no hay que
 * asumir que el server lo usa.
 */
data class AddOrderItemRequest(
    val productId: String,
    val quantity: Int,
    /** Venta por peso: obligatorio si el producto es `soldByWeight`. */
    val weightQuantity: Double? = null,
    val notes: String? = null,
    val modifierIds: List<String>? = null,
    /**
     * Llave de idempotencia POR LÍNEA — [TablesRepository.addItems][com.jaac.avoqado_tpv.features.tables.data.TablesRepository.addItems]
     * la inyecta ANTES del primer intento (`sync:<intentId>:<idx>`, mismo
     * formato que el fallback de `applyAddItems` en
     * `avoqado-server/src/services/mobile/sync.mobile.service.ts`) para que el
     * mismo `externalId` viaje tanto en el intento online como en el intent
     * que se reproduce si la respuesta se pierde (DROP_RESPONSE) — el server
     * dedupea por `externalId` (`findFirst` → UPDATE en vez de duplicar).
     *
     * 🔴 `addOrderItemsSchema` (`avoqado-server/src/schemas/tpv.schema.ts`),
     * que valida la ruta ONLINE (`PATCH .../orders/{orderId}/items`), hoy NO
     * declara `externalId` en el item — Zod lo descarta en silencio antes de
     * llegar al controller/servicio (que SÍ lo soporta, ver
     * `AddOrderItemInput.externalId` en `order.tpv.service.ts`). Hasta que ese
     * schema se actualice (cambio aditivo, un campo opcional), el intento
     * ONLINE sigue sin poder dedupearse contra su replay — este campo deja al
     * cliente LISTO para el día que el server lo acepte, y sí protege el
     * replay-contra-replay del MISMO intent (ya reproducible hoy vía
     * `sync/intents`).
     */
    val externalId: String? = null,
)

/**
 * `PATCH /tpv/venues/{venueId}/orders/{orderId}/items` (`orderController.addItemsToOrder`).
 * 🔴 Es PATCH, no POST — el ejemplo del plan (Task 2 Step 4) decía `@POST`; la
 * ruta real registrada en `tpv.routes.ts:3908` es `router.patch(...)`.
 */
data class AddItemsRequest(
    val items: List<AddOrderItemRequest>,
    val version: Int,
)
