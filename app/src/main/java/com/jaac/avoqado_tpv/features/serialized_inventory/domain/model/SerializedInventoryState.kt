package com.jaac.avoqado_tpv.features.serialized_inventory.domain.model

import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock

/**
 * UI state for SerializedInventoryScreen (Alta de Productos)
 */
data class SerializedInventoryUiState(
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val categories: List<CategoryWithStock> = emptyList(),
    val selectedCategory: CategoryWithStock? = null,
    val scannedSerialNumbers: List<String> = emptyList(),
    val error: String? = null,
    val successMessage: String? = null,
    val registrationResult: RegistrationResult? = null
) {
    val canRegister: Boolean
        get() = selectedCategory != null && scannedSerialNumbers.isNotEmpty() && !isLoading

    val scannedCount: Int
        get() = scannedSerialNumbers.size
}

/**
 * Result of batch registration
 */
data class RegistrationResult(
    val created: Int,
    val duplicates: List<String>
) {
    val hasSuccess: Boolean get() = created > 0
    val hasDuplicates: Boolean get() = duplicates.isNotEmpty()
}

/**
 * Scan result for inventory registration
 */
sealed class InventoryScanResult {
    data class Added(val serialNumber: String) : InventoryScanResult()
    data class Duplicate(val serialNumber: String) : InventoryScanResult()
    data class AlreadyScanned(val serialNumber: String) : InventoryScanResult()
}
