package com.jaac.avoqado_tpv.core.data.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.SubirVentaGuardadaUseCase
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** La misma corrida drena pagos → reembolsos → fotos de comprobante, y la cola de fotos nunca tumba al worker. */
class PaymentSyncWorkerProofOfSaleTest {
    private val payments = mockk<PaymentQueueRepository>(relaxed = true).also { coEvery { it.claimBatch(any()) } returns emptyList() }
    private val refunds = mockk<RefundQueueRepository>(relaxed = true).also { coEvery { it.claimBatch(any()) } returns emptyList() }
    private val proofs = mockk<ProofOfSaleQueueRepository>(relaxed = true)

    @Test fun `el worker drena pagos, luego reembolsos y al final las fotos`() = runTest {
        buildWorker().doWork()

        coVerifyOrder {
            payments.claimBatch(any())
            refunds.claimBatch(any())
            proofs.drain(any())
        }
    }

    @Test fun `si la cola de fotos revienta el worker no se cae`() = runTest {
        coEvery { proofs.drain(any()) } throws IllegalStateException("db cerrada")

        assertThat(buildWorker().doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test fun `si quedan fotos por subir el worker encadena otra pasada, y si no, no`() = runTest {
        mockkObject(PaymentSyncScheduler)
        try {
            every { PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) } returns Unit
            coEvery { proofs.drain(any()) } returns true
            buildWorker().doWork()
            verify(exactly = 1) { PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) }

            coEvery { proofs.drain(any()) } returns false
            buildWorker().doWork()
            verify(exactly = 1) { PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) }
        } finally {
            unmockkObject(PaymentSyncScheduler)
        }
    }

    private fun buildWorker(): PaymentSyncWorker =
        TestListenableWorkerBuilder<PaymentSyncWorker>(mockk<Context>(relaxed = true))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    PaymentSyncWorker(
                        appContext, workerParameters, payments, mockk(), PaymentQueueStateManager(), refunds,
                        mockk<PaymentAttemptLedger>(relaxed = true), mockk<SubirVentaGuardadaUseCase>(),
                        tableSyncCoordinator = com.jaac.avoqado_tpv.features.shift.data.emptyTableReplayFixture(),
                        proofOfSaleQueueRepository = proofs,
                    )
            })
            .build()
}
