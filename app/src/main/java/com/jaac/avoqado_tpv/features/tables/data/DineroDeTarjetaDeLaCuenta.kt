package com.jaac.avoqado_tpv.features.tables.data

import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
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
 * Usa las fuentes POR CUENTA que ya usa la tienda híbrida para no cobrar dos veces un folio (R89,
 * `PorPagarViewModel.dineroSinConfirmar`):
 *  - la libreta (PAX y Nexgo): un cobro de ESA cuenta corriendo, aprobado y sin registrar (HOST_RESPONDIO, AUTORIZADO,
 *    REGISTRO_FALLIDO…). Cubre el tramo de «Aprobado» a «registrado» de la PAX: la libreta ya está en HOST_RESPONDIO cuando
 *    se enseña «Aprobado» y guarda el `orderId` de la mesa en su contexto;
 *  - la cola de la terminal: un cobro de ESA cuenta rechazado sin revisar, o todavía subiendo (si el registro falló, ahí queda).
 * 🔴 NO usa `PaymentStateHolder.isCharging()` (Codex r2): es de toda la terminal, así que la tarjeta de la mesa A (o una
 * devolución de AngelPay) frenaba el efectivo de la mesa B diciendo «la tarjeta de esta cuenta».
 * 🔴 Cola y libreta se leen en UNA sola sentencia ([PaymentAttemptLedger.dineroDeTarjetaSinConfirmarDeLaCuenta], Codex r3): en
 * dos consultas, el traspaso libreta → cola podía caer entre las dos y ninguna veía el cobro.
 *
 * `null` = nada. Un fallo de lectura SE LANZA: quien pregunta para no cobrar de más falla cerrado.
 */
@Singleton
class DineroDeTarjetaDeLaCuenta @Inject constructor(
    private val libreta: PaymentAttemptLedger,
) {
    suspend fun motivo(venueId: String, orderId: String): String? =
        when (libreta.dineroDeTarjetaSinConfirmarDeLaCuenta(venueId, orderId)) {
            null -> null
            "SIN_REGISTRAR" -> TARJETA_SIN_REGISTRAR
            "SUBIENDO" -> TARJETA_SUBIENDOSE
            else -> TARJETA_REGISTRANDOSE // 'LIBRETA' (o algo que no se conoce: falla cerrado)
        }
}
