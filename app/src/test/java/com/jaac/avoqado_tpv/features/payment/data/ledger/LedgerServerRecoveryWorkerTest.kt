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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Codex (código del checkpoint 2, P2-1, ronda 2): el worker REAL (`doWork()`), no sólo su predicado — sin red o con el tope
 * propio de una consulta la pasada SE REPITE (desde el 23-sep con su propio seguimiento a 31 s, no con `retry()`); pasada
 * resuelta ⇒ `success()` y las bandejas resueltas se emiten; una cancelación EXTERNA se propaga (nunca se degrada a `success`).
 */
class LedgerServerRecoveryWorkerTest {
    private val secureStorage = mockk<SecureStorage>(relaxed = true).also { every { it.getVenueId() } returns "v1" }
    private val recovery = mockk<LedgerServerRecovery>()
    private val socketManager = mockk<SocketManager>(relaxed = true)
    // Pieza D: el worker también concilia la BANDEJA (solicitudes huérfanas sin intento correlacionado).
    private val bandejaRecovery = mockk<com.jaac.avoqado_tpv.core.remotepayment.BandejaServerRecovery>(relaxed = true)

    private fun worker(): LedgerServerRecoveryWorker =
        TestListenableWorkerBuilder<LedgerServerRecoveryWorker>(mockk<Context>(relaxed = true))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    LedgerServerRecoveryWorker(appContext, workerParameters, secureStorage, recovery, bandejaRecovery, socketManager)
            })
            .build()

    /**
     * 🔴 Hallazgo en la N86 real (23-sep): `retry()` le entrega el reintento a WorkManager, que DUPLICA la espera en cada
     * intento (30 s, 1 min, 2 min… hasta 5 h). Como toda la recuperación va en UNA cadena `APPEND_OR_REPLACE`, cualquier
     * «corre ya» (arranque, reconexión, una duda nueva) quedaba BLOQUEADO detrás: medido, una cabeza con 9 reintentos y 10
     * pasadas bloqueadas detrás, y un cobro de $9 sin liberarse 3 min después de volver la red. La pasada que no obtuvo
     * respuesta sigue repitiéndose —lo que pidió Codex (P2-1)—, pero con su PROPIO seguimiento a 31 s, no con el backoff.
     */
    private fun conScheduler(bloque: suspend () -> Unit) = runTest {
        io.mockk.mockkObject(LedgerSweepScheduler)
        try {
            every { LedgerSweepScheduler.runServerRecoveryNow(any(), any(), any()) } returns Unit
            bloque()
        } finally {
            io.mockk.unmockkObject(LedgerSweepScheduler)
        }
    }

    @Test fun `hardware 23-sep · una consulta sin respuesta termina en success y agenda su seguimiento a 31 s — nunca retry`() = conScheduler {
        coEvery { recovery.recover("v1", any()) } returns LedgerServerRecovery.Resultado(0, 1, 0, emptyList(), sinRespuesta = 1)
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        verify(exactly = 1) { LedgerSweepScheduler.runServerRecoveryNow(any(), 0L, 31L) }
    }

    @Test fun `hardware 23-sep · sin red tambien termina en success con seguimiento a 31 s`() = conScheduler {
        coEvery { recovery.recover("v1", any()) } throws java.io.IOException("sin red")
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        verify(exactly = 1) { LedgerSweepScheduler.runServerRecoveryNow(any(), 0L, 31L) }
    }

    @Test fun `hardware 23-sep · la bandeja sin respuesta tambien agenda el seguimiento a 31 s`() = conScheduler {
        coEvery { recovery.recover("v1", any()) } returns LedgerServerRecovery.Resultado(0, 0, 0, emptyList())
        coEvery { bandejaRecovery.conciliar("v1", any()) } returns
            com.jaac.avoqado_tpv.core.remotepayment.BandejaServerRecovery.Resultado(consultadas = 1, conciliadas = 0, sinRespuesta = 1)
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        verify(exactly = 1) { LedgerSweepScheduler.runServerRecoveryNow(any(), 0L, 31L) }
    }

    @Test fun `hardware 23-sep · sin respuesta y una duda que espera 10 s — UN solo seguimiento, al menor`() = conScheduler {
        coEvery { recovery.recover("v1", any()) } returns
            LedgerServerRecovery.Resultado(0, 1, 0, emptyList(), sinRespuesta = 1, proximaLiberacionSolaEnMs = 10_000)
        worker().doWork()
        verify(exactly = 1) { LedgerSweepScheduler.runServerRecoveryNow(any(), any(), any()) }
        verify(exactly = 1) { LedgerSweepScheduler.runServerRecoveryNow(any(), 0L, 11L) }
    }

    @Test fun `hardware 23-sep · el seguimiento nunca pasa de 31 s aunque la duda espere 10 min — seria la cabeza que bloquea la fila`() {
        val base = LedgerServerRecovery.Resultado(0, 0, 0, emptyList())
        assertThat(LedgerServerRecoveryWorker.segundosParaSeguir(base.copy(proximaLiberacionSolaEnMs = LedgerServerRecovery.REINTENTO_SIN_AVISO_MS)))
            .isEqualTo(31L)
    }

    @Test fun `hardware 23-sep · la fila estrena nombre y cancela la vieja — un aparato ya atorado se destraba al actualizar`() {
        val wm = mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        try {
            every { androidx.work.WorkManager.getInstance(any()) } returns wm
            LedgerSweepScheduler.runServerRecoveryNow(mockk(relaxed = true))
            verify(exactly = 1) { wm.cancelUniqueWork("ledger_server_recovery") }
            verify(exactly = 1) {
                wm.enqueueUniqueWork("ledger_server_recovery_v2", androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, any<androidx.work.OneTimeWorkRequest>())
            }
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test fun `una pasada resuelta devuelve success y emite las bandejas resueltas`() = runTest {
        coEvery { recovery.recover("v1", any()) } returns LedgerServerRecovery.Resultado(1, 2, 1, listOf("""{"requestId":"r1","status":"success"}"""), sinRespuesta = 0)
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        verify(exactly = 1) { socketManager.emitDurableTerminalPaymentResult("""{"requestId":"r1","status":"success"}""") }
    }

    @Test fun `una cancelacion EXTERNA se propaga — nunca se degrada a success ni a retry`() = runTest {
        // Codex (ronda 3): `cancel()` + `await()` sobre el Deferred lanza SIEMPRE, aunque `doWork()` se trague la cancelación y
        // devuelva `success`. Lo que se comprueba es el desenlace de `doWork()` MISMO, capturado dentro de la corrutina.
        val entro = CompletableDeferred<Unit>()
        val desenlace = CompletableDeferred<Any>()
        coEvery { recovery.recover("v1", any()) } coAnswers { entro.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val trabajo = launch { desenlace.complete(runCatching { worker().doWork() }.fold({ it }, { it })) }
        entro.await()
        trabajo.cancel()
        trabajo.join()
        val loQueDevolvio = desenlace.await()
        assertThat(loQueDevolvio).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(loQueDevolvio).isNotInstanceOf(ListenableWorker.Result::class.java)
    }

    @Test fun `ronda 21 — una duda local que todavia no cumple su espera pide la pasada siguiente a tiempo`() {
        val base = LedgerServerRecovery.Resultado(0, 0, 0, emptyList())
        assertThat(LedgerServerRecoveryWorker.segundosParaSeguir(base.copy(proximaLiberacionSolaEnMs = 10_000))).isEqualTo(11L)
        assertThat(LedgerServerRecoveryWorker.segundosParaSeguir(base.copy(proximaLiberacionSolaEnMs = 1))).isEqualTo(2L)
        assertThat(LedgerServerRecoveryWorker.segundosParaSeguir(base)).isNull()
    }

    @Test fun `Codex r20 P3 · doWork() de verdad agenda la pasada siguiente cuando una duda local todavia espera`() = runTest {
        coEvery { recovery.recover("v1", any()) } returns
            LedgerServerRecovery.Resultado(0, 0, 0, emptyList(), proximaLiberacionSolaEnMs = 10_000)
        io.mockk.mockkObject(LedgerSweepScheduler)
        try {
            every { LedgerSweepScheduler.runServerRecoveryNow(any(), any(), any()) } returns Unit
            worker().doWork()
            verify(exactly = 1) { LedgerSweepScheduler.runServerRecoveryNow(any(), 0L, 11L) }
        } finally {
            io.mockk.unmockkObject(LedgerSweepScheduler)
        }
    }

    @Test fun `sin venue no hay nada que recuperar`() = runTest {
        every { secureStorage.getVenueId() } returns null
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
    }
}
