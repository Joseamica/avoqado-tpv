package com.jaac.avoqado_tpv.features.tables.presentation

import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import timber.log.Timber

/**
 * La salida de un cobro con TARJETA que saldó la mesa (PAX y Nexgo): libera la mesa con la cuenta (como el efectivo; el
 * SERVIDOR rechaza si algo todavía se debe, y sin red lo encola con la cuenta, D79) y suelta «Tu mesa».
 *
 * Antes sólo lo hacía «Nueva Orden». hz:nube0000:na286b5f (encargo 64000e): salir del recibo por «Inicio» (o «Ver en Pagos»
 * en la Nexgo) dejaba la sesión pegada a una cuenta ya pagada. Ahora las tres salidas pasan por aquí, con la MISMA regla.
 * Quien llama decide que el cobro saldó la cuenta (en la PAX, sin saldo pendiente y sin ser reembolso).
 */
suspend fun soltarLaMesaTrasElCobroConTarjeta(
    venueId: String?,
    tableSession: TableSession,
    tablesRepository: TablesRepository,
    salida: String,
) {
    val session = tableSession.current()
    if (venueId != null && session != null) {
        tablesRepository.clearTable(venueId, session.tableId, session.orderId, session.isProvisional)
            .onFailure { Timber.w(it, "⚠️ [Mesas] clearTable no liberó la mesa tras el cobro con tarjeta (%s)", salida) }
    }
    tableSession.clear()
}
