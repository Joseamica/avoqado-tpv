package com.jaac.avoqado_tpv.features.payment.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.DigitalReceiptData
import com.jaac.avoqado_tpv.features.payment.data.dto.OrderPaymentRequest
import com.jaac.avoqado_tpv.features.payment.data.dto.PaymentData
import com.jaac.avoqado_tpv.features.payment.data.dto.PaymentResponse
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.CardNature
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
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
 * Unit tests for [OrderPaymentRecorder] focused on:
 * 1. The `normalizedCashMerchantAccount()` shim that drops `merchantAccountId` for CASH.
 * 2. The `parseBackendErrorMessage()` JSON-error parser cascade.
 *
 * Both helpers are private. We exercise them through the public `recordPayment(...)` API
 * by capturing the [OrderPaymentRequest] sent to the mocked apiService and the resulting
 * [Result.failure] message.
 *
 * Background: production bug 2026-05-06 — see
 * `/Avoqado-HQ/specs/payment-recording-400-audit-2026-05-06.md`.
 */
class OrderPaymentRecorderTest {

    private val apiService = mockk<PaymentApiService>()
    private val recorder = OrderPaymentRecorder(apiService)

    // region — helpers

    private val merchantCuid = "cmlah9251000ik628rhkkwhp0"

    /** Builds an `OrderPayment` context that mimics the production session leaking a CUID. */
    private fun cashContextWithLeakedMerchantId(): PaymentContext.OrderPayment =
        PaymentContext.OrderPayment(
            venueId = "venue_123",
            staffId = "staff_456",
            shiftId = "shift_789",
            orderId = "order_abc",
            amount = BigDecimal("100.00"),
            tip = BigDecimal.ZERO,
            merchantAccountId = merchantCuid, // ← THE BUG: a CUID is set even though this is CASH
            blumonSerialNumber = "",
            splitType = SplitType.FULLPAYMENT,
        )

    private fun cardContext(): PaymentContext.OrderPayment =
        PaymentContext.OrderPayment(
            venueId = "venue_123",
            staffId = "staff_456",
            orderId = "order_abc",
            amount = BigDecimal("100.00"),
            tip = BigDecimal("5.00"),
            merchantAccountId = merchantCuid, // ← legitimately set for cards
            blumonSerialNumber = "AVQD-2841548417",
            splitType = SplitType.FULLPAYMENT,
        )

    private fun successResponse(): Response<PaymentResponse> = Response.success(
        PaymentResponse(
            success = true,
            data = PaymentData(
                id = "pmt_123",
                amount = BigDecimal("100.00"),
                tipAmount = BigDecimal.ZERO,
                authorizationNumber = "EFECTIVO",
                referenceNumber = "CASH-1",
                digitalReceipt = DigitalReceiptData(
                    id = "rcp_1",
                    accessKey = "key1",
                    receiptUrl = "https://api.avoqado.io/api/v1/public/receipt/key1",
                ),
            ),
        ),
    )

    private fun errorResponse(body: String): Response<PaymentResponse> =
        Response.error(
            400,
            body.toResponseBody("application/json".toMediaTypeOrNull()),
        )

    // endregion

    @Test
    fun `recordPayment drops merchantAccountId when method is CASH`() = runTest {
        val captured = slot<OrderPaymentRequest>()
        coEvery {
            apiService.recordOrderPayment(
                venueId = any(),
                orderId = any(),
                request = capture(captured),
            )
        } returns successResponse()

        val result = recorder.recordPayment(
            context = cashContextWithLeakedMerchantId(),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "EFECTIVO",
            referenceNumber = "CASH-1",
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(captured.captured.method).isEqualTo("CASH")
        // The shim normalised the leaked CUID to null before the POST.
        assertThat(captured.captured.merchantAccountId).isNull()
    }

    @Test
    fun `recordPayment preserves merchantAccountId when method is CREDIT_CARD`() = runTest {
        val captured = slot<OrderPaymentRequest>()
        coEvery {
            apiService.recordOrderPayment(
                venueId = any(),
                orderId = any(),
                request = capture(captured),
            )
        } returns successResponse()

        val cardDetails = CardDetails(
            maskedPan = "411111******1111",
            cardBrand = CardBrand.VISA,
            entryMode = CardEntryMode.CHIP,
            cardNature = CardNature.CREDIT,
        )

        recorder.recordPayment(
            context = cardContext(),
            cardDetails = cardDetails,
            authorizationNumber = "067718",
            referenceNumber = "000000067718",
        )

        // Card flow must NOT be touched by the shim.
        assertThat(captured.captured.method).isEqualTo("CREDIT_CARD")
        assertThat(captured.captured.merchantAccountId).isEqualTo(merchantCuid)
    }

    @Test
    fun `recordPayment surfaces parsed JSON message field on 400`() = runTest {
        val backendMessage =
            "Error de validación: merchantAccountId: Card payments require " +
                "merchantAccountId OR blumonSerialNumber for merchant resolution. " +
                "Cash payments should not have merchantAccountId."
        val errorBody = """{"message":"$backendMessage"}"""
        coEvery {
            apiService.recordOrderPayment(any(), any(), any())
        } returns errorResponse(errorBody)

        val result = recorder.recordPayment(
            context = cashContextWithLeakedMerchantId(),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "EFECTIVO",
            referenceNumber = "CASH-1",
        )

        assertThat(result.isFailure).isTrue()
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertThat(message).contains("400")
        assertThat(message).contains("Cash payments should not have merchantAccountId")
        // Should NOT fall back to the opaque HTTP status text.
        assertThat(message).doesNotContain("Error desconocido (400): Bad Request")
    }

    @Test
    fun `recordPayment falls back to error field when message is absent`() = runTest {
        val errorBody = """{"error":"PAYMENT_VALIDATION_FAILED"}"""
        coEvery {
            apiService.recordOrderPayment(any(), any(), any())
        } returns errorResponse(errorBody)

        val result = recorder.recordPayment(
            context = cashContextWithLeakedMerchantId(),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "EFECTIVO",
            referenceNumber = "CASH-1",
        )

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertThat(message).contains("PAYMENT_VALIDATION_FAILED")
    }

    @Test
    fun `recordPayment falls back to raw body when JSON is malformed`() = runTest {
        val rawBody = "<html><body>502 Bad Gateway from Cloudflare</body></html>"
        coEvery {
            apiService.recordOrderPayment(any(), any(), any())
        } returns errorResponse(rawBody)

        val result = recorder.recordPayment(
            context = cashContextWithLeakedMerchantId(),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "EFECTIVO",
            referenceNumber = "CASH-1",
        )

        val message = result.exceptionOrNull()?.message.orEmpty()
        // The first 300 chars of raw body bubble up so a future ops engineer can grep it.
        assertThat(message).contains("502 Bad Gateway from Cloudflare")
    }
}
