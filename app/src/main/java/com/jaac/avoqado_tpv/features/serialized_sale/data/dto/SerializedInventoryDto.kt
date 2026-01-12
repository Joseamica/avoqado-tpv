package com.jaac.avoqado_tpv.features.serialized_sale.data.dto

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

// ========== Scan DTOs ==========

/**
 * Request body for scanning a serialized item
 * POST /tpv/serialized-inventory/scan
 */
data class ScanRequestDto(
    val serialNumber: String
)

/**
 * Response from scan endpoint
 */
data class ScanResponseDto(
    val found: Boolean,
    val item: SerializedItemDto?,
    val category: ItemCategoryDto?,
    val status: String, // 'available' | 'already_sold' | 'not_registered' | 'module_disabled'
    val suggestedPrice: Double?
)

/**
 * Serialized item (SIM, jewelry piece, device, etc.)
 */
data class SerializedItemDto(
    val id: String,
    val venueId: String,
    val categoryId: String,
    val serialNumber: String,
    val status: String, // 'AVAILABLE' | 'SOLD'
    val soldAt: String?,
    val orderItemId: String?,
    val createdAt: String,
    val category: ItemCategoryDto?
)

/**
 * Item category (SIM type, jewelry type, etc.)
 */
data class ItemCategoryDto(
    val id: String,
    val venueId: String,
    val name: String, // "SIM Negra", "SIM Blanca", etc.
    val description: String?,
    val suggestedPrice: String?, // Decimal as string
    val sortOrder: Int?
)

// ========== Quick Sell DTOs ==========

/**
 * Request body for quick sell
 * POST /tpv/serialized-inventory/sell
 */
data class QuickSellRequestDto(
    val serialNumber: String,
    val categoryId: String?, // Optional if item is already registered
    val price: Double, // Price in pesos
    val paymentMethodId: String? = null,
    val notes: String? = null
)

/**
 * Response from quick sell endpoint (returns Order)
 */
data class QuickSellResponseDto(
    val id: String,
    val orderNumber: String,
    val venueId: String,
    val subtotal: String, // Decimal as string
    val total: String,
    val status: String,
    val items: List<OrderItemDto>?
)

data class OrderItemDto(
    val id: String,
    val productName: String,
    val productSku: String?,
    val unitPrice: String,
    val quantity: Int,
    val subtotal: String
)

// ========== Categories DTOs ==========

/**
 * Response from categories endpoint
 * GET /tpv/serialized-inventory/categories
 */
data class CategoriesResponseDto(
    val success: Boolean,
    val data: List<CategoryWithStockDto>?
)

/**
 * Category with stock counts
 */
data class CategoryWithStockDto(
    val id: String,
    val name: String,
    val description: String?,
    val suggestedPrice: String?,
    val availableCount: Int
)

// ========== Register Batch DTOs ==========

/**
 * Request body for batch registration
 * POST /tpv/serialized-inventory/register-batch
 */
data class RegisterBatchRequestDto(
    val categoryId: String,
    val serialNumbers: List<String>
)

/**
 * Response from batch registration
 */
data class RegisterBatchResponseDto(
    val success: Boolean,
    val data: RegisterBatchResultDto?
)

data class RegisterBatchResultDto(
    val created: Int,
    val duplicates: List<String>
)
