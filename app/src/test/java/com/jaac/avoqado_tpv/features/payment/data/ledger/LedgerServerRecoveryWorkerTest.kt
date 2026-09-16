package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Codex (código del checkpoint 2, P2-1, ronda 2): el worker REAL (`doWork()`), no sólo su predicado — sin red o con el tope
 * propio de una consulta ⇒ `retry()`; pasada resuelta ⇒ `success()` y las bandejas resueltas se emiten; una cancelación
 * EXTERNA se propaga (nunca se degrada a `success`).
 */
class LedgerServerRecoveryWorkerTest {
    private val secureStorage = mockk<SecureStorage>(relaxed = true).also { every { it.getVenueId() } returns "v1" }
    private val recovery = mockk<LedgerServerRecovery>()
    private val socketManager = mockk<SocketManager>(relaxed = true)

    private fun worker(): LedgerServerRecoveryWorker =
        TestListenableWorkerBuilder<LedgerServerRecoveryWorker>(mockk<Context>(relaxed = true))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    LedgerServerRecoveryWorker(appContext, workerParameters, secureStorage, recovery, socketManager)
            })
            .build()

    @Test fun `una consulta sin respuesta en la pasada (tope propio o red) devuelve retry`() = runTest {
        coEvery { recovery.recover("v1", any()) } returns LedgerServerRecovery.Resultado(0, 1, 0, emptyList(), sinRespuesta = 1)
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.retry())
        coEvery { recovery.recover("v1", any()) } throws java.io.IOException("sin red")
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test fun `una pasada resuelta devuelve success y emite las bandejas resueltas`() = runTest {
        coEvery { recovery.recover("v1", any()) } returns LedgerServerRecovery.Resultado(1, 2, 1, listOf("""{"requestId":"r1","status":"success"}"""), sinRespuesta = 0)
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        verify(exactly = 1) { socketManager.emitDurableTerminalPaymentResult("""{"requestId":"r1","status":"success"}""") }
    }

    @Test fun `una cancelacion EXTERNA se propaga — nunca se degrada a success ni a retry`() = runTest {
        val entro = CompletableDeferred<Unit>()
        coEvery { recovery.recover("v1", any()) } coAnswers { entro.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val trabajo = async { worker().doWork() }
        entro.await()
        trabajo.cancel()
        assertThat(runCatching { trabajo.await() }.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(trabajo.isCancelled).isTrue()
    }

    @Test fun `sin venue no hay nada que recuperar`() = runTest {
        every { secureStorage.getVenueId() } returns null
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
    }
}
