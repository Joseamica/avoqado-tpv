package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import io.mockk.every
import io.mockk.mockk
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** Real Room + inbox + result sender; only the network boundary is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class RemotePaymentTimeoutNotificationTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var inbox: RemotePaymentInbox
    private lateinit var manager: SocketManager
    private lateinit var senderScope: CoroutineScope
    private val sent = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val request = SocketEvent.TerminalPaymentRequest(
        requestId = "req-timeout", amountCents = 100, tipCents = 0, rating = null,
        skipReview = true, orderId = null, processedByStaffId = "staff-1",
        senderDeviceName = "POS", venueId = "venue-1", timestamp = "2026-09-16T14:47:23Z",
    )

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        inbox = RemotePaymentInbox(db.remotePaymentRequestDao())
        inbox.receive(request)
        assertThat(inbox.markProcessingForVenue(request.requestId, "venue-1")).isTrue()
        db.paymentAttemptDao().insert(PaymentAttemptEntity(
            attemptId = "attempt-timeout", venueId = "venue-1", processor = "ANGELPAY",
            state = PaymentAttemptEntity.STATE_INDETERMINADO, amountCents = 100, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = """{"terminalPaymentRequestId":"req-timeout"}""",
            terminalPaymentRequestId = request.requestId, createdAt = 1, updatedAt = 2,
        ))
        manager = SocketManager(
            secureStorage = mockk(relaxed = true), authRepositoryLazy = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true), remotePaymentInbox = inbox,
            remotePaymentCoordinator = RemotePaymentCoordinator(inbox), paymentAttemptLedger = mockk(relaxed = true),
        )
        val socket = mockk<Socket>(relaxed = true)
        every { socket.emit("terminal:payment_result", any<JSONObject>()) } answers {
            sent += secondArg<Array<Any>>().single() as JSONObject
            socket
        }
        SocketManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
        senderScope = SocketManager::class.java.getDeclaredField("socketScope").run {
            isAccessible = true
            get(manager) as CoroutineScope
        }
    }

    @After fun tearDown() = runBlocking {
        senderScope.cancel()
        senderScope.coroutineContext[Job]!!.join()
        db.close()
    }

    private suspend fun awaitSender() = withTimeout(5_000) {
        senderScope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    @Test fun `timeout notifies POS without closing the request or admitting a second execution`() = runBlocking {
        manager.emitTerminalPaymentResult(
            request.requestId, "timeout", errorMessage = "No se pudo confirmar el cobro",
            outcomeEvidence = "PRE_AUTHORIZATION", paymentId = "stale-payment",
        )
        awaitSender()

        assertThat(sent).hasSize(1)
        assertThat(sent.single().getString("requestId")).isEqualTo("req-timeout")
        assertThat(sent.single().getString("status")).isEqualTo("timeout")
        assertThat(sent.single().getString("errorMessage")).isEqualTo("No se pudo confirmar el cobro")
        assertThat(sent.single().has("outcomeEvidence")).isFalse()
        assertThat(sent.single().has("paymentId")).isFalse()
        val durable = db.remotePaymentRequestDao().getById(request.requestId)!!
        assertThat(durable.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(durable.finalResultJson).isNull()
        assertThat(db.paymentAttemptDao().getById("attempt-timeout")!!.state)
            .isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.AckOnly)
        assertThat(inbox.markProcessingForVenue(request.requestId, "venue-1")).isFalse()
    }

    @Test fun `late timeout replays the already durable approval instead of downgrading it`() = runBlocking {
        val success = """{"requestId":"req-timeout","status":"success","paymentId":"pay-confirmed"}"""
        assertThat(inbox.persistResult(request.requestId, success)).isEqualTo(success)

        manager.emitTerminalPaymentResult(request.requestId, "timeout")
        awaitSender()

        assertThat(sent).hasSize(1)
        assertThat(sent.single().getString("status")).isEqualTo("success")
        assertThat(sent.single().getString("paymentId")).isEqualTo("pay-confirmed")
        assertThat(db.remotePaymentRequestDao().getById(request.requestId)!!.finalResultJson).isEqualTo(success)
    }

    @Test fun `failure without accredited outcome still cannot bypass durable result validation`() = runBlocking {
        manager.emitTerminalPaymentResult(request.requestId, "failed", outcomeEvidence = "PRE_AUTHORIZATION")
        awaitSender()

        assertThat(sent).isEmpty()
        assertThat(db.remotePaymentRequestDao().getById(request.requestId)!!.status)
            .isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.AckOnly)
    }

    @Test fun `missing socket preserves uncertainty and a later notification uses the confirmed winner`() = runBlocking {
        val socketField = SocketManager::class.java.getDeclaredField("socket").apply { isAccessible = true }
        val socket = socketField.get(manager)
        socketField.set(manager, null)
        manager.emitTerminalPaymentResult(request.requestId, "timeout")
        awaitSender()
        assertThat(sent).isEmpty()
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.AckOnly)

        val success = """{"requestId":"req-timeout","status":"success","paymentId":"pay-later"}"""
        assertThat(inbox.persistResult(request.requestId, success)).isEqualTo(success)
        socketField.set(manager, socket)
        manager.emitTerminalPaymentResult(request.requestId, "timeout")
        awaitSender()
        assertThat(sent).hasSize(1)
        assertThat(sent.single().getString("status")).isEqualTo("success")
        assertThat(sent.single().getString("paymentId")).isEqualTo("pay-later")
    }
}
