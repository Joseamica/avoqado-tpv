package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/** The PAX must consume the same server evidence as Nexgo, with the same money vetoes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class BlumonServerRecoveryRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var ledger: PaymentAttemptLedger
    private val api = mockk<TerminalAttemptApiService>()
    private val now = 1_700_000_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
    }

    @After fun tearDown() { db.close() }

    private fun attempt(id: String = "pax", request: String? = null) = PaymentAttemptEntity(
        attemptId = id, venueId = "venue", processor = "BLUMON", state = "INDETERMINADO",
        amountCents = 10000, tipCents = 500, recordingRoute = "FAST", paymentContextJson = "{}",
        createdAt = now - 600_000, updatedAt = now - 600_000, terminalPaymentRequestId = request,
    )

    @Test fun `the live recovery observation is scoped to its venue`() = runTest {
        dao.insert(attempt())
        assertThat(ledger.observarIntento("pax", "venue").first()?.attemptId).isEqualTo("pax")
        assertThat(ledger.observarIntento("pax", "other-venue").first()).isNull()
    }

    @Test fun `closing a failed preparation cannot override money or a server veto`() = runTest {
        val preparing = attempt().copy(state = "PREPARANDO")
        val protected = listOf(
            preparing.copy(attemptId = "host", hostApproved = true),
            preparing.copy(attemptId = "bank", serverProcessorEvidence = "APPROVED"),
            preparing.copy(attemptId = "veto", serverVeto = "PAYMENT_CONTRADICTION"),
            preparing.copy(attemptId = "recorded", serverPaymentId = "payment-id", serverOutcome = "RECORDED"),
        )
        protected.forEach { dao.insert(it) }
        val closed = protected.map { ledger.markDiscardedBeforeCharge(it.attemptId, "preparation_failed") }
        assertThat(closed).containsExactly(false, false, false, false)
        assertThat(protected.map { dao.getById(it.attemptId)!!.state }).containsExactly(
            "PREPARANDO", "PREPARANDO", "PREPARANDO", "PREPARANDO")
    }

    @Test fun `PAX enters the bounded server recovery page without a POS request`() = runTest {
        dao.insert(attempt())
        dao.insert(attempt("other-venue").copy(venueId = "elsewhere"))
        dao.insert(attempt("refund").copy(kind = "REFUND"))
        dao.insert(attempt("legacy").copy(legacyShadow = true))
        val page = dao.candidatasDeConsultaAlServidor("venue", now - 120_000, now, 1000, 30000)
        assertThat(page.map { it.attemptId }).containsExactly("pax")
    }

    @Test fun `PAX server payment is saved and releases a matching unknown charge`() = runTest {
        dao.insert(attempt())
        coEvery { api.getAttemptStatus("venue", "pax") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "pax", attempt = TerminalAttemptResultDto(
                attemptId = "pax", outcome = "RECORDED", paymentId = "payment-pax", paymentStatus = "COMPLETED",
                amountCents = 10000, tipCents = 500, isWinner = true, recordedVia = "terminal",
            )),
        )
        LedgerServerRecovery(dao, ledger, api).recoverOne("venue", "pax", now)
        assertThat(dao.getById("pax")!!.state).isEqualTo("REGISTRADO")
        assertThat(dao.getById("pax")!!.serverPaymentId).isEqualTo("payment-pax")
    }

    @Test fun `operator resolution releases the exact local PAX attempt`() = runTest {
        dao.insert(attempt())
        val released = ledger.aplicarLiberacionDelServidor(
            LiberacionDelServidor("venue", "pax", null, "OPERATOR_RECONCILED"), now,
        ).getOrThrow()
        assertThat(released).isTrue()
        assertThat(dao.getById("pax")!!.state).isEqualTo("DESCARTADA")
        assertThat(dao.getById("pax")!!.serverOutcome).isEqualTo("OPERATOR_NO_INSTRUMENT")
    }

    @Test fun `bank approval on PAX survives recreation and vetoes an older operator resolution`() = runTest {
        dao.insert(attempt())
        assertThat(ledger.marcarEvidenciaPositivaDelServidor("venue", "pax", now).getOrThrow()).isTrue()
        val recreated = PaymentAttemptLedger(dao, mockk(relaxed = true))
        assertThat(recreated.aplicarLiberacionDelServidor(
            LiberacionDelServidor("venue", "pax", null, "OPERATOR_RECONCILED"), now + 1,
        ).getOrThrow()).isFalse()
        assertThat(dao.getById("pax")!!.serverProcessorEvidence).isEqualTo("APPROVED")
        assertThat(dao.getById("pax")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `PAX resolution cannot cross venue request or a live authorization`() = runTest {
        dao.insert(attempt("remote", "pos-request"))
        dao.insert(attempt("live").copy(state = "AUTORIZANDO"))
        for (resolution in listOf(
            LiberacionDelServidor("other-venue", "remote", "pos-request", "OPERATOR_RECONCILED"),
            LiberacionDelServidor("venue", "remote", null, "OPERATOR_RECONCILED"),
            LiberacionDelServidor("venue", "remote", "other-request", "OPERATOR_RECONCILED"),
            LiberacionDelServidor("venue", "live", null, "OPERATOR_RECONCILED"),
        )) assertThat(ledger.aplicarLiberacionDelServidor(resolution, now).getOrThrow()).isFalse()
        assertThat(dao.getById("remote")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("live")!!.state).isEqualTo("AUTORIZANDO")
        assertThat(ledger.aplicarLiberacionDelServidor(
            LiberacionDelServidor("venue", "remote", "pos-request", "OPERATOR_RECONCILED"), now,
        ).getOrThrow()).isTrue()
    }

    @Test fun `server absence cannot release a remote PAX either`() = runTest {
        dao.insert(attempt("remote", "pos-request"))
        assertThat(ledger.aplicarLiberacionDelServidor(
            LiberacionDelServidor("venue", "remote", "pos-request", "NO_EVIDENCE_AFTER_WINDOW"), now,
        ).getOrThrow()).isFalse()
        assertThat(dao.getById("remote")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `late matching PAX charge can be acknowledged without erasing the operator declaration`() = runTest {
        dao.insert(attempt().copy(state = "DESCARTADA", serverOutcome = "RECORDED",
            serverPaymentId = "payment-pax", serverAmountCents = 10000, serverTipCents = 500,
            serverProcessorEvidence = "APPROVED"))
        assertThat(dao.reconocerCobroRegistrado("pax", "venue", "manager", now)).isEqualTo(1)
        assertThat(dao.getById("pax")!!.state).isEqualTo("REGISTRADO")
        assertThat(dao.getById("pax")!!.serverPaymentId).isEqualTo("payment-pax")
        assertThat(dao.getById("pax")!!.acknowledgedBy).isEqualTo("manager")
    }

    @Test fun `absence in the server never automatically releases a PAX without its own bank reference`() = runTest {
        val row = attempt()
        dao.insert(row)
        coEvery { api.getAttemptStatus("venue", "pax") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "pax", attempt = TerminalAttemptResultDto(
                attemptId = "pax", outcome = "NOT_RECORDED",
            )),
        )
        LedgerServerRecovery(dao, ledger, api).recoverOne("venue", "pax", now)
        assertThat(dao.getById("pax")!!.state).isEqualTo("INDETERMINADO")
        assertThat(ledger.puedeLiberarseSola(row, "venue")).isFalse()
        assertThat(dao.localesPorLiberarSolas("venue", Long.MIN_VALUE, "", 25)).isEmpty()
    }
    @Test fun `PAX quarantine age cannot masquerade as the end of a native authorization`() = runTest {
        assertThat(ledger.openAttempt("live", "venue", "BLUMON", 10000, 500, "FAST", "{}")).isTrue()
        val aged = attempt("live").copy(lastError = PaymentAttemptEntity.CUARENTENA_POR_ANTIGUEDAD)
        assertThat(ledger.puedeConciliarBlumon(aged, "venue")).isFalse()
        assertThat(ledger.puedeConciliarBlumon(aged.copy(lastError = "IOException"), "venue")).isTrue()
        val restarted = PaymentAttemptLedger(dao, mockk(relaxed = true))
        assertThat(restarted.puedeConciliarBlumon(aged, "venue")).isTrue()
        assertThat(restarted.puedeConciliarBlumon(aged.copy(state = "AUTORIZANDO"), "venue")).isFalse()
    }

}
