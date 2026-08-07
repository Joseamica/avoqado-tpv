package com.jaac.avoqado_tpv.features.payment.presentation

import com.jaac.avoqado_tpv.features.payment.domain.AuthWatchdogLevel

/**
 * Traduce el [AuthWatchdogLevel] publicado por el vigilante de autorización
 * (ambos rieles: Blumon en [com.jaac.avoqado_tpv.features.payment.domain.PaymentState.Processing],
 * AngelPay en [com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentState.WaitingForResult])
 * en el texto que ve el cajero.
 *
 * 🔴 El texto importa más que el código. La regla del repo: nunca pintar un
 * éxito encolado como error, y nunca decirle al cajero que el cobro falló
 * cuando puede estar aprobándose en ese instante — decir "error" aquí sería
 * mentir sobre dinero en vuelo. `WatchdogCopyTest` afirma explícitamente que
 * ningún mensaje contiene "error", "fall" ni "rechaz".
 *
 * Pura, sin Compose: testeable en JVM.
 */
fun watchdogMessage(level: AuthWatchdogLevel): String? = when (level) {
    AuthWatchdogLevel.NONE -> null
    AuthWatchdogLevel.SLOW ->
        "Sigue procesando… no retires la tarjeta"
    AuthWatchdogLevel.VERY_SLOW ->
        "La red está lenta. NO cobres de nuevo: revisa el resultado en Pagos antes de reintentar"
}
