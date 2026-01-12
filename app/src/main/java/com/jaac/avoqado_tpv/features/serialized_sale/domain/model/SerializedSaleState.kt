package com.jaac.avoqado_tpv.features.serialized_sale.domain.model

import java.math.BigDecimal

/**
 * States for the serialized sale flow (Vender)
 */

/**
 * Result of scanning a barcode
 */
sealed class ScanResult {
    /**
     * Item found and available for sale
     */
    data class Available(
        val item: SerializedItem,
        val category: ItemCategory?,
        val suggestedPrice: BigDecimal?
    ) : ScanResult()

    /**
     * Item found but already sold
     */
    data class AlreadySold(
        val item: SerializedItem,
        val soldAt: String?
    ) : ScanResult()

    /**
     * Item not registered in system - need to select category
     */
    data class NotRegistered(
        val serialNumber: String
    ) : ScanResult()

    /**
     * Module is disabled for this venue
     */
    data object ModuleDisabled : ScanResult()
}

/**
 * Serialized item (SIM, jewelry, device, etc.)
 */
data class SerializedItem(
    val id: String,
    val venueId: String,
    val categoryId: String,
    val serialNumber: String,
    val status: ItemStatus,
    val soldAt: String?,
    val orderItemId: String?,
    val createdAt: String,
    val category: ItemCategory?
)

/**
 * Status of a serialized item
 */
enum class ItemStatus {
    AVAILABLE,
    SOLD;

    companion object {
        fun fromString(value: String): ItemStatus {
            return when (value.uppercase()) {
                "AVAILABLE" -> AVAILABLE
                "SOLD" -> SOLD
                else -> AVAILABLE
            }
        }
    }
}

/**
 * Category for serialized items (SIM type, jewelry type, etc.)
 */
data class ItemCategory(
    val id: String,
    val venueId: String,
    val name: String,
    val description: String?,
    val suggestedPrice: BigDecimal?,
    val sortOrder: Int?
)

/**
 * Category with stock counts
 */
data class CategoryWithStock(
    val id: String,
    val name: String,
    val description: String?,
    val suggestedPrice: BigDecimal?,
    val availableCount: Int
)

/**
 * Result of a quick sell operation
 */
data class QuickSellResult(
    val orderId: String,
    val orderNumber: String,
    val total: BigDecimal,
    val status: String
)

/**
 * UI state for SerializedSaleScreen
 */
data class SerializedSaleUiState(
    val isLoading: Boolean = false,
    val isScanning: Boolean = true,
    val scanResult: ScanResult? = null,
    val categories: List<CategoryWithStock> = emptyList(),
    val selectedCategory: CategoryWithStock? = null,
    val enteredPrice: String = "",
    val error: String? = null,
    val sellResult: QuickSellResult? = null,
    val currentSerialNumber: String = ""
) {
    val canProceedToSell: Boolean
        get() = scanResult != null &&
                enteredPrice.isNotEmpty() &&
                enteredPrice.toBigDecimalOrNull() != null &&
                (scanResult is ScanResult.Available ||
                 (scanResult is ScanResult.NotRegistered && selectedCategory != null))

    val displayPrice: BigDecimal?
        get() = enteredPrice.toBigDecimalOrNull()
}
