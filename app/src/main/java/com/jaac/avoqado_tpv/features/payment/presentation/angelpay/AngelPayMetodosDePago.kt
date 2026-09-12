package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState

/** Qué botones de cobro se apagan en «Método de pago» (T26). */
internal data class BloqueoMetodosDePago(val tarjeta: Boolean, val efectivo: Boolean)

/**
 * T26 (2026-09-11): la recuperación de fondo de AngelPay ([AngelPayAuthState.Recuperando])
 * apaga la Tarjeta pero NO el Efectivo.
 *
 * Por qué `Authenticating` apaga los dos (2026-05-19, se conserva igual): un solo interruptor
 * cubría los tres botones para cerrar una carrera de la TARJETA — `selectMerchant` encadena
 * `switchAccount → switchActiveMerchant` y, si el cajero tocaba Tarjeta a medio camino,
 * `_currentMerchant` volvía a null y el registro salía sin `merchantAccountId` (400). Además
 * `selectMerchant`, al fallar, escribe «No se pudo cambiar de merchant» sobre el estado de la
 * pantalla y taparía un cobro en efectivo ya registrado. Esas auths son de la PANTALLA.
 *
 * La recuperación de fondo es otra cosa: no toca el estado de la pantalla y el efectivo se
 * registra con `merchantAccountId = null`, sin usar la sesión de AngelPay. Por eso tiene su
 * propio estado y deja el Efectivo disponible mientras dura (hasta ~39 s de reintentos).
 */
internal fun bloqueoDeMetodosDePago(
    selectionInProgress: Boolean,
    inFlightSwitch: Int?,
    authState: AngelPayAuthState,
): BloqueoMetodosDePago {
    val cambioDesdeLaPantalla = selectionInProgress || inFlightSwitch != null
    val authDeLaPantalla = authState is AngelPayAuthState.Authenticating
    return BloqueoMetodosDePago(
        tarjeta = cambioDesdeLaPantalla || authDeLaPantalla || authState is AngelPayAuthState.Recuperando,
        efectivo = cambioDesdeLaPantalla || authDeLaPantalla,
    )
}
