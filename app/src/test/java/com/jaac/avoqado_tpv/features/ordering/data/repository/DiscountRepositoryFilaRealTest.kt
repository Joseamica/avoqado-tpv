package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.ordering.data.api.DiscountApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyCouponDataDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyCouponResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyDiscountDataDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyDiscountResponse
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * D16 (hz:e82938e1:D16) · **TPV SOLA → ÓRDENES**. La fila recién aplicada lleva el id real que da el **SERVIDOR** (antes:
 * `manual_…`, el id del catálogo o el del cupón, y «Eliminar descuento» fallaba). Sin respuesta, el resultado dice «sin confirmar»
 * para que la pantalla no lo reenvíe. Quitar rechazado trae su razón en español.
 */
class DiscountRepositoryFilaRealTest {
    private val api = mockk<DiscountApiService>()
    private val repo = DiscountRepositoryImpl(api)

    private fun aplicado(id: String?) =
        Response.success(ApplyDiscountResponse(success = true, data = ApplyDiscountDataDto(10.0, 90.0, id), message = null, error = null))

    @Test fun `cortesia o manual - la fila lleva el id real del SERVIDOR`() = runTest {
        coEvery { api.applyManualDiscount(any(), any(), any(), any()) } returns aplicado("od-manual")
        val fila = repo.applyManualDiscount("v-1", "o-1", DiscountType.PERCENTAGE, 100.0, "Cortesía").getOrThrow()
        assertThat(fila.id).isEqualTo("od-manual")
    }

    @Test fun `SERVIDOR anterior sin id - la fila sale sin id para que la pantalla no invente uno`() = runTest {
        coEvery { api.applyManualDiscount(any(), any(), any(), any()) } returns aplicado(null)
        val fila = repo.applyManualDiscount("v-1", "o-1", DiscountType.FIXED, 10.0, "x").getOrThrow()
        assertThat(fila.id).isEqualTo(ID_DE_FILA_SIN_CONFIRMAR)
    }

    @Test fun `del catalogo - la fila lleva el id de la fila, no el del catalogo`() = runTest {
        coEvery { api.applyPredefinedDiscount(any(), any(), any()) } returns aplicado("od-catalogo")
        val fila = repo.applyPredefinedDiscount("v-1", "o-1", "d-catalogo").getOrThrow()
        assertThat(fila.id).isEqualTo("od-catalogo")
        assertThat(fila.discountId).isEqualTo("d-catalogo")
    }

    @Test fun `cupon - la fila lleva su id y el cupon el suyo`() = runTest {
        coEvery { api.applyCoupon(any(), any(), any()) } returns Response.success(
            ApplyCouponResponse(true, ApplyCouponDataDto("c-1", "Promo", 10.0, 90.0, "od-cupon"), null, null),
        )
        val fila = repo.applyCoupon("v-1", "o-1", "PROMO").getOrThrow()
        assertThat(fila.id).isEqualTo("od-cupon")
        assertThat(fila.couponCodeId).isEqualTo("c-1")
    }

    @Test fun `aplicar sin respuesta es sin confirmar`() = runTest {
        coEvery { api.applyManualDiscount(any(), any(), any(), any()) } throws IOException("timeout")
        coEvery { api.applyPredefinedDiscount(any(), any(), any()) } throws IOException("timeout")
        assertThat(repo.applyManualDiscount("v-1", "o-1", DiscountType.FIXED, 10.0, "x").exceptionOrNull())
            .isInstanceOf(DescuentoSinConfirmarException::class.java)
        assertThat(repo.applyPredefinedDiscount("v-1", "o-1", "d-1").exceptionOrNull())
            .isInstanceOf(DescuentoSinConfirmarException::class.java)
    }

    @Test fun `aplicar con 5xx es sin confirmar`() = runTest {
        coEvery { api.applyManualDiscount(any(), any(), any(), any()) } returns Response.error(503, "".toResponseBody())
        assertThat(repo.applyManualDiscount("v-1", "o-1", DiscountType.FIXED, 10.0, "x").exceptionOrNull())
            .isInstanceOf(DescuentoSinConfirmarException::class.java)
    }

    @Test fun `del catalogo rechazado trae la razon del SERVIDOR`() = runTest {
        coEvery { api.applyPredefinedDiscount(any(), any(), any()) } returns
            Response.error(400, """{"success":false,"error":"Este descuento no aplica a esta cuenta."}""".toResponseBody())
        assertThat(repo.applyPredefinedDiscount("v-1", "o-1", "d-1").exceptionOrNull()?.message)
            .isEqualTo("Este descuento no aplica a esta cuenta.")
    }

    // --- Quitar ---

    @Test fun `quitar una rebaja fija de vales da la razon de D15, sin mandar a cancelar`() = runTest {
        coEvery { api.removeDiscount(any(), any(), any(), any()) } returns Response.error(
            400,
            """{"message":"Este descuento quedó fijo al cobrar los vales. Cancela la venta y vuelve a cobrar.","code":"AREA_TICKET_DISCOUNT_FROZEN"}"""
                .toResponseBody(),
        )
        assertThat(repo.removeDiscount("v-1", "o-1", "od-1", 3).exceptionOrNull()?.message).isEqualTo(TEXTO_REBAJA_FIJA_DE_VALES)
    }

    @Test fun `quitar una fila que ya no esta lo dice en espanol`() = runTest {
        coEvery { api.removeDiscount(any(), any(), any(), any()) } returns
            Response.error(400, """{"success":false,"error":"Discount not found on this order"}""".toResponseBody())
        assertThat(repo.removeDiscount("v-1", "o-1", "od-1", 3).exceptionOrNull()?.message).isEqualTo(TEXTO_DESCUENTO_YA_NO_ESTABA)
    }

    @Test fun `quitar rechazado con texto en espanol lo muestra tal cual`() = runTest {
        coEvery { api.removeDiscount(any(), any(), any(), any()) } returns Response.error(
            400,
            """{"message":"Esta cuenta ya está pagada: su descuento ya no se puede quitar."}""".toResponseBody(),
        )
        assertThat(repo.removeDiscount("v-1", "o-1", "od-1", 3).exceptionOrNull()?.message)
            .isEqualTo("Esta cuenta ya está pagada: su descuento ya no se puede quitar.")
    }

    @Test fun `quitar sin respuesta o con 5xx es sin confirmar`() = runTest {
        coEvery { api.removeDiscount(any(), any(), "od-red", any()) } throws IOException("sin red")
        coEvery { api.removeDiscount(any(), any(), "od-500", any()) } returns Response.error(500, "".toResponseBody())
        assertThat(repo.removeDiscount("v-1", "o-1", "od-red", 3).exceptionOrNull()).isInstanceOf(DescuentoSinConfirmarException::class.java)
        assertThat(repo.removeDiscount("v-1", "o-1", "od-500", 3).exceptionOrNull()).isInstanceOf(DescuentoSinConfirmarException::class.java)
    }
}
