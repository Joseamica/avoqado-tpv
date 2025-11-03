package com.jaac.avoqado_tpv.domain.models

/**
 * Errores de dominio relacionados con el procesamiento de pagos
 */
sealed class PaymentError(
    open val message: String,
    open val userMessage: String,
    open val isRecoverable: Boolean = false
) {
    /**
     * Tarjeta declinada por el procesador EMV
     */
    data class CardDeclined(
        override val message: String = "Card declined by EMV processor",
        override val userMessage: String = "Tarjeta declinada. Por favor intente con otra tarjeta."
    ) : PaymentError(message, userMessage, false)

    /**
     * Error al detectar el chip de la tarjeta
     */
    data class ChipDetectionFailed(
        override val message: String = "Failed to detect card chip",
        override val userMessage: String = "No se pudo leer el chip. Por favor reinserte la tarjeta."
    ) : PaymentError(message, userMessage, true)

    /**
     * Error de timeout en la operación
     */
    data class Timeout(
        override val message: String = "Operation timed out",
        override val userMessage: String = "Tiempo de espera agotado. Por favor intente nuevamente."
    ) : PaymentError(message, userMessage, true)

    /**
     * Tarjeta retirada antes de completar la transacción
     */
    data class CardWithdrawn(
        override val message: String = "Card was withdrawn before completing transaction",
        override val userMessage: String = "La tarjeta fue retirada. Por favor complete la operación."
    ) : PaymentError(message, userMessage, true)

    /**
     * Operación cancelada por el usuario
     */
    data class UserCancelled(
        override val message: String = "Operation cancelled by user",
        override val userMessage: String = "Operación cancelada."
    ) : PaymentError(message, userMessage, false)

    /**
     * Tarjeta inválida o no soportada
     */
    data class InvalidCard(
        override val message: String = "Invalid or unsupported card",
        override val userMessage: String = "Tarjeta inválida o no soportada."
    ) : PaymentError(message, userMessage, false)

    /**
     * Transacción EMV incompleta
     */
    data class EmvIncomplete(
        override val message: String = "EMV transaction incomplete",
        override val userMessage: String = "Transacción incompleta. Por favor intente nuevamente."
    ) : PaymentError(message, userMessage, true)

    /**
     * Error general de EMV
     */
    data class EmvError(
        override val message: String = "EMV processing error",
        override val userMessage: String = "Error al procesar la transacción. Intente nuevamente."
    ) : PaymentError(message, userMessage, true)

    /**
     * Error al detectar tarjeta contactless
     */
    data class ContactlessDetectionFailed(
        override val message: String = "Failed to detect contactless card",
        override val userMessage: String = "No se pudo leer la tarjeta contactless. Intente con chip."
    ) : PaymentError(message, userMessage, true)

    /**
     * Error al leer banda magnética
     */
    data class MagneticStripeReadFailed(
        override val message: String = "Failed to read magnetic stripe",
        override val userMessage: String = "Error al leer la banda magnética. Intente con chip."
    ) : PaymentError(message, userMessage, true)

    /**
     * Monto de transacción inválido
     */
    data class InvalidAmount(
        override val message: String = "Invalid transaction amount",
        override val userMessage: String = "Monto inválido. Por favor verifique el monto."
    ) : PaymentError(message, userMessage, false)

    /**
     * SDK no inicializado
     */
    data class SdkNotInitialized(
        override val message: String = "Payment SDK not initialized",
        override val userMessage: String = "Error de inicialización. Reinicie la aplicación."
    ) : PaymentError(message, userMessage, false)

    /**
     * Error desconocido
     */
    data class Unknown(
        override val message: String = "Unknown error occurred",
        override val userMessage: String = "Error desconocido. Por favor intente nuevamente."
    ) : PaymentError(message, userMessage, true)
}
