package com.jaac.avoqado_tpv.core.remotepayment

import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import timber.log.Timber

/**
 * 🔒 Encargo f3cba0 (24-nov): ¿el POS pide cobrar una cuenta que ESTA terminal ya cobró y cuyo cobro todavía no sube a Avoqado?
 *
 * Mientras ese cobro no sube, el SERVIDOR ve la cuenta «por pagar» y deja que el POS la mande otra vez: abrir la tarjeta sería
 * cobrarle dos veces al cliente. Pasa con el efectivo ya en el cajón cuyo registro falló (la pantalla «Error registrando pago en
 * efectivo», o el efectivo guardado sin red) y con un registro que el SERVIDOR rechazó y nadie ha revisado.
 *
 * Es «la misma venta espera; las demás pasan» (founder, 25-sep: una duda no aparta la terminal, sólo cerca su venta): un error en
 * pantalla NO vuelve ocupada a la terminal, y otra cuenta o un Pago rápido del POS se abren igual. Usa el MISMO predicado de la cola
 * que «Por pagar» (R78/R83, [PendingPaymentDao.hayCobroSubiendose] y [PendingPaymentDao.hayCobroSinRegistrar]), que es el que frena
 * el cierre de caja acotado a la cuenta.
 *
 * Falla CERRADO: si la cola no se deja leer o no hay negocio en la sesión, tampoco se arriesga un segundo cobro. No toca el SDK: la
 * navegación contesta `failed + PRE_AUTHORIZATION` al instante, como cualquier otro motivo para no iniciar.
 */
internal object CuentaCobradaSinSubir {

    /** La cola de la terminal tiene un cobro de esta cuenta subiéndose (PENDING/SYNCING). */
    const val COBRO_SUBIENDOSE =
        "La terminal ya cobró esta cuenta y ese cobro todavía no sube a Avoqado: este cobro NO se inició. " +
            "No la vuelvas a cobrar; espera a que suba y revisa la cuenta."

    /** El SERVIDOR rechazó el registro (o pide revisión) y nadie lo ha revisado: el dinero está en la caja. */
    const val COBRO_SIN_REGISTRAR =
        "La terminal cobró esta cuenta y Avoqado no aceptó su registro: este cobro NO se inició. " +
            "Pide a un gerente que lo revise en la terminal («Turnos de caja» › «Cerrar caja») antes de volver a cobrarla."

    /** No se pudo leer la cola (o no hay negocio en la sesión): no se arriesga. */
    const val NO_SE_PUDO_REVISAR =
        "La terminal no pudo revisar si ya cobró esta cuenta: este cobro NO se inició. Vuelve a enviarlo en unos segundos."

    /** null = se puede abrir. Sin cuenta (Pago rápido del POS) no hay nada que cercar. Nunca lanza, salvo una cancelación. */
    suspend fun motivo(orderId: String?, venueId: String?, cola: PendingPaymentDao): String? {
        if (orderId.isNullOrBlank()) return null
        if (venueId.isNullOrBlank()) return NO_SE_PUDO_REVISAR
        return try {
            when {
                cola.hayCobroSinRegistrar(venueId, orderId) -> COBRO_SIN_REGISTRAR
                cola.hayCobroSubiendose(venueId, orderId) -> COBRO_SUBIENDOSE
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
