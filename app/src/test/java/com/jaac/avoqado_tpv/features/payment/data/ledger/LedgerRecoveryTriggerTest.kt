package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 🔴 Ronda 21 (Codex r19, P2-2): al ARRANCAR, la liberación de las dudas locales corre EN EL PROCESO — no detrás de la cadena
 * de WorkManager, donde un reintento pendiente de otra pasada (minutos de backoff) la dejaba esperando con el servidor ya
 * disponible y la terminal apartada. WorkManager queda sólo como respaldo (sin red o sin salida).
 */
class LedgerRecoveryTriggerTest {

    private val recuperacion = mockk<LedgerServerRecovery>()
    private val libreta = mockk<PaymentAttemptLedger>(relaxed = true)
    private val almacen = mockk<SecureStorage>(relaxed = true)
    private val disparador = LedgerRecoveryTrigger(
        mockk(relaxed = true), libreta,
        dagger.Lazy { recuperacion }, dagger.Lazy { mockk<SocketManager>(relaxed = true) }, almacen,
    )

    @Test fun `Codex r20 P3 · instalar() de verdad corre la liberacion de arranque (no basta con que exista la funcion)`() {
        coEvery { libreta.cuarentenaDeHuerfanos(any()) } returns 0
        every { almacen.getVenueId() } returns "v1"
        coEvery { recuperacion.liberarDudasLocales("v1", any()) } returns LedgerServerRecovery.Solas()

        disparador.instalar()

        coVerify(timeout = 5_000) { recuperacion.liberarDudasLocales("v1", any()) }
    }

    @Test fun `libera EN EL PROCESO — pide ya, espera lo que falta y vuelve a pedir, sin tocar WorkManager`() = runTest {
        coEvery { recuperacion.liberarDudasLocales("v1", any()) } returnsMany listOf(
            LedgerServerRecovery.Solas(proximaEnMs = 10_000),
            LedgerServerRecovery.Solas(liberadas = 1),
        )
        val respaldo = async { disparador.liberarTrasArrancar("v1") }
        runCurrent()
        coVerify(exactly = 1) { recuperacion.liberarDudasLocales("v1", any()) }
        advanceTimeBy(9_000); runCurrent()
        coVerify(exactly = 1) { recuperacion.liberarDudasLocales("v1", any()) }   // todavía no pasa la espera del aviso
        advanceUntilIdle()
        coVerify(exactly = 2) { recuperacion.liberarDudasLocales("v1", any()) }
        assertThat(respaldo.await()).isFalse()                                  // se resolvió sola: nada que encolar
    }

    @Test fun `sin red, pide el respaldo durable de WorkManager`() = runTest {
        coEvery { recuperacion.liberarDudasLocales("v1", any()) } returns LedgerServerRecovery.Solas(sinRespuesta = 1)
        assertThat(disparador.liberarTrasArrancar("v1")).isTrue()
        coVerify(exactly = 1) { recuperacion.liberarDudasLocales("v1", any()) }
    }

    /**
     * 🔴 Cobro simple · D5 mínimo (Codex d5-minimo r1 #3): un aprobado cuyo registro falló y que la cola tampoco tomó lo registra la
     * recuperación de aprobados; nadie pedía su primera pasada (esperaba a reconectar, entrar o las 6 h). Al nacer la incertidumbre se pide
     * para cuando la fila cumple su espera.
     */
    @Test fun `al nacer una incertidumbre tambien se pide la pasada de aprobados para cuando cumple su espera`() {
        coEvery { libreta.cuarentenaDeHuerfanos(any()) } returns 0
        every { almacen.getVenueId() } returns "v1"
        coEvery { recuperacion.liberarDudasLocales("v1", any()) } returns LedgerServerRecovery.Solas()
        coEvery { recuperacion.recoverOne(any(), any()) } throws IllegalStateException("sin red")
        val aviso = io.mockk.slot<(String) -> Unit>()
        io.mockk.mockkObject(LedgerSweepScheduler)
        try {
            every { LedgerSweepScheduler.runServerRecoveryNow(any(), any(), any()) } returns Unit
            every { LedgerSweepScheduler.runApprovalFollowUp(any(), any()) } returns Unit
            disparador.instalar()
            io.mockk.verify { libreta.onUncertaintyBorn = capture(aviso) }
            aviso.captured("a1")
            io.mockk.verify(exactly = 1) {
                LedgerSweepScheduler.runApprovalFollowUp(any(), LedgerApprovalRecovery.ESPERA_MS + LedgerApprovalRecovery.MARGEN_MS)
            }
        } finally {
            io.mockk.unmockkObject(LedgerSweepScheduler)
        }
    }

    @Test fun `sin dudas que esperar no hay nada mas que hacer`() = runTest {
        coEvery { recuperacion.liberarDudasLocales("v1", any()) } returns LedgerServerRecovery.Solas()
        assertThat(disparador.liberarTrasArrancar("v1")).isFalse()
    }
}
