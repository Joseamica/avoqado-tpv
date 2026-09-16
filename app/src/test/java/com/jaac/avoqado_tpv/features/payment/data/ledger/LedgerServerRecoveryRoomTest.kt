package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/** N3 (E2/E3) sobre Room real con S6 mockeado: reaplicar sin red, consultar con avance, 404/5xx conservan, estampado por respuesta. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LedgerServerRecoveryRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var ledger: PaymentAttemptLedger
    private val api = mockk<TerminalAttemptApiService>()
    private val now = 1_700_000_000_000L
    private val venue = "venue-1"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
    }

    @After fun tearDown() { db.close() }

    private suspend fun fila(attemptId: String, state: String, requestId: String = "req-$attemptId", hostApproved: Boolean? = true) = dao.insert(
        PaymentAttemptEntity(
            attemptId = attemptId, venueId = venue, processor = "ANGELPAY", state = state, amountCents = 10000, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = """{"terminalPaymentRequestId":"$requestId"}""", hostApproved = hostApproved,
            createdAt = now - 600_000, updatedAt = now - 600_000, terminalPaymentRequestId = requestId,
        ),
    )

    private suspend fun bandeja(requestId: String) = db.remotePaymentRequestDao().insert(
        RemotePaymentRequestEntity(
            requestId = requestId, venueId = venue, amountCents = 10000, tipCents = 0, rating = null, skipReview = true, orderId = null,
            processedByStaffId = null, senderDeviceName = null, sourceTimestamp = "t", status = RemotePaymentRequestEntity.STATUS_PROCESSING,
            createdAt = now - 600_000, updatedAt = now - 600_000,
        ),
    )

    private fun recorded(attemptId: String, paymentId: String, winner: Boolean = true) = Response.success(
        TerminalAttemptStatusResponse(
            success = true, attemptId = attemptId, requestId = "req-$attemptId",
            attempt = TerminalAttemptResultDto(attemptId = attemptId, outcome = "RECORDED", paymentId = paymentId, paymentStatus = "COMPLETED",
                recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = winner),
        ),
    )
    private val notRecorded = Response.success(TerminalAttemptStatusResponse(attempt = TerminalAttemptResultDto(outcome = "NOT_RECORDED")))
    private fun http(code: Int) = Response.error<TerminalAttemptStatusResponse>(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

    @Test fun `RECORDED por S6 cierra una ENTREGADA_A_COLA (la cola no cerraba la libreta) y resuelve la bandeja; 404 y NOT_RECORDED solo estampan`() = runTest {
        fila("a", "ENTREGADA_A_COLA"); fila("b", "INDETERMINADO", hostApproved = null); fila("c", "REGISTRO_FALLIDO")
        bandeja("req-a")
        coEvery { api.getAttemptStatus(venue, "a") } returns recorded("a", "pay-a")
        coEvery { api.getAttemptStatus(venue, "b") } returns http(404)
        coEvery { api.getAttemptStatus(venue, "c") } returns notRecorded

        val r = LedgerServerRecovery(dao, ledger, api).recover(venue, now)

        assertThat(r.consultados).isEqualTo(3)
        assertThat(r.aplicados).isEqualTo(1)
        assertThat(r.bandejasResueltas).hasSize(1)
        assertThat(dao.getById("a")!!.state).isEqualTo("REGISTRADO")
        assertThat(db.remotePaymentRequestDao().getById("req-a")!!.status).isEqualTo("RESOLVED")
        assertThat(dao.getById("b")!!.state).isEqualTo("INDETERMINADO") // 404: nada acredita nada
        assertThat(dao.getById("b")!!.serverCheckedAt).isEqualTo(now)
        assertThat(dao.getById("c")!!.state).isEqualTo("REGISTRO_FALLIDO")
        assertThat(dao.getById("c")!!.serverCheckCount).isEqualTo(1)
        // Segunda pasada inmediata: las tres ya gastaron su turno, nada se consulta.
        assertThat(LedgerServerRecovery(dao, ledger, api).recover(venue, now + 1).consultados).isEqualTo(0)
        coVerify(exactly = 1) { api.getAttemptStatus(venue, "a") }
    }

    @Test fun `sin respuesta HTTP no se gasta el turno y un 5xx si; el paso 0 reaplica lo guardado antes de consultar`() = runTest {
        fila("d", "HOST_RESPONDIO"); fila("e", "HOST_RESPONDIO")
        coEvery { api.getAttemptStatus(venue, "d") } throws java.io.IOException("sin red")
        coEvery { api.getAttemptStatus(venue, "e") } returns http(503)
        LedgerServerRecovery(dao, ledger, api).recover(venue, now)
        assertThat(dao.getById("d")!!.serverCheckedAt).isNull()
        assertThat(dao.getById("e")!!.serverCheckedAt).isEqualTo(now)

        // Un veredicto final guardado con el SDK dentro y luego un callback incierto: se reaplica SIN consultar.
        fila("f", "AUTORIZANDO", hostApproved = null); bandeja("req-f")
        dao.aplicarVeredictoDelServidor(VeredictoDeIntento.desdeAvisoS5(venue, "req-f", "f", "pay-f", "webhook", 10000, 0), now)
        ledger.markIndeterminate("f", "sin veredicto")
        val r = LedgerServerRecovery(dao, ledger, api).recover(venue, now + 2)
        assertThat(r.reaplicados).isEqualTo(1)
        assertThat(dao.getById("f")!!.state).isEqualTo("REGISTRADO")
        coVerify(exactly = 0) { api.getAttemptStatus(venue, "f") }
    }

    @Test fun `recoverOne: primero lo guardado, despues S6; con veredicto final no consulta`() = runTest {
        fila("g", "INDETERMINADO", hostApproved = null); bandeja("req-g")
        coEvery { api.getAttemptStatus(venue, "g") } returns recorded("g", "pay-g")
        val json = LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "g")
        assertThat(json).isNotNull()
        assertThat(dao.getById("g")!!.state).isEqualTo("REGISTRADO")
        assertThat(LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "g")).isNull()
        coVerify(exactly = 1) { api.getAttemptStatus(venue, "g") }
        // Una fila SIN solicitud (cobro local) nunca se consulta.
        dao.insert(PaymentAttemptEntity(attemptId = "local", venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 1, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = "{}", createdAt = now, updatedAt = now))
        assertThat(LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "local")).isNull()
        coVerify(exactly = 0) { api.getAttemptStatus(venue, "local") }
    }
}
