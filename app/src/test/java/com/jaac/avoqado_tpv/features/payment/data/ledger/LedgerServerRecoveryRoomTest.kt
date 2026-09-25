package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.launch
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

    /** Un cobro LOCAL de la propia terminal: Pago rápido / «Cobrar», SIN solicitud del POS. */
    private suspend fun filaLocal(attemptId: String, state: String, hostApproved: Boolean? = null) = dao.insert(
        PaymentAttemptEntity(
            attemptId = attemptId, venueId = venue, processor = "ANGELPAY", state = state, amountCents = 4000, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = """{"amount":40.00}""", hostApproved = hostApproved,
            createdAt = now - 600_000, updatedAt = now - 600_000, terminalPaymentRequestId = null,
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
        // 🔴 «Ninguna terminal muerta» (22-sep): una fila SIN solicitud (cobro LOCAL) SÍ se consulta ahora. Antes esta
        // misma prueba fijaba lo contrario, y ése era el defecto: el intento que aparta el aparato entero y no tiene otra
        // salida era justo el que nunca se le preguntaba al servidor. S6 contesta por INTENTO desde la pieza A.
        dao.insert(PaymentAttemptEntity(attemptId = "local", venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 1, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = "{}", createdAt = now, updatedAt = now))
        coEvery { api.getAttemptStatus(venue, "local") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "local", requestId = null, request = null,
                attempt = TerminalAttemptResultDto(attemptId = "local", outcome = "NOT_RECORDED", processorEvidence = "NONE")),
        )
        assertThat(LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "local").bandejaResueltaJson).isNull()
        coVerify(exactly = 1) { api.getAttemptStatus(venue, "local") }
        // Sin declaración del servidor, NOT_RECORDED es «no sé»: la fila conserva su estado y su retención.
        assertThat(dao.getById("local")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `respaldo sin red - recoverOne dice si el servidor CONTESTO, y sin red, tope o 5xx NO es contestar`() = runTest {
        // El respaldo del aparato (founder, 22-sep: «con internet decide el servidor; si no contesta, el aparato») se ofrece
        // sólo si el servidor NO contestó en toda la ventana. «Contestar» es un 2xx: con un 5xx o un 401 la declaración por
        // el servidor tampoco pasaría, así que para el cajero es lo mismo que no tener servidor.
        filaLocal("s1", "INDETERMINADO"); filaLocal("s2", "INDETERMINADO"); filaLocal("s3", "INDETERMINADO"); filaLocal("s4", "INDETERMINADO")
        coEvery { api.getAttemptStatus(venue, "s1") } returns notRecorded
        coEvery { api.getAttemptStatus(venue, "s2") } throws java.io.IOException("sin red")
        coEvery { api.getAttemptStatus(venue, "s3") } returns http(503)
        coEvery { api.getAttemptStatus(venue, "s4") } coAnswers { kotlinx.coroutines.delay(LedgerServerRecovery.CONSULTA_TIMEOUT_MS + 1_000); notRecorded }
        assertThat(recovery.recoverOne(venue, "s1", now, estampar = false).servidorContesto).isTrue()
        assertThat(recovery.recoverOne(venue, "s2", now, estampar = false).servidorContesto).isFalse()
        assertThat(recovery.recoverOne(venue, "s3", now, estampar = false).servidorContesto).isFalse()
        assertThat(recovery.recoverOne(venue, "s4", now, estampar = false).servidorContesto).isFalse()
        // Y un veredicto aplicado también es haber contestado.
        fila("s5", "INDETERMINADO", hostApproved = null); bandeja("req-s5")
        coEvery { api.getAttemptStatus(venue, "s5") } returns recorded("s5", "pay-s5")
        assertThat(recovery.recoverOne(venue, "s5", now).servidorContesto).isTrue()
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

    // ── Codex r6 (P1-2): el VETO durable se escribe también en la pasada del WORKER, no sólo en la interactiva ──

    @Test fun `P1 r6 P1-2 · el worker hace DURABLE el veto y una respuesta limpia atrasada ya no libera`() = runTest {
        // r5-4 puso el veto en `recoverOne` (la consulta interactiva). Pero `recover()` NO pasa por ella: tiene su propio
        // procesamiento. Así que el worker recibía la contradicción, conservaba la fila y dejaba `server_veto` en NULL — y la
        // respuesta LIMPIA y ATRASADA de otro de los tres consumidores concurrentes pasaba el CAS y liberaba la venta. Sin
        // reiniciar el proceso: el defecto que Codex reprodujo.
        fila("v1", "INDETERMINADO", requestId = "req-v1", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "v1") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "v1", requestId = "req-v1",
                attempt = TerminalAttemptResultDto(attemptId = "v1", outcome = "NOT_RECORDED", paymentContradiction = true)),
        )
        recovery.recover(venue, now)
        assertThat(dao.getById("v1")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)

        // Y ahora la respuesta limpia atrasada: liberaría si el veto no fuera durable.
        val limpia = LiberacionDelServidor(venue, "v1", "req-v1", "NO_EVIDENCE_AFTER_WINDOW")
        assertThat(ledger.aplicarLiberacionDelServidor(limpia, now + 1_000).getOrNull()).isFalse()
        assertThat(dao.getById("v1")!!.state).isEqualTo("INDETERMINADO")
    }

    // ── Codex r8 (P1-2): el veto de la RESPUESTA llega a quien consultó aunque su escritura FALLE ──

    @Test fun `r8 P1-2 · recoverOne devuelve el veto de la respuesta aunque guardarlo en la libreta falle`() = runTest {
        // El escenario de Codex: la escritura del veto falla por un momento, pero leer la fila funciona. Si el veto sólo
        // vivía en la libreta, la pantalla leía la liberación VIEJA de la fila y volvía a decir «puedes cobrar».
        filaLocal("w1", "INDETERMINADO")
        val ledgerQueNoGuarda = io.mockk.spyk(ledger)
        coEvery { ledgerQueNoGuarda.marcarVetoDelServidor(any(), any(), any(), any()) } returns Result.failure(java.io.IOException("disco lleno"))
        coEvery { api.getAttemptStatus(venue, "w1") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "w1", requestId = null,
                attempt = TerminalAttemptResultDto(attemptId = "w1", outcome = "NOT_RECORDED", paymentContradiction = true)),
        )

        val lectura = LedgerServerRecovery(dao, ledgerQueNoGuarda, api).recoverOne(venue, "w1", now, estampar = false)

        assertThat(dao.getById("w1")!!.serverVeto).isNull()                                          // la escritura NO quedó…
        assertThat(lectura.vetoDelServidor).isEqualTo(PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)  // …y el veto llega igual
    }

    // ── Codex r9 (P1-2 / P1-3): lo que trae la respuesta se decide por el CUERPO y se guarda ANTES de anotar «contestó» ──

    /** Una fila del POS que el servidor YA liberó: si la lectura se pierde, la pantalla la lee y dice «se puede volver a cobrar». */
    private suspend fun conLiberacionVieja(attemptId: String) {
        fila(attemptId, "INDETERMINADO", requestId = "req-$attemptId", hostApproved = null)
        dao.cerrarPorLiberacionDelServidor(attemptId, venue, "req-$attemptId", "RELEASED_NO_EVIDENCE", "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", now - 1)
        assertThat(dao.getById(attemptId)!!.state).isEqualTo("DESCARTADA")
    }

    private fun contradiccion(attemptId: String) = Response.success(
        TerminalAttemptStatusResponse(success = true, attemptId = attemptId, requestId = "req-$attemptId",
            attempt = TerminalAttemptResultDto(attemptId = attemptId, outcome = "NOT_RECORDED", paymentContradiction = true)),
    )

    @Test fun `r9 P1-2 · si anotar la respuesta FALLA, recoverOne devuelve igual el veto y lo deja durable`() = runTest {
        conLiberacionVieja("l1")
        // DAO DECORADOR, no `spyk`: el espía de mockk sobre funciones suspendidas de Room devuelve COROUTINE_SUSPENDED.
        val daoQueNoAnota = object : PaymentAttemptDao by dao {
            override suspend fun estamparRespuestaDelServidor(attemptId: String, now: Long): Int = throw java.io.IOException("disco lleno")
        }
        coEvery { api.getAttemptStatus(venue, "l1") } returns contradiccion("l1")

        val lectura = LedgerServerRecovery(daoQueNoAnota, PaymentAttemptLedger(daoQueNoAnota, mockk(relaxed = true)), api)
            .recoverOne(venue, "l1", now, estampar = false)

        assertWithMessage("la marca de «contestó» no puede tragarse el veto").that(lectura.vetoDelServidor).isEqualTo(PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
        assertThat(dao.getById("l1")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
    }

    @Test fun `r9 P1-2 · un RECORDED que no se pudo guardar vuelve como evidencia de dinero y no anota «contesto»`() = runTest {
        conLiberacionVieja("l2")
        val daoQueNoAplica = object : PaymentAttemptDao by dao {
            override suspend fun aplicarVeredictoDelServidor(veredicto: VeredictoDeIntento, now: Long): ResultadoDelVeredicto =
                throw java.io.IOException("disco lleno")
        }
        coEvery { api.getAttemptStatus(venue, "l2") } returns recorded("l2", "pay-l2")

        val lectura = LedgerServerRecovery(daoQueNoAplica, PaymentAttemptLedger(daoQueNoAplica, mockk(relaxed = true)), api)
            .recoverOne(venue, "l2", now, estampar = false)

        assertWithMessage("el cuerpo acredita dinero: la pantalla no puede leer la liberación vieja").that(lectura.evidenciaPositivaSinRegistro).isTrue()
        assertWithMessage("sin la evidencia guardada no se habilita la poda").that(dao.getById("l2")!!.serverAnsweredAt).isNull()
    }

    @Test fun `r9 P1-3 · el worker no anota «contesto» si no pudo guardar el veto ni el veredicto que la respuesta trae`() = runTest {
        filaLocal("w1", "INDETERMINADO"); fila("w2", "INDETERMINADO", requestId = "req-w2", hostApproved = null)
        val daoQueNoGuarda = object : PaymentAttemptDao by dao {
            override suspend fun marcarVetoDelServidor(attemptId: String, venueId: String, motivo: String, now: Long): Int =
                throw java.io.IOException("disco lleno")
            override suspend fun aplicarVeredictoDelServidor(veredicto: VeredictoDeIntento, now: Long): ResultadoDelVeredicto =
                throw java.io.IOException("disco lleno")
        }
        coEvery { api.getAttemptStatus(venue, "w1") } returns contradiccion("w1")
        coEvery { api.getAttemptStatus(venue, "w2") } returns recorded("w2", "pay-w2")

        LedgerServerRecovery(daoQueNoGuarda, PaymentAttemptLedger(daoQueNoGuarda, mockk(relaxed = true)), api).recover(venue, now)

        assertThat(dao.getById("w1")!!.serverAnsweredAt).isNull()
        assertThat(dao.getById("w2")!!.serverAnsweredAt).isNull()
    }

    // ── Codex r10 (P1-2): un veredicto que la libreta RECHAZÓ por pertenencia no es un veredicto guardado ──

    /** S6 dice RECORDED con un Payment para el intento… pero atado a OTRA solicitud: el DAO lo rechaza sin escribir nada. */
    private fun recordedDeOtraSolicitud(attemptId: String) = Response.success(
        TerminalAttemptStatusResponse(success = true, attemptId = attemptId, requestId = "req-otra",
            attempt = TerminalAttemptResultDto(attemptId = attemptId, outcome = "RECORDED", paymentId = "pay-ajeno", paymentStatus = "COMPLETED",
                recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = true)),
    )

    /** La misma comprobación para los DOS caminos que aplican veredictos: el sondeo de la pantalla y la pasada del worker. */
    private suspend fun comprobarPertenenciaNoGuardada(entrada: String, intento: String) {
        val f = dao.getById(intento)!!
        assertWithMessage("$entrada: el Payment de otra solicitud no se atribuye").that(f.serverPaymentId).isNull()
        assertWithMessage("$entrada: el dinero queda DURABLE sin atribuir").that(f.serverProcessorEvidence).isEqualTo("APPROVED")
        assertWithMessage("$entrada: sin veredicto guardado no se habilita la poda").that(f.serverAnsweredAt).isNull()
        assertWithMessage(entrada).that(dao.esContradiccion(intento)).isTrue()
    }

    @Test fun `r10 P1-2 · sondeo - un RECORDED de OTRA solicitud no cuenta como guardado - evidencia de dinero, marca durable, sin atribuir ni anotar contesto`() = runTest {
        conLiberacionVieja("per-s")   // ya liberada: si la lectura se pierde, la pantalla dice «puedes cobrar»
        coEvery { api.getAttemptStatus(venue, "per-s") } returns recordedDeOtraSolicitud("per-s")

        val lectura = recovery.recoverOne(venue, "per-s", now, estampar = false)

        assertWithMessage("el servidor dice que hay dinero").that(lectura.evidenciaPositivaSinRegistro).isTrue()
        comprobarPertenenciaNoGuardada("sondeo", "per-s")
    }

    @Test fun `r10 P1-2 · worker - el mismo RECORDED de OTRA solicitud en la pasada de respaldo`() = runTest {
        conLiberacionVieja("per-w")
        coEvery { api.getAttemptStatus(venue, "per-w") } returns recordedDeOtraSolicitud("per-w")

        recovery.recover(venue, now + 5_000)

        comprobarPertenenciaNoGuardada("worker", "per-w")
        // Codex r11: la anomalía GASTA el turno (si no, se consultaría en cada pasada del worker).
        assertThat(dao.getById("per-w")!!.serverCheckedAt).isEqualTo(now + 5_000)
    }

    @Test fun `r11 P2-4 · worker - si falla SOLO aplicar el veredicto, deja la marca durable y pide reintento`() = runTest {
        // Un fallo transitorio de la transacción del veredicto no es «sin dinero»: la escritura independiente de la evidencia
        // puede funcionar, y el worker tiene que volver a intentarlo (sinRespuesta), no dar la consulta por hecha.
        conLiberacionVieja("fa")
        val daoQueNoAplica = object : PaymentAttemptDao by dao {
            override suspend fun aplicarVeredictoDelServidor(veredicto: VeredictoDeIntento, now: Long): ResultadoDelVeredicto =
                throw java.io.IOException("transacción caída")
        }
        coEvery { api.getAttemptStatus(venue, "fa") } returns recorded("fa", "pay-fa")

        val r = LedgerServerRecovery(daoQueNoAplica, PaymentAttemptLedger(daoQueNoAplica, mockk(relaxed = true)), api).recover(venue, now + 5_000)

        val f = dao.getById("fa")!!
        assertThat(f.serverProcessorEvidence).isEqualTo("APPROVED")
        assertThat(f.serverAnsweredAt).isNull()
        assertThat(r.sinRespuesta).isEqualTo(1)
        // Codex r12 (P3-4): y NO gasta el turno — un reintento que se espacia 20 min no es un reintento.
        assertWithMessage("el fallo conserva el turno").that(f.serverCheckedAt).isNull()
        assertThat(f.serverCheckCount).isEqualTo(0)
    }

    // ── Codex r12 (P2-1): cancelar a quien llama ENTRE «aplicar el veredicto» y «dejar la marca» ──

    /** DAO que se detiene DENTRO de la transacción del veredicto hasta que la prueba lo suelte. */
    private fun daoQueSeDetieneAlAplicar(entro: kotlinx.coroutines.CompletableDeferred<Unit>, soltar: kotlinx.coroutines.CompletableDeferred<Unit>) =
        object : PaymentAttemptDao by dao {
            override suspend fun aplicarVeredictoDelServidor(veredicto: VeredictoDeIntento, now: Long): ResultadoDelVeredicto {
                entro.complete(Unit); soltar.await()
                return dao.aplicarVeredictoDelServidor(veredicto, now)
            }
        }

    @Test fun `r12 P2-1 · sondeo - cerrar la pantalla mientras se aplica el veredicto no se lleva la marca de dinero`() = runTest {
        conLiberacionVieja("can-s")   // si la marca se pierde, la fila sólo conserva «se puede volver a cobrar»
        coEvery { api.getAttemptStatus(venue, "can-s") } returns recordedDeOtraSolicitud("can-s")
        val entro = kotlinx.coroutines.CompletableDeferred<Unit>(); val soltar = kotlinx.coroutines.CompletableDeferred<Unit>()
        val d = daoQueSeDetieneAlAplicar(entro, soltar)

        var siguio = false   // Codex r13 (P2-2): `isCancelled` es cierto porque la prueba llamó a cancel(); esto mide si se PROPAGÓ
        val pantalla = launch { LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recoverOne(venue, "can-s", now, estampar = false); siguio = true }
        entro.await()
        pantalla.cancel(); soltar.complete(Unit); pantalla.join()

        assertThat(pantalla.isCancelled).isTrue()
        assertWithMessage("la cancelación se propaga al salir del tramo: quien llamó no sigue").that(siguio).isFalse()
        val f = dao.getById("can-s")!!
        assertWithMessage("el dinero queda durable aunque la pantalla ya no esté").that(f.serverProcessorEvidence).isEqualTo("APPROVED")
        assertThat(f.serverPaymentId).isNull()
    }

    @Test fun `r12 P2-1 · worker - detener el worker mientras se aplica el veredicto no se lleva la marca de dinero`() = runTest {
        conLiberacionVieja("can-w")
        coEvery { api.getAttemptStatus(venue, "can-w") } returns recordedDeOtraSolicitud("can-w")
        val entro = kotlinx.coroutines.CompletableDeferred<Unit>(); val soltar = kotlinx.coroutines.CompletableDeferred<Unit>()
        val d = daoQueSeDetieneAlAplicar(entro, soltar)

        var siguio = false
        val worker = launch { LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recover(venue, now + 5_000); siguio = true }
        entro.await()
        worker.cancel(); soltar.complete(Unit); worker.join()

        assertThat(worker.isCancelled).isTrue()
        assertWithMessage("la cancelación se propaga al salir del tramo: quien llamó no sigue").that(siguio).isFalse()
        val f = dao.getById("can-w")!!
        assertWithMessage("el dinero queda durable aunque el worker se haya detenido").that(f.serverProcessorEvidence).isEqualTo("APPROVED")
        assertThat(f.serverPaymentId).isNull()
    }

    // ── Codex r12 (P2-2): si lo que había que dejar durable NO se escribió, el worker pide reintento y no gasta el turno ──

    private fun daoQueNoMarcaEvidencia() = object : PaymentAttemptDao by dao {
        override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int = throw java.io.IOException("disco lleno")
    }

    @Test fun `r12 P2-2 · worker - si la marca de respaldo FALLA tras el rechazo por pertenencia, pide reintento y conserva el turno`() = runTest {
        conLiberacionVieja("mf")
        coEvery { api.getAttemptStatus(venue, "mf") } returns recordedDeOtraSolicitud("mf")
        val d = daoQueNoMarcaEvidencia()

        val r = LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recover(venue, now + 5_000)

        val f = dao.getById("mf")!!
        assertWithMessage("sin marca ni veredicto guardado, el worker tiene que volver").that(r.sinRespuesta).isEqualTo(1)
        assertWithMessage("el turno no se gasta").that(f.serverCheckedAt).isNull()
        assertThat(f.serverCheckCount).isEqualTo(0)
        assertThat(f.serverAnsweredAt).isNull()
    }

    @Test fun `r12 P2-2b · worker - sin veredicto, si falla guardar la evidencia que trae el cuerpo, pide reintento y conserva el turno`() = runTest {
        fila("sv", "INDETERMINADO", requestId = "req-sv", hostApproved = null)
        // El banco aprobó y todavía no hay Payment: dinero sin veredicto aplicable. Guardarlo falla.
        coEvery { api.getAttemptStatus(venue, "sv") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "sv", requestId = "req-sv",
                attempt = TerminalAttemptResultDto(attemptId = "sv", outcome = "NOT_RECORDED", processorEvidence = "APPROVED")),
        )
        val d = daoQueNoMarcaEvidencia()

        val r = LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recover(venue, now + 5_000)

        val f = dao.getById("sv")!!
        assertThat(r.sinRespuesta).isEqualTo(1)
        assertWithMessage("el turno no se gasta").that(f.serverCheckedAt).isNull()
        assertThat(f.serverAnsweredAt).isNull()
    }

    @Test fun `r12 P2-2c · respaldo por recoverOne (estampar) - si falla guardar la evidencia, no gasta el turno`() = runTest {
        fila("sc", "INDETERMINADO", requestId = "req-sc", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "sc") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "sc", requestId = "req-sc",
                attempt = TerminalAttemptResultDto(attemptId = "sc", outcome = "NOT_RECORDED", processorEvidence = "APPROVED")),
        )
        val d = daoQueNoMarcaEvidencia()

        val lectura = LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recoverOne(venue, "sc", now, estampar = true)

        assertThat(lectura.evidenciaPositivaSinRegistro).isTrue()
        assertWithMessage("el turno queda para el worker").that(dao.getById("sc")!!.serverCheckedAt).isNull()
    }

    // ── Codex r13 (P1): lo que trae UNA respuesta (veto + aprobación) se escribe JUNTO — el veto solo NO aparta el aparato ──

    /** Pago rápido A (sin solicitud y sin orden), ya LIBERADO por el servidor: la fila conserva «se puede volver a cobrar». */
    private suspend fun cobroLocalLiberado(attemptId: String) {
        filaLocal(attemptId, "INDETERMINADO")
        dao.cerrarPorLiberacionLocalDelServidor(attemptId, venue, PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT, "liberada_por_el_servidor:OPERATOR_RECONCILED", now - 1)
        assertThat(dao.getById(attemptId)!!.state).isEqualTo("DESCARTADA")
    }

    /** S6 sin Payment: el banco APROBÓ y además hay un evento que lo contradice. Combinación posible (Codex r13). */
    private fun aprobadoConContradiccion(attemptId: String) = Response.success(
        TerminalAttemptStatusResponse(success = true, attemptId = attemptId, requestId = null,
            attempt = TerminalAttemptResultDto(attemptId = attemptId, outcome = "NOT_RECORDED", processorEvidence = "APPROVED", evidenceContradiction = true)),
    )

    /** DAO que se detiene DENTRO de la escritura del veto hasta que la prueba lo suelte. */
    private fun daoQueSeDetieneEnElVeto(entro: kotlinx.coroutines.CompletableDeferred<Unit>, soltar: kotlinx.coroutines.CompletableDeferred<Unit>) =
        object : PaymentAttemptDao by dao {
            override suspend fun marcarVetoDelServidor(attemptId: String, venueId: String, motivo: String, now: Long): Int {
                entro.complete(Unit); soltar.await()
                return dao.marcarVetoDelServidor(attemptId, venueId, motivo, now)
            }
        }

    /** ¿Un cobro NUEVO de Pago rápido puede apartar la terminal? -1 = la reserva se rechaza. */
    private suspend fun reservaDeOtroCobro(id: String = "otro-cobro"): Long =
        dao.reserveTerminal(id, venue, "ANGELPAY", "SALE", 4000, 0, "FAST", """{"amount":40.00}""", null, now + 10_000, null, false)

    @Test fun `r13 P1 · sondeo - cerrar la pantalla entre el veto y la aprobacion no deja volver a cobrar`() = runTest {
        cobroLocalLiberado("vp-s")
        coEvery { api.getAttemptStatus(venue, "vp-s") } returns aprobadoConContradiccion("vp-s")
        val entro = kotlinx.coroutines.CompletableDeferred<Unit>(); val soltar = kotlinx.coroutines.CompletableDeferred<Unit>()
        val d = daoQueSeDetieneEnElVeto(entro, soltar)
        var siguio = false

        val pantalla = launch { LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recoverOne(venue, "vp-s", now, estampar = false); siguio = true }
        entro.await()
        pantalla.cancel(); soltar.complete(Unit); pantalla.join()

        assertWithMessage("la cancelación se propaga").that(siguio).isFalse()
        val f = dao.getById("vp-s")!!
        assertThat(f.serverVeto).isNotNull()
        assertWithMessage("la aprobación de la MISMA respuesta queda durable").that(f.serverProcessorEvidence).isEqualTo("APPROVED")
        assertWithMessage("otro cobro NO puede apartar la terminal").that(reservaDeOtroCobro()).isEqualTo(-1L)
    }

    @Test fun `r13 P1 · worker - detenerlo entre el veto y la aprobacion no deja volver a cobrar`() = runTest {
        cobroLocalLiberado("vp-w")
        coEvery { api.getAttemptStatus(venue, "vp-w") } returns aprobadoConContradiccion("vp-w")
        val entro = kotlinx.coroutines.CompletableDeferred<Unit>(); val soltar = kotlinx.coroutines.CompletableDeferred<Unit>()
        val d = daoQueSeDetieneEnElVeto(entro, soltar)
        var siguio = false

        val worker = launch { LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recover(venue, now + 5_000); siguio = true }
        entro.await()
        worker.cancel(); soltar.complete(Unit); worker.join()

        assertWithMessage("la cancelación se propaga").that(siguio).isFalse()
        val f = dao.getById("vp-w")!!
        assertThat(f.serverVeto).isNotNull()
        assertWithMessage("la aprobación de la MISMA respuesta queda durable").that(f.serverProcessorEvidence).isEqualTo("APPROVED")
        assertWithMessage("otro cobro NO puede apartar la terminal").that(reservaDeOtroCobro()).isEqualTo(-1L)
    }

    @Test fun `r13 P1 control - con la aprobacion durable la reserva se rechaza, con solo el veto no (lo que mide Codex con SQL)`() = runTest {
        cobroLocalLiberado("ctl")
        dao.marcarVetoDelServidor("ctl", venue, PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION, now)
        assertWithMessage("sólo el veto: la terminal queda libre para otro cobro").that(reservaDeOtroCobro()).isNotEqualTo(-1L)
        db.clearAllTables(); cobroLocalLiberado("ctl")   // sin la reserva de arriba, que ya aparta la terminal por sí sola
        dao.marcarVetoDelServidor("ctl", venue, PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION, now)
        dao.marcarEvidenciaPositivaDelServidor("ctl", venue, now)
        assertWithMessage("veto + aprobación: la terminal queda apartada").that(reservaDeOtroCobro("otro-cobro-2")).isEqualTo(-1L)
    }

    // ── Codex r14 (P1-1): entre las DOS escrituras de una respuesta nadie puede reservar ni autorizar otro cobro ──

    /** DAO que se detiene DESPUÉS del commit del veto (la ventana que describe Codex r14). */
    private fun daoQueSeDetieneTrasElVeto(entro: kotlinx.coroutines.CompletableDeferred<Unit>, soltar: kotlinx.coroutines.CompletableDeferred<Unit>) =
        object : PaymentAttemptDao by dao {
            override suspend fun marcarVetoDelServidor(attemptId: String, venueId: String, motivo: String, now: Long): Int {
                val n = dao.marcarVetoDelServidor(attemptId, venueId, motivo, now)
                entro.complete(Unit); soltar.await()
                return n
            }
        }

    @Test fun `r14 P1-1 · worker - en plena ventana entre el veto y la aprobacion no se reserva otro cobro`() = runTest {
        cobroLocalLiberado("win-w")
        coEvery { api.getAttemptStatus(venue, "win-w") } returns aprobadoConContradiccion("win-w")
        val entro = kotlinx.coroutines.CompletableDeferred<Unit>(); val soltar = kotlinx.coroutines.CompletableDeferred<Unit>()
        val d = daoQueSeDetieneTrasElVeto(entro, soltar)

        val worker = launch { LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recover(venue, now + 5_000) }
        entro.await()
        val enLaVentana = reservaDeOtroCobro("en-la-ventana")
        soltar.complete(Unit); worker.join()

        assertWithMessage("la marca que aparta el aparato ya estaba escrita cuando se escribió el veto").that(enLaVentana).isEqualTo(-1L)
    }

    @Test fun `r14 P1-1 · worker - en plena ventana un cobro ya reservado no puede pasar a AUTORIZANDO`() = runTest {
        cobroLocalLiberado("win-a")
        coEvery { api.getAttemptStatus(venue, "win-a") } returns aprobadoConContradiccion("win-a")
        val entro = kotlinx.coroutines.CompletableDeferred<Unit>(); val soltar = kotlinx.coroutines.CompletableDeferred<Unit>()
        val d = daoQueSeDetieneTrasElVeto(entro, soltar)
        // 🔴 Ronda 20: B lo reserva la MISMA libreta que usa el worker — como en producción, donde es una sola. Reservado directo en la
        // base parecía de un proceso MUERTO: la cuarentena de huérfanos lo descartaba y esta prueba pasaba por el motivo equivocado.
        val libreta = PaymentAttemptLedger(d, mockk(relaxed = true))
        assertWithMessage("B se reservó ANTES de la respuesta").that(libreta.openAttempt("B-previo", venue, "ANGELPAY", 4000, 0, "FAST", """{"amount":40.00}""")).isTrue()

        val worker = launch { LedgerServerRecovery(d, libreta, api).recover(venue, now + 5_000) }
        entro.await()
        val autorizo = dao.casTransition("B-previo", listOf("PREPARANDO", "KERNEL_ACTIVO"), "AUTORIZANDO", now + 20_000)
        soltar.complete(Unit); worker.join()

        assertWithMessage("B no puede autorizar con la aprobación de A ya conocida").that(autorizo).isEqualTo(0)
        assertWithMessage("…y no porque B se hubiera descartado").that(dao.getById("B-previo")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
    }

    @Test fun `r14 P1-1 · sondeo - en plena ventana entre el veto y la aprobacion no se reserva otro cobro`() = runTest {
        cobroLocalLiberado("win-s")
        coEvery { api.getAttemptStatus(venue, "win-s") } returns aprobadoConContradiccion("win-s")
        val entro = kotlinx.coroutines.CompletableDeferred<Unit>(); val soltar = kotlinx.coroutines.CompletableDeferred<Unit>()
        val d = daoQueSeDetieneTrasElVeto(entro, soltar)

        val pantalla = launch { LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recoverOne(venue, "win-s", now, estampar = false) }
        entro.await()
        val enLaVentana = reservaDeOtroCobro("en-la-ventana")
        soltar.complete(Unit); pantalla.join()

        assertThat(enLaVentana).isEqualTo(-1L)
    }

    // ── Codex r14 (P1-2): una aprobación que llega CON Payment tampoco puede dejar el aparato sin apartar ──

    /** S6 con dinero Y Payment sobre el cobro LOCAL (sin solicitud): el veredicto se guarda, la fila sigue DESCARTADA. */
    private fun aprobadoConPago(attemptId: String, outcome: String) = Response.success(
        TerminalAttemptStatusResponse(success = true, attemptId = attemptId, requestId = null,
            attempt = TerminalAttemptResultDto(attemptId = attemptId, outcome = outcome, paymentId = "pay-$attemptId", paymentStatus = "COMPLETED",
                recordedVia = "webhook", amountCents = 4000, tipCents = 0, isWinner = true, processorEvidence = "APPROVED")),
    )

    @Test fun `r14 P1-2 · worker - REFERENCE_COLLISION con Payment sobre la fila liberada aparta el aparato`() = runTest {
        cobroLocalLiberado("col-w")
        coEvery { api.getAttemptStatus(venue, "col-w") } returns aprobadoConPago("col-w", "REFERENCE_COLLISION_EVIDENCE")

        recovery.recover(venue, now + 5_000)

        assertWithMessage("con dinero pendiente de conciliar, otro cobro NO aparta la terminal").that(reservaDeOtroCobro()).isEqualTo(-1L)
    }

    @Test fun `r14 P1-2 · worker - RECORDED sobre la fila liberada aparta el aparato`() = runTest {
        cobroLocalLiberado("rec-w")
        coEvery { api.getAttemptStatus(venue, "rec-w") } returns aprobadoConPago("rec-w", "RECORDED")

        recovery.recover(venue, now + 5_000)

        assertThat(reservaDeOtroCobro()).isEqualTo(-1L)
    }

    @Test fun `r14 P1-2 · sondeo - REFERENCE_COLLISION con Payment sobre la fila liberada aparta el aparato`() = runTest {
        cobroLocalLiberado("col-s")
        coEvery { api.getAttemptStatus(venue, "col-s") } returns aprobadoConPago("col-s", "REFERENCE_COLLISION_EVIDENCE")

        recovery.recoverOne(venue, "col-s", now, estampar = false)

        assertThat(reservaDeOtroCobro()).isEqualTo(-1L)
    }

    // ── Codex r14 (P2-3, P2-4): la rotación entre PASADAS, y el fallo de una estampa también es atasco de escritura ──

    @Test fun `r14 P2-3 · 100 candidatas atoradas no tapan a la 101 - la pasada siguiente empieza por las que no se consultaron`() = runTest {
        for (i in 0 until 100) fila("at-%03d".format(i), "INDETERMINADO", hostApproved = null)
        fila("zz-sana", "INDETERMINADO", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, any()) } answers {
            val id = secondArg<String>(); if (id.startsWith("at-")) recordedDeOtraSolicitud(id) else recorded(id, "pay-sana")
        }
        val d = object : PaymentAttemptDao by dao {
            override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int =
                if (attemptId.startsWith("at-")) throw java.io.IOException("disco lleno") else dao.marcarEvidenciaPositivaDelServidor(attemptId, venueId, at)
        }
        val worker = LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api)

        worker.recover(venue, now + 5_000)   // cuatro páginas de atoradas: tope de la pasada
        worker.recover(venue, now + 6_000)

        assertWithMessage("la sana se atendió en la pasada siguiente").that(dao.getById("zz-sana")!!.serverPaymentId).isEqualTo("pay-sana")
    }

    @Test fun `r14 P2-4 · si falla ESTAMPAR tras un 2xx, cuenta como atasco de escritura y la pasada pagina`() = runTest {
        for (i in 0 until 25) fila("es-%02d".format(i), "INDETERMINADO", hostApproved = null)
        fila("zz-sana", "INDETERMINADO", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, any()) } answers { val id = secondArg<String>(); if (id.startsWith("es-")) notRecorded else recorded(id, "pay-sana") }
        val d = object : PaymentAttemptDao by dao {
            override suspend fun estamparRespuestaDelServidor(attemptId: String, now: Long): Int =
                if (attemptId.startsWith("es-")) throw java.io.IOException("disco lleno") else dao.estamparRespuestaDelServidor(attemptId, now)
        }

        LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recover(venue, now + 5_000)

        assertThat(dao.getById("zz-sana")!!.serverPaymentId).isEqualTo("pay-sana")
    }

    @Test fun `r14 · el tramo de lo del cuerpo PROPAGA la cancelacion aunque la escritura falle (sin otra suspension despues)`() = runTest {
        // Aísla el `ensureActive()` de `guardarLoDelCuerpo`: si la escritura falla, `recoverOne` ya no llama a Room después, así
        // que nada más detectaría la cancelación.
        fila("ea", "INDETERMINADO", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "ea") } returns contradiccion("ea")
        val entro = kotlinx.coroutines.CompletableDeferred<Unit>(); val soltar = kotlinx.coroutines.CompletableDeferred<Unit>()
        val d = object : PaymentAttemptDao by dao {
            override suspend fun marcarVetoDelServidor(attemptId: String, venueId: String, motivo: String, now: Long): Int {
                entro.complete(Unit); soltar.await(); throw java.io.IOException("disco lleno")
            }
        }
        var siguio = false

        val pantalla = launch { LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recoverOne(venue, "ea", now, estampar = false); siguio = true }
        entro.await()
        pantalla.cancel(); soltar.complete(Unit); pantalla.join()

        assertWithMessage("quien llamó no sigue").that(siguio).isFalse()
    }

    // ── Codex r15 (P1): sin la marca que aparta el aparato NO se guarda el veredicto — si no, la fila sale de toda recuperación ──

    /** La marca de aprobación falla UNA vez (fallo transitorio) y después funciona. */
    private fun daoConMarcaQueFallaUnaVez() = object : PaymentAttemptDao by dao {
        val primera = java.util.concurrent.atomic.AtomicBoolean(true)
        override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int =
            if (primera.getAndSet(false)) throw java.io.IOException("disco lleno") else dao.marcarEvidenciaPositivaDelServidor(attemptId, venueId, at)
    }

    @Test fun `r15 P1 · worker - si la marca falla NO se guarda el RECORDED, y la pasada siguiente deja el aparato apartado`() = runTest {
        cobroLocalLiberado("mf-w")
        coEvery { api.getAttemptStatus(venue, "mf-w") } returns aprobadoConPago("mf-w", "RECORDED")
        val d = daoConMarcaQueFallaUnaVez()
        // 🔴 Ronda 20: B lo reserva la libreta del worker (una sola, como en producción): directo en la base parecía de un proceso
        // muerto, la cuarentena lo descartaba y la aserción final pasaba por el motivo equivocado.
        val libreta = PaymentAttemptLedger(d, mockk(relaxed = true))
        assertWithMessage("B se reservó antes (sin protección, entra)").that(libreta.openAttempt("B-previo", venue, "ANGELPAY", 4000, 0, "FAST", """{"amount":40.00}""")).isTrue()
        val worker = LedgerServerRecovery(d, libreta, api)

        val primera = worker.recover(venue, now + 5_000)
        val tras1 = dao.getById("mf-w")!!
        // La fila venía de una liberación (OPERATOR_NO_INSTRUMENT): sin la marca, el RECORDED NO la sustituye y sigue siendo candidata.
        assertWithMessage("sin la marca, el veredicto NO se guarda").that(tras1.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
        assertThat(tras1.serverPaymentId).isNull()
        assertThat(tras1.serverCheckedAt).isNull()
        assertThat(primera.sinRespuesta).isEqualTo(1)

        worker.recover(venue, now + 6_000)
        assertThat(dao.getById("mf-w")!!.serverProcessorEvidence).isEqualTo("APPROVED")
        assertWithMessage("recuperada la marca, B ya no puede autorizar").that(
            dao.casTransition("B-previo", listOf("PREPARANDO", "KERNEL_ACTIVO"), "AUTORIZANDO", now + 20_000)).isEqualTo(0)
        assertWithMessage("…y no porque B se hubiera descartado").that(dao.getById("B-previo")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
    }

    @Test fun `r15 P1 · sondeo - si la marca falla NO se guarda el veredicto y la lectura dice que hay dinero`() = runTest {
        cobroLocalLiberado("mf-s")
        coEvery { api.getAttemptStatus(venue, "mf-s") } returns aprobadoConPago("mf-s", "RECORDED")
        val d = daoConMarcaQueFallaUnaVez()

        val lectura = LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recoverOne(venue, "mf-s", now, estampar = true)

        assertThat(lectura.evidenciaPositivaSinRegistro).isTrue()
        val f = dao.getById("mf-s")!!
        assertWithMessage("el veredicto queda para el worker, que sí puede proteger").that(f.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
        assertThat(f.serverPaymentId).isNull()
        assertThat(f.serverCheckedAt).isNull()
    }

    // ── Codex r15 (P2): la rotación avanza por TODO el conjunto de atoradas, no alterna dos grupos ──

    @Test fun `r15 P2 · 200 atoradas no tapan a la 201, y las atoradas vuelven a recibir turno`() = runTest {
        for (i in 0 until 200) fila("at-%03d".format(i), "INDETERMINADO", hostApproved = null)
        fila("zz-sana", "INDETERMINADO", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, any()) } answers {
            val id = secondArg<String>(); if (id.startsWith("at-")) recordedDeOtraSolicitud(id) else recorded(id, "pay-sana")
        }
        val d = object : PaymentAttemptDao by dao {
            override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int =
                if (attemptId.startsWith("at-")) throw java.io.IOException("disco lleno") else dao.marcarEvidenciaPositivaDelServidor(attemptId, venueId, at)
        }
        val worker = LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api)

        for (pasada in 1..3) worker.recover(venue, now + 5_000 + pasada)

        assertWithMessage("la sana se atendió dentro de tres pasadas").that(dao.getById("zz-sana")!!.serverPaymentId).isEqualTo("pay-sana")
        coVerify(atLeast = 2) { api.getAttemptStatus(venue, "at-000") }   // las atoradas vuelven a recibir turno
        coVerify(atLeast = 2) { api.getAttemptStatus(venue, "at-030") }   // …también las de la SEGUNDA vuelta: la rueda avanza
        coVerify(atLeast = 1) { api.getAttemptStatus(venue, "at-199") }   // …y todas lo reciben al menos una vez
    }

    // ── Codex r16 (P1): la marca va DENTRO de la transacción del veredicto — ningún llamador puede guardarlo sin ella ──

    @Test fun `r16 P1 · el respaldo de S5 de la pantalla (aplica sin marcar antes) deja el aparato apartado`() = runTest {
        // A: cobro remoto sin orden que el SDK cerró como «no se cobró». El listener de S5 no pudo marcar (y por eso no aplicó);
        // la pantalla ya anunció el negativo y deja durable el MISMO veredicto de S5 llamando a la libreta directamente.
        fila("s5-a", "DESCARTADA", hostApproved = null)
        assertWithMessage("B se reservó antes (A no aparta nada todavía)").that(reservaDeOtroCobro("B-previo")).isNotEqualTo(-1L)

        val r = ledger.aplicarVeredictoDelServidor(
            VeredictoDeIntento.desdeAvisoS5(venue, "req-s5-a", "s5-a", "pay-s5-a", "webhook", 10000, 0), now + 5_000,
        ).getOrThrow()

        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR)
        assertWithMessage("el veredicto con dinero se guardó CON la marca").that(dao.getById("s5-a")!!.serverProcessorEvidence).isEqualTo("APPROVED")
        assertWithMessage("B ya no puede autorizar").that(
            dao.casTransition("B-previo", listOf("PREPARANDO", "KERNEL_ACTIVO"), "AUTORIZANDO", now + 20_000)).isEqualTo(0)
    }

    // ── Codex r16 (P2): la rueda no puede desbordar las variables de SQLite, y un fallo de LECTURA no es «ya no hay más» ──

    @Test fun `r16 P2 · 1000 atoradas con el tope de 999 variables de SQLite no dejan sin turno a la sana`() = runTest {
        var lecturas = 0
        for (i in 0 until 1000) fila("at-%04d".format(i), "INDETERMINADO", hostApproved = null)
        fila("zz-sana", "INDETERMINADO", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, any()) } answers {
            val id = secondArg<String>(); if (id.startsWith("at-")) recordedDeOtraSolicitud(id) else recorded(id, "pay-sana")
        }
        val d = object : PaymentAttemptDao by dao {
            override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int =
                if (attemptId.startsWith("at-")) throw java.io.IOException("disco lleno") else dao.marcarEvidenciaPositivaDelServidor(attemptId, venueId, at)
            // SQLite anterior a 3.32 (Android API 27 trae 3.19): 999 variables por sentencia.
            override suspend fun candidatasDeConsultaAlServidor(
                venueId: String, vivosAntesDe: Long, now: Long, espaciadoBaseMs: Long, espaciadoTopeMs: Long,
                trasTurno: Long, trasCreado: Long, trasId: String, limite: Int,
            ): List<PaymentAttemptEntity> {
                lecturas++
                return dao.candidatasDeConsultaAlServidor(venueId, vivosAntesDe, now, espaciadoBaseMs, espaciadoTopeMs, trasTurno, trasCreado, trasId, limite)
            }
        }
        val worker = LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api)

        for (pasada in 1..15) worker.recover(venue, now + 5_000 + pasada)
        lecturas = 0
        worker.recover(venue, now + 5_000 + 16)

        assertWithMessage("la sana se atendió").that(dao.getById("zz-sana")!!.serverPaymentId).isEqualTo("pay-sana")
        // 🔴 Codex r17 (P3): con la rueda llena, cada página de 25 volvía a ordenar el venue entero (~40 lecturas por pasada).
        assertWithMessage("una pasada con la rueda llena lee pocas páginas").that(lecturas).isAtMost(6)
    }

    @Test fun `r16 P2 · la consulta de candidatas no recibe listas (sus variables no crecen con la rueda)`() {
        val conLista = PaymentAttemptDao::class.java.methods.filter { it.name == "candidatasDeConsultaAlServidor" }
            .filter { m -> m.parameterTypes.any { java.util.Collection::class.java.isAssignableFrom(it) } }
        assertThat(conLista).isEmpty()
    }

    @Test fun `r16 P2 · si la LECTURA de candidatas falla, lo dice y no saca de la rueda a las que no se consultaron`() = runTest {
        for (i in 0 until 100) fila("at-%03d".format(i), "INDETERMINADO", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, any()) } answers { recordedDeOtraSolicitud(secondArg()) }
        val fallarLectura = java.util.concurrent.atomic.AtomicBoolean(false)
        val d = object : PaymentAttemptDao by dao {
            override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int =
                throw java.io.IOException("disco lleno")
            override suspend fun candidatasDeConsultaAlServidor(
                venueId: String, vivosAntesDe: Long, now: Long, espaciadoBaseMs: Long, espaciadoTopeMs: Long,
                trasTurno: Long, trasCreado: Long, trasId: String, limite: Int,
            ): List<PaymentAttemptEntity> {
                if (fallarLectura.getAndSet(false)) throw android.database.sqlite.SQLiteException("database is locked")
                return dao.candidatasDeConsultaAlServidor(venueId, vivosAntesDe, now, espaciadoBaseMs, espaciadoTopeMs, trasTurno, trasCreado, trasId, limite)
            }
        }
        val worker = LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api)

        worker.recover(venue, now + 5_000)                // pasada 1: conoce las 100 atoradas (el tope de la pasada)
        fallarLectura.set(true)
        val fallida = worker.recover(venue, now + 6_000)  // pasada 2: su turno era at-000…at-024, y la lectura falla
        worker.recover(venue, now + 7_000)                // pasada 3: turno de at-025…at-049

        assertWithMessage("un fallo de lectura pide reintento (no es «ya no hay más»)").that(fallida.sinRespuesta).isGreaterThan(0)
        coVerify(exactly = 1) { api.getAttemptStatus(venue, "at-000") }   // sigue en la rueda: la pasada 3 la excluye
    }

    // ── Codex r17 (P2): el tope de la pasada se aplica fila por fila, no al terminar la página ──

    @Test fun `r17 P2 · una atorada y 24 sin respuesta en la primera pagina - la pasada para en 25 sin respuesta, no en 49`() = runTest {
        fila("a-atorada", "INDETERMINADO", hostApproved = null)
        for (i in 0 until 49) fila("b-%02d".format(i), "INDETERMINADO", hostApproved = null)
        var llamadas = 0
        coEvery { api.getAttemptStatus(venue, any()) } answers {
            llamadas++
            val id = secondArg<String>(); if (id.startsWith("a-")) recordedDeOtraSolicitud(id) else throw java.io.IOException("sin red")
        }
        val d = object : PaymentAttemptDao by dao {
            override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int =
                if (attemptId.startsWith("a-")) throw java.io.IOException("disco lleno") else dao.marcarEvidenciaPositivaDelServidor(attemptId, venueId, at)
        }

        LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recover(venue, now + 5_000)

        assertWithMessage("la atorada + 25 sin respuesta (el tope), no 49").that(llamadas).isEqualTo(26)
    }

    @Test fun `r17 P2 · con una excluida en la primera pagina la pasada atiende 100, no 124`() = runTest {
        for (i in 0 until 26) fila("at-%03d".format(i), "INDETERMINADO", hostApproved = null)
        var llamadas = 0
        coEvery { api.getAttemptStatus(venue, any()) } answers { llamadas++; recordedDeOtraSolicitud(secondArg()) }
        val d = object : PaymentAttemptDao by dao {
            override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int =
                throw java.io.IOException("disco lleno")
        }
        val worker = LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api)
        worker.recover(venue, now + 5_000)                        // la rueda conoce 26: turno at-000…at-024, excluida at-025
        for (i in 26 until 200) fila("at-%03d".format(i), "INDETERMINADO", hostApproved = null)
        llamadas = 0

        worker.recover(venue, now + 6_000)

        assertWithMessage("el tope de 100 filas atendidas").that(llamadas).isEqualTo(100)
    }

    // ── Codex r17 (P3): el cursor, con TODAS sus claves; y la marca, dentro de una transacción de verdad ──

    @Test fun `r17 P3 · el cursor recorre todas las claves del orden - turnos distintos, empates de fecha y de turno`() = runTest {
        val filas = mutableListOf<PaymentAttemptEntity>()
        var n = 0
        for (turno in listOf<Long?>(null, now - 3_600_000, now - 7_200_000)) for (creada in listOf(now - 600_000, now - 900_000)) for (k in 0 until 5) {   // ids AL REVÉS de la fecha: la fecha tiene que mandar
            filas += PaymentAttemptEntity(
                attemptId = "c-%02d".format(n++), venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 100, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = "{}", createdAt = creada, updatedAt = creada, serverCheckedAt = turno,
            )
        }
        filas.forEach { dao.insert(it) }
        val esperado = filas.sortedWith(compareBy<PaymentAttemptEntity>({ it.serverCheckedAt ?: -1L }, { it.createdAt }, { it.attemptId })).map { it.attemptId }

        val todas = dao.candidatasDeConsultaAlServidor(venue, now, now, 600_000, 86_400_000, Long.MIN_VALUE, Long.MIN_VALUE, "", 1000).map { it.attemptId }
        val paginadas = mutableListOf<String>()
        var tras = Triple(Long.MIN_VALUE, Long.MIN_VALUE, "")
        while (true) {
            val pagina = dao.candidatasDeConsultaAlServidor(venue, now, now, 600_000, 86_400_000, tras.first, tras.second, tras.third, 4)
            paginadas += pagina.map { it.attemptId }
            if (pagina.size < 4) break
            pagina.last().let { tras = Triple(it.serverCheckedAt ?: -1L, it.createdAt, it.attemptId) }
        }

        assertWithMessage("el orden de siempre (NULL primero, luego fecha e id)").that(todas).containsExactlyElementsIn(esperado).inOrder()
        assertWithMessage("paginado de 4 en 4: todas, una vez y en orden").that(paginadas).containsExactlyElementsIn(esperado).inOrder()
    }

    @Test fun `r17 P3 · si una escritura posterior del veredicto falla, la transaccion no deja ni la marca ni el veredicto a medias`() = runTest {
        fila("tx", "HOST_RESPONDIO")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER falla_al_registrar BEFORE UPDATE OF state ON payment_attempts " +
                "WHEN NEW.attempt_id = 'tx' AND NEW.state = 'REGISTRADO' BEGIN SELECT RAISE(ABORT, 'disco lleno'); END",
        )

        val r = runCatching { dao.aplicarVeredictoDelServidor(VeredictoDeIntento.desdeAvisoS5(venue, "req-tx", "tx", "pay-tx", "webhook", 10000, 0), now) }

        assertThat(r.isFailure).isTrue()
        val f = dao.getById("tx")!!
        assertWithMessage("la marca se deshizo con la transacción").that(f.serverProcessorEvidence).isNull()
        assertWithMessage("el veredicto también").that(f.serverOutcome).isNull()
        assertThat(f.state).isEqualTo("HOST_RESPONDIO")
    }

    // ── Codex r13 (P2-4): 25 filas que no avanzan no pueden tapar para siempre a la 26 ──

    @Test fun `r13 P2-4 · 25 candidatas que no pueden guardar no tapan a la 26 - la pasada sigue a la pagina siguiente`() = runTest {
        for (i in 0 until 25) fila("at-%02d".format(i), "INDETERMINADO", hostApproved = null)
        fila("zz-sana", "INDETERMINADO", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, any()) } answers {
            val id = secondArg<String>(); if (id.startsWith("at-")) recordedDeOtraSolicitud(id) else recorded(id, "pay-sana")
        }
        val d = object : PaymentAttemptDao by dao {   // la marca de respaldo falla SIEMPRE para las 25 atoradas
            override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int =
                if (attemptId.startsWith("at-")) throw java.io.IOException("disco lleno") else dao.marcarEvidenciaPositivaDelServidor(attemptId, venueId, at)
        }

        LedgerServerRecovery(d, PaymentAttemptLedger(d, mockk(relaxed = true)), api).recover(venue, now + 5_000)

        assertWithMessage("la 26 se atendió en la misma pasada").that(dao.getById("zz-sana")!!.serverPaymentId).isEqualTo("pay-sana")
        assertWithMessage("las atoradas siguen sin gastar su turno").that(dao.getById("at-00")!!.serverCheckedAt).isNull()
    }

    @Test fun `r13 P2-4 control - sin red NO se pagina (martillaria al servidor sin poder avanzar)`() = runTest {
        for (i in 0..25) fila("sr-%02d".format(i), "INDETERMINADO", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, any()) } throws java.io.IOException("sin red")

        val r = recovery.recover(venue, now + 5_000)

        assertThat(r.sinRespuesta).isEqualTo(25)   // una página (el LIMIT de la consulta), no las 26
        coVerify(exactly = 25) { api.getAttemptStatus(venue, any()) }
    }

    @Test fun `r9 P1-3 control · con todo guardado, el worker SI anota que el servidor contesto`() = runTest {
        filaLocal("w3", "INDETERMINADO")
        coEvery { api.getAttemptStatus(venue, "w3") } returns contradiccion("w3")
        recovery.recover(venue, now)
        assertThat(dao.getById("w3")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
        assertThat(dao.getById("w3")!!.serverAnsweredAt).isEqualTo(now)
    }

    @Test fun `r8 P2-4 · el sondeo de la pantalla anota que el servidor CONTESTO sin gastar el turno, y un 503 no anota nada`() = runTest {
        // El sondeo interactivo (estampar=false) no gasta el turno de E3, pero una respuesta 2xx es un hecho: es lo que el
        // candado 5 de la declaración sin red y la poda necesitan saber. Un 503 no es respuesta.
        filaLocal("r1", "INDETERMINADO"); filaLocal("r2", "INDETERMINADO")
        coEvery { api.getAttemptStatus(venue, "r1") } returns notRecorded
        coEvery { api.getAttemptStatus(venue, "r2") } returns http(503)

        recovery.recoverOne(venue, "r1", now, estampar = false)
        recovery.recoverOne(venue, "r2", now, estampar = false)

        assertThat(dao.getById("r1")!!.serverAnsweredAt).isEqualTo(now)
        assertThat(dao.getById("r1")!!.serverCheckedAt).isNull()
        assertThat(dao.getById("r2")!!.serverAnsweredAt).isNull()
    }

    @Test fun `r8 P1-2 control · una respuesta limpia no trae veto`() = runTest {
        filaLocal("w2", "INDETERMINADO")
        coEvery { api.getAttemptStatus(venue, "w2") } returns notRecorded
        assertThat(recovery.recoverOne(venue, "w2", now, estampar = false).vetoDelServidor).isNull()
    }

    @Test fun `r6 P1-2b · una respuesta limpia del worker NO inventa ningun veto (control positivo)`() = runTest {
        fila("v2", "INDETERMINADO", requestId = "req-v2", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "v2") } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "v2", requestId = "req-v2",
                attempt = TerminalAttemptResultDto(attemptId = "v2", outcome = "NOT_RECORDED")),
        )
        recovery.recover(venue, now)
        assertThat(dao.getById("v2")!!.serverVeto).isNull()
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

    // ── Task 7 · fix 4 (Codex, P1): la evidencia POSITIVA del servidor es DURABLE — la escriben el lote (E3), `recoverOne` y el 2xx. ──

    private fun aprobadaSinPayment(attemptId: String, requestId: String, outcome: String = "NOT_RECORDED", paymentId: String? = null, evidencia: String? = "APPROVED") = Response.success(
        TerminalAttemptStatusResponse(success = true, attemptId = attemptId, requestId = requestId,
            attempt = TerminalAttemptResultDto(attemptId = attemptId, outcome = outcome, paymentId = paymentId, processorEvidence = evidencia),
            request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"}""").asJsonObject),
    )

    @Test fun `fix4 W4 escritor - la marca APPROVED se escribe en INDETERMINADO y en DESCARTADA, la primera fecha es estable, y estado, version, host y outcome quedan intactos`() = runTest {
        fila("w1", "INDETERMINADO", requestId = "req-w1", hostApproved = null)
        fila("w2", "INDETERMINADO", requestId = "req-w2", hostApproved = null)
        dao.cerrarPorLiberacionDelServidor("w2", venue, "req-w2", "RELEASED_NO_EVIDENCE", "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", now - 1)
        val antes1 = dao.getById("w1")!!; val antes2 = dao.getById("w2")!!
        fila("legacy", "INDETERMINADO", requestId = "req-legacy", hostApproved = null)
        dao.insert(PaymentAttemptEntity(attemptId = "unsupported", venueId = venue, processor = "OTHER", state = "INDETERMINADO", amountCents = 1, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = "{}", createdAt = now, updatedAt = now, terminalPaymentRequestId = "req-b"))
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET legacy_shadow = 1 WHERE attempt_id = 'legacy'")

        assertThat(dao.marcarEvidenciaPositivaDelServidor("w1", venue, now)).isEqualTo(1)
        assertThat(dao.marcarEvidenciaPositivaDelServidor("w2", venue, now)).isEqualTo(1)
        assertThat(dao.marcarEvidenciaPositivaDelServidor("w1", venue, now + 5_000)).isEqualTo(1)   // idempotente…
        assertThat(dao.marcarEvidenciaPositivaDelServidor("w1", "otro-venue", now)).isEqualTo(0)      // pertenencia
        assertThat(dao.marcarEvidenciaPositivaDelServidor("legacy", venue, now)).isEqualTo(0)        // alcance del checkpoint
        assertThat(dao.marcarEvidenciaPositivaDelServidor("unsupported", venue, now)).isEqualTo(0)

        val w1 = dao.getById("w1")!!; val w2 = dao.getById("w2")!!
        assertThat(w1.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        assertThat(w1.serverProcessorEvidenceAt).isEqualTo(now)                                        // …y la PRIMERA fecha se conserva
        assertThat(w2.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        assertThat(w2.serverProcessorEvidenceAt).isEqualTo(now)
        assertThat(w1.copy(serverProcessorEvidence = null, serverProcessorEvidenceAt = null)).isEqualTo(antes1)   // nada más cambió
        assertThat(w2.copy(serverProcessorEvidence = null, serverProcessorEvidenceAt = null)).isEqualTo(antes2)
        assertThat(w2.state).isEqualTo("DESCARTADA"); assertThat(w2.serverOutcome).isEqualTo("RELEASED_NO_EVIDENCE"); assertThat(w2.hostApproved).isNull()
        assertThat(dao.getById("legacy")!!.serverProcessorEvidence).isNull()
        assertThat(dao.getById("unsupported")!!.serverProcessorEvidence).isNull()
    }

    @Test fun `fix4 W1 recover - S6 APPROVED sin Payment deja la evidencia DURABLE en la fila, no libera y la fila sigue INDETERMINADO`() = runTest {
        fila("l1", "INDETERMINADO", requestId = "req-l1", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "l1") } returns aprobadaSinPayment("l1", "req-l1")
        val r = recovery.recover(venue, now)
        assertThat(r.liberadas).isEqualTo(0)
        val tras = dao.getById("l1")!!
        assertThat(tras.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        assertThat(tras.serverProcessorEvidenceAt).isEqualTo(now)
        assertThat(tras.state).isEqualTo("INDETERMINADO")
        assertThat(tras.serverOutcome).isNull()
        assertThat(tras.hostApproved).isNull()
        assertThat(tras.serverCheckedAt).isEqualTo(now)   // el lote sí gasta el turno
    }

    @Test fun `fix4 W2 recoverOne - escribe la marca, y un outcome con dinero SIN paymentId tambien la escribe con server_outcome NULL`() = runTest {
        fila("o1", "INDETERMINADO", requestId = "req-o1", hostApproved = null)
        fila("o2", "INDETERMINADO", requestId = "req-o2", hostApproved = null)
        coEvery { api.getAttemptStatus(venue, "o1") } returns aprobadaSinPayment("o1", "req-o1")
        coEvery { api.getAttemptStatus(venue, "o2") } returns aprobadaSinPayment("o2", "req-o2", outcome = "PENDING_EVIDENCE", paymentId = null, evidencia = null)

        val l1 = recovery.recoverOne(venue, "o1", now, estampar = false)
        assertThat(l1.evidenciaPositivaSinRegistro).isTrue()
        assertThat(dao.getById("o1")!!.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        assertThat(dao.getById("o1")!!.serverProcessorEvidenceAt).isEqualTo(now)
        assertThat(dao.getById("o1")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("o1")!!.serverCheckedAt).isNull()   // el sondeo no estampa

        val l2 = recovery.recoverOne(venue, "o2", now)
        assertThat(l2.evidenciaPositivaSinRegistro).isTrue()
        val o2 = dao.getById("o2")!!
        assertThat(o2.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        assertThat(o2.serverOutcome).isNull()          // sin `paymentId` no hay veredicto que guardar…
        assertThat(o2.serverPaymentId).isNull()
        assertThat(o2.state).isEqualTo("INDETERMINADO")   // …y la fila no se marca RECORDED en local
    }

    @Test fun `fix4 W2b recoverOne - la marca se escribe tambien sobre una fila YA liberada (DESCARTADA) y una segunda consulta no la re-fecha`() = runTest {
        fila("d1", "INDETERMINADO", requestId = "req-d1", hostApproved = null)
        dao.cerrarPorLiberacionDelServidor("d1", venue, "req-d1", "RELEASED_NO_EVIDENCE", "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", now - 10)
        coEvery { api.getAttemptStatus(venue, "d1") } returns aprobadaSinPayment("d1", "req-d1")

        assertThat(recovery.recoverOne(venue, "d1", now, estampar = false).evidenciaPositivaSinRegistro).isTrue()
        assertThat(recovery.recoverOne(venue, "d1", now + 7_000, estampar = false).evidenciaPositivaSinRegistro).isTrue()

        val d1 = dao.getById("d1")!!
        assertThat(d1.state).isEqualTo("DESCARTADA")
        assertThat(d1.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        assertThat(d1.serverProcessorEvidenceAt).isEqualTo(now)
        assertThat(dao.esContradiccion("d1")).isTrue()
    }

    // ── Un cobro LOCAL (sin solicitud del POS) también se recupera (21-sep) ──────────────────

    @Test fun `P1 un cobro LOCAL sin solicitud del POS TAMBIEN es candidato de consulta al servidor`() = runTest {
        // 🔴 MEDIDO EN UNA N86 (21-sep): al morir la app con el lector activo, la fila queda AUTORIZANDO
        // apartando la terminal, y a los 3 minutos NADIE la había consultado (`server_check_count = 0`).
        // La causa no era un job mal agendado: `candidatasDeConsultaAlServidor` exigía
        // `terminal_payment_request_id IS NOT NULL`, y un Pago rápido no lleva solicitud ⇒ nunca entraba
        // en la lista. El cajero se quedaba sin cobrar con esa terminal y sin nada que lo intentara.
        filaLocal("loc1", "INDETERMINADO")

        val candidatas = dao.candidatasDeConsultaAlServidor(venue, now - 120_000, now + 1, 600_000, 86_400_000)

        assertThat(candidatas.map { it.attemptId }).contains("loc1")
    }

    @Test fun `P1 un cobro LOCAL que el servidor dice RECORDED se cierra solo y suelta la terminal`() = runTest {
        filaLocal("loc2", "INDETERMINADO")
        coEvery { api.getAttemptStatus(venue, "loc2") } returns Response.success(
            TerminalAttemptStatusResponse(
                success = true, attemptId = "loc2", requestId = null,
                attempt = TerminalAttemptResultDto(
                    attemptId = "loc2", outcome = "RECORDED", paymentId = "pay-loc2", isWinner = true,
                    amountCents = 4000, tipCents = 0, recordedVia = "terminal",
                ),
                request = null,
            ),
        )

        recovery.recover(venue, now)

        assertThat(dao.getById("loc2")!!.state).isEqualTo("REGISTRADO")
    }

    @Test fun `P1 un cobro LOCAL SIN evidencia NUNCA se libera — sin solicitud no hay pertenencia que comprobar`() = runTest {
        // 🔴 El lado seguro del mismo cambio: que entre a la lista NO puede volverlo liberable. Un 404 o un
        // «sin dinero» jamás acredita ausencia de cobro, y sin solicitud la liberación ni siquiera aplica.
        filaLocal("loc3", "INDETERMINADO")
        coEvery { api.getAttemptStatus(venue, "loc3") } returns Response.success(
            TerminalAttemptStatusResponse(
                success = true, attemptId = "loc3", requestId = null,
                attempt = TerminalAttemptResultDto(attemptId = "loc3", outcome = "NOT_RECORDED"),
                request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"}""").asJsonObject,
            ),
        )

        val r = recovery.recover(venue, now)

        assertThat(r.liberadas).isEqualTo(0)
        assertThat(dao.getById("loc3")!!.state).isEqualTo("INDETERMINADO")
    }
}
