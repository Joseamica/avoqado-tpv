package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode

/**
 * Traduce lo que AngelPay SÍ dice de la tarjeta (últimos 4 y modo de lectura) a lo que guarda el registro del cobro.
 *
 * Hasta el 23-nov la Nexgo registraba TODO cobro con tarjeta con `maskedPan = ""` y `entryMode = OTHER`, aunque el
 * `PaymentResult` del SDK (y el historial de AngelPay) traen `cardLast4` y `cardEntryMode`: el dueño y soporte no podían
 * saber con qué tarjeta se pagó (hz:248b195b:D27). El servidor ya completa sólo los campos vacíos cuando el registro de la
 * terminal llega después del aviso del banco (`planDeConsolidacion`), así que basta con que la terminal los mande.
 *
 * Puro y tolerante: un dato raro nunca tumba el registro de un cobro; se queda como antes ("" / OTHER).
 */
object TarjetaDeAngelPay {

    /**
     * `************1234` — sólo con exactamente 4 dígitos (PCI: jamás el PAN completo). El servidor lee los últimos 4 con
     * `slice(-4)` y `[0-9]{4}$`, la misma forma que ya usan las ligas de pago. Sin dato válido, `""` como hasta hoy.
     */
    fun numeroEnmascarado(ultimos4: String?): String {
        val digitos = ultimos4?.trim().orEmpty()
        return if (DIGITOS_4.matches(digitos)) "************$digitos" else ""
    }

    /**
     * El texto del SDK 1.0.23 es `chip` · `contactless` · `contactless_card`; el historial puede traerlo en otra forma, así
     * que se lee por contenido. Lo que no se reconoce queda OTHER, como antes.
     */
    fun modoDeLectura(texto: String?): CardEntryMode {
        val t = texto?.trim()?.lowercase().orEmpty()
        return when {
            t.isEmpty() -> CardEntryMode.OTHER
            "contactless" in t || "ctls" in t || "nfc" in t || "sin contacto" in t -> CardEntryMode.CONTACTLESS
            "chip" in t || "icc" in t || "emv" in t -> CardEntryMode.CHIP
            "banda" in t || "magn" in t || "swipe" in t || "msr" in t -> CardEntryMode.SWIPE
            "manual" in t || "digitada" in t -> CardEntryMode.MANUAL
            else -> CardEntryMode.OTHER
        }
    }

    private val DIGITOS_4 = Regex("^[0-9]{4}$")
}
