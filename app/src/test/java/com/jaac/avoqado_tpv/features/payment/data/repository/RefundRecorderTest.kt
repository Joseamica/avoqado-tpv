package com.jaac.avoqado_tpv.features.payment.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.RefundResponse
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

/**
 * El recorder REAL contra respuestas HTTP reales — auditoría de Codex F3 (4-sep-2026).
 *
 * Antes, todo HTTP no-2xx salía como `Exception("texto amigable")` y hasta el `IOException` se
 * envolvía: `classifySyncFailure` nunca veía un `BackendHttpException`, así que con el recorder de
 * producción la rama «permanente» de la cola de reembolsos era inalcanzable y un 400/422 se
 * reintentaba como si fuera la red. Las pruebas de la cola no lo veían porque inyectaban el
 * `BackendHttpException` directo al mock del recorder — pasaban por el motivo equivocado.
 */
class RefundRecorderTest {

    private val api = mockk<PaymentApiService>()
    private val recorder = RefundRecorder(api)

    private fun contexto() = PaymentContext.RefundPayment(
        venueId = "v1",
        staffId = "s1",
        shiftId = null,
        amount = BigDecimal("50.00"),
        merchantAccountId = "m1",
        blumonSerialNumber = "SER1",
        idempotencyKey = "k-1",
        originalPaymentId = "pay-orig",
        originalOrderId = null,
        originalTotalAmount = BigDecimal("100.00"),
        refundReason = RefundReason.CUSTOMER_REQUEST,
        isPartialRefund = true,
        originalOperationNumber = 1,
        tipRefundCents = null,
    )

    private val tarjeta = CardDetails(maskedPan = "411111******1111", cardBrand = CardBrand.VISA, entryMode = CardEntryMode.CHIP)

    private fun http(code: Int) {
        coEvery { api.recordRefund(any(), any(), any()) } returns
            Response.error(code, """{"success":false,"message":"x"}""".toResponseBody("application/json".toMediaType()))
    }

    private suspend fun registrar() = recorder.recordRefund(
        context = contexto(), cardDetails = tarjeta, authorizationNumber = "502511",
        referenceNumber = "000000188231", tipRefundCents = null, processor = null,
    )

    @Test
    fun `P1 un 422 llega como BackendHttpException y la cola lo clasifica PERMANENTE`() = runTest {
        http(422)
        val error = registrar().exceptionOrNull()
        assertThat(error).isInstanceOf(BackendHttpException::class.java)
        assertThat((error as BackendHttpException).statusCode).isEqualTo(422)
        assertThat(classifySyncFailure(error)).isInstanceOf(SyncOutcome.Permanent::class.java)
    }

    @Test
    fun `400 y 404 tambien son permanentes, con su codigo intacto`() = runTest {
        for (code in listOf(400, 404)) {
            http(code)
            val error = registrar().exceptionOrNull() as BackendHttpException
            assertThat(error.statusCode).isEqualTo(code)
            assertThat(classifySyncFailure(error)).isInstanceOf(SyncOutcome.Permanent::class.java)
        }
    }

    @Test
    fun `401 403 409 429 y 500 se reintentan - la sesion, el ritmo y el servidor no son la operacion`() = runTest {
        for (code in listOf(401, 403, 409, 429, 500)) {
            http(code)
            val error = registrar().exceptionOrNull() as BackendHttpException
            assertThat(error.statusCode).isEqualTo(code)
            assertThat(classifySyncFailure(error)).isEqualTo(SyncOutcome.Retryable)
        }
    }

    @Test
    fun `el mensaje sigue siendo el texto amigable en espanol que ve el cajero`() = runTest {
        http(400)
        assertThat(registrar().exceptionOrNull()!!.message).contains("Datos de reembolso inválidos")
    }

    @Test
    fun `P1 un IOException NO se envuelve - sigue siendo fallo de red reintentable`() = runTest {
        coEvery { api.recordRefund(any(), any(), any()) } throws IOException("sin red")
        val error = registrar().exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(classifySyncFailure(error)).isEqualTo(SyncOutcome.Retryable)
    }

    @Test
    fun `un 200 devuelve el recibo`() = runTest {
        coEvery { api.recordRefund(any(), any(), any()) } returns Response.success(mockk<RefundResponse>(relaxed = true))
        assertThat(registrar().isSuccess).isTrue()
    }
}
