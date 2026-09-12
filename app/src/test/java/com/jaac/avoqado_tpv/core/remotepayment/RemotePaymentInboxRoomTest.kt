package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class RemotePaymentInboxRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var inbox: RemotePaymentInbox
    private lateinit var coordinator: RemotePaymentCoordinator
    private val request = SocketEvent.TerminalPaymentRequest(
        requestId = "req-1", amountCents = 10000, tipCents = 1000, rating = null,
        skipReview = true, orderId = "order-1", processedByStaffId = "staff-1",
        senderDeviceName = "POS", venueId = "venue-1", timestamp = "2026-09-09T12:00:00Z",
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        inbox = RemotePaymentInbox(db.remotePaymentRequestDao())
        coordinator = RemotePaymentCoordinator(inbox)
    }

    @After fun tearDown() { db.close() }

    private suspend fun receive() {
        val decision = inbox.receive(request) as RemotePaymentReceiveDecision.Deliver
        coordinator.submitSocketPaymentRequest(decision.request)
    }

    @Test fun `committed received cancellation keeps proof across duplicate replay`() = runTest {
        receive()
        val result = inbox.cancel("req-1")
        assertThat(result.disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        assertThat(org.json.JSONObject(result.finalResultJson!!).optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
        assertThat(coordinator.claimSocketPaymentRequest("req-1")).isFalse()
        assertThat(inbox.cancel("req-1").finalResultJson).isEqualTo(result.finalResultJson)
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.ReplayResult(result.finalResultJson!!))
    }

    @Test fun `claim winning cancellation race never fabricates preauthorization proof`() = runTest {
        receive()
        assertThat(coordinator.claimSocketPaymentRequest("req-1")).isTrue()
        val result = inbox.cancel("req-1")
        assertThat(result.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(result.finalResultJson).isNull()
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.AckOnly)
    }

    @Test fun `queue rejection has proof only when received CAS commits`() = runTest {
        receive()
        val result = inbox.rejectUnclaimed("req-1")!!
        assertThat(org.json.JSONObject(result).optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
        assertThat(coordinator.claimSocketPaymentRequest("req-1")).isFalse()
        assertThat(inbox.rejectUnclaimed("req-1")).isNull()
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.ReplayResult(result))
    }

    @Test fun `actual Room preserves later approval after refused cancellation`() = runTest {
        receive()
        assertThat(coordinator.claimSocketPaymentRequest("req-1")).isTrue()
        coordinator.cancelSocketPaymentRequest("req-1")
        val success = """{"requestId":"req-1","status":"success","paymentId":"pay-1"}"""
        assertThat(inbox.markResolved("req-1", success)).isTrue()
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.ReplayResult(success))
    }

    @Test fun `cancel before claim prevents stale queued SDK launch`() = runTest {
        receive()
        coordinator.cancelSocketPaymentRequest("req-1")
        assertThat(coordinator.claimSocketPaymentRequest("req-1")).isFalse()
        assertThat(inbox.receive(request)).isInstanceOf(RemotePaymentReceiveDecision.ReplayResult::class.java)
    }

    @Test fun `new coordinator cannot reauthorize persisted processing request`() = runTest {
        receive()
        assertThat(coordinator.claimSocketPaymentRequest("req-1")).isTrue()
        val restoredInbox = RemotePaymentInbox(db.remotePaymentRequestDao())
        assertThat(restoredInbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.AckOnly)
        assertThat(RemotePaymentCoordinator(restoredInbox).claimSocketPaymentRequest("req-1")).isFalse()
    }
    @Test fun `repeated cancellation replays approval without overwriting it`() = runTest {
        receive()
        coordinator.claimSocketPaymentRequest("req-1")
        val success = """{"requestId":"req-1","status":"success","paymentId":"pay-1"}"""
        inbox.persistResult("req-1", success)
        repeat(2) {
            val decision = coordinator.cancelSocketPaymentRequest("req-1")
            assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ALREADY_RESOLVED)
            assertThat(decision.finalResultJson).isEqualTo(success)
        }
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.ReplayResult(success))
    }

    @Test fun `queue rejection cannot resolve a command already claimed`() = runTest {
        receive()
        coordinator.claimSocketPaymentRequest("req-1")
        assertThat(inbox.rejectUnclaimed("req-1")).isNull()
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.AckOnly)
    }

    @Test fun `real late approval upgrades historical cancelled result and cannot regress`() = runTest {
        receive()
        coordinator.cancelSocketPaymentRequest("req-1")
        val success = """{"requestId":"req-1","status":"success","paymentId":"pay-1"}"""
        assertThat(inbox.persistResult("req-1", success)).isEqualTo(success)
        assertThat(inbox.persistResult("req-1", """{"requestId":"req-1","status":"failed"}""")).isEqualTo(success)
        assertThat(inbox.receive(request)).isEqualTo(RemotePaymentReceiveDecision.ReplayResult(success))
    }

    @Test fun `closed database reopens processing identity without allowing another authorization`() = runTest {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "remote-inbox-restart-test"
        context.deleteDatabase(name)
        try {
            db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, name).allowMainThreadQueries().build()
            inbox = RemotePaymentInbox(db.remotePaymentRequestDao())
            inbox.receive(request)
            assertThat(inbox.markProcessing("req-1")).isTrue()
            db.close()
            db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, name).allowMainThreadQueries().build()
            val restored = RemotePaymentInbox(db.remotePaymentRequestDao())
            assertThat(restored.receive(request)).isEqualTo(RemotePaymentReceiveDecision.AckOnly)
            assertThat(restored.markProcessing("req-1")).isFalse()
            assertThat(restored.observePendingObligationCount("venue-1").first()).isEqualTo(1)
            assertThat(restored.observePendingObligationCount("other-venue").first()).isEqualTo(0)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `pending projection deduplicates inbox attempt mapping and excludes other venues`() = runTest {
        receive()
        coordinator.claimSocketPaymentRequest("req-1")
        val row = PaymentAttemptEntity("attempt-1", "venue-1", "BLUMON", state = "INDETERMINADO",
            amountCents = 10000, tipCents = 1000, recordingRoute = "ORDER",
            paymentContextJson = """{"terminalPaymentRequestId":"req-1"}""", createdAt = 1, updatedAt = 1)
        db.paymentAttemptDao().insert(row.copy(state = "PREPARANDO"))
        assertThat(inbox.observePendingObligationCount("venue-1").first()).isEqualTo(1)
        db.paymentAttemptDao().casTransition("attempt-1", listOf("PREPARANDO"), "INDETERMINADO", 2)
        db.paymentAttemptDao().insert(row.copy(attemptId = "other", venueId = "other-venue"))
        assertThat(inbox.observePendingObligationCount("venue-1").first()).isEqualTo(1)
        db.paymentAttemptDao().insert(row.copy(attemptId = "manual", paymentContextJson = "{}"))
        assertThat(inbox.observePendingObligationCount("venue-1").first()).isEqualTo(2)
        db.paymentAttemptDao().casTransition("attempt-1", listOf("INDETERMINADO"), "REGISTRADO", 2)
        assertThat(inbox.observePendingObligationCount("venue-1").first()).isEqualTo(1)
    }

    @Test fun `cancel during readiness wait prevents navigation after readiness resumes`() = runTest {
        receive()
        val readiness = CompletableDeferred<Boolean>()
        var navigations = 0
        val admission = launch {
            if (coordinator.prepareSocketPaymentRequest("req-1", { readiness.await() }, { "venue-1" }) == RemotePaymentAdmission.READY) {
                navigations++
            }
        }
        runCurrent()
        assertThat(navigations).isEqualTo(0)
        assertThat(coordinator.cancelSocketPaymentRequest("req-1").disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        readiness.complete(true)
        admission.join()
        assertThat(navigations).isEqualTo(0)
    }

    @Test fun `queued old venue cannot claim after activation changes during readiness`() = runTest {
        receive()
        val admission = coordinator.prepareSocketPaymentRequest("req-1", { true }, { "venue-2" })
        assertThat(admission).isEqualTo(RemotePaymentAdmission.NOT_CLAIMABLE)
        assertThat(db.remotePaymentRequestDao().getById("req-1")?.status).isEqualTo("RECEIVED")
    }

    @Test fun `concurrent Room claim and cancellation have exactly one admission winner`() = runTest {
        repeat(10) { index ->
            val id = "race-$index"
            inbox.receive(request.copy(requestId = id))
            val gate = CompletableDeferred<Unit>()
            val claim = async(Dispatchers.IO) { gate.await(); inbox.markProcessing(id) }
            val cancel = async(Dispatchers.IO) { gate.await(); inbox.cancel(id) }
            gate.complete(Unit)
            val claimed = claim.await()
            val cancelled = cancel.await().disposition == RemotePaymentCancelDisposition.ACCEPTED
            assertThat(claimed.xor(cancelled)).isTrue()
            assertThat(db.remotePaymentRequestDao().getById(id)?.status)
                .isEqualTo(if (claimed) "PROCESSING" else "RESOLVED")
        }
    }

    @Test fun `ready request for active venue admits exactly once`() = runTest {
        receive()
        assertThat(coordinator.prepareSocketPaymentRequest("req-1", { true }, { "venue-1" }))
            .isEqualTo(RemotePaymentAdmission.READY)
        assertThat(coordinator.prepareSocketPaymentRequest("req-1", { true }, { "venue-1" }))
            .isEqualTo(RemotePaymentAdmission.NOT_CLAIMABLE)
    }


    // ═══ SONDA sobre Room real: una solicitud RECIBIDA se cancela durablemente y NUNCA pasa a PROCESSING ═══
    @Test fun `probe on a received request resolves it durably without ever delivering`() = runTest {
        receive()
        val first = inbox.probe("req-1", "venue-1")
        assertThat(first.disposition).isEqualTo(RemotePaymentProbeDisposition.RECEIVED_CANCELLED)
        val row = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(row.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(row.finalResultJson).contains("PRE_AUTHORIZATION")
        // Una segunda sonda reproduce EXACTAMENTE lo mismo (idempotente), y la solicitud ya no se puede reclamar.
        val second = inbox.probe("req-1", "venue-1")
        assertThat(second.disposition).isEqualTo(RemotePaymentProbeDisposition.RESOLVED)
        assertThat(second.finalResultJson).isEqualTo(row.finalResultJson)
        assertThat(inbox.markProcessing("req-1")).isFalse()
    }

    // ═══ LÁPIDA sobre Room real: NOT_FOUND es una promesa durable — la misma solicitud, si llega después, se rechaza ═══
    @Test fun `probe NOT_FOUND leaves a tombstone so a late delivery of the same request is rejected`() = runTest {
        val first = inbox.probe("req-1", "venue-1")
        assertThat(first.disposition).isEqualTo(RemotePaymentProbeDisposition.NOT_FOUND)
        val row = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(row.status).isEqualTo(RemotePaymentRequestEntity.STATUS_NOT_FOUND_ANSWERED)
        assertThat(row.venueId).isEqualTo("venue-1")
        // Llega DESPUÉS la solicitud con ese mismo requestId: se rechaza, no se entrega y no se puede reclamar.
        assertThat(inbox.receive(request)).isInstanceOf(RemotePaymentReceiveDecision.Reject::class.java)
        assertThat(inbox.markProcessing("req-1")).isFalse()
        assertThat(inbox.markProcessingForVenue("req-1", "venue-1")).isFalse()
        assertThat(db.remotePaymentRequestDao().getById("req-1")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_NOT_FOUND_ANSWERED)
        // La sonda repetida contesta lo mismo, la lápida no cuenta como obligación pendiente y un cancel no fabrica evidencia.
        assertThat(inbox.probe("req-1", "venue-1").disposition).isEqualTo(RemotePaymentProbeDisposition.NOT_FOUND)
        assertThat(inbox.observePendingObligationCount("venue-1").first()).isEqualTo(0)
        assertThat(inbox.cancel("req-1").disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
    }

    // Auditoría 11-sep (P3-2): `markResolved` usaba `status != 'RESOLVED'` y alcanzaba la lápida. Un resultado para una
    // solicitud que esta bandeja declaró no recibida no tiene de dónde salir; si llega, no la convierte en resuelta.
    @Test fun `a late result never overwrites a tombstone`() = runTest {
        assertThat(inbox.probe("req-1", "venue-1").disposition).isEqualTo(RemotePaymentProbeDisposition.NOT_FOUND)
        val tardio = """{"requestId":"req-1","status":"success","paymentId":"p-1"}"""
        assertThat(inbox.persistResult("req-1", tardio)).isNull()
        assertThat(inbox.markResolved("req-1", tardio)).isFalse()
        val row = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(row.status).isEqualTo(RemotePaymentRequestEntity.STATUS_NOT_FOUND_ANSWERED)
        assertThat(row.finalResultJson).isNull()
    }
}
