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
    val startDate: String = "",
    val endDate: String = "",
    val reference: String = "",
    val terminal: String = "",
    val isLoading: Boolean = false,
    val transactions: List<UnifiedTransaction> = emptyList(),
    val message: String? = null,
)

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

    fun refreshHistory() {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = current.copy(isLoading = true, message = null)

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
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        transactions = items,
                        message = "Se encontraron ${items.size} transacciones",
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

    fun cancelTransaction(transaction: UnifiedTransaction) {
        executePostOperation("cancelación") {
            adapter.cancelTransaction(transaction)
        }
    }

    fun refundTransaction(transaction: UnifiedTransaction) {
        executePostOperation("devolución") {
            adapter.refundTransaction(transaction)
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

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun executePostOperation(
        name: String,
        action: suspend () -> Result<com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationResult>,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            val result = action()
            result.fold(
                onSuccess = { op ->
                    val msg = if (op.approved) {
                        "$name aprobada${op.reference?.let { " (ref: $it)" } ?: ""}"
                    } else {
                        "$name rechazada: ${op.message ?: "sin detalle"}"
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, message = msg)
                    refreshHistory()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "Error en $name: ${error.message}",
                    )
                }
            )
        }
    }

    private fun parseProcessorType(raw: String?): ProcessorType {
        val normalized = raw?.trim()?.uppercase()
        return ProcessorType.entries.firstOrNull { it.name == normalized } ?: ProcessorType.ANGELPAY
    }
}
