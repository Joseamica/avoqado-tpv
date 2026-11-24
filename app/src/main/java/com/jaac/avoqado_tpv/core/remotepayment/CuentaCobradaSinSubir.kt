package com.jaac.avoqado_tpv.core.remotepayment

import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.features.tables.data.MESA_COBRO_POR_REVISAR
import com.jaac.avoqado_tpv.features.tables.data.MESA_COBRO_SIN_SINCRONIZAR
import timber.log.Timber

/**
 * 🔒 Encargo f3cba0 (24-nov): ¿el POS pide cobrar una cuenta que ESTA terminal ya cobró y cuyo cobro todavía no sube a Avoqado?
 *
 * Mientras ese cobro no sube, el SERVIDOR ve la cuenta «por pagar» y deja que el POS la mande otra vez: abrir la tarjeta sería
 * cobrarle dos veces al cliente. Pasa con el efectivo ya en el cajón cuyo registro falló (la pantalla «Error registrando pago en
 * efectivo», o el efectivo guardado sin red), con el efectivo de Mesas que sigue en su cola, y con un registro que el SERVIDOR
 * rechazó y nadie ha revisado.
 *
 * Es «la misma venta espera; las demás pasan» (founder, 25-sep: una duda no aparta la terminal, sólo cerca su venta): un error en
 * pantalla NO vuelve ocupada a la terminal, y otra cuenta o un Pago rápido del POS se abren igual. Las dos colas se leen con los
 * MISMOS predicados que ya usan sus pantallas:
 *  - la de cobros ([PendingPaymentDao.hayCobroSinSubir]: el de «Por pagar», R78/R83, y el del cierre de caja, en UNA lectura);
 *  - la de Mesas (`SaldoDeMesaSinRed.pendienteDeSubir`, D86): sólo sus dos respuestas de EFECTIVO; una ronda sin subir no es
 *    dinero en el cajón y no cerca la cuenta.
 *
 * Falla CERRADO: si una cola no se deja leer o no hay negocio en la sesión, tampoco se arriesga un segundo cobro. No toca el SDK: la
 * navegación contesta `failed + PRE_AUTHORIZATION` al instante, como cualquier otro motivo para no iniciar.
 */
internal object CuentaCobradaSinSubir {

    /** Hay un cobro de esta cuenta de esta terminal subiendo, o rechazado sin revisar: el dinero ya está en la caja. */
    const val COBRO_SIN_SUBIR =
        "La terminal ya cobró esta cuenta y Avoqado todavía no tiene ese cobro: este cobro NO se inició. " +
            "No la vuelvas a cobrar; si no se acomoda sola en unos minutos, pide a un gerente que revise la terminal."

    /** No se pudo leer una cola (o no hay negocio en la sesión): no se arriesga. */
    const val NO_SE_PUDO_REVISAR =
        "La terminal no pudo revisar si ya cobró esta cuenta: este cobro NO se inició. Vuelve a enviarlo en unos segundos."

    /**
     * null = se puede abrir. Sin cuenta (Pago rápido del POS) no hay nada que cercar. [mesas] es la revisión de la cola de Mesas
     * (`SaldoDeMesaSinRed.pendienteDeSubir`). Nunca lanza, salvo una cancelación.
     */
    suspend fun motivo(
        orderId: String?,
        venueId: String?,
        cola: PendingPaymentDao,
        mesas: suspend (venueId: String, orderId: String) -> String?,
    ): String? {
        if (orderId.isNullOrBlank()) return null
        if (venueId.isNullOrBlank()) return NO_SE_PUDO_REVISAR
        return try {
            when {
                cola.hayCobroSinSubir(venueId, orderId) -> COBRO_SIN_SUBIR
                mesas(venueId, orderId).let { it == MESA_COBRO_SIN_SINCRONIZAR || it == MESA_COBRO_POR_REVISAR } -> COBRO_SIN_SUBIR
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "📡 [Remote] No se pudo leer la cola de cobros para la cuenta %s: no se abre el cobro del POS", orderId)
            NO_SE_PUDO_REVISAR
        }
    }
}
