package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.jaac.avoqado_tpv.core.data.network.PermisoRequeridoException
import com.jaac.avoqado_tpv.features.ordering.data.api.DiscountApiService
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DiscountRepositoryManualPermisoTest {
    private val api = mockk<DiscountApiService>()
    private val repo = DiscountRepositoryImpl(api)

    @Test
    fun `403 de orders comp sale como PermisoRequeridoException y el token viaja al API`() = runTest {
        coEvery { api.applyManualDiscount(any(), any(), any(), any()) } returns
            Response.error(403, """{"required":"orders:comp","overridable":true}""".toResponseBody())
        val r = repo.applyManualDiscount("v-1", "o-1", DiscountType.PERCENTAGE, 100.0, "x", null, "tok-1")
        val e = r.exceptionOrNull()
        assertTrue(e is PermisoRequeridoException)
        assertEquals("orders:comp", (e as PermisoRequeridoException).required)
        coVerify(exactly = 1) { api.applyManualDiscount("v-1", "o-1", any(), "tok-1") }
    }
}
