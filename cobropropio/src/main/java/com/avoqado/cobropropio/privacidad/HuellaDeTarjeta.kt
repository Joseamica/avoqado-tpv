package com.avoqado.cobropropio.privacidad

import com.avoqado.cobropropio.cripto.aHex
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Lo que la libreta guarda de una tarjeta o de una lectura para compararlas después: nunca el dato, sólo su huella.
 * HMAC-SHA256 con una llave del aparato: la misma tarjeta da la misma huella, y sin la llave no se vuelve al dato.
 * Ola final, 4ª parte (R24-4): ya no hay puerto (`HuellaDeTarjeta`). Era una interfaz pública: quien la implementara recibía
 * el PAN y el criptograma, y lo que devolviera se escribía tal cual en la libreta. Clase final; la app sólo le pasa la llave.
 */
internal class HuellaHmac(private val llave: SecretKey) {
    @JvmSynthetic internal fun de(dato: String): String =
        Mac.getInstance("HmacSHA256").run { init(llave); doFinal(dato.toByteArray(Charsets.UTF_8)) }.aHex()

    override fun toString() = "HuellaHmac(oculta)"
}
