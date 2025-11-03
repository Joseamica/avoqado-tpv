package com.jaac.avoqado_tpv.presentation.payment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardParams
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardParams
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase
import com.blumonpay.pax.shared.trans_process.domain.entity.TransType
import com.blumonpay.pax.shared.trans_process.domain.use_case.pre_trans.PreTransParams
import com.blumonpay.pax.shared.trans_process.domain.use_case.pre_trans.PreTransUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransUseCase
import com.blumonpay.pax.shared_tools.manager.CountryConstants
import com.jaac.avoqado_tpv.domain.models.PaymentRequest
import com.pax.dal.entity.EReaderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel para el flujo de pagos
 *
 * Orquesta el flujo completo de procesamiento de pagos EMV:
 * 1. Pre-transacción (configuración de kernel)
 * 2. Detección de tarjeta
 * 3. Procesamiento EMV
 * 4. Completar transacción
 *
 * @param preTransUseCase Use case para configurar parámetros EMV
 * @param startDetectCardUseCase Use case para iniciar detección de tarjeta
 * @param stopDetectCardUseCase Use case para detener detección
 * @param startEmvTransUseCase Use case para procesar transacción EMV
 */
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val preTransUseCase: PreTransUseCase,
    private val startDetectCardUseCase: StartDetectCardUseCase,
    private val stopDetectCardUseCase: StopDetectCardUseCase,
    private val startEmvTransUseCase: StartEmvTransUseCase
) : ViewModel() {

    // Estado actual del pago
    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val paymentState: StateFlow<PaymentState> = _paymentState.asStateFlow()

    // Canal para eventos one-shot
    private val _paymentEvents = Channel<PaymentEvent>(Channel.BUFFERED)
    val paymentEvents = _paymentEvents.receiveAsFlow()

    /**
     * Inicia el flujo de pago con un monto específico
     *
     * @param paymentRequest Solicitud de pago con monto y detalles
     */
    fun startPayment(paymentRequest: PaymentRequest) {
        if (!paymentRequest.isValid()) {
            viewModelScope.launch {
                _paymentState.value = PaymentState.Error(
                    com.jaac.avoqado_tpv.domain.models.PaymentError.InvalidAmount()
                )
                _paymentEvents.send(PaymentEvent.ShowToast("Monto inválido"))
            }
            return
        }

        viewModelScope.launch {
            try {
                _paymentState.value = PaymentState.ConfiguringKernel

                // Paso 1: Configurar parámetros del kernel EMV (Pre-Trans)
                launchPreTrans(paymentRequest)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /**
     * Paso 1: Configurar parámetros del kernel EMV
     */
    private suspend fun launchPreTrans(paymentRequest: PaymentRequest) {
        try {
            val params = PreTransParams(
                amount = paymentRequest.getAmountForSDK(),
                otherAmount = paymentRequest.getOtherAmountForSDK(),
                transType = mapTransType(paymentRequest.transactionType),
                countryConstants = mapCountry(paymentRequest.country)
            )

            // Ejecutar pre-transacción
            preTransUseCase.runInfallible(params)

            android.util.Log.d("PaymentViewModel", "PreTrans completado, iniciando detección de tarjeta")

            // Continuar con detección de tarjeta
            _paymentState.value = PaymentState.WaitingForCard()
            launchStartDetectCard()

        } catch (e: Exception) {
            android.util.Log.e("PaymentViewModel", "Error en PreTrans", e)
            handleError(e)
        }
    }

    /**
     * Paso 2: Iniciar detección de tarjeta
     */
    private suspend fun launchStartDetectCard() {
        try {
            // EReaderType.MAG_ICC_PICC activa todos los lectores
            val params = StartDetectCardParams(readerType = EReaderType.MAG_ICC_PICC)

            android.util.Log.d("PaymentViewModel", "Iniciando detección de tarjeta (MAG_ICC_PICC)")

            _paymentState.value = PaymentState.WaitingForCard(
                instructions = "Inserte, deslice o acerque su tarjeta"
            )

            // NOTA: El SDK usa un patrón complejo con Flows que requiere más investigación
            // Por ahora, el flujo quedará esperando la tarjeta
            // Para completarlo, necesitamos entender mejor la API del SDK

            android.util.Log.d("PaymentViewModel", "Esperando tarjeta... (implementación pendiente)")

        } catch (e: Exception) {
            android.util.Log.e("PaymentViewModel", "Excepción en detección de tarjeta", e)
            handleError(e)
        }
    }

    /**
     * Detiene la detección de tarjeta
     * Se llama cuando el usuario cancela o hay un error
     */
    fun stopDetection() {
        viewModelScope.launch {
            try {
                val params = StopDetectCardParams()
                // stopDetectCardUseCase.runInfallible(params)

                _paymentState.value = PaymentState.Idle
                _paymentEvents.send(PaymentEvent.ShowToast("Operación cancelada"))
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /**
     * Cancela la transacción actual
     */
    fun cancelTransaction() {
        stopDetection()
    }

    /**
     * Reinicia el estado a Idle
     */
    fun resetState() {
        _paymentState.value = PaymentState.Idle
    }

    /**
     * Maneja errores y actualiza el estado
     */
    private suspend fun handleError(error: Exception) {
        val paymentError = com.jaac.avoqado_tpv.domain.models.PaymentError.Unknown(
            message = error.message ?: "Error desconocido"
        )

        _paymentState.value = PaymentState.Error(paymentError)
        _paymentEvents.send(PaymentEvent.PlayErrorSound)
        _paymentEvents.send(PaymentEvent.ShowToast(paymentError.userMessage))
    }

    // Mappers de tipos de dominio a tipos del SDK
    private fun mapTransType(type: com.jaac.avoqado_tpv.domain.models.TransactionType): TransType {
        return when (type) {
            com.jaac.avoqado_tpv.domain.models.TransactionType.SALE -> TransType.SALE
            com.jaac.avoqado_tpv.domain.models.TransactionType.REFUND -> TransType.REFUND
            else -> TransType.SALE // Default to SALE for unsupported types
        }
    }

    private fun mapCountry(country: com.jaac.avoqado_tpv.domain.models.Country): CountryConstants {
        return when (country) {
            com.jaac.avoqado_tpv.domain.models.Country.MEXICO -> CountryConstants.MEX
            else -> CountryConstants.MEX // Default to Mexico
        }
    }
}
