package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 🔴 Cobro simple · D5 mínimo (Codex r1 #9 del plan T2 y d5-minimo r1 #4): el barrido REAL (`doWork()`) agenda la vuelta del aprobado
 * que falló para cuando vence su pausa, aparte de la pasada corta; y el despertador sólo pide la pasada.
 */
class LedgerShadowSweepWorkerTest {
    private val secureStorage = mockk<SecureStorage>(relaxed = true).also { every { it.getVenueId() } returns "v1" }
    private val approvalRecovery = mockk<LedgerApprovalRecovery>()

    private fun worker(): LedgerShadowSweepWorker =
        TestListenableWorkerBuilder<LedgerShadowSweepWorker>(mockk<Context>(relaxed = true))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    LedgerShadowSweepWorker(appContext, workerParameters, mockk(relaxed = true), mockk(relaxed = true), secureStorage,
                        mockk(relaxed = true), approvalRecovery, mockk(relaxed = true))
            })
            .build()

    private fun conScheduler(bloque: suspend () -> Unit) = runTest {
        mockkObject(LedgerSweepScheduler, LedgerSweepLogic)
        try {
            every { LedgerSweepScheduler.runServerRecoveryNow(any(), any(), any()) } returns Unit
            every { LedgerSweepScheduler.runApprovalFollowUp(any(), any()) } returns Unit
            every { LedgerSweepScheduler.agendarFinDePausa(any(), any()) } returns Unit
            coEvery { LedgerSweepLogic.runGated(any(), any(), any(), any(), any()) } returns null
            coEvery { approvalRecovery.recover("v1", any()) } returns 0
            coEvery { approvalRecovery.cuandoHaceFaltaOtraPasada("v1", any()) } returns null
            bloque()
        } finally {
            unmockkObject(LedgerSweepScheduler, LedgerSweepLogic)
        }
    }

    @Test fun `tras un registro fallido el barrido agenda el fin de la pausa y no la pasada corta`() = conScheduler {
        coEvery { approvalRecovery.cuandoVenceLaPausa("v1", any()) } returns 300_000L
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        verify(exactly = 1) { LedgerSweepScheduler.agendarFinDePausa(any(), 300_000L) }
        verify(exactly = 0) { LedgerSweepScheduler.runApprovalFollowUp(any(), any()) }
    }

    @Test fun `sin aprobados reservados no se agenda nada`() = conScheduler {
        coEvery { approvalRecovery.cuandoVenceLaPausa("v1", any()) } returns null
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        verify(exactly = 0) { LedgerSweepScheduler.agendarFinDePausa(any(), any()) }
    }

    @Test fun `el despertador del fin de la pausa sólo pide la pasada ya`() = runTest {
        mockkObject(LedgerSweepScheduler)
        try {
            every { LedgerSweepScheduler.runApprovalFollowUp(any(), any()) } returns Unit
            val despertador = TestListenableWorkerBuilder<LedgerFinDePausaWorker>(mockk<Context>(relaxed = true)).build()
            assertThat(despertador.doWork()).isEqualTo(ListenableWorker.Result.success())
            verify(exactly = 1) { LedgerSweepScheduler.runApprovalFollowUp(any(), 0L) }
        } finally {
            unmockkObject(LedgerSweepScheduler)
        }
    }
}
