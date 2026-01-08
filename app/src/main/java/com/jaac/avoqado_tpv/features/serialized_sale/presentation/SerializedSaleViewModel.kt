package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleLabels
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.QuickSellResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.SerializedSaleUiState
import com.jaac.avoqado_tpv.features.serialized_sale.domain.repository.SerializedSaleRepository
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
 * ViewModel for SerializedSaleScreen (Vender flow)
 *
 * Flow:
 * 1. User scans barcode
 * 2. API check returns: available, not_registered, already_sold, or module_disabled
 * 3. If available → show suggested price
 * 4. If not_registered → show category selector
 * 5. User enters/confirms price
 * 6. User confirms sale → quickSell creates order
 * 7. Navigate to PaymentScreen with order ID
 */
@HiltViewModel
class SerializedSaleViewModel @Inject constructor(
    private val serializedSaleRepository: SerializedSaleRepository,
    private val modulesRepository: ModulesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SerializedSaleUiState())
    val uiState: StateFlow<SerializedSaleUiState> = _uiState.asStateFlow()

    // Labels from module config (e.g., "SIM", "ICCID")
    val labels: ModuleLabels?
        get() = modulesRepository.getModuleConfig(ModulesRepository.MODULE_SERIALIZED_INVENTORY)?.labels

    init {
        loadCategories()
    }

    /**
     * Load categories for the venue.
     * Used when item is not registered and user needs to select category.
     */
    private fun loadCategories() {
        viewModelScope.launch {
            serializedSaleRepository.getCategories()
                .onSuccess { categories ->
                    _uiState.update { it.copy(categories = categories) }
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to load categories")
                }
        }
    }

    /**
     * Handle barcode scan result from scanner.
     *
     * @param serialNumber The scanned barcode/serial number
     */
    fun onBarcodeScanned(serialNumber: String) {
        Timber.d("Barcode scanned: $serialNumber")

        _uiState.update {
            it.copy(
                isLoading = true,
                isScanning = false,
                error = null,
                currentSerialNumber = serialNumber
            )
        }

        viewModelScope.launch {
            serializedSaleRepository.scanItem(serialNumber)
                .onSuccess { result ->
                    handleScanResult(result, serialNumber)
                }
                .onFailure { error ->
                    Timber.e(error, "Scan failed")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Error al escanear"
                        )
                    }
                }
        }
    }

    private fun handleScanResult(result: ScanResult, serialNumber: String) {
        when (result) {
            is ScanResult.Available -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        scanResult = result,
                        enteredPrice = result.suggestedPrice?.toPlainString() ?: ""
                    )
                }
            }
            is ScanResult.NotRegistered -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        scanResult = result,
                        enteredPrice = ""
                    )
                }
            }
            is ScanResult.AlreadySold -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        scanResult = result,
                        error = "Este artículo ya fue vendido"
                    )
                }
            }
            is ScanResult.ModuleDisabled -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        scanResult = result,
                        error = "El módulo de inventario serializado no está habilitado"
                    )
                }
            }
        }
    }

    /**
     * Update the entered price.
     *
     * @param price Price string entered by user
     */
    fun onPriceChanged(price: String) {
        // Only allow valid decimal input
        if (price.isEmpty() || price.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
            _uiState.update { it.copy(enteredPrice = price) }
        }
    }

    /**
     * Select a category for unregistered items.
     *
     * @param category The selected category
     */
    fun onCategorySelected(category: CategoryWithStock) {
        _uiState.update {
            it.copy(
                selectedCategory = category,
                enteredPrice = category.suggestedPrice?.toPlainString() ?: ""
            )
        }
    }

    /**
     * Execute the sale.
     *
     * Creates an order via quickSell endpoint.
     * Returns the order info for navigation to PaymentScreen.
     *
     * @param onSuccess Callback with QuickSellResult for navigation
     */
    fun onConfirmSale(onSuccess: (QuickSellResult) -> Unit) {
        val state = _uiState.value
        val price = state.enteredPrice.toBigDecimalOrNull()

        if (price == null || price <= BigDecimal.ZERO) {
            _uiState.update { it.copy(error = "Ingresa un precio válido") }
            return
        }

        val categoryId = when (val result = state.scanResult) {
            is ScanResult.Available -> result.item.categoryId
            is ScanResult.NotRegistered -> state.selectedCategory?.id
            else -> null
        }

        if (state.scanResult is ScanResult.NotRegistered && categoryId == null) {
            _uiState.update { it.copy(error = "Selecciona una categoría") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            serializedSaleRepository.quickSell(
                serialNumber = state.currentSerialNumber,
                categoryId = categoryId,
                price = price
            )
                .onSuccess { result ->
                    Timber.d("Quick sell success: ${result.orderNumber}")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            sellResult = result
                        )
                    }
                    onSuccess(result)
                }
                .onFailure { error ->
                    Timber.e(error, "Quick sell failed")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Error al procesar venta"
                        )
                    }
                }
        }
    }

    /**
     * Reset state to scan another item.
     */
    fun resetForNewScan() {
        _uiState.update {
            SerializedSaleUiState(
                isScanning = true,
                categories = it.categories // Keep categories loaded
            )
        }
    }

    /**
     * Dismiss error message.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Return to scanning mode.
     */
    fun returnToScanner() {
        _uiState.update {
            it.copy(
                isScanning = true,
                scanResult = null,
                enteredPrice = "",
                selectedCategory = null,
                error = null,
                currentSerialNumber = ""
            )
        }
    }
}
