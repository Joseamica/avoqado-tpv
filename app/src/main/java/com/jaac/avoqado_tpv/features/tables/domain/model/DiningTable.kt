package com.jaac.avoqado_tpv.features.tables.domain.model

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
    val isAvailable: Boolean get() = status == "AVAILABLE"
    val isOccupied: Boolean get() = status == "OCCUPIED"
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
    /** Pesos (major units) — el server serializa `Decimal` como Number. */
    val total: Double = 0.0,
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
)
