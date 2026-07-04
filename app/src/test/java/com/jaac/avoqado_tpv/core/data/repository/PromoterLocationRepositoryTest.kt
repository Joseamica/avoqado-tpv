package com.jaac.avoqado_tpv.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.PromoterLocationPingResponseDto
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant

/**
 * PromoterLocationRepository — one "cambaceo" ping per worker run.
 * Error taxonomy matters: NetworkError -> worker retries; HttpError (e.g. 403
 * venue flag off) -> worker must NOT retry (quiet no-op).
 */
class PromoterLocationRepositoryTest {

    private val apiService: ApiService = mockk()
    private val repository = PromoterLocationRepository(apiService)

    @Test
    fun `sendPing returns Success on 201 and sends ISO capturedAt with PERIODIC source`() = runTest {
        coEvery { apiService.sendPromoterLocationPing(any()) } returns
            Response.success(201, PromoterLocationPingResponseDto(success = true, data = null))

        val result = repository.sendPing(
            latitude = 19.4326,
            longitude = -99.1332,
            accuracy = 25f,
            capturedAt = Instant.parse("2026-07-02T17:00:00Z"),
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify {
            apiService.sendPromoterLocationPing(
                match {
                    it.latitude == 19.4326 &&
                        it.longitude == -99.1332 &&
                        it.accuracy == 25f &&
                        it.capturedAt == "2026-07-02T17:00:00Z" &&
                        it.source == "PERIODIC"
                },
            )
        }
    }

    @Test
    fun `sendPing returns HttpError on 403 (venue flag off - must not retry)`() = runTest {
        coEvery { apiService.sendPromoterLocationPing(any()) } returns
            Response.error(403, "{}".toResponseBody())

        val result = repository.sendPing(19.4326, -99.1332, null, Instant.now())

        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(ApiException.HttpError::class.java)
        assertThat((error.exception as ApiException.HttpError).code).isEqualTo(403)
    }

    @Test
    fun `sendPing returns NetworkError on IOException (worker retries)`() = runTest {
        coEvery { apiService.sendPromoterLocationPing(any()) } throws IOException("offline")

        val result = repository.sendPing(19.4326, -99.1332, null, Instant.now())

        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(ApiException.NetworkError::class.java)
    }
}
