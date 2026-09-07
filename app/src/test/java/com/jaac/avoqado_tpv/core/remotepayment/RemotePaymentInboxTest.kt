package com.jaac.avoqado_tpv.core.remotepayment

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RemotePaymentInboxTest {

    private val dao = mockk<RemotePaymentRequestDao>(relaxed = true)
    private val inbox = RemotePaymentInbox(dao)

    private fun event(requestId: String = "req-1") = SocketEvent.TerminalPaymentRequest(
        requestId = requestId,
        amountCents = 10_000,
        tipCents = 1_000,
        rating = 5,
        skipReview = true,
        orderId = null,
        processedByStaffId = "staff-pos",
        senderDeviceName = "iPad caja",
        venueId = "venue-1",
        timestamp = "2026-09-03T14:00:00Z",
    )

    @Test
    fun `persiste request completo antes de permitir ACK y entrega`() = runTest {
        coEvery { dao.insert(any()) } returns 1L

        val decision = inbox.receive(event())

        assertThat(decision).isInstanceOf(RemotePaymentReceiveDecision.Deliver::class.java)
        coVerify(exactly = 1) {
            dao.insert(match {
                it.requestId == "req-1" &&
                    it.amountCents == 10_000L &&
                    it.tipCents == 1_000L &&
                    it.rating == 5 &&
                    it.skipReview &&
                    it.processedByStaffId == "staff-pos" &&
                    it.status == RemotePaymentRequestEntity.STATUS_RECEIVED
            })
        }
    }

    @Test
    fun `duplicado en procesamiento confirma pero nunca vuelve a lanzar SDK`() = runTest {
        coEvery { dao.insert(any()) } returns -1L
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_PROCESSING)

        val decision = inbox.receive(event())

        assertThat(decision).isEqualTo(RemotePaymentReceiveDecision.AckOnly)
    }

    @Test
    fun `duplicado resuelto reproduce el resultado durable al servidor`() = runTest {
        val finalJson = """{"requestId":"req-1","status":"success","paymentId":"pay-1"}"""
        coEvery { dao.insert(any()) } returns -1L
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(
            event(),
            status = RemotePaymentRequestEntity.STATUS_RESOLVED,
            finalResultJson = finalJson,
        )

        val decision = inbox.receive(event())

        assertThat(decision).isEqualTo(RemotePaymentReceiveDecision.ReplayResult(finalJson))
    }

    @Test
    fun `mismo requestId con otro dinero se rechaza como conflicto`() = runTest {
        coEvery { dao.insert(any()) } returns -1L
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_RECEIVED)

        val decision = inbox.receive(event().copy(amountCents = 99_999))

        assertThat(decision).isInstanceOf(RemotePaymentReceiveDecision.Reject::class.java)
    }

    @Test
    fun `resultado se guarda antes de que SocketManager pueda emitirlo`() = runTest {
        coEvery { dao.markResolved(any(), any(), any()) } returns 1

        val saved = inbox.markResolved("req-1", """{"requestId":"req-1","status":"failed"}""")

        assertThat(saved).isTrue()
        coVerify(exactly = 1) { dao.markResolved("req-1", any(), any()) }
    }
}
