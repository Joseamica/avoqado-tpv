package com.jaac.avoqado_tpv.features.payment.domain.model

/**
 * Detalles de la tarjeta extraídos del Blumon PAX SDK.
 *
 * Esta clase encapsula la información sensible de la tarjeta
 * que se envía al backend para registro de transacciones.
 *
 * **Seguridad (PCI-DSS Compliant):**
 * - maskedPan: Solo últimos 4 dígitos (ej. "411111******1111")
 * - NUNCA almacenamos el PAN completo
 * - NUNCA almacenamos CVV
 * - NUNCA logging de datos sensibles
 *
 * **Origen de datos:**
 * - Track2 (EMV tag 0x57) del chip/contactless
 * - BIN (primeros 6 dígitos) para detectar marca
 * - CardType enum de Blumon SDK para entry mode
 *
 * @param maskedPan Número de tarjeta enmascarado (ej. "411111******1111")
 * @param cardBrand Marca de la tarjeta (VISA, MASTERCARD, etc.)
 * @param entryMode Método de entrada (CHIP, CONTACTLESS, SWIPE)
 * @param isInternational true si es tarjeta internacional (BIN extranjero)
 * @param isCash true si es pago en efectivo (sin tarjeta física)
 */
data class CardDetails(
    val maskedPan: String,
    val cardBrand: CardBrand,
    val entryMode: CardEntryMode,
    val isInternational: Boolean = false,
    val isCash: Boolean = false
) {
    companion object {
        /**
         * Instancia especial para pagos en efectivo.
         *
         * Usado cuando el pago NO involucra lectura de tarjeta física.
         * Backend registrará este pago con method = "CASH".
         */
        val CASH = CardDetails(
            maskedPan = "",
            cardBrand = CardBrand.UNKNOWN,
            entryMode = CardEntryMode.MANUAL,
            isInternational = false,
            isCash = true
        )
    }
}

/**
 * Marca de tarjeta de crédito/débito.
 *
 * Detectada a partir del BIN (primeros 6 dígitos del PAN).
 *
 * **BIN Ranges:**
 * - VISA: 4xxxxx
 * - MASTERCARD: 51-55xxxx, 222100-272099xxxx
 * - AMEX: 34xxxx, 37xxxx
 * - DISCOVER: 6011xx, 622126-622925xxxx, 644-649xxx, 65xxxx
 * - DINERS: 36xxxx, 38xxxx
 * - JCB: 3528-3589xxxx
 */
enum class CardBrand(val displayName: String, val backendName: String) {
    VISA("Visa", "VISA"),
    MASTERCARD("Mastercard", "MASTERCARD"),
    AMEX("American Express", "AMERICAN_EXPRESS"),  // Backend expects AMERICAN_EXPRESS, not AMEX
    DISCOVER("Discover", "DISCOVER"),
    DINERS("Diners Club", "DINERS_CLUB"),  // Backend expects DINERS_CLUB, not DINERS
    JCB("JCB", "JCB"),
    UNKNOWN("Unknown", "UNKNOWN");

    /**
     * Detecta la marca a partir del BIN.
     *
     * @param bin Primeros 6 dígitos del PAN
     * @return CardBrand detectado o UNKNOWN
     */
    companion object {
        fun fromBin(bin: String): CardBrand {
            if (bin.length < 4) return UNKNOWN

            return when {
                bin.startsWith("4") -> VISA
                bin.substring(0, 2).toIntOrNull() in 51..55 -> MASTERCARD
                bin.substring(0, 6).toIntOrNull() in 222100..272099 -> MASTERCARD
                bin.startsWith("34") || bin.startsWith("37") -> AMEX
                bin.startsWith("6011") -> DISCOVER
                bin.substring(0, 6).toIntOrNull() in 622126..622925 -> DISCOVER
                bin.substring(0, 3).toIntOrNull() in 644..649 -> DISCOVER
                bin.startsWith("65") -> DISCOVER
                bin.startsWith("36") || bin.startsWith("38") -> DINERS
                bin.substring(0, 4).toIntOrNull() in 3528..3589 -> JCB
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Método de entrada de la tarjeta en el lector.
 *
 * **Mapeado desde Blumon SDK:**
 * - CardType.ICC → CHIP
 * - CardType.PICC → CONTACTLESS
 * - CardType.MAG → SWIPE
 *
 * **Uso en costos:**
 * - CHIP: Comisión estándar
 * - CONTACTLESS: Comisión estándar (mismo que CHIP)
 * - SWIPE: Comisión más alta (mayor riesgo de fraude)
 */
enum class CardEntryMode(val displayName: String) {
    CHIP("Chip (EMV)"),
    CONTACTLESS("Contactless (NFC)"),
    SWIPE("Swipe (Magnetic)"),
    MANUAL("Manual Entry"),
    OTHER("Other"),
    UNKNOWN("Unknown");

    /**
     * Convierte a string para backend API.
     * Backend espera: "CHIP", "CONTACTLESS", "SWIPE"
     */
    fun toBackendString(): String = this.name
}
