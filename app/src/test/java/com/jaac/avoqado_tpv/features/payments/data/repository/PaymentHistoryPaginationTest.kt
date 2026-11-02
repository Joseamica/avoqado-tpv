package com.jaac.avoqado_tpv.features.payments.data.repository

import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.payments.data.dto.PaymentHistoryResponse
import com.jaac.avoqado_tpv.features.payments.domain.models.PaginatedPayments
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class PaymentHistoryPaginationTest {
    private fun read(meta: String) = runBlocking {
        val api = mockk<ApiService>()
        val body = Gson().fromJson("""{"success":true,"data":[],"meta":$meta}""", PaymentHistoryResponse::class.java)
        coEvery { api.getPaymentHistory(any(), any(), any(), any()) } returns Response.success(body)
        val result = PaymentRepositoryImpl(api).getPaymentHistory("cmvjzw7r70002wamrlf6igos2", 1, 20, null, null, null)
        assertTrue(result.toString(), result is Result.Success)
        (result as Result.Success<PaginatedPayments>).data
    }

    @Test fun `current metadata keeps all 48 records reachable after page one`() {
        val page = read("""{"totalCount":48,"pageSize":20,"currentPage":1,"totalPages":3,"hasNextPage":true}""")
        assertEquals(48, page.total)
        assertEquals(1, page.page)
        assertEquals(20, page.pageSize)
        assertTrue(page.hasMore)
    }

    @Test fun `current metadata ends after page three instead of requesting another page`() {
        val page = read("""{"totalCount":48,"pageSize":20,"currentPage":3,"totalPages":3,"hasNextPage":false}""")
        assertEquals(48, page.total)
        assertEquals(3, page.page)
        assertEquals(false, page.hasMore)
    }

    @Test fun `legacy metadata remains readable`() {
        val page = read("""{"total":48,"pageSize":20,"pageNumber":2}""")
        assertEquals(48, page.total)
        assertEquals(2, page.page)
        assertTrue(page.hasMore)
    }

    @Test fun `empty current history is a real final page`() {
        val page = read("""{"totalCount":0,"pageSize":20,"currentPage":1,"totalPages":0,"hasNextPage":false}""")
        assertEquals(0, page.total)
        assertEquals(1, page.page)
        assertEquals(false, page.hasMore)
    }
}
