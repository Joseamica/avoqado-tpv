package com.jaac.avoqado_tpv.features.tables.data

import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import javax.inject.Inject
import javax.inject.Singleton

/** D9: la tarjeta de esta cuenta sigue registrándose (o la libreta la tiene aprobada y sin registrar). */
internal const val TARJETA_REGISTRANDOSE =
    "La tarjeta de esta cuenta todavía se está registrando: lo que falta se cobra en cuanto termine."

/** D9: un cobro con tarjeta de esta cuenta está en la cola de la terminal y todavía no sube. */
internal const val TARJETA_SUBIENDOSE =
    "Un cobro con tarjeta de esta cuenta todavía no sube al servidor: lo que falta se cobra en cuanto suba."

/** D9: el SERVIDOR no registró un cobro con tarjeta de esta cuenta (o pide revisión) y nadie lo ha revisado. */
internal const val TARJETA_SIN_REGISTRAR =
    "Un cobro con tarjeta de esta cuenta no se registró en el servidor: avisa al gerente antes de cobrar lo que falta."

/**
 * D9 (Codex r1 de la unión, 16-nov): ¿esta terminal tiene dinero de TARJETA de esta cuenta que el SERVIDOR todavía no
 * confirma? Si sí, la cuenta que lee el SERVIDOR todavía no lo descuenta, y cobrar lo que dice cobraría de más: la PAX enseña
 * «Aprobado» ANTES de registrar el cobro (lo registra en segundo plano), así que «atrás» desde «Aprobado» puede volver a la
 * «Cobrar mesa» con la cuenta de antes de la tarjeta.
 *
 * Son las MISMAS fuentes que ya usa la tienda híbrida para no cobrar dos veces un folio (R89,
 * `PorPagarViewModel.dineroSinConfirmar`):
 *  - un cobro con tarjeta corriendo o registrándose en esta terminal ([PaymentStateHolder.isCharging]);
 *  - la cola de la terminal: rechazado sin revisar, o todavía subiendo (el mismo predicado que frena el cierre de caja);
 *  - la libreta (PAX y Nexgo): un cobro aprobado y sin registrar, aunque ya no tenga fila en la cola.
 *
 * `null` = nada. Un fallo de lectura SE LANZA: quien pregunta para no cobrar de más falla cerrado.
 */
@Singleton
class DineroDeTarjetaDeLaCuenta @Inject constructor(
    private val cobroEnCurso: PaymentStateHolder,
    private val cola: PendingPaymentDao,
    private val libreta: PaymentAttemptLedger,
) {
    suspend fun motivo(venueId: String, orderId: String): String? = when {
        cobroEnCurso.isCharging() -> TARJETA_REGISTRANDOSE
        cola.hayCobroSinRegistrar(venueId, orderId) -> TARJETA_SIN_REGISTRAR
        cola.hayCobroSubiendose(venueId, orderId) -> TARJETA_SUBIENDOSE
        libreta.hayDineroEnJuegoDeLaVenta(orderId) -> TARJETA_REGISTRANDOSE
        else -> null
    }
}
