package com.jaac.avoqado_tpv.features.tables.domain.model

/**
 * La cuenta "principal" de una mesa (`Table.currentOrder`) tal como la manda
 * `GET /tpv/venues/{venueId}/tables` dentro de `DiningTable.currentOrder` —
 * espejo EXACTO del `currentOrder` de `TableStatusResponse` en el server
 * (`src/services/tpv/table.tpv.service.ts`). Es un resumen para el plano/lista,
 * NO el detalle completo del cheque (para eso: `OrderDetail`, via
 * `GET /tpv/venues/{venueId}/orders/{orderId}`).
 */
data class TableOrder(
    val id: String,
    val orderNumber: String,
    val covers: Int? = null,
    /** Pesos (major units). */
    val total: Double = 0.0,
    val itemCount: Int = 0,
    /** `Order.version` — CAS optimista al agregar una ronda (`ADD_ITEMS`/`orders/:id/items`). */
    val version: Int = 1,
    val items: List<TableOrderItem> = emptyList(),
    val waiter: TableWaiter? = null,
    val createdAt: String? = null,
) {
    val totalDisplay: String get() = "$${String.format(java.util.Locale.US, "%.2f", total)}"
}

data class TableOrderItem(
    val id: String,
    val productName: String = "",
    val quantity: Int = 1,
    val unitPrice: Double = 0.0,
    val total: Double = 0.0,
    /** Curso/tiempo TABLE_SERVICE ("Aperitivos"...). Null = "Inmediato". */
    val course: String? = null,
    /** Línea de cortesía (cuenta en la comanda, cuesta 0). */
    val isCortesia: Boolean = false,
    val cortesiaReason: String? = null,
)

data class TableWaiter(val id: String, val name: String)
