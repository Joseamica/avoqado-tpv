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
    private lateinit var recovery: LedgerServerRecovery
    private val api = mockk<TerminalAttemptApiService>()
    private val now = 1_700_000_000_000L
    private val venue = "venue-1"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
        recovery = LedgerServerRecovery(dao, ledger, api)
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

    @Test fun `RECORDED por S6 cierra una ENTREGADA_A_COLA (la cola no cerraba la libreta) y resuelve la bandeja · 404 y NOT_RECORDED solo estampan`() = runTest {
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

    @Test fun `sin respuesta HTTP no se gasta el turno y un 5xx si · el paso 0 reaplica lo guardado antes de consultar`() = runTest {
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

    @Test fun `P2-1 el timeout de UNA consulta no aborta la pasada — cuenta como sin respuesta, no gasta el turno y el worker reintenta`() = runTest {
        // Codex (código, P2-1): `withTimeout` lanza `TimeoutCancellationException` (una CancellationException) y la pasada
        // entera se relanzaba como cancelada; y una IOException absorbida nunca llegaba al `retry()` del worker.
        fila("t1", "HOST_RESPONDIO"); fila("t2", "HOST_RESPONDIO", requestId = "req-t2"); bandeja("req-t2")
        coEvery { api.getAttemptStatus(venue, "t1") } coAnswers { kotlinx.coroutines.delay(LedgerServerRecovery.CONSULTA_TIMEOUT_MS + 1_000); http(503) }
        coEvery { api.getAttemptStatus(venue, "t2") } returns recorded("t2", "pay-t2")
        val r = LedgerServerRecovery(dao, ledger, api).recover(venue, now)
        assertThat(r.sinRespuesta).isEqualTo(1)
        assertThat(r.consultados).isEqualTo(1)
        assertThat(r.aplicados).isEqualTo(1)
        assertThat(dao.getById("t1")!!.serverCheckedAt).isNull()
        assertThat(dao.getById("t2")!!.state).isEqualTo("REGISTRADO")
        assertThat(LedgerServerRecoveryWorker.debeReintentar(r)).isTrue()
        assertThat(LedgerServerRecoveryWorker.debeReintentar(LedgerServerRecovery.Resultado(0, 1, 1, emptyList(), sinRespuesta = 0))).isFalse()
    }

    @Test fun `recoverOne — primero lo guardado, despues S6 · con veredicto final no consulta`() = runTest {
        fila("g", "INDETERMINADO", hostApproved = null); bandeja("req-g")
        coEvery { api.getAttemptStatus(venue, "g") } returns recorded("g", "pay-g")
        val json = LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "g").bandejaResueltaJson
        assertThat(json).isNotNull()
        assertThat(dao.getById("g")!!.state).isEqualTo("REGISTRADO")
        assertThat(LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "g").bandejaResueltaJson).isNull()
        coVerify(exactly = 1) { api.getAttemptStatus(venue, "g") }
        // Una fila SIN solicitud (cobro local) nunca se consulta.
        dao.insert(PaymentAttemptEntity(attemptId = "local", venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 1, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = "{}", createdAt = now, updatedAt = now))
        assertThat(LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "local").bandejaResueltaJson).isNull()
        coVerify(exactly = 0) { api.getAttemptStatus(venue, "local") }
    }

    // ── Task 6 (ventana de confirmación): N3 aplica la LIBERACIÓN del servidor y sigue consultando la fila liberada. ──

    @Test fun `una fila INDETERMINADO cuya S6 dice NOT_CHARGED por ventana se cierra en la pasada N3 y cuenta como liberada`() = runTest {
        fila("b1", "INDETERMINADO", requestId = "req-1", hostApproved = null)   // creada hace ≥ 120 s: candidata de consulta; la MISMA solicitud que contesta S6
        coEvery { api.getAttemptStatus(venue, "b1") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "b1", requestId = "req-1",
                attempt = TerminalAttemptResultDto(attemptId = "b1", outcome = "NOT_RECORDED"),
                request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"}""").asJsonObject),
        )
        val r = recovery.recover(venue, now)
        assertThat(r.liberadas).isEqualTo(1)
        assertThat(dao.getById("b1")!!.state).isEqualTo("DESCARTADA")
        assertThat(dao.getById("b1")!!.lastError).isEqualTo("liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW")
    }

    @Test fun `una fila liberada sigue siendo candidata de consulta S6 — si el servidor dice RECORDED despues, queda contradiccion fechada desde el dinero`() = runTest {
        // La fila lleva la MISMA solicitud que contesta S6 (`req-1`): un veredicto de otra solicitud se rechaza por pertenencia.
        fila("b3", "INDETERMINADO", requestId = "req-1", hostApproved = null)
        dao.cerrarPorLiberacionDelServidor("b3", venue, "req-1", "RELEASED_NO_EVIDENCE", "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", now)
        assertThat(dao.candidatasDeConsultaAlServidor(venue, now - 120_000, now + 1, 600_000, 86_400_000).map { it.attemptId }).contains("b3")
        coEvery { api.getAttemptStatus(venue, "b3") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "b3", requestId = "req-1",
                attempt = TerminalAttemptResultDto(attemptId = "b3", outcome = "RECORDED", paymentId = "pay-3", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 500, isWinner = true, winnerPaymentId = "pay-3")),
        )
        recovery.recover(venue, now + 5_000)
        val tras = dao.getById("b3")!!
        assertThat(tras.state).isEqualTo("DESCARTADA")             // la liberación local no se «des-hace»: queda como contradicción
        assertThat(tras.serverOutcome).isEqualTo("RECORDED")
        assertThat(tras.serverVerdictAt).isEqualTo(now + 5_000)      // fechada desde el DINERO, no desde la liberación
        assertThat(dao.esContradiccion("b3")).isTrue()
    }

    @Test fun `una respuesta S6 cuyo requestId es de OTRA solicitud no libera la fila (pertenencia)`() = runTest {
        // Task 7 (item B): la liberación es de la SOLICITUD. Una respuesta atada a otra solicitud —por un intento reusado,
        // un id cruzado o una respuesta atrasada— no puede cerrar esta fila como «no se cobró».
        fila("b4", "INDETERMINADO", requestId = "req-b4", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "b4") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "b4", requestId = "req-otra",
                attempt = TerminalAttemptResultDto(attemptId = "b4", outcome = "NOT_RECORDED"),
                request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"}""").asJsonObject),
        )
        assertThat(recovery.recoverOne(venue, "b4", now).bandejaResueltaJson).isNull()
        val tras = dao.getById("b4")!!
        assertThat(tras.state).isEqualTo("INDETERMINADO")
        assertThat(tras.serverOutcome).isNull()
        assertThat(tras.serverCheckedAt).isEqualTo(now)   // sólo se estampó la consulta
    }

    @Test fun `el sondeo interactivo de la pantalla (recoverOne sin estampar) no gasta el turno de E3 — la fila sigue siendo candidata del respaldo N3`() = runTest {
        // Task 7 · fix 1 (Important #1): la pantalla consulta S6 cada 5 s durante la ventana. Si cada consulta «sin veredicto»
        // estampara `server_check_count`, tras 7 sondeos el espaciado de E3 (base × 2^7 ≈ 21 h) dejaba la fila FUERA del worker
        // de respaldo y del barrido — justo cuando la terminal se queda sin red al liberar el servidor.
        fila("b5", "INDETERMINADO", requestId = "req-b5", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "b5") } returns notRecorded
        // RED visto (run-avoqado-tpv.FjLeR5): con la firma anterior, 10 consultas del sondeo estampaban `server_check_count = 10`.
        repeat(10) { recovery.recoverOne(venue, "b5", now + it, estampar = false) }
        assertThat(dao.getById("b5")!!.serverCheckCount).isEqualTo(0)
        assertThat(dao.getById("b5")!!.serverCheckedAt).isNull()
        assertThat(dao.candidatasDeConsultaAlServidor(venue, now - 120_000, now + 11, 600_000, 86_400_000).map { it.attemptId }).contains("b5")
        // Control: el respaldo (trigger / worker / barrido) SÍ estampa — con 10 consultas sin veredicto la misma fila deja de ser candidata.
        repeat(10) { recovery.recoverOne(venue, "b5", now + 100 + it, estampar = true) }
        assertThat(dao.getById("b5")!!.serverCheckCount).isEqualTo(10)
        assertThat(dao.candidatasDeConsultaAlServidor(venue, now - 120_000, now + 200, 600_000, 86_400_000).map { it.attemptId }).doesNotContain("b5")
        // Y sin estampa la LIBERACIÓN se sigue aplicando igual: el sondeo no pierde veredictos, sólo no gasta el turno.
        fila("b6", "INDETERMINADO", requestId = "req-b6", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "b6") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "b6", requestId = "req-b6",
                attempt = TerminalAttemptResultDto(attemptId = "b6", outcome = "NOT_RECORDED"),
                request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"}""").asJsonObject),
        )
        assertThat(recovery.recoverOne(venue, "b6", now, estampar = false).bandejaResueltaJson).isNull()
        assertThat(dao.getById("b6")!!.state).isEqualTo("DESCARTADA")
    }

    @Test fun `P1-2 — S6 con NOT_RECORDED + processorEvidence APPROVED y la solicitud liberada NO libera la fila (sigue INDETERMINADO) y lo dice`() = runTest {
        // Secuencia real: el servidor libera a los 30 s → llega una aprobación bancaria TARDÍA con importe distinto → el servidor
        // conserva el evento APROBADO como evidencia sin Payment. Liberar aquí sería decir «se puede volver a cobrar» con una
        // aprobación conocida. La fila se queda INDETERMINADO (F0 la muestra); NUNCA se marca RECORDED en local (no hay Payment).
        fila("b7", "INDETERMINADO", requestId = "req-b7", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "b7") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "b7", requestId = "req-b7",
                attempt = TerminalAttemptResultDto(attemptId = "b7", outcome = "NOT_RECORDED", paymentId = null, processorEvidence = "APPROVED"),
                request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"}""").asJsonObject),
        )
        val lectura = recovery.recoverOne(venue, "b7", now, estampar = false)
        assertThat(lectura.bandejaResueltaJson).isNull()
        assertThat(lectura.evidenciaPositivaSinRegistro).isTrue()
        val tras = dao.getById("b7")!!
        assertThat(tras.state).isEqualTo("INDETERMINADO")
        assertThat(tras.serverOutcome).isNull()
        assertThat(tras.hostApproved).isNull()
        // Y por la pasada N3 (con estampa) tampoco: cuenta como consulta, no como liberación.
        val r = recovery.recover(venue, now + 1)
        assertThat(r.liberadas).isEqualTo(0)
        assertThat(dao.getById("b7")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `recoverOne aplica la liberacion y la fila queda legible para la pantalla`() = runTest {
        fila("b2", "INDETERMINADO", requestId = "req-1", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "b2") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "b2", requestId = "req-1",
                attempt = TerminalAttemptResultDto(attemptId = "b2", outcome = "NOT_RECORDED"),
                request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}""").asJsonObject),
        )
        assertThat(recovery.recoverOne(venue, "b2", now).bandejaResueltaJson).isNull()   // no hay bandeja resuelta que emitir
        assertThat(ledger.leerIntento("b2")!!.serverOutcome).isEqualTo("OPERATOR_NO_INSTRUMENT")
    }
}
