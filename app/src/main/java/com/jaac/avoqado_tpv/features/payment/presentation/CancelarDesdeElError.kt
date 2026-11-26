package com.jaac.avoqado_tpv.features.payment.presentation

import com.jaac.avoqado_tpv.features.payment.domain.PaymentState

/**
 * A dónde lleva «Cancelar» en la pantalla de error de la PAX: `true` regresa a «Método de Pago» con el mismo importe; `false` sale del
 * cobro (`resetPayment()` + regresar).
 *
 * - En un reembolso, en un cobro del POS, sin reintento o con «Abrir caja» siempre se sale (no hay otro método que elegir aquí).
 * - 🔴 1164aa: tras un error de REGISTRO del efectivo ([com.jaac.avoqado_tpv.features.payment.domain.RetryContext.reintentoDeEfectivo]),
 *   el cliente ya pagó y el dinero está en el cajón. Regresar a métodos dejaba la TARJETA a un toque por el mismo importe: cobro
 *   doble. Ahí también se sale. La fila del efectivo, si quedó guardada, la sube la cola; si no, ya se avisó con `reportRecordingLost`.
 */
internal fun cancelarDesdeElErrorVuelveAMetodos(
    error: PaymentState.Error,
    esReembolso: Boolean,
    esCobroDelPos: Boolean,
): Boolean {
    val contexto = error.context ?: return false
    return !esReembolso && error.canRetry && !error.showOpenShiftButton && !esCobroDelPos &&
        contexto.reintentoDeEfectivo == null
}
