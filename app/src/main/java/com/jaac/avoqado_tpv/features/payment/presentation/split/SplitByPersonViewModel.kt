package com.jaac.avoqado_tpv.features.payment.presentation.split

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * Person payment status for equal parts split
 */
data class PersonPayment(
    val index: Int,
    val label: String, // "Persona 1", "Persona 2", etc.
    val amount: BigDecimal,
    val isPaid: Boolean = false
)

/**
 * UI State for SplitByPersonScreen
 */
data class SplitByPersonUiState(
    val isLoading: Boolean = true,
    val order: Order? = null,
    val partySize: Int = 2, // Number of people to split among
    val paidCount: Int = 0, // How many have already paid
    val selectedIndices: Set<Int> = emptySet(), // Which persons are selected for payment
    val error: String? = null
) {
    /**
     * Total amount to split (remaining after any previous payments)
     * ⭐ Uses remainingBalance to support partial payments correctly
     */
    val totalAmount: BigDecimal
        get() = order?.remainingBalance ?: BigDecimal.ZERO

    /**
     * Amount per person (total / partySize)
     */
    val amountPerPerson: BigDecimal
        get() = if (partySize > 0) {
            totalAmount.divide(BigDecimal(partySize), 2, RoundingMode.CEILING)
        } else {
            BigDecimal.ZERO
        }

    /**
     * List of persons with their payment status
     */
    val persons: List<PersonPayment>
        get() = (1..partySize).map { index ->
            PersonPayment(
                index = index,
                label = "Persona $index",
                amount = amountPerPerson,
                isPaid = index <= paidCount
            )
        }

    /**
     * Persons available for selection (not yet paid)
     */
    val availablePersons: List<PersonPayment>
        get() = persons.filter { !it.isPaid }

    /**
     * Persons already paid (show as disabled)
     */
    val paidPersons: List<PersonPayment>
        get() = persons.filter { it.isPaid }

    /**
     * Total amount for selected persons
     */
    val selectedAmount: BigDecimal
        get() = amountPerPerson.multiply(BigDecimal(selectedIndices.size))

    /**
     * Number of selected persons
     */
    val selectedCount: Int
        get() = selectedIndices.size

    /**
     * Can proceed to payment (at least one person selected)
     */
    val canProceed: Boolean
        get() = selectedIndices.isNotEmpty()

    /**
     * Can change party size (only when no one has paid yet)
     */
    val canChangePartySize: Boolean
        get() = paidCount == 0
}

/**
 * SplitByPerson ViewModel
 *
 * Manages state for splitting payment equally among N people.
 *
 * **Flow:**
 * 1. Load order from repository
 * 2. User adjusts party size (if no one paid yet)
 * 3. User selects which persons to pay for
 * 4. Calculate total for selected persons
 * 5. Navigate to PaymentScreen with EQUALPARTS + partySize
 *
 * @param savedStateHandle Navigation args (orderId)
 * @param orderRepository Repository to fetch order
 */
@HiltViewModel
class SplitByPersonViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository,
    private val deviceInfoManager: DeviceInfoManager
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle["orderId"]) {
        "orderId is required for SplitByPersonScreen"
    }

    private val venueId: String
        get() = deviceInfoManager.getVenueId() ?: throw IllegalStateException("VenueId not found")

    private val _uiState = MutableStateFlow(SplitByPersonUiState())
    val uiState: StateFlow<SplitByPersonUiState> = _uiState.asStateFlow()

    init {
        loadOrder()
    }

    /**
     * Load order from repository
     */
    private fun loadOrder() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val result = orderRepository.getOrder(venueId, orderId)
                result.onSuccess { order ->
                    // TODO: Extract paidCount and partySize from previous EQUALPARTS payments
                    // For now, assume fresh split
                    val existingSplit = extractExistingSplit(order)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            order = order,
                            partySize = existingSplit.partySize,
                            paidCount = existingSplit.paidCount
                        )
                    }

                    // 🔍 DEBUG: Split by person order details
                    Timber.i("═══════════════════════════════════════════════════════════")
                    Timber.i("👥 SPLIT BY PERSON - ORDER LOADED")
                    Timber.i("   orderId: ${order.id}")
                    Timber.i("   orderNumber: ${order.orderNumber}")
                    Timber.i("   ─────────────────────────────────────────────────────")
                    Timber.i("   💰 AMOUNTS:")
                    Timber.i("      total: ${order.total}")
                    Timber.i("      paidAmount: ${order.paidAmount}")
                    Timber.i("      remainingBalance: ${order.remainingBalance}")
                    Timber.i("   ─────────────────────────────────────────────────────")
                    Timber.i("   🔀 SPLIT STATE:")
                    Timber.i("      partySize: ${existingSplit.partySize}")
                    Timber.i("      paidCount: ${existingSplit.paidCount}")
                    Timber.i("      amountPerPerson: ${order.remainingBalance.divide(BigDecimal(existingSplit.partySize), 2, RoundingMode.CEILING)}")
                    Timber.i("   ─────────────────────────────────────────────────────")
                    Timber.i("   📦 ITEMS (${order.items.size}):")
                    order.items.forEach { item ->
                        Timber.i("      - ${item.productName} x${item.quantity} = ${item.totalPrice}")
                    }
                    Timber.i("═══════════════════════════════════════════════════════════")
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "No se encontró la orden"
                        )
                    }
                    Timber.e(error, "Failed to load order for split by person")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load order for split by person")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Error cargando la orden: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Extract existing split information from order payments.
     *
     * TODO: Implement once backend returns EQUALPARTS payment data
     */
    private data class ExistingSplit(val partySize: Int, val paidCount: Int)

    private fun extractExistingSplit(order: Order): ExistingSplit {
        // TODO: When backend returns EQUALPARTS payment data:
        // val equalPartsPayments = order.payments.filter { it.splitType == "EQUALPARTS" }
        // if (equalPartsPayments.isNotEmpty()) {
        //     val firstPayment = equalPartsPayments.first()
        //     return ExistingSplit(
        //         partySize = firstPayment.equalPartsPartySize ?: 2,
        //         paidCount = equalPartsPayments.sumOf { it.equalPartsPayedFor ?: 1 }
        //     )
        // }
        return ExistingSplit(partySize = 2, paidCount = 0)
    }

    /**
     * Increment party size
     */
    fun incrementPartySize() {
        if (!_uiState.value.canChangePartySize) return
        _uiState.update { state ->
            if (state.partySize < MAX_PARTY_SIZE) {
                state.copy(
                    partySize = state.partySize + 1,
                    selectedIndices = emptySet() // Clear selection when size changes
                )
            } else state
        }
    }

    /**
     * Decrement party size
     */
    fun decrementPartySize() {
        if (!_uiState.value.canChangePartySize) return
        _uiState.update { state ->
            if (state.partySize > MIN_PARTY_SIZE) {
                state.copy(
                    partySize = state.partySize - 1,
                    selectedIndices = emptySet() // Clear selection when size changes
                )
            } else state
        }
    }

    /**
     * Toggle person selection
     */
    fun togglePerson(index: Int) {
        // Don't allow selecting paid persons
        if (index <= _uiState.value.paidCount) return

        _uiState.update { state ->
            val newSelection = if (index in state.selectedIndices) {
                state.selectedIndices - index
            } else {
                state.selectedIndices + index
            }
            state.copy(selectedIndices = newSelection)
        }
    }

    /**
     * Select all available persons
     */
    fun selectAll() {
        _uiState.update { state ->
            val allAvailableIndices = state.availablePersons.map { it.index }.toSet()
            state.copy(selectedIndices = allAvailableIndices)
        }
    }

    /**
     * Clear all selections
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectedIndices = emptySet()) }
    }

    /**
     * Get payment parameters for navigation
     */
    fun getPaymentParams(): PaymentParams {
        val state = _uiState.value
        return PaymentParams(
            amount = state.selectedAmount,
            partySize = state.partySize,
            payingFor = state.selectedCount
        )
    }

    data class PaymentParams(
        val amount: BigDecimal,
        val partySize: Int,
        val payingFor: Int
    )

    companion object {
        private const val MIN_PARTY_SIZE = 2
        private const val MAX_PARTY_SIZE = 20
    }
}
