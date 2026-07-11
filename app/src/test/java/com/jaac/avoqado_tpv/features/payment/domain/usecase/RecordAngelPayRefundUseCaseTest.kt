package com.jaac.avoqado_tpv.features.payment.domain.usecase

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

    private val useCase = RecordAngelPayRefundUseCase(
        refundRecorder = refundRecorder,
        authRepository = authRepository,
        postOperationsAdapterFactory = adapterFactory,
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
}
