package com.jaac.avoqado_tpv.features.serialized_sale.domain.repository

import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.QuickSellResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult
import java.math.BigDecimal

/**
 * Repository interface for serialized inventory operations.
 *
 * This repository handles:
 * - Barcode scanning and item lookup
 * - Category management
 * - Quick sell transactions
 * - Batch registration
 *
 * Used by:
 * - SerializedSaleScreen (Vender flow)
 * - SerializedInventoryScreen (Alta flow)
 */
interface SerializedSaleRepository {

    /**
     * Scan a serialized item by barcode/serial number.
     *
     * @param serialNumber The scanned barcode or serial number
     * @return Result with ScanResult indicating item status
     */
    suspend fun scanItem(serialNumber: String): Result<ScanResult>

    /**
     * Get all categories with stock counts for the venue.
     *
     * @return Result with list of categories and their stock
     */
    suspend fun getCategories(): Result<List<CategoryWithStock>>

    /**
     * Create a new category.
     *
     * Used when no categories exist and user needs to create one from TPV.
     *
     * @param name Category name (e.g., "SIM Movistar", "Anillo de Oro")
     * @param description Optional category description
     * @param suggestedPrice Optional suggested price for items in this category
     * @return Result with the created CategoryWithStock
     */
    suspend fun createCategory(
        name: String,
        description: String? = null,
        suggestedPrice: BigDecimal? = null
    ): Result<CategoryWithStock>

    /**
     * Execute a quick sell for a serialized item.
     *
     * This creates an order with a single item and prepares for payment.
     *
     * @param serialNumber The item's serial number
     * @param categoryId Optional category ID (required if item not registered)
     * @param price Sale price in venue currency
     * @param paymentMethodId Optional payment method
     * @param notes Optional sale notes
     * @param terminalId Optional terminal ID for sales attribution
     * @return Result with QuickSellResult containing order info
     */
    suspend fun quickSell(
        serialNumber: String,
        categoryId: String?,
        price: BigDecimal,
        paymentMethodId: String? = null,
        notes: String? = null,
        terminalId: String? = null,
        isPortabilidad: Boolean = false,
        skipProofOfSale: Boolean = false
    ): Result<QuickSellResult>

    /**
     * Register a batch of serial numbers under a category.
     *
     * Used by the "Alta de Productos" flow.
     *
     * @param categoryId The category to register items under
     * @param serialNumbers List of serial numbers to register
     * @return Result with pair of (created count, duplicate serial numbers)
     */
    suspend fun registerBatch(
        categoryId: String,
        serialNumbers: List<String>
    ): Result<Pair<Int, List<String>>>
}
