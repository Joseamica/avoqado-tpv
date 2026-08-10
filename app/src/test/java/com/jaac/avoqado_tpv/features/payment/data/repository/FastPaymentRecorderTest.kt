package com.jaac.avoqado_tpv.features.payment.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.DigitalReceiptData
import com.jaac.avoqado_tpv.features.payment.data.dto.FastPaymentRequest
import com.jaac.avoqado_tpv.features.payment.data.dto.PaymentData
import com.jaac.avoqado_tpv.features.payment.data.dto.PaymentResponse
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.CardNature
import com.jaac.avoqado_tpv.features.payment.domain.model.IssuerCountrySource
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
 * Unit tests for [FastPaymentRecorder] focused on:
 * 1. The `normalizedCashMerchantAccount()` shim that drops `merchantAccountId` for CASH.
 * 2. The `parseBackendErrorMessage()` JSON-error parser cascade.
 *
 * Mirror of [OrderPaymentRecorderTest] — the helpers are duplicated across both
 * recorders, so both must be exercised. If the helpers are ever extracted to a
 * shared file, these test classes should consolidate.
 *
 * Background: production bug 2026-05-06 — see
 * `/Avoqado-HQ/specs/payment-recording-400-audit-2026-05-06.md`.
 */
class FastPaymentRecorderTest {

    private val apiService = mockk<PaymentApiService>()
    private val recorder = FastPaymentRecorder(apiService)

    // region — helpers

    private val merchantCuid = "cmlah9251000ik628rhkkwhp0"

    private fun cashContextWithLeakedMerchantId(): PaymentContext.FastPayment =
        PaymentContext.FastPayment(
            venueId = "venue_123",
            staffId = "staff_456",
            shiftId = "shift_789",
            amount = BigDecimal("100.00"),
            tip = BigDecimal.ZERO,
            merchantAccountId = merchantCuid, // ← THE BUG
            blumonSerialNumber = "",
        )

    private fun cardContext(): PaymentContext.FastPayment =
        PaymentContext.FastPayment(
            venueId = "venue_123",
            staffId = "staff_456",
            amount = BigDecimal("100.00"),
            tip = BigDecimal("5.00"),
            merchantAccountId = merchantCuid, // ← legitimately set for cards
            blumonSerialNumber = "AVQD-2841548417",
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

    /**
     * Respuesta 200 con `digitalReceipt: null` — el cuerpo REAL que manda el backend cuando el
     * pago existente no tiene fila en `DigitalReceipt` (rama idempotente) o cuando
     * `generateDigitalReceipt()` falló en el cobro fresco.
     */
    private fun successResponseWithoutReceipt(): Response<PaymentResponse> = Response.success(
        PaymentResponse(
            success = true,
            data = PaymentData(
                id = "pmt_sin_recibo",
                amount = BigDecimal("70.00"),
                tipAmount = BigDecimal("7.00"),
                authorizationNumber = "486728",
                referenceNumber = "855502456783",
                digitalReceipt = null,
            ),
        ),
    )

    private fun errorResponse(body: String): Response<PaymentResponse> =
        Response.error(
            400,
            body.toResponseBody("application/json".toMediaTypeOrNull()),
        )

    // endregion

    // region Regresión — bucle infinito de Testarudo Café (2026-08-05)

    /**
     * 🔴 REGRESIÓN. `digitalReceipt` venía declarado NO-NULO en el DTO; Gson no respeta la
     * no-nulabilidad de Kotlin, así que un `null` del JSON entraba igual y reventaba con NPE al
     * primer acceso. La NPE se clasificaba como error TRANSITORIO → 5 reintentos internos × 10 del
     * worker = 50 requests por ciclo, indefinidamente. Una terminal de Testarudo Café reintentó el
     * MISMO pago del 23-jun 2,781 veces en 6.3 h (verificado en logs de prod + Crashlytics
     * `FastPaymentRecorder$recordPayment$2` NPE, ref=855502456783).
     *
     * El cobro SIEMPRE estuvo registrado y no hubo doble cargo — la idempotencia del server hizo
     * su trabajo las 2,781 veces. Lo único que faltaba era el QR.
     */
    @Test
    fun `recordPayment succeeds when backend returns null digitalReceipt`() = runTest {
        coEvery {
            apiService.recordFastPayment(venueId = any(), request = any())
        } returns successResponseWithoutReceipt()

        val result = recorder.recordPayment(
            context = cardContext(),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "486728",
            referenceNumber = "855502456783",
        )

        // Un recibo faltante NO invalida el cobro: tiene que resolverse OK para que la fila
        // salga de la cola. Si esto falla, el pago se reintenta para siempre.
        assertThat(result.isSuccess).isTrue()
        val receipt = result.getOrThrow()
        assertThat(receipt.paymentId).isEqualTo("pmt_sin_recibo")
        assertThat(receipt.receiptUrl).isEmpty()
        assertThat(receipt.accessKey).isEmpty()
        assertThat(receipt.autofacturaAvailable).isFalse()
    }

    /** El camino normal (CON recibo) sigue intacto: el blindaje no degrada el happy path. */
    @Test
    fun `recordPayment still maps receiptUrl when digitalReceipt is present`() = runTest {
        coEvery {
            apiService.recordFastPayment(venueId = any(), request = any())
        } returns successResponse()

        val result = recorder.recordPayment(
            context = cardContext(),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "EFECTIVO",
            referenceNumber = "CASH-1",
        )

        assertThat(result.isSuccess).isTrue()
        val receipt = result.getOrThrow()
        assertThat(receipt.receiptUrl).isEqualTo("https://api.avoqado.io/api/v1/public/receipt/key1")
        assertThat(receipt.accessKey).isEqualTo("key1")
    }

    // endregion

    @Test
    fun `recordPayment drops merchantAccountId when method is CASH`() = runTest {
        val captured = slot<FastPaymentRequest>()
        coEvery {
            apiService.recordFastPayment(
                venueId = any(),
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
        assertThat(captured.captured.merchantAccountId).isNull()
    }

    @Test
    fun `recordPayment preserves merchantAccountId when method is CREDIT_CARD`() = runTest {
        val captured = slot<FastPaymentRequest>()
        coEvery {
            apiService.recordFastPayment(
                venueId = any(),
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

        assertThat(captured.captured.method).isEqualTo("CREDIT_CARD")
        assertThat(captured.captured.merchantAccountId).isEqualTo(merchantCuid)
    }

    @Test
    fun `recordPayment sends issuer evidence without changing legacy international flag`() = runTest {
        val captured = slot<FastPaymentRequest>()
        coEvery { apiService.recordFastPayment(any(), capture(captured)) } returns successResponse()

        val cardDetails = CardDetails(
            maskedPan = "477291******5280",
            cardBrand = CardBrand.VISA,
            entryMode = CardEntryMode.CONTACTLESS,
            isInternational = true,
            cardNature = CardNature.CREDIT,
            issuerCountryCode = "0484",
            issuerCountrySource = IssuerCountrySource.EMV_5F28,
        )

        recorder.recordPayment(
            context = cardContext(),
            cardDetails = cardDetails,
            authorizationNumber = "623288",
            referenceNumber = "297648987740",
        )

        assertThat(captured.captured.isInternational).isTrue()
        assertThat(captured.captured.issuerCountryCode).isEqualTo("0484")
        assertThat(captured.captured.issuerCountrySource).isEqualTo("EMV_5F28")
    }

    @Test
    fun `recordPayment surfaces parsed JSON message field on 400`() = runTest {
        val backendMessage =
            "Error de validación: merchantAccountId: Card payments require " +
                "merchantAccountId OR blumonSerialNumber for merchant resolution. " +
                "Cash payments should not have merchantAccountId."
        val errorBody = """{"message":"$backendMessage"}"""
        coEvery { apiService.recordFastPayment(any(), any()) } returns errorResponse(errorBody)

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
        assertThat(message).doesNotContain("Error desconocido (400): Bad Request")
    }

    @Test
    fun `recordPayment falls back to error field when message is absent`() = runTest {
        val errorBody = """{"error":"PAYMENT_VALIDATION_FAILED"}"""
        coEvery { apiService.recordFastPayment(any(), any()) } returns errorResponse(errorBody)

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
        coEvery { apiService.recordFastPayment(any(), any()) } returns errorResponse(rawBody)

        val result = recorder.recordPayment(
            context = cashContextWithLeakedMerchantId(),
            cardDetails = CardDetails.CASH,
            authorizationNumber = "EFECTIVO",
            referenceNumber = "CASH-1",
        )

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertThat(message).contains("502 Bad Gateway from Cloudflare")
    }
}
