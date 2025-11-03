package com.jaac.avoqado_tpv.presentation.payment

import com.jaac.avoqado_tpv.domain.models.CardholderVerificationMethod
import com.jaac.avoqado_tpv.domain.models.PaymentError
import com.jaac.avoqado_tpv.domain.models.TransactionResult

/**
 * Estados de la UI para el flujo de pago
 *
 * Representa todos los posibles estados por los que pasa
 * la interfaz durante el procesamiento de un pago
 */
sealed class PaymentState {
    /**
     * Estado inicial - esperando acción del usuario
     */
    data object Idle : PaymentState()

    /**
     * Configurando parámetros del kernel EMV
     */
    data object ConfiguringKernel : PaymentState()

    /**
     * Esperando que el usuario presente la tarjeta
     * @param instructions Instrucciones específicas para el usuario
     */
    data class WaitingForCard(
        val instructions: String = "Inserte, deslice o acerque su tarjeta"
    ) : PaymentState()

    /**
     * Procesando la tarjeta detectada
     * @param cardType Tipo de entrada detectada (chip, contactless, magnetic)
     */
    data class ProcessingCard(
        val cardType: String
    ) : PaymentState()

    /**
     * Requiere PIN del tarjetahabiente
     * @param pinType Tipo de PIN requerido (online u offline)
     */
    data class RequiresPin(
        val pinType: CardholderVerificationMethod
    ) : PaymentState()

    /**
     * Requiere firma del tarjetahabiente
     * @param transactionData Datos de la transacción para mostrar
     */
    data class RequiresSignature(
        val transactionData: TransactionResult
    ) : PaymentState()

    /**
     * Conectando con el procesador para autorización online
     */
    data object ConnectingToProcessor : PaymentState()

    /**
     * Transacción completada exitosamente
     * @param result Resultado completo de la transacción
     */
    data class Success(
        val result: TransactionResult
    ) : PaymentState()

    /**
     * Error durante el procesamiento
     * @param error Detalle del error ocurrido
     */
    data class Error(
        val error: PaymentError
    ) : PaymentState()
}

/**
 * Eventos que puede emitir el ViewModel al presentador
 */
sealed class PaymentEvent {
    /**
     * Mostrar mensaje toast
     */
    data class ShowToast(val message: String) : PaymentEvent()

    /**
     * Navegar a pantalla de receipt
     */
    data class NavigateToReceipt(val transactionId: String) : PaymentEvent()

    /**
     * Reproducir sonido de éxito
     */
    data object PlaySuccessSound : PaymentEvent()

    /**
     * Reproducir sonido de error
     */
    data object PlayErrorSound : PaymentEvent()

    /**
     * Vibrar el dispositivo
     */
    data class Vibrate(val durationMs: Long = 200) : PaymentEvent()

    /**
     * Solicitar input de PIN al usuario
     */
    data class RequestPinInput(
        val isOnline: Boolean
    ) : PaymentEvent()

    /**
     * Solicitar captura de firma
     */
    data object RequestSignatureCapture : PaymentEvent()
}
