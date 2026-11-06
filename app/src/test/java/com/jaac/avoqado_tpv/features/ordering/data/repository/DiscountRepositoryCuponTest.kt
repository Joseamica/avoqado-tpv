package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.checkout.data.DescuentoDto
import com.jaac.avoqado_tpv.features.ordering.data.api.DiscountApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.CouponValidationResponse
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.math.BigDecimal
import java.net.UnknownHostException

/**
 * 🔴 D5 (5-nov): en producción había 8 cupones activos y 0 canjes. El mapeo dejaba `id` y `code` vacíos, así que la venta salía con
 * `orderDiscountId: ""` y el SERVIDOR la rechazaba. Los cuerpos son la respuesta REAL de `POST /tpv/venues/:id/coupons/validate`
 * (`discount.tpv.service.ts#validateCoupon`), también la de un SERVIDOR anterior a D5.
 */
class DiscountRepositoryCuponTest {

    private val api = mockk<DiscountApiService>()
    private val repo = DiscountRepositoryImpl(api)

    private fun responde(cuerpo: String) {
        coEvery { api.validateCoupon(any(), any()) } returns Response.success(Gson().fromJson(cuerpo, CouponValidationResponse::class.java))
    }

    @Test
    fun `P1 un cupon valido conserva su codigo, su id, su alcance y su tope`() = runTest {
        responde(
            """{"success":true,"data":{"valid":true,"message":"Coupon is valid","discountName":"Diez","discountType":"PERCENTAGE",
               "discountValue":10,"estimatedSavings":10,"couponId":"cup-1","code":"DESC10","scope":"ORDER","maxDiscountAmount":50}}""",
        )
        val cupon = repo.validateCoupon("v-1", "DESC10", 100.0, "c-1").getOrThrow()
        assertTrue(cupon.isValid)
        assertEquals("cup-1", cupon.id)
        assertEquals("DESC10", cupon.code)
        assertEquals("Diez", cupon.name)
        assertEquals(DiscountType.PERCENTAGE, cupon.type)
        assertEquals(0, BigDecimal(10).compareTo(cupon.value))
        assertEquals("ORDER", cupon.scope)
        assertEquals(0, BigDecimal(50).compareTo(cupon.maxDiscountAmount))
    }

    @Test
    fun `un servidor anterior a D5 se sigue leyendo`() = runTest {
        responde(
            """{"success":true,"data":{"valid":true,"message":"Coupon is valid","discountName":"Diez","discountType":"FIXED_AMOUNT",
               "discountValue":15,"estimatedSavings":15}}""",
        )
        val cupon = repo.validateCoupon("v-1", "SAVE10", 100.0).getOrThrow()
        assertEquals(DiscountType.FIXED, cupon.type)
        assertEquals("", cupon.code)
        assertNull(cupon.scope)
        assertNull(cupon.maxDiscountAmount)
    }

    @Test
    fun `P1 cada rechazo llega con su razon`() = runTest {
        responde("""{"success":false,"data":{"valid":false,"message":"Este cliente ya usó este cupón 1 vez.","errorCode":"CUSTOMER_LIMIT"}}""")
        val cupon = repo.validateCoupon("v-1", "DESC10", 100.0, "c-1").getOrThrow()
        assertFalse(cupon.isValid)
        assertEquals("Este cliente ya usó este cupón 1 vez.", cupon.invalidReason)
    }

    @Test
    fun `una cortesia del catalogo regala toda la base`() = runTest {
        responde(
            """{"success":true,"data":{"valid":true,"message":"Coupon is valid","discountName":"Gratis","discountType":"COMP",
               "discountValue":0,"estimatedSavings":100,"couponId":"c","code":"GRATIS","scope":"ORDER","maxDiscountAmount":null}}""",
        )
        val cupon = repo.validateCoupon("v-1", "GRATIS", 100.0).getOrThrow()
        assertEquals(DiscountType.PERCENTAGE, cupon.type)
        assertEquals(0, BigDecimal(100).compareTo(cupon.value))
    }

    @Test
    fun `P1 sin respuesta dice Sin conexion, nunca el texto del sistema ni que el cupon no sirve`() = runTest {
        coEvery { api.validateCoupon(any(), any()) } throws UnknownHostException("Unable to resolve host api.avoqado.io")
        val fallo = repo.validateCoupon("v-1", "DESC10", 100.0).exceptionOrNull()!!
        assertEquals(TEXTO_CUPON_SIN_RED_AL_VALIDAR, fallo.message)
    }

    @Test
    fun `un 500 o un intermediario no dicen que el cupon no sirve, un 4xx trae su razon`() = runTest {
        coEvery { api.validateCoupon(any(), any()) } returns Response.error(500, "{}".toResponseBody())
        assertEquals(TEXTO_NO_SE_PUDO_VALIDAR_EL_CUPON, repo.validateCoupon("v-1", "DESC10", 100.0).exceptionOrNull()!!.message)
        coEvery { api.validateCoupon(any(), any()) } returns Response.error(400, """{"message":"El código del cupón debe tener al menos 3 caracteres."}""".toResponseBody())
        assertEquals("El código del cupón debe tener al menos 3 caracteres.", repo.validateCoupon("v-1", "AB", 100.0).exceptionOrNull()!!.message)
        coEvery { api.validateCoupon(any(), any()) } returns Response.error(403, "<html>portal</html>".toResponseBody())
        assertEquals(TEXTO_NO_SE_PUDO_VALIDAR_EL_CUPON, repo.validateCoupon("v-1", "DESC10", 100.0).exceptionOrNull()!!.message)
    }

    @Test
    fun `al rechazar la venta, la razon del cupon sale del servidor`() {
        assertEquals("Este cupón ya venció.", mensajeDelCuponRechazado("""{"success":false,"message":"Este cupón ya venció."}"""))
        assertNull(mensajeDelCuponRechazado("<html>portal</html>"))
        assertNull(mensajeDelCuponRechazado(null))
    }

    @Test
    fun `el carrito durable conserva el cupon y su tope`() {
        val cupon = Discount(
            id = "cup-1",
            name = "Diez (DESC10)",
            type = DiscountType.PERCENTAGE,
            value = BigDecimal(10),
            scope = DiscountScope.ORDER,
            conditions = null,
            active = true,
            requiresAuthorization = false,
            maxDiscountAmount = BigDecimal("50"),
            couponCode = "DESC10",
        )
        val leido = Gson().fromJson(Gson().toJson(DescuentoDto.desde(cupon)), DescuentoDto::class.java).aDescuento()
        assertEquals("DESC10", leido.couponCode)
        assertEquals(0, BigDecimal(50).compareTo(leido.maxDiscountAmount))
    }
}
