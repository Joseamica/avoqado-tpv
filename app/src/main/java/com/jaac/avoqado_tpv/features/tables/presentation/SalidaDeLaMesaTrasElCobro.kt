package com.jaac.avoqado_tpv.features.tables.presentation

import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import timber.log.Timber

/**
 * «Nueva Orden» tras un cobro con TARJETA que saldó la mesa (PAX y Nexgo): libera la mesa con la cuenta (el SERVIDOR rechaza
 * si algo todavía se debe, y sin red lo encola con la cuenta, D79) y suelta «Tu mesa». Es lo que ya hacía; sólo vive aquí.
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

/**
 * hz:nube0000:na286b5f (encargo 64000e): salir del recibo de un cobro con tarjeta que saldó la cuenta por «Inicio» (PAX y
 * Nexgo) o «Ver en Pagos» (Nexgo) dejaba «Tu mesa» pegada a una cuenta ya pagada: sólo «Nueva Orden» la soltaba.
 *
 * Rediseño tras Codex r2/r3: estas salidas sólo sueltan la SESIÓN, al momento y sin red, y sólo si la sesión es la de la
 * cuenta que se acaba de cobrar ([orderIdDelCobro]). NO liberan la mesa (el `/clear` sólo lleva la mesa y con el plano atrasado
 * podía liberar una mesa ajena o reservada; además el SERVIDOR libera la mesa al registrar el cobro que la saldó) y no dejan
 * nada corriendo en segundo plano que pueda pisar la siguiente mesa que se abra. Quien llama decide que el cobro saldó la
 * cuenta (en la PAX: por el total o con el saldo confirmado en cero; en la Nexgo todo cobro de Mesas es por el total).
 *
 * @return `true` si soltó la sesión.
 */
fun soltarSesionDelCobro(tableSession: TableSession, orderIdDelCobro: String?, salida: String): Boolean {
    val session = tableSession.current() ?: return false
    if (orderIdDelCobro.isNullOrBlank() || session.orderId != orderIdDelCobro) return false
    Timber.i("🪑 [Mesas] %s: se suelta «Tu mesa» de la cuenta %s, ya cobrada", salida, orderIdDelCobro)
    tableSession.clear()
    return true
}
