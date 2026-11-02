package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.ProductResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.math.BigDecimal

class ProductRepositoryImplTest {
    private val apiService = mockk<ApiService>()
    private val repository = ProductRepositoryImpl(apiService)

    @Test
    fun `quick add preserves the master catalog governance explanation`() = runTest {
        coEvery { apiService.createQuickAddProduct("venue-1", any()) } returns errorResponse(
            422,
            """{"message":"Este producto debe crearse o activarse desde el Catálogo maestro.","code":"CATALOG_GOVERNANCE_REQUIRED"}""",
        )

        val result = repository.createQuickAddProduct(
            venueId = "venue-1",
            barcode = "000123",
            name = "Agua",
            price = BigDecimal("25.00"),
            categoryId = "category-1",
            trackInventory = false,
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message)
            .isEqualTo("Este producto debe crearse o activarse desde el Catálogo maestro.")
    }

    // Un producto escaneado con red lleva al carrito la hora del catálogo del SERVIDOR (raíz de la respuesta, S1) y su gtin.
    @Test
    fun `barcode copies the root catalogAt and the gtin to the product`() = runTest {
        coEvery { apiService.getProductByBarcode("venue-1", "7501055300075") } returns Response.success(
            Gson().fromJson(
                """{"message":"ok","correlationId":"x","catalogAt":"2026-10-20T18:00:00.000Z","data":{
                    "id":"p-1","name":"Agua","description":null,"price":25.0,"type":"SIMPLE","sku":"AGUA-1","gtin":"7501055300075",
                    "categoryId":"cat1","active":true,"displayOrder":1,"trackInventory":false,"inventoryMethod":null,"imageUrl":null,
                    "category":{"id":"cat1","name":"Bebidas","venueId":"venue-1","displayOrder":1,"active":true},
                    "inventory":null,"modifierGroups":[],"availableQuantity":null}}""",
                ProductResponse::class.java,
            ),
        )

        val product = repository.getProductByBarcode("venue-1", "7501055300075").getOrThrow()!!

        assertThat(product.catalogAt).isEqualTo("2026-10-20T18:00:00.000Z")
        assertThat(product.gtin).isEqualTo("7501055300075")
    }

    private fun errorResponse(status: Int, body: String): Response<ProductResponse> =
        Response.error(status, body.toResponseBody("application/json".toMediaType()))
}
