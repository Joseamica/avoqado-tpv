package com.jaac.avoqado_tpv.features.payments.domain.models

/**
 * La liga del recibo digital de un cobro ya hecho, para dibujar el QR al REIMPRIMIR un ticket.
 *
 * Tres desenlaces, no dos. Un 403/404/500 **no es «sin conexión»**: decirle al cajero que no hay
 * red cuando el servidor sí respondió lo manda a revisar el WiFi por un problema que no está ahí.
 *
 * Espejo de `ResultadoLigaRecibo` en avoqado-android y avoqado-ios.
 */
sealed interface ResultadoLigaRecibo {
    data class Obtenida(val receiptUrl: String, val autofacturaAvailable: Boolean) : ResultadoLigaRecibo

    /** No se pudo llegar al servidor (sin red, DNS, timeout). */
    data object SinRed : ResultadoLigaRecibo

    /** El servidor respondió, pero con un error. */
    data class FalloDelServidor(val codigo: Int) : ResultadoLigaRecibo
}
