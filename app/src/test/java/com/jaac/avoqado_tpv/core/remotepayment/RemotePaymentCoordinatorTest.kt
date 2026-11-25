package com.jaac.avoqado_tpv.core.remotepayment

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Test

class RemotePaymentCoordinatorTest {
    @Test
    fun `cancel received while charging cannot persist a fabricated cancelled result`() = runTest {
        val inbox = mockk<RemotePaymentInbox>(relaxed = true)
        coEvery { inbox.markProcessing("req-1") } returns true
        val coordinator = RemotePaymentCoordinator(inbox)
        coordinator.submitSocketPaymentRequest(RemotePaymentRequest(10000, socketRequestId = "req-1"))
        coordinator.claimSocketPaymentRequest("req-1")

        coordinator.cancelSocketPaymentRequest("req-1")

        coVerify(exactly = 0) { inbox.markResolved(any(), any()) }
    }
    // Auditoría 11-sep (P3-1): «sin lápida no hay NOT_FOUND» también en la API del coordinador. Un id vacío no puede
    // dejar lápida, así que no puede contestar NOT_FOUND; ACTIVE conserva la reserva (igual que el cancel sin id).
    @Test fun `probe without request id never answers NOT_FOUND and never touches the inbox`() = runTest {
        val inbox = mockk<RemotePaymentInbox>(relaxed = true)
        val coordinator = RemotePaymentCoordinator(inbox)
        assertThat(coordinator.probeSocketPaymentRequest(null, "venue-1").disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(coordinator.probeSocketPaymentRequest("  ", "venue-1").disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        coVerify(exactly = 0) { inbox.probe(any(), any()) }
    }

    @Test fun `activation change while claim suspends refuses navigation`() = runTest {
        val inbox = mockk<RemotePaymentInbox>(relaxed = true)
        var venue = "venue-1"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { inbox.reclamarParaVenue("req-1", "venue-1") } coAnswers {
            entered.complete(Unit)
            release.await()
            RemotePaymentAdmission.READY
        }
        val coordinator = RemotePaymentCoordinator(inbox)
        val result = async { coordinator.prepareSocketPaymentRequest("req-1", { true }, { venue }) }
        runCurrent()
        assertThat(entered.isCompleted).isTrue()
        venue = "venue-2"
        release.complete(Unit)
        assertThat(result.await()).isEqualTo(RemotePaymentAdmission.VENUE_CHANGED)
    }

    // D669 (Codex r2 #2): `SocketManager.events` reentrega el ÚLTIMO evento a cada `HomeViewModel` nuevo (replay = 1). Una devolución
    // ya contestada (`rejected` con la terminal bloqueada, u `opened`) no se vuelve a contestar ni a abrir.
    @Test fun `P2 D669 una devolucion del POS se contesta una sola vez aunque el evento se reentregue`() {
        val coordinator = RemotePaymentCoordinator(mockk(relaxed = true))
        assertThat(coordinator.esDevolucionNueva("refund-1")).isTrue()
        assertThat(coordinator.esDevolucionNueva("refund-1")).isFalse()
        assertThat(coordinator.esDevolucionNueva("refund-2")).isTrue()
        // Sin id no hay con qué reconocerla: se atiende como siempre.
        assertThat(coordinator.esDevolucionNueva("")).isTrue()
        assertThat(coordinator.esDevolucionNueva("")).isTrue()
    }
}
