package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.google.common.truth.Truth.assertThat
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

    private fun errorResponse(status: Int, body: String): Response<ProductResponse> =
        Response.error(status, body.toResponseBody("application/json".toMediaType()))
}
