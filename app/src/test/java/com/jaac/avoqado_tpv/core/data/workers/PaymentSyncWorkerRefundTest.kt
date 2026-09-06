package com.jaac.avoqado_tpv.core.data.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal

/**
 * 💸 Fase 1, Task 8: el worker de sincronización también drena la cola de REEMBOLSOS.
 *
 * Lo que se fija aquí:
 * 1. **Sin cobros pendientes, los reembolsos se drenan igual.** El `return` temprano de «no hay
 *    pagos» los habría dejado sin reproducir para siempre — y cuando hay un reembolso encolado,
 *    el dinero YA salió del cajón.
 * 2. **Los cobros van ANTES que los reembolsos**: un reembolso apunta al pago original por su id
 *    de servidor.
 * 3. **Cada escritura es compare-and-swap con el token del claim**, y un rechazo permanente NUNCA
 *    se libera (liberarlo lo resucitaría en cada reconexión).
 * 4. **Un fallo en una fila no abandona el resto de la tanda.**
 */
class PaymentSyncWorkerRefundTest {

    @Test
    fun `P1 sin cobros pendientes el worker IGUAL drena los reembolsos`() = runTest {
        val payments = mockk<PaymentQueueRepository>(relaxed = true)
        coEvery { payments.claimBatch(any()) } returns emptyList()
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } returns listOf(queuedRefund("k-1"))
        coEvery { refunds.replay(any()) } returns SyncOutcome.Synced

        buildWorker(payments, mockk(), refunds).doWork()

        coVerify(exactly = 1) { refunds.replay(match { it.idempotencyKey == "k-1" }) }
        coVerify(exactly = 1) { refunds.markSuccess("k-1", "tok-1") }
    }

    @Test
    fun `P1 los cobros se reproducen ANTES que los reembolsos`() = runTest {
        val orden = mutableListOf<String>()
        val payments = mockk<PaymentQueueRepository>(relaxed = true)
        coEvery { payments.claimBatch(any()) } returns listOf(queuedPayment("ref-1"))
        val useCase = mockk<RecordPaymentUseCase>()
        coEvery { useCase(any(), any(), any(), any()) } coAnswers {
            orden += "cobro"
            Result.success(mockk(relaxed = true))
        }
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } returns listOf(queuedRefund("k-1"))
        coEvery { refunds.replay(any()) } coAnswers {
            orden += "reembolso"
            SyncOutcome.Synced
        }

        buildWorker(payments, useCase, refunds).doWork()

        assertThat(orden).containsExactly("cobro", "reembolso").inOrder()
    }

    @Test
    fun `un cobro que falla transitoriamente NO impide drenar los reembolsos - el original ya vive en el servidor`() = runTest {
        // Un reembolso sólo nace de un pago que el servidor YA devolvió en la lista de transacciones
        // (auditoría de Codex F5: no aplica el «404 por original ausente»); un cobro local atorado no lo frena.
        val payments = mockk<PaymentQueueRepository>(relaxed = true)
        coEvery { payments.claimBatch(any()) } returns listOf(queuedPayment("ref-1"))
        val useCase = mockk<RecordPaymentUseCase>()
        coEvery { useCase(any(), any(), any(), any()) } returns Result.failure(java.io.IOException("sin red"))
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } returns listOf(queuedRefund("k-1"))
        coEvery { refunds.replay(any()) } returns SyncOutcome.Synced

        buildWorker(payments, useCase, refunds).doWork()

        coVerify(exactly = 1) { refunds.markSuccess("k-1", "tok-1") }
    }

    @Test
    fun `el banner se refresca con las devoluciones aunque no haya cobros`() = runTest {
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } returns emptyList()
        coEvery { refunds.getPendingCount() } returns 2
        coEvery { refunds.getFailedCount() } returns 1
        val stateManager = PaymentQueueStateManager()

        buildWorker(emptyPayments(), mockk(), refunds, stateManager).doWork()

        assertThat(stateManager.queueState.value.pendingRefundCount).isEqualTo(2)
        assertThat(stateManager.queueState.value.failedRefundCount).isEqualTo(1)
        assertThat(stateManager.queueState.value.hasAnyPayments).isTrue()
    }

    @Test
    fun `P1 un rechazo permanente marca FAILED con el token del claim y NUNCA libera`() = runTest {
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } returns listOf(queuedRefund("k-1"))
        coEvery { refunds.replay(any()) } returns SyncOutcome.Permanent("422: monto excede")

        buildWorker(emptyPayments(), mockk(), refunds).doWork()

        coVerify(exactly = 1) { refunds.markPermanentlyFailed("k-1", "tok-1", "422: monto excede") }
        coVerify(exactly = 0) { refunds.release(any(), any(), any(), any()) }
        coVerify(exactly = 0) { refunds.markSuccess(any(), any()) }
    }

    @Test
    fun `un fallo transitorio libera la fila con retry mas uno y el token del claim`() = runTest {
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } returns listOf(queuedRefund("k-1", retryCount = 3))
        coEvery { refunds.replay(any()) } returns SyncOutcome.Retryable

        buildWorker(emptyPayments(), mockk(), refunds).doWork()

        coVerify(exactly = 1) { refunds.release("k-1", "tok-1", 4, any()) }
        coVerify(exactly = 0) { refunds.markPermanentlyFailed(any(), any(), any()) }
    }

    @Test
    fun `P1 un fallo de escritura en una fila NO abandona el resto de la tanda`() = runTest {
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } returns listOf(queuedRefund("k-1"), queuedRefund("k-2", token = "tok-2"))
        coEvery { refunds.replay(any()) } returns SyncOutcome.Synced
        coEvery { refunds.markSuccess("k-1", any()) } throws IllegalStateException("disco lleno")

        val result = buildWorker(emptyPayments(), mockk(), refunds).doWork()

        coVerify(exactly = 1) { refunds.replay(match { it.idempotencyKey == "k-2" }) }
        coVerify(exactly = 1) { refunds.markSuccess("k-2", "tok-2") }
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `si reclamar la cola de reembolsos revienta, el worker no se cae`() = runTest {
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } throws IllegalStateException("db cerrada")

        val result = buildWorker(emptyPayments(), mockk(), refunds).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun emptyPayments(): PaymentQueueRepository =
        mockk<PaymentQueueRepository>(relaxed = true).also { coEvery { it.claimBatch(any()) } returns emptyList() }

    private fun buildWorker(
        payments: PaymentQueueRepository,
        useCase: RecordPaymentUseCase,
        refunds: RefundQueueRepository,
        stateManager: PaymentQueueStateManager = PaymentQueueStateManager(),
    ): PaymentSyncWorker {
        val context = mockk<Context>(relaxed = true)
        return TestListenableWorkerBuilder<PaymentSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = PaymentSyncWorker(
                    appContext, workerParameters, payments, useCase, stateManager, refunds,
                )
            })
            .build()
    }

    private fun queuedRefund(key: String, retryCount: Int = 0, token: String = "tok-1") = QueuedRefund(
        idempotencyKey = key,
        venueId = "v1",
        staffId = "s1",
        processor = ProcessorType.BLUMON,
        originalPaymentId = "pay-orig",
        originalOrderId = null,
        amount = BigDecimal("50.00"),
        originalTotalAmount = BigDecimal("100.00"),
        tipRefundCents = null,
        isPartialRefund = true,
        refundReason = RefundReason.CUSTOMER_REQUEST,
        merchantAccountId = "m1",
        blumonSerialNumber = "SER1",
        originalOperationNumber = 75656,
        authorizationNumber = "502511",
        referenceNumber = "000000188231",
        maskedPan = null,
        cardBrand = null,
        entryMode = "CHIP",
        createdAt = 1_000L,
        retryCount = retryCount,
        claimToken = token,
    )

    private fun queuedPayment(reference: String): QueuedPayment = mockk(relaxed = true) {
        coEvery { referenceNumber } returns reference
        coEvery { claimToken } returns "tok-p"
        coEvery { retryCount } returns 0
        coEvery { queueId } returns 1L
    }
}
