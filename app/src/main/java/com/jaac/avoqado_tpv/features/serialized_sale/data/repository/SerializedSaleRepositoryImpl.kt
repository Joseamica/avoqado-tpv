package com.jaac.avoqado_tpv.features.serialized_sale.data.repository

import android.util.Log
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.ItemCategoryDto
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.QuickSellRequestDto
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.ScanRequestDto
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.SerializedItemDto
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ItemCategory
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ItemStatus
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.QuickSellResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.SerializedItem
import com.jaac.avoqado_tpv.features.serialized_sale.domain.repository.SerializedSaleRepository
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of SerializedSaleRepository.
 *
 * Handles API calls for serialized inventory operations and maps
 * DTOs to domain models.
 */
@Singleton
class SerializedSaleRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : SerializedSaleRepository {

    companion object {
        private const val TAG = "SerializedSaleRepo"
    }

    override suspend fun scanItem(serialNumber: String): Result<ScanResult> {
        return try {
            Log.d(TAG, "Scanning item: $serialNumber")

            val response = apiService.scanSerializedItem(ScanRequestDto(serialNumber))

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    val result = mapScanResponse(body.status, body.item, body.category, body.suggestedPrice, serialNumber)
                    Log.d(TAG, "Scan result: $result")
                    Result.success(result)
                } else {
                    Log.e(TAG, "Empty response body")
                    Result.failure(Exception("Empty response from server"))
                }
            } else {
                val error = response.errorBody()?.string() ?: "Unknown error"
                Log.e(TAG, "Scan failed: ${response.code()} - $error")
                Result.failure(Exception("Scan failed: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            Result.failure(e)
        }
    }

    override suspend fun getCategories(): Result<List<CategoryWithStock>> {
        return try {
            Log.d(TAG, "Fetching categories")

            val response = apiService.getSerializedCategories()

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.data != null) {
                    val categories = body.data.map { dto ->
                        CategoryWithStock(
                            id = dto.id,
                            name = dto.name,
                            description = dto.description,
                            suggestedPrice = dto.suggestedPrice?.toBigDecimalOrNull(),
                            availableCount = dto.availableCount
                        )
                    }
                    Log.d(TAG, "Fetched ${categories.size} categories")
                    Result.success(categories)
                } else {
                    Result.success(emptyList())
                }
            } else {
                val error = response.errorBody()?.string() ?: "Unknown error"
                Log.e(TAG, "Categories fetch failed: ${response.code()} - $error")
                Result.failure(Exception("Failed to fetch categories: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Categories error", e)
            Result.failure(e)
        }
    }

    override suspend fun quickSell(
        serialNumber: String,
        categoryId: String?,
        price: BigDecimal,
        paymentMethodId: String?,
        notes: String?
    ): Result<QuickSellResult> {
        return try {
            Log.d(TAG, "Quick sell: $serialNumber, price: $price, category: $categoryId")

            val request = QuickSellRequestDto(
                serialNumber = serialNumber,
                categoryId = categoryId,
                price = price.toDouble(),
                paymentMethodId = paymentMethodId,
                notes = notes
            )

            val response = apiService.quickSellSerializedItem(request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    val result = QuickSellResult(
                        orderId = body.id,
                        orderNumber = body.orderNumber,
                        total = body.total.toBigDecimalOrNull() ?: price,
                        status = body.status
                    )
                    Log.d(TAG, "Quick sell success: order ${result.orderNumber}")
                    Result.success(result)
                } else {
                    Log.e(TAG, "Empty response body")
                    Result.failure(Exception("Empty response from server"))
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                Log.e(TAG, "Quick sell failed: ${response.code()} - $errorBody")

                // Parse permission errors for better UX
                val userMessage = when {
                    response.code() == 403 && errorBody.contains("Permission") -> {
                        "No tienes permiso para vender. Contacta al administrador."
                    }
                    response.code() == 401 -> {
                        "Sesión expirada. Por favor inicia sesión de nuevo."
                    }
                    else -> {
                        "Error al procesar venta: ${response.code()}"
                    }
                }
                Result.failure(Exception(userMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Quick sell error", e)
            Result.failure(e)
        }
    }

    override suspend fun registerBatch(
        categoryId: String,
        serialNumbers: List<String>
    ): Result<Pair<Int, List<String>>> {
        return try {
            Log.d(TAG, "Register batch: ${serialNumbers.size} items to category $categoryId")

            val request = com.jaac.avoqado_tpv.features.serialized_sale.data.dto.RegisterBatchRequestDto(
                categoryId = categoryId,
                serialNumbers = serialNumbers
            )

            val response = apiService.registerSerializedBatch(request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.data != null) {
                    val result = Pair(body.data.created, body.data.duplicates)
                    Log.d(TAG, "Register batch success: ${result.first} created, ${result.second.size} duplicates")
                    Result.success(result)
                } else {
                    Result.failure(Exception("Registration failed"))
                }
            } else {
                val error = response.errorBody()?.string() ?: "Unknown error"
                Log.e(TAG, "Register batch failed: ${response.code()} - $error")
                Result.failure(Exception("Registration failed: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Register batch error", e)
            Result.failure(e)
        }
    }

    // ========== Private Helpers ==========

    private fun mapScanResponse(
        status: String,
        itemDto: SerializedItemDto?,
        categoryDto: ItemCategoryDto?,
        suggestedPrice: Double?,
        serialNumber: String
    ): ScanResult {
        return when (status) {
            "available" -> {
                val item = itemDto?.let { mapItem(it) }
                    ?: throw IllegalStateException("Item missing for available status")
                val category = categoryDto?.let { mapCategory(it) }
                ScanResult.Available(
                    item = item,
                    category = category,
                    suggestedPrice = suggestedPrice?.toBigDecimal()
                )
            }
            "already_sold" -> {
                val item = itemDto?.let { mapItem(it) }
                    ?: throw IllegalStateException("Item missing for already_sold status")
                ScanResult.AlreadySold(
                    item = item,
                    soldAt = item.soldAt
                )
            }
            "not_registered" -> {
                ScanResult.NotRegistered(serialNumber = serialNumber)
            }
            "module_disabled" -> {
                ScanResult.ModuleDisabled
            }
            else -> {
                Log.w(TAG, "Unknown scan status: $status")
                ScanResult.NotRegistered(serialNumber = serialNumber)
            }
        }
    }

    private fun mapItem(dto: SerializedItemDto): SerializedItem {
        return SerializedItem(
            id = dto.id,
            venueId = dto.venueId,
            categoryId = dto.categoryId,
            serialNumber = dto.serialNumber,
            status = ItemStatus.fromString(dto.status),
            soldAt = dto.soldAt,
            orderItemId = dto.orderItemId,
            createdAt = dto.createdAt,
            category = dto.category?.let { mapCategory(it) }
        )
    }

    private fun mapCategory(dto: ItemCategoryDto): ItemCategory {
        return ItemCategory(
            id = dto.id,
            venueId = dto.venueId,
            name = dto.name,
            description = dto.description,
            suggestedPrice = dto.suggestedPrice?.toBigDecimalOrNull(),
            sortOrder = dto.sortOrder
        )
    }
}
