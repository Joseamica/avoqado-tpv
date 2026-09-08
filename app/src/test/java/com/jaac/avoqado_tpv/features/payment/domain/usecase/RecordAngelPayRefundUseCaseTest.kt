package com.jaac.avoqado_tpv.features.payment.domain.usecase

import com.google.common.truth.Truth.assertThat
import android.content.Context
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * P0 guard (2026-07-09): the AngelPay SDK post-operations refund/cancel the FULL
 * original sale amount — [RecordAngelPayRefundUseCase.processSdkRefund] must reject
 * anything that isn't a full, first-time refund BEFORE touching the SDK, otherwise
 * a "$100 partial" on a $1,000 sale returns $1,000 to the cardholder while Avoqado
 * books $100.
 */
class RecordAngelPayRefundUseCaseTest {

    private val refundRecorder: RefundRecorder = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val adapterFactory: PostOperationsAdapterFactory = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val refundQueue: com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository = mockk(relaxed = true)
    private val ledger: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger = mockk(relaxed = true)

    private val useCase = RecordAngelPayRefundUseCase(
        refundRecorder = refundRecorder,
        authRepository = authRepository,
        postOperationsAdapterFactory = adapterFactory,
        refundQueueRepository = refundQueue,
        paymentAttemptLedger = ledger,
    )

    private suspend fun runRefund(
        requested: String,
        original: String,
        alreadyRefunded: String,
    ) = useCase.processSdkRefund(
        paymentReference = "000000188231",
        createdAt = Instant.parse("2026-07-01T12:00:00Z"),
        requestedReason = RefundReason.CUSTOMER_REQUEST,
        appContext = appContext,
        requestedAmount = BigDecimal(requested),
        originalAmount = BigDecimal(original),
        alreadyRefundedAmount = BigDecimal(alreadyRefunded),
        originalPaymentId = "pay-001",
        paymentVenueId = "venue-1",
    )

    @Test
    fun `partial refund is rejected before touching the SDK`() = runTest {
        val result = runRefund(requested = "100.00", original = "1000.00", alreadyRefunded = "0.00")

        assertTrue(result.isFailure)
        assertEquals(
            RecordAngelPayRefundUseCase.PARTIAL_REFUND_UNSUPPORTED_MESSAGE,
            result.exceptionOrNull()?.message,
        )
        // The guard must fire BEFORE any SDK/adapter interaction.
        verify(exactly = 0) { adapterFactory.get(any()) }
    }

    @Test
    fun `refund on a payment with a prior partial refund is rejected`() = runTest {
        // Even if the operator asks for exactly the remaining balance, the SDK
        // would return the FULL original amount — must be blocked.
        val result = runRefund(requested = "900.00", original = "1000.00", alreadyRefunded = "100.00")

        assertTrue(result.isFailure)
        assertEquals(
            RecordAngelPayRefundUseCase.PARTIAL_REFUND_UNSUPPORTED_MESSAGE,
            result.exceptionOrNull()?.message,
        )
        verify(exactly = 0) { adapterFactory.get(any()) }
    }

    @Test
    fun `full first-time refund passes the guard and reaches the SDK adapter`() = runTest {
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        // Empty history → downstream "transaction not found" failure, proving we got past the guard.
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(emptyList())

        val result = runRefund(requested = "1000.00", original = "1000.00", alreadyRefunded = "0.00")

        assertTrue(result.isFailure)
        assertNotEquals(
            RecordAngelPayRefundUseCase.PARTIAL_REFUND_UNSUPPORTED_MESSAGE,
            result.exceptionOrNull()?.message,
        )
        coVerify(atLeast = 1) { adapter.getTransactionHistory(any()) }
    }

    @Test
    fun `guard compares numeric value, not BigDecimal scale`() = runTest {
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(emptyList())

        // "1000" vs "1000.00" — equals() differs by scale, compareTo does not.
        val result = runRefund(requested = "1000", original = "1000.00", alreadyRefunded = "0")

        assertTrue(result.isFailure)
        assertNotEquals(
            RecordAngelPayRefundUseCase.PARTIAL_REFUND_UNSUPPORTED_MESSAGE,
            result.exceptionOrNull()?.message,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 💸 Task 7 + auditoría de Codex (4-sep): write-ahead, candado y desenlaces
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun recordBackend(key: String = "k-fixed") = useCase.recordInBackend(
        paymentId = "pay-001",
        orderId = null,
        paymentVenueId = "venue-1",
        merchantAccountId = "merchant-1",
        originalTotalAmount = BigDecimal("100.00"),
        refundAmount = BigDecimal("100.00"),
        refundReason = RefundReason.CUSTOMER_REQUEST,
        sdkReferenceNumber = "000000188231",
        tipRefundCents = null,
        refundedAmount = BigDecimal.ZERO,
        idempotencyKey = key,
    )

    private fun conSesion() { every { authRepository.getStaffId() } returns "staff-1" }
    private fun writeAheadOk() { coEvery { refundQueue.enqueueClaimed(any(), any()) } returns Result.success(Unit) }
    private fun sinRed() {
        coEvery { refundRecorder.recordRefund(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(java.io.IOException("sin red"))
    }

    private fun filaEncolada() = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund(
        idempotencyKey = "k-prev", venueId = "venue-1", staffId = "s1", processor = ProcessorType.ANGELPAY,
        originalPaymentId = "pay-001", originalOrderId = null, amount = BigDecimal("100.00"),
        originalTotalAmount = BigDecimal("100.00"), tipRefundCents = null, isPartialRefund = false,
        refundReason = RefundReason.CUSTOMER_REQUEST, merchantAccountId = "merchant-1", blumonSerialNumber = "",
        originalOperationNumber = 0, authorizationNumber = "ANGELPAY_SDK", referenceNumber = "000000188231",
        maskedPan = null, cardBrand = null, entryMode = "OTHER", createdAt = 1_000L,
    )

    @Test
    fun `P1 la fila write-ahead se escribe ANTES del POST y lleva la MISMA llave que viaja al servidor`() = runTest {
        conSesion()
        val orden = mutableListOf<String>()
        val fila = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund>()
        coEvery { refundQueue.enqueueClaimed(capture(fila), any()) } coAnswers { orden += "room"; Result.success(Unit) }
        val ctx = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.RefundPayment>()
        coEvery { refundRecorder.recordRefund(capture(ctx), any(), any(), any(), any(), any()) } coAnswers {
            orden += "red"
            Result.failure(java.io.IOException("sin red"))
        }

        val r = recordBackend("k-9")

        assertThat(orden).containsExactly("room", "red").inOrder()
        assertThat(fila.captured.idempotencyKey).isEqualTo("k-9")
        assertThat(ctx.captured.idempotencyKey).isEqualTo("k-9")
        assertThat(fila.captured.processor).isEqualTo(ProcessorType.ANGELPAY)
        assertThat(fila.captured.authorizationNumber).isEqualTo("ANGELPAY_SDK")
        assertThat(fila.captured.referenceNumber).isEqualTo("000000188231")
        coVerify(exactly = 1) { refundQueue.release("k-9", any(), 1, any()) }
        assertTrue(r.exceptionOrNull() is RefundQueuedException)
        assertTrue(!(r.exceptionOrNull() as RefundQueuedException).permanent)
    }

    @Test
    fun `P1 un 422 deja la fila FAILED permanente y el aviso lo dice`() = runTest {
        conSesion(); writeAheadOk()
        coEvery { refundRecorder.recordRefund(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(422, "monto excede"))

        val r = recordBackend()

        coVerify(exactly = 1) { refundQueue.markPermanentlyFailed("k-fixed", any(), match { it.contains("monto excede") }) }
        coVerify(exactly = 0) { refundQueue.release(any(), any(), any(), any()) }
        assertTrue((r.exceptionOrNull() as RefundQueuedException).permanent)
    }

    @Test
    fun `P1 sin sesion se escribe la fila y no se toca la red`() = runTest {
        every { authRepository.getStaffId() } returns null
        writeAheadOk()

        val r = recordBackend()

        coVerify(exactly = 1) { refundQueue.enqueueClaimed(any(), any()) }
        coVerify(exactly = 1) { refundQueue.release("k-fixed", any(), 1, any()) }
        coVerify(exactly = 0) { refundRecorder.recordRefund(any(), any(), any(), any(), any(), any()) }
        assertTrue(r.exceptionOrNull() is RefundQueuedException)
    }

    @Test
    fun `el exito del servidor cierra la fila write-ahead con el mismo token`() = runTest {
        conSesion()
        val token = slot<String>()
        coEvery { refundQueue.enqueueClaimed(any(), capture(token)) } returns Result.success(Unit)
        coEvery { refundRecorder.recordRefund(any(), any(), any(), any(), any(), any()) } returns
            Result.success(mockk(relaxed = true))

        val r = recordBackend()

        assertTrue(r.isSuccess)
        coVerify(exactly = 1) { refundQueue.markSuccess("k-fixed", token.captured) }
        coVerify(exactly = 0) { refundQueue.release(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 si el write-ahead falla se cae al encolado de respaldo`() = runTest {
        conSesion(); sinRed()
        coEvery { refundQueue.enqueueClaimed(any(), any()) } returns Result.failure(IllegalStateException("room"))
        coEvery { refundQueue.enqueue(any()) } returns Result.success(Unit)

        val r = recordBackend()

        coVerify(exactly = 1) { refundQueue.enqueue(match { it.idempotencyKey == "k-fixed" && !it.permanent }) }
        assertTrue(r.exceptionOrNull() is RefundQueuedException)
    }

    @Test
    fun `P1 si write-ahead, backend Y respaldo fallan se reporta como perdido, jamas como exito`() = runTest {
        conSesion(); sinRed()
        coEvery { refundQueue.enqueueClaimed(any(), any()) } returns Result.failure(IllegalStateException("room"))
        coEvery { refundQueue.enqueue(any()) } returns Result.failure(IllegalStateException("disco lleno"))

        val r = recordBackend()

        assertTrue(r.exceptionOrNull() is RefundLostException)
    }

    @Test
    fun `P1 processSdkRefund se NIEGA si el pago ya tiene una devolucion sin registrar - antes del SDK`() = runTest {
        coEvery { refundQueue.unresolvedForPayment("pay-001") } returns listOf(filaEncolada())

        val result = runRefund(requested = "100.00", original = "100.00", alreadyRefunded = "0.00")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("sin registrar"))
        coVerify(exactly = 0) { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `el candado dice la verdad si la devolucion previa fue RECHAZADA - no promete que se registre sola`() = runTest {
        // founder, N86, 7-sep-2026: con la fila FAILED permanente el texto decía «espera a que se
        // registre», una espera infinita — el servidor ya la rechazó y nadie la reintenta.
        val rechazada = filaEncolada().copy(
            syncStatus = com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity.SYNC_STATUS_FAILED,
            permanent = true,
            lastError = "HTTP 400: Datos de reembolso inválidos",
        )
        coEvery { refundQueue.unresolvedForPayment("pay-001") } returns listOf(rechazada)

        val result = runRefund(requested = "100.00", original = "100.00", alreadyRefunded = "0.00")

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg.contains("RECHAZÓ registrar"))
        assertTrue(msg.contains("HTTP 400: Datos de reembolso inválidos"))
        assertTrue(msg.contains("no se reintenta sola"))
        assertTrue(!msg.contains("Espera a que se registre"))
        coVerify(exactly = 0) { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `validateBeforeSdk rechaza venue o merchant vacios y deja pasar lo completo`() {
        assertTrue(useCase.validateBeforeSdk("", "merchant-1") != null)
        assertTrue(useCase.validateBeforeSdk("venue-1", "") != null)
        assertTrue(useCase.validateBeforeSdk("venue-1", "merchant-1") == null)
    }
}
