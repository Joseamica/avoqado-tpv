package com.jaac.avoqado_tpv.features.tables.presentation

import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * hz:nube0000:na286b5f (encargo 64000e): salir del recibo de un cobro con tarjeta de Mesas por «Inicio» (o «Ver en Pagos» en
 * la Nexgo) dejaba «Tu mesa» pegada a una cuenta ya pagada: sólo «Nueva Orden» la soltaba.
 *
 * Rediseño tras Codex r2: estas salidas NO liberan la mesa (el `/clear` sólo lleva la mesa, y con el plano atrasado liberaba
 * una mesa ajena o reservada; además la mesa ya la libera el SERVIDOR al registrar el cobro que la saldó). Sólo sueltan la
 * SESIÓN, y sólo si el SERVIDOR dice que su cuenta ya no debe: un abono que no saldó, una cuenta a la que otro aparato le
 * agregó algo o un registro que todavía no sube dejan «Tu mesa» como antes. La pantalla navega de inmediato; la pregunta corre
 * en un alcance propio que no muere con la pantalla.
 */
object SalidaDelReciboDeLaMesa {
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun soltarSiLaCuentaYaNoDebe(
        venueId: String?,
        tableSession: TableSession,
        tablesRepository: TablesRepository,
        salida: String,
    ): Job? {
        venueId ?: return null
        tableSession.current() ?: return null
        val epoca = tableSession.epoch
        return alcance.launch { soltarSesionSiLaCuentaYaNoDebe(venueId, tableSession, tablesRepository, epoca, salida) }
    }
}

/**
 * Pregunta por la cuenta de la sesión de [epoca] (la de AHORA: si una apertura sin red se confirmó, ya trae el id del SERVIDOR)
 * y la suelta si está pagada o cerrada. Si no se puede preguntar, lo vuelve a intentar ([ESPERAS_PARA_PREGUNTAR_POR_LA_CUENTA]).
 * Nunca toca otra sesión: si el mesero ya entró a otra cuenta, no hace nada.
 */
internal suspend fun soltarSesionSiLaCuentaYaNoDebe(
    venueId: String,
    tableSession: TableSession,
    tablesRepository: TablesRepository,
    epoca: Long,
    salida: String,
) {
    for (espera in ESPERAS_PARA_PREGUNTAR_POR_LA_CUENTA) {
        if (espera > 0) delay(espera)
        val vigente = tableSession.current()?.takeIf { tableSession.epoch == epoca } ?: return
        val detalle = tablesRepository.getOrder(venueId, vigente.orderId).getOrNull() ?: continue
        if (detalle.paymentStatus == "PAID" || detalle.status == "COMPLETED") {
            if (tableSession.epoch == epoca && tableSession.current()?.orderId == vigente.orderId) {
                Timber.i("🪑 [Mesas] %s ya no debe — se suelta «Tu mesa» (%s)", vigente.orderId, salida)
                tableSession.clear()
            }
        }
        return
    }
    Timber.w("⚠️ [Mesas] no se pudo preguntar por la cuenta tras el cobro (%s): «Tu mesa» se queda", salida)
}
