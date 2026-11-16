package com.jaac.avoqado_tpv.features.payment.presentation.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

data class PaymentTransactionsUiState(
    val processorType: ProcessorType = ProcessorType.ANGELPAY,
    val canPrintTickets: Boolean = com.jaac.avoqado_tpv.BuildConfig.ENABLE_PAX_SDK,
    val startDate: String = "",
    val endDate: String = "",
    val reference: String = "",
    val terminal: String = "",
    val isLoading: Boolean = false,
    val transactions: List<UnifiedTransaction> = emptyList(),
    val message: String? = null,
)

/**
 * «Transacciones» del superadmin (acceso por TOTP): consulta el historial del procesador, reimprime y manda el ticket.
 *
 * 🔴 SÓLO CONSULTA (D3, encargo 0292fc, 13-nov): antes cancelaba y devolvía llamando directo al adaptador, sin la libreta, sin
 * el candado de «devolución pendiente» y sin registrar nada en Avoqado (Codex r1 #3 de D1). Devolver dinero va sólo por
 * Pagos → el pago → Reembolso (`RecordAngelPayRefundUseCase`). No vuelvas a poner aquí una llamada a `cancelTransaction` ni
 * a `refundTransaction`: lo vigila `TransaccionesSoloConsultaTest`.
 */
@HiltViewModel
class PaymentTransactionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val adapterFactory: PostOperationsAdapterFactory,
) : ViewModel() {

    private val processorType: ProcessorType = parseProcessorType(
        savedStateHandle.get<String>("processorType")
    )
    private val adapter = adapterFactory.get(processorType)
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private val _uiState = MutableStateFlow(
        PaymentTransactionsUiState(
            processorType = processorType,
            startDate = LocalDate.now().format(dateFormatter),
            endDate = LocalDate.now().format(dateFormatter),
        )
    )
    val uiState: StateFlow<PaymentTransactionsUiState> = _uiState.asStateFlow()

    init {
        refreshHistory()
    }

    fun updateStartDate(value: String) {
        _uiState.value = _uiState.value.copy(startDate = value)
    }

    fun updateEndDate(value: String) {
        _uiState.value = _uiState.value.copy(endDate = value)
    }

    fun updateReference(value: String) {
        _uiState.value = _uiState.value.copy(reference = value)
    }

    fun updateTerminal(value: String) {
        _uiState.value = _uiState.value.copy(terminal = value)
    }

    fun refreshHistory(showSummaryMessage: Boolean = true) {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = current.copy(
                isLoading = true,
                message = if (showSummaryMessage) null else current.message,
            )

            val result = adapter.getTransactionHistory(
                TransactionHistoryQuery(
                    startDate = current.startDate,
                    endDate = current.endDate,
                    reference = current.reference.ifBlank { null },
                    terminal = current.terminal.ifBlank { null },
                )
            )

            result.fold(
                onSuccess = { items ->
                    val summaryMessage = "Se encontraron ${items.size} transacciones"
                    val currentMessage = _uiState.value.message
                    val message = if (showSummaryMessage) {
                        summaryMessage
                    } else {
                        val base = currentMessage?.trim().orEmpty()
                        if (base.isBlank()) summaryMessage else "$base\n$summaryMessage"
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        transactions = items,
                        message = message,
                    )
                },
                onFailure = { error ->
                    Timber.e(error, "❌ [Tx] Failed loading history")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        transactions = emptyList(),
                        message = "Error: ${error.message ?: "No se pudo cargar historial"}",
                    )
                }
            )
        }
    }

    fun sendTicketEmail(transaction: UnifiedTransaction, email: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            val result = adapter.sendTicketEmail(transaction, email)
            result.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "Email enviado: $response",
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "Error enviando ticket: ${error.message}",
                    )
                }
            )
        }
    }

    fun getTicketUrl(transaction: UnifiedTransaction) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            val result = adapter.getTicketUrl(transaction)
            result.fold(
                onSuccess = { url ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "URL ticket: $url",
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "Error obteniendo URL: ${error.message}",
                    )
                }
            )
        }
    }

    fun printTicket(transaction: UnifiedTransaction) {
        viewModelScope.launch {
        val result = adapter.printTicket(transaction)
        result.fold(
            onSuccess = {
                _uiState.value = _uiState.value.copy(message = "Ticket enviado a imprimir")
            },
            onFailure = { error ->
                _uiState.value = _uiState.value.copy(message = "Error imprimiendo: ${error.message}")
            }
        )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun parseProcessorType(raw: String?): ProcessorType {
        val normalized = raw?.trim()?.uppercase()
        return ProcessorType.entries.firstOrNull { it.name == normalized } ?: ProcessorType.ANGELPAY
    }
}
