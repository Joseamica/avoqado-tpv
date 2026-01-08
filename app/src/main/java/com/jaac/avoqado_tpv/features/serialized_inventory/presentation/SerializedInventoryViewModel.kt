package com.jaac.avoqado_tpv.features.serialized_inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleLabels
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.InventoryScanResult
import com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.RegistrationResult
import com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.SerializedInventoryUiState
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.serialized_sale.domain.repository.SerializedSaleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for SerializedInventoryScreen (Alta de Productos flow)
 *
 * Flow:
 * 1. Select category first
 * 2. Scan multiple barcodes (batch collection)
 * 3. Review scanned items
 * 4. Register batch → API creates items
 */
@HiltViewModel
class SerializedInventoryViewModel @Inject constructor(
    private val serializedSaleRepository: SerializedSaleRepository,
    private val modulesRepository: ModulesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SerializedInventoryUiState())
    val uiState: StateFlow<SerializedInventoryUiState> = _uiState.asStateFlow()

    // Labels from module config (e.g., "SIM", "ICCID")
    val labels: ModuleLabels?
        get() = modulesRepository.getModuleConfig(ModulesRepository.MODULE_SERIALIZED_INVENTORY)?.labels

    init {
        loadCategories()
    }

    /**
     * Load categories for the venue.
     */
    private fun loadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            serializedSaleRepository.getCategories()
                .onSuccess { categories ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            categories = categories
                        )
                    }
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to load categories")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Error al cargar categorías: ${error.message}"
                        )
                    }
                }
        }
    }

    /**
     * Select a category for registration.
     */
    fun onCategorySelected(category: CategoryWithStock) {
        _uiState.update {
            it.copy(
                selectedCategory = category,
                error = null
            )
        }
    }

    /**
     * Start scanning mode.
     */
    fun startScanning() {
        if (_uiState.value.selectedCategory == null) {
            _uiState.update { it.copy(error = "Selecciona una categoría primero") }
            return
        }
        _uiState.update { it.copy(isScanning = true, error = null) }
    }

    /**
     * Stop scanning mode.
     */
    fun stopScanning() {
        _uiState.update { it.copy(isScanning = false) }
    }

    /**
     * Handle barcode scan result.
     *
     * @param serialNumber The scanned barcode/serial number
     * @return ScanResult indicating if it was added, duplicate, or already scanned
     */
    fun onBarcodeScanned(serialNumber: String): InventoryScanResult {
        val currentList = _uiState.value.scannedSerialNumbers

        // Check if already in current batch
        if (currentList.contains(serialNumber)) {
            Timber.d("Barcode already scanned in batch: $serialNumber")
            return InventoryScanResult.AlreadyScanned(serialNumber)
        }

        // Add to list
        _uiState.update {
            it.copy(
                scannedSerialNumbers = currentList + serialNumber,
                error = null
            )
        }

        Timber.d("Barcode added to batch: $serialNumber (total: ${currentList.size + 1})")
        return InventoryScanResult.Added(serialNumber)
    }

    /**
     * Remove a serial number from the scanned list.
     */
    fun removeSerialNumber(serialNumber: String) {
        _uiState.update {
            it.copy(
                scannedSerialNumbers = it.scannedSerialNumbers.filter { it != serialNumber }
            )
        }
    }

    /**
     * Clear all scanned serial numbers.
     */
    fun clearScannedList() {
        _uiState.update {
            it.copy(
                scannedSerialNumbers = emptyList(),
                registrationResult = null,
                successMessage = null
            )
        }
    }

    /**
     * Register the batch of scanned items.
     */
    fun registerBatch(onSuccess: () -> Unit = {}) {
        val state = _uiState.value
        val categoryId = state.selectedCategory?.id

        if (categoryId == null) {
            _uiState.update { it.copy(error = "Selecciona una categoría") }
            return
        }

        if (state.scannedSerialNumbers.isEmpty()) {
            _uiState.update { it.copy(error = "Escanea al menos un artículo") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            serializedSaleRepository.registerBatch(
                categoryId = categoryId,
                serialNumbers = state.scannedSerialNumbers
            )
                .onSuccess { (created, duplicates) ->
                    Timber.d("Batch registration success: $created created, ${duplicates.size} duplicates")

                    val result = RegistrationResult(created, duplicates)
                    val message = buildString {
                        append("$created artículos registrados")
                        if (duplicates.isNotEmpty()) {
                            append(" (${duplicates.size} duplicados)")
                        }
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            registrationResult = result,
                            successMessage = message,
                            // Clear list after successful registration
                            scannedSerialNumbers = emptyList()
                        )
                    }

                    // Reload categories to update stock counts
                    loadCategories()

                    onSuccess()
                }
                .onFailure { error ->
                    Timber.e(error, "Batch registration failed")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Error al registrar artículos"
                        )
                    }
                }
        }
    }

    /**
     * Dismiss error message.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Dismiss success message.
     */
    fun dismissSuccess() {
        _uiState.update { it.copy(successMessage = null, registrationResult = null) }
    }

    /**
     * Reset state for new registration session.
     */
    fun resetForNewSession() {
        _uiState.update {
            SerializedInventoryUiState(
                categories = it.categories,
                selectedCategory = it.selectedCategory
            )
        }
    }
}
