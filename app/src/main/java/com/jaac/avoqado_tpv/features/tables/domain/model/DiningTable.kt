package com.jaac.avoqado_tpv.features.tables.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Mesa con su estado en vivo — espejo EXACTO por campo de `TableStatusResponse`
 * (avoqado-server `src/services/tpv/table.tpv.service.ts::getTablesWithStatus`),
 * lo que `GET /tpv/venues/{venueId}/tables` devuelve envuelto en `{ success, data }`.
 *
 * Verificado contra el server 2026-07-28 (Plan C Task 2). A diferencia del
 * `DiningTable` de `avoqado-android` (que mira `/mobile`, con `settings`/`viewer`
 * de propiedad de mesa en el sobre), `/tpv`'s `tableController.getTables` NO manda
 * esos dos campos — `table.tpv.controller.ts::getTables` solo hace
 * `{ success: true, data: tables }`. Si una tarea futura los agrega ahí, esta
 * clase no necesita cambiar (son campos del SOBRE, no de la mesa).
 */
data class DiningTable(
    val id: String,
    val number: String,
    val capacity: Int = 2,
    /** Coordenadas normalizadas 0–1 del plano del venue. Null = mesa sin posicionar. */
    val positionX: Float? = null,
    val positionY: Float? = null,
    /** SQUARE | ROUND | RECTANGLE (Prisma `TableShape`). */
    val shape: String = "SQUARE",
    val rotation: Int = 0,
    /** AVAILABLE | OCCUPIED | RESERVED | CLEANING (Prisma `TableStatus`). */
    val status: String = "AVAILABLE",
    val areaId: String? = null,
    val areaName: String? = null,
    /** Cuenta "principal" denormalizada de la mesa — la que apunta `Table.currentOrderId`. */
    val currentOrder: TableOrder? = null,
    /**
     * Multi-cheque (Square's separate checks): TODAS las cuentas abiertas de la
     * mesa, no solo `currentOrder`. Aditivo — additivo: un cliente viejo que solo
     * lee `currentOrder` no se rompe si esto llega vacío.
     */
    val openOrders: List<OpenCheckSummary> = emptyList(),
) {
    /**
     * ¿Hay dinero vivo en esta mesa? Se mira la CUENTA, no el campo `status`
     * denormalizado — mismo fix que `avoqado-android`/`avoqado-ios`
     * (2026-07-28): mesa M2 traía una orden PENDING de $603.50 con
     * `Table.status = AVAILABLE`, así que el plano la pintaba LIBRE. Un
     * mesero sentaba gente nueva ahí y la cuenta anterior quedaba huérfana —
     * nadie la cobraba. No se sabe qué desincroniza `status`, así que esto es
     * defensa en profundidad: pase lo que pase en la base, una mesa con
     * cuenta abierta se ve ocupada.
     */
    val hasOpenCheck: Boolean get() = openOrders.isNotEmpty() || currentOrder != null

    /**
     * La cuenta sobre la que actúan las acciones de la mesa (ver cuenta,
     * cobrar…). Prefiere el puntero denormalizado `currentOrder`; si viene
     * nulo, o apunta a una orden que YA NO ESTÁ ABIERTA (el server nulea/deja
     * atrás `currentOrder` al completarse un cheque dividido —
     * `table.tpv.service.ts:143`), cae a la cuenta abierta MÁS ANTIGUA.
     *
     * El complemento obligatorio de [hasOpenCheck]: ese arregla cómo se PINTA
     * la mesa, pero no cómo se ABRE — sin esto, cualquier acción que
     * dependiera de `currentOrder` directo moría en silencio (`currentOrder
     * ?: return`) justo cuando quedaba una parte de un cheque dividido por
     * cobrar. Las acciones de mesa SIEMPRE deben leer esto, nunca
     * `currentOrder` directo.
     */
    val primaryCheck: OpenCheckSummary?
        get() = currentOrder?.let { o ->
            // La misma orden ya viene en la lista casi siempre; se prefiere
            // esa para no duplicar la fuente de verdad de total/versión.
            openOrders.firstOrNull { it.id == o.id }
                // El puntero apunta a una orden que ya no está abierta — si
                // hay otra cuenta abierta, ESA manda.
                ?: openOrders.firstOrNull()
                // Solo si no hay ninguna abierta se cree al puntero: server
                // viejo que no manda `openOrders`.
                ?: OpenCheckSummary(
                    id = o.id,
                    orderNumber = o.orderNumber,
                    covers = o.covers,
                    total = o.total,
                    itemCount = o.itemCount,
                    version = o.version,
                    waiterId = o.waiter?.id,
                    waiterName = o.waiter?.name,
                    createdAt = o.createdAt,
                )
        } ?: openOrders.firstOrNull()

    /**
     * La CUENTA manda, no `status` — `status` solo sigue mandando para
     * RESERVED, la única ocupación legítima sin cuenta abierta. Mirror EXACTO
     * de `avoqado-android`/`avoqado-ios` `DiningTable` (2026-07-28) — no
     * rediseñar esta semántica sin actualizar los tres clientes.
     */
    val isAvailable: Boolean get() = !hasOpenCheck && status != "RESERVED"
    val isOccupied: Boolean get() = hasOpenCheck
    val isReserved: Boolean get() = status == "RESERVED"
    val hasPosition: Boolean get() = positionX != null && positionY != null
}

/**
 * Resumen ligero de una cuenta abierta en la mesa (picker multi-cheque) — espejo
 * EXACTO del `openOrders[]` de `TableStatusResponse` en el server.
 */
data class OpenCheckSummary(
    val id: String,
    val orderNumber: String = "",
    val covers: Int? = null,
    /** Pesos (major units), `BigDecimal` — NUNCA `Double` (ver `critical-warnings.md`). */
    val total: BigDecimal = BigDecimal.ZERO,
    val itemCount: Int = 0,
    /** `Order.version` para CAS optimista al agregar una ronda. */
    val version: Int = 1,
    val name: String? = null,
    /**
     * Dueño de la cuenta — se compara contra el `staffId` propio para pintar
     * read-only cuando `enforceTableOwnership` está encendido (VenueSettings).
     */
    val waiterId: String? = null,
    val waiterName: String? = null,
    val createdAt: String? = null,
) {
    val totalDisplay: String get() = "$${total.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
}
