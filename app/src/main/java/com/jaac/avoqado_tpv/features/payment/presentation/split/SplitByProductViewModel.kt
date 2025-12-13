package com.jaac.avoqado_tpv.features.payment.presentation.split

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * UI State for SplitByProductScreen
 */
data class SplitByProductUiState(
    val isLoading: Boolean = true,
    val order: Order? = null,
    val selectedProductIds: Set<String> = emptySet(),
    val paidProductIds: Set<String> = emptySet(), // Products already paid in previous split
    val error: String? = null
) {
    /**
     * Items available for selection (not yet paid)
     */
    val availableItems: List<OrderItem>
        get() = order?.items?.filter { it.id !in paidProductIds } ?: emptyList()

    /**
     * Items that have been paid (show as disabled)
     */
    val paidItems: List<OrderItem>
        get() = order?.items?.filter { it.id in paidProductIds } ?: emptyList()

    /**
     * Total amount for selected products
     */
    val selectedAmount: BigDecimal
        get() = order?.items
            ?.filter { it.id in selectedProductIds }
            ?.sumOf { it.totalPrice }
            ?: BigDecimal.ZERO

    /**
     * Total remaining to pay
     * Uses remainingBalance to support partial payments correctly
     * (works for EQUALPARTS, CUSTOMAMOUNT, and PERPRODUCT splits)
     */
    val remainingAmount: BigDecimal
        get() = order?.remainingBalance ?: BigDecimal.ZERO

    /**
     * Number of selected products
     */
    val selectedCount: Int
        get() = selectedProductIds.size

    /**
     * Can proceed to payment (at least one product selected)
     */
    val canProceed: Boolean
        get() = selectedProductIds.isNotEmpty()
}

/**
 * SplitByProduct ViewModel
 *
 * Manages state for selecting specific products to pay.
 *
 * **Flow:**
 * 1. Load order from repository
 * 2. User selects products via toggle
 * 3. Calculate total for selected products
 * 4. Navigate to PaymentScreen with PERPRODUCT + productIds
 *
 * @param savedStateHandle Navigation args (orderId)
 * @param orderRepository Repository to fetch order
 */
@HiltViewModel
class SplitByProductViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository,
    private val deviceInfoManager: DeviceInfoManager
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle["orderId"]) {
        "orderId is required for SplitByProductScreen"
    }

    private val venueId: String
        get() = deviceInfoManager.getVenueId() ?: throw IllegalStateException("VenueId not found")

    private val _uiState = MutableStateFlow(SplitByProductUiState())
    val uiState: StateFlow<SplitByProductUiState> = _uiState.asStateFlow()

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
                    // ✅ Use paidItemIds from backend (items with paymentAllocations)
                    val paidProductIds = order.paidItemIds.toSet()

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            order = order,
                            paidProductIds = paidProductIds
                        )
                    }
                    Timber.i("📦 [SplitByProduct] Loaded order: ${order.items.size} items, ${paidProductIds.size} already paid")

                    // Verbose logging only in debug builds to avoid performance impact on production
                    if (BuildConfig.DEBUG) {
                        order.items.forEachIndexed { index, item ->
                            Timber.d("   Item $index: id=${item.id}, product=${item.productName}, qty=${item.quantity}, price=${item.totalPrice}, isPaid=${item.id in paidProductIds}")
                        }
                        if (paidProductIds.isNotEmpty()) {
                            Timber.d("   💰 Paid items: ${paidProductIds.joinToString()}")
                        }
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "No se encontró la orden"
                        )
                    }
                    Timber.e(error, "Failed to load order for split by product")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load order for split by product")
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
     * Toggle product selection
     */
    fun toggleProduct(productId: String) {
        _uiState.update { state ->
            val newSelection = if (productId in state.selectedProductIds) {
                state.selectedProductIds - productId
            } else {
                state.selectedProductIds + productId
            }
            state.copy(selectedProductIds = newSelection)
        }
    }

    /**
     * Select all available products
     */
    fun selectAll() {
        _uiState.update { state ->
            val allAvailableIds = state.availableItems.map { it.id }.toSet()
            state.copy(selectedProductIds = allAvailableIds)
        }
    }

    /**
     * Clear all selections
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectedProductIds = emptySet()) }
    }

    /**
     * Get list of selected product IDs for payment
     */
    fun getSelectedProductIds(): List<String> = _uiState.value.selectedProductIds.toList()

    /**
     * Get selected amount for payment
     */
    fun getSelectedAmount(): BigDecimal = _uiState.value.selectedAmount
}
