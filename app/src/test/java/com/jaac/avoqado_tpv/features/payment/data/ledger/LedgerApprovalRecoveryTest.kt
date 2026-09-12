package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.payment.domain.model.*
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class LedgerApprovalRecoveryTest {
    private val dao = mockk<PaymentAttemptDao>(relaxed = true)
    private val fast = mockk<FastPaymentRecorder>()
    private val order = mockk<OrderPaymentRecorder>()
    private val context = PaymentContext.FastPayment(
        venueId = "venue", staffId = "staff", amount = BigDecimal("100.00"),
        tip = BigDecimal("10.00"), merchantAccountId = "merchant",
        deviceSerialNumber = "terminal", idempotencyKey = "attempt", terminalPaymentRequestId = "request"
    )
    private val approved = PaymentAttemptEntity(
        attemptId = "attempt", venueId = "venue", processor = "BLUMON", state = "HOST_RESPONDIO",
        amountCents = 10000, tipCents = 1000, recordingRoute = "FAST",
        paymentContextJson = Gson().toJson(context), hostApproved = true,
        authCode = "AUTH", referenceNumber = "REF", operationId = "123",
        createdAt = 1, updatedAt = 1
    )
    init { coEvery { dao.completeRecovery(any(), any(), any(), any(), any(), any()) } returns 1 }
    private val receipt = PaymentReceipt("payment", "url", "key", BigDecimal("100.00"), BigDecimal("10.00"))

    @Test
    fun `restart replays approval registration with original key and processor identity`() = runTest {
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved)
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 1
        val contexts = mutableListOf<PaymentContext>()
        coEvery { fast.recordPayment(capture(contexts), any(), "AUTH", "REF") } returns Result.success(receipt)
        assertEquals(1, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        assertEquals("attempt", contexts.single().idempotencyKey)
        assertEquals("request", contexts.single().terminalPaymentRequestId)
        assertEquals(123, (contexts.single() as PaymentContext.FastPayment).blumonOperationNumber)
        coVerify { dao.completeRecovery("attempt", "venue", any(), "REGISTRADO", any(), null) }
    }

    @Test
    fun `unknown result cannot enter registration`() = runTest {
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved.copy(state = "INDETERMINADO", hostApproved = null))
        assertEquals(0, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        coVerify(exactly = 0) { fast.recordPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `failed registration waits and next run uses same attempt without SDK`() = runTest {
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved)
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 1
        var calls = 0
        coEvery { fast.recordPayment(any(), any(), any(), any()) } answers {
            calls++
            Result.failure(java.io.IOException("response lost after commit"))
        }
        assertEquals(0, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        assertEquals(1, calls)
        coVerify(exactly = 0) { dao.completeRecovery(any(), any(), any(), "REGISTRADO", any(), any()) }
    }

    @Test
    fun `another worker lease and queue handoff prevent second registration`() = runTest {
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved, approved.copy(state = "ENTREGADA_A_COLA"))
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 0
        assertEquals(0, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        coVerify(exactly = 0) { fast.recordPayment(any(), any(), any(), any()) }
    }
}
