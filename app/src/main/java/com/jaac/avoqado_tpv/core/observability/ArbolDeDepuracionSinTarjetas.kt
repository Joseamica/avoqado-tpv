package com.jaac.avoqado_tpv.core.observability

import timber.log.Timber

/**
 * El `DebugTree` de Timber para las compilaciones de depuración (QA, `.lab`), con el número de tarjeta
 * tapado (6 + 4) antes de escribir en logcat. Timber ya juntó el mensaje y la pila del error en `message`,
 * así que también se tapa lo que venga en el texto de una excepción. D64.
 */
class ArbolDeDepuracionSinTarjetas : Timber.DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, TapaNumeroDeTarjeta.tapar(message), t)
    }
}
