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
