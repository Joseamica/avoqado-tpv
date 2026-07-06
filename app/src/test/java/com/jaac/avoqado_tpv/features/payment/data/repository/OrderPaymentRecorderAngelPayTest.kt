package com.jaac.avoqado_tpv.features.payment.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.DigitalReceiptData
import com.jaac.avoqado_tpv.features.payment.data.dto.OrderPaymentRequest
import com.jaac.avoqado_tpv.features.payment.data.dto.PaymentData
import com.jaac.avoqado_tpv.features.payment.data.dto.PaymentResponse
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.math.BigDecimal

/**
 * Verifies that serialized-inventory (SIM) proof-of-sale metadata rides along on
 * AngelPay ORDER payments — the path SIM sales take (they always carry an orderId).
 *
 * Guards the "no rompe el cobro normal" property: a normal AngelPay order payment
 * (no SIM) must send `isPortabilidad`/`serialNumbers` as null.
 */
class OrderPaymentRecorderAngelPayTest {

    private val apiService = mockk<PaymentApiService>()
    private val recorder = OrderPaymentRecorder(apiService)

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

    private fun angelPayOrderContext(
        isPortabilidad: Boolean = false,
        serialNumbers: List<String> = emptyList(),
    ): PaymentContext.AngelPayPayment = PaymentContext.AngelPayPayment(
        venueId = "venue_1",
        staffId = "staff_1",
        amount = BigDecimal("100.00"),
        orderId = "order_1",
        isPortabilidad = isPortabilidad,
        serialNumbers = serialNumbers,
    )

    @Test
    fun `AngelPay order payment with SIM attaches serialNumbers and isPortabilidad`() = runTest {
        val captured = slot<OrderPaymentRequest>()
        coEvery {
            apiService.recordOrderPayment(venueId = any(), orderId = any(), request = capture(captured))
        } returns successResponse()

        recorder.recordPayment(
            context = angelPayOrderContext(
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
    fun `normal AngelPay order payment leaves serialized fields null`() = runTest {
        val captured = slot<OrderPaymentRequest>()
        coEvery {
            apiService.recordOrderPayment(venueId = any(), orderId = any(), request = capture(captured))
        } returns successResponse()

        recorder.recordPayment(
            context = angelPayOrderContext(),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "AUTH",
            referenceNumber = "REF-1",
        )

        assertThat(captured.captured.isPortabilidad).isNull()
        assertThat(captured.captured.serialNumbers).isNull()
    }
}
