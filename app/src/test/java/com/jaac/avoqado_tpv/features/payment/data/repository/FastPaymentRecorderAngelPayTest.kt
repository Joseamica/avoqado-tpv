package com.jaac.avoqado_tpv.features.payment.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.DigitalReceiptData
import com.jaac.avoqado_tpv.features.payment.data.dto.FastPaymentRequest
import com.jaac.avoqado_tpv.features.payment.data.dto.PaymentData
import com.jaac.avoqado_tpv.features.payment.data.dto.PaymentResponse
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import java.math.BigDecimal

/**
 * Verifies serialized-inventory (SIM) metadata (isPortabilidad/serialNumbers) rides
 * along on AngelPay FAST payments (order-less). Symmetry with
 * [OrderPaymentRecorderAngelPayTest]; also guards the "normal payment unchanged"
 * property. Proof-of-sale photos are NOT part of the payment record — they're POSTed
 * separately, post-payment, keyed by paymentId (mirrors Blumon).
 */
class FastPaymentRecorderAngelPayTest {

    private val apiService = mockk<PaymentApiService>()
    private val recorder = FastPaymentRecorder(apiService)

    private fun successResponse(): Response<PaymentResponse> = Response.success(
        PaymentResponse(
            success = true,
            data = PaymentData(
                id = "pmt_1",
                amount = BigDecimal("100.00"),
                tipAmount = BigDecimal.ZERO,
                authorizationNumber = "AUTH",
                referenceNumber = "REF-1",
                digitalReceipt = DigitalReceiptData(
                    id = "rcp_1",
                    accessKey = "key1",
                    receiptUrl = "https://api.avoqado.io/api/v1/public/receipt/key1",
                ),
            ),
        ),
    )

    private fun angelPayFastContext(
        isPortabilidad: Boolean = false,
        serialNumbers: List<String> = emptyList(),
    ): PaymentContext.AngelPayPayment = PaymentContext.AngelPayPayment(
        venueId = "venue_1",
        staffId = "staff_1",
        amount = BigDecimal("100.00"),
        orderId = null, // order-less → FastPaymentRecorder
        isPortabilidad = isPortabilidad,
        serialNumbers = serialNumbers,
    )

    @Test
    fun `AngelPay fast payment with SIM attaches isPortabilidad and serialNumbers`() = runTest {
        val captured = slot<FastPaymentRequest>()
        coEvery {
            apiService.recordFastPayment(venueId = any(), request = capture(captured))
        } returns successResponse()

        recorder.recordPayment(
            context = angelPayFastContext(
                isPortabilidad = true,
                serialNumbers = listOf("8952140061234567890"),
            ),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "AUTH",
            referenceNumber = "REF-1",
        )

        assertThat(captured.captured.isPortabilidad).isTrue()
        assertThat(captured.captured.serialNumbers).containsExactly("8952140061234567890")
    }

    @Test
    fun `normal AngelPay fast payment leaves serialized fields null`() = runTest {
        val captured = slot<FastPaymentRequest>()
        coEvery {
            apiService.recordFastPayment(venueId = any(), request = capture(captured))
        } returns successResponse()

        recorder.recordPayment(
            context = angelPayFastContext(),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "AUTH",
            referenceNumber = "REF-1",
        )

        assertThat(captured.captured.isPortabilidad).isNull()
        assertThat(captured.captured.serialNumbers).isNull()
    }

    @Test
    fun `C4 la cola fast AngelPay envia turno, replay booleano, hora de la venta y centavos intactos`() = runTest {
        val captured = slot<FastPaymentRequest>()
        coEvery { apiService.recordFastPayment(venueId = any(), request = capture(captured)) } returns successResponse()
        val vivo = angelPayFastContext().copy(shiftId = "shift_789", tip = BigDecimal("5.75"), idempotencyKey = "k-replay")
        val fila = QueuedPayment.desdeContexto(vivo, null, CardDetails.CASH, "EFECTIVO", "CASH-replay", null, createdAt = 1_762_900_000_000L)

        recorder.recordPayment(fila.toPaymentContext(), CardDetails.CASH, "EFECTIVO", "CASH-replay").getOrThrow()

        val esperado = JsonParser.parseString("""{"venueId":"venue_1","amount":10000,"tip":575,"status":"COMPLETED","method":"CASH","source":"AVOQADO_TPV","splitType":"FULLPAYMENT","staffId":"staff_1","authorizationNumber":"EFECTIVO","referenceNumber":"CASH-replay","maskedPan":"","entryMode":"MANUAL","currency":"MXN","isInternational":false,"idempotencyKey":"k-replay","shiftIdDeLaVenta":"shift_789","isOfflineReplay":true,"createdAtLocal":1762900000000}""")
        assertThat(Gson().toJsonTree(captured.captured)).isEqualTo(esperado)
    }

    @Test
    fun `C4 el cobro fast AngelPay en vivo omite turno de venta, replay y hora de la venta en el JSON`() = runTest {
        val captured = slot<FastPaymentRequest>()
        coEvery { apiService.recordFastPayment(venueId = any(), request = capture(captured)) } returns successResponse()
        val vivo = angelPayFastContext().copy(shiftId = "shift_789", tip = BigDecimal("5.75"), idempotencyKey = "k-replay")

        recorder.recordPayment(vivo, CardDetails.CASH, "EFECTIVO", "CASH-replay").getOrThrow()

        val cuerpo = Gson().toJsonTree(captured.captured).asJsonObject
        assertThat(cuerpo.has("shiftIdDeLaVenta")).isFalse()
        assertThat(cuerpo.has("isOfflineReplay")).isFalse()
        assertThat(cuerpo.has("createdAtLocal")).isFalse()
        assertThat(cuerpo["amount"].asInt).isEqualTo(10000)
        assertThat(cuerpo["tip"].asInt).isEqualTo(575)
    }

}
