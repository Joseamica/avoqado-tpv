package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerServerRecovery
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptDao
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptApiService
import com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptResultDto
import com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptStatusResponse
import com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * Task 7 · fix 4 (Codex, P1): la evidencia POSITIVA del servidor sobre un intento («el banco aprobó, aunque no haya
 * Payment») es DURABLE en la libreta (`server_processor_evidence = 'APPROVED'`, Room v36) y la leen TODOS los lectores que
 * antes sólo miraban la RAM del ViewModel — sobre Room REAL, con la bandeja y la libreta en la misma base:
 *  - la cancelación remota (`cancelarTrasReclamar` ⇒ false ⇒ ACTIVE) y el CAS de negativos H.3 (no se escribe nada);
 *  - el CAS de liberación (una respuesta atrasada sin evidencia NO descarta una INDETERMINADO con evidencia);
 *  - la contradicción derivada (`SQL_CONTRADICCION`), el aviso F0 fechado desde la evidencia, el cierre y la poda;
 *  - las cercas de VENTA (por `orderId`, incluso con otra solicitud) y de APARATO (sin identidad / kiosco);
 *  - y un RECORDED posterior: promueve la INDETERMINADO, conserva la DESCARTADA como contradicción.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class EvidenciaDurableDelServidorRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var inbox: RemotePaymentInbox
    private lateinit var coordinator: RemotePaymentCoordinator
    private lateinit var ledger: PaymentAttemptLedger
    private val api = mockk<TerminalAttemptApiService>()
    private lateinit var recovery: LedgerServerRecovery
    private val now = 1_700_000_000_000L
    private val venue = "venue-1"
    private val h = 60L * 60 * 1000

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        inbox = RemotePaymentInbox(db.remotePaymentRequestDao())
        coordinator = RemotePaymentCoordinator(inbox)
        ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
        recovery = LedgerServerRecovery(dao, ledger, api)
    }

    @After fun tearDown() = db.close()

    private fun solicitud(requestId: String, orderId: String?) = SocketEvent.TerminalPaymentRequest(
        requestId = requestId, amountCents = 10_000, tipCents = 0, rating = null, skipReview = true,
        orderId = orderId, processedByStaffId = "staff-1", senderDeviceName = "POS", venueId = venue,
        timestamp = "2026-09-17T12:00:00Z",
    )

    /** Reclama la solicitud como lo hace la navegación (con prueba de propiedad en este proceso). */
    private suspend fun reclamar(requestId: String = "req-1", orderId: String? = "o1"): RemotePaymentAdmission {
        inbox.receive(solicitud(requestId, orderId))
        return coordinator.prepareSocketPaymentRequest(requestId, { true }, { venue })
    }

    private fun contexto(requestId: String?, orderId: String?): String = buildString {
        append("{")
        requestId?.let { append("\"terminalPaymentRequestId\":\"$it\"") }
        if (requestId != null && orderId != null) append(",")
        orderId?.let { append("\"orderId\":\"$it\"") }
        append("}")
    }

    private suspend fun abrir(attemptId: String, requestId: String?, orderId: String?, esKiosco: Boolean = false) =
        ledger.openAttempt(attemptId, venue, "ANGELPAY", 10_000, 0, "FAST", contexto(requestId, orderId), esKiosco = esKiosco)

    /** El SDK entró y volvió sin veredicto: INDETERMINADO con motivo (del procesador, no del reloj). */
    private suspend fun incierto(attemptId: String) {
        assertThat(ledger.markAuthorizing(attemptId)).isTrue()
        ledger.markIndeterminate(attemptId, "AngelPay U101")
        assertThat(dao.getById(attemptId)!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    /** La ventana de 30 s del servidor venció sin evidencia: la solicitud quedó NOT_CHARGED y la fila DESCARTADA. */
    private suspend fun liberar(attemptId: String, requestId: String, at: Long = now): Int =
        dao.cerrarPorLiberacionDelServidor(attemptId, venue, requestId, "RELEASED_NO_EVIDENCE", "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", at)

    private suspend fun marcar(attemptId: String, at: Long = now): Int = dao.marcarEvidenciaPositivaDelServidor(attemptId, venue, at)

    private fun s6Liberada(attemptId: String, requestId: String) = Response.success(
        TerminalAttemptStatusResponse(
            success = true, attemptId = attemptId, requestId = requestId,
            attempt = TerminalAttemptResultDto(attemptId = attemptId, outcome = "NOT_RECORDED"),
            request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"}""").asJsonObject,
        ),
    )

    // ═══ D3(a) · cancelación remota y H.3 con Room real ═══

    @Test fun `D3a cancel remoto - la solicitud reclamada cuyo intento fue liberado pero tiene evidencia APPROVED contesta ACTIVE y la bandeja sigue PROCESSING`() = runTest {
        assertThat(reclamar()).isEqualTo(RemotePaymentAdmission.READY)
        assertThat(abrir("a1", "req-1", "o1")).isTrue()
        incierto("a1")
        assertThat(liberar("a1", "req-1")).isEqualTo(1)
        assertThat(dao.getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(marcar("a1")).isEqualTo(1)

        val decision = coordinator.cancelSocketPaymentRequest("req-1")

        assertWithMessage("una DESCARTADA con evidencia positiva del servidor BLOQUEA el cancel")
            .that(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(decision.finalResultJson).isNull()
        assertThat(db.remotePaymentRequestDao().contarIntentosBloqueadores("req-1")).isEqualTo(1)
        val fila = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.cancelAcceptedAt).isNull()
        assertThat(fila.finalEmittedAt).isNull()
        // La fila de la libreta no se tocó: sigue DESCARTADA (liberada) con su marca.
        val a1 = dao.getById("a1")!!
        assertThat(a1.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(a1.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
    }

    @Test fun `D3a control - sin la marca, la misma DESCARTADA liberada deja aceptar el cancel (es la marca lo que cambia la respuesta)`() = runTest {
        reclamar()
        abrir("a1", "req-1", "o1"); incierto("a1"); liberar("a1", "req-1")

        assertThat(coordinator.cancelSocketPaymentRequest("req-1").disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
    }

    @Test fun `D3a H3 - con evidencia APPROVED durable en el ultimo intento (DESCARTADO) no se escribe ningun desenlace negativo`() = runTest {
        reclamar()
        abrir("a1", "req-1", "o1"); incierto("a1"); liberar("a1", "req-1"); marcar("a1")

        val escrito = inbox.persistResult("req-1", """{"requestId":"req-1","status":"failed","outcomeEvidence":"PRE_AUTHORIZATION"}""")

        assertWithMessage("H.3: un negativo sobre una solicitud con evidencia de dinero jamás se certifica").that(escrito).isNull()
        val fila = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.finalEmittedAt).isNull()
    }

    // ═══ D3(f) · CAS de liberación ═══

    @Test fun `D3f CAS de liberacion - una respuesta atrasada sin evidencia NO descarta una INDETERMINADO con evidencia APPROVED`() = runTest {
        reclamar("req-1"); reclamar("req-2")
        abrir("a1", "req-1", "o1"); incierto("a1"); marcar("a1")
        abrir("a2", "req-2", "o2"); incierto("a2")   // control: sin marca, la MISMA liberación sí cierra

        assertThat(liberar("a1", "req-1")).isEqualTo(0)
        val a1 = dao.getById("a1")!!
        assertThat(a1.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(a1.serverOutcome).isNull()
        assertThat(a1.lastError).isEqualTo("AngelPay U101")

        // Y por el camino real: una S6 atrasada «NOT_CHARGED por ventana» sin evidencia de dinero.
        coEvery { api.getAttemptStatus(venue, "a1") } returns s6Liberada("a1", "req-1")
        assertThat(recovery.recoverOne(venue, "a1", now + 1).bandejaResueltaJson).isNull()
        assertThat(dao.getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)

        assertThat(liberar("a2", "req-2")).isEqualTo(1)   // control
        assertThat(dao.getById("a2")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
    }

    // ═══ D3(b) · contradicción, F0 y cierre/poda ═══

    @Test fun `D3b SQL_CONTRADICCION - verdadera con evidencia pendiente (INDETERMINADO y DESCARTADA liberada), falsa sin evidencia y falsa para un REGISTRADO con Payment`() = runTest {
        reclamar("req-i"); reclamar("req-l"); reclamar("req-n")
        abrir("i", "req-i", "oi"); incierto("i"); marcar("i")
        abrir("l", "req-l", "ol"); incierto("l"); liberar("l", "req-l"); marcar("l")
        abrir("n", "req-n", "on"); incierto("n")
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "r", venueId = venue, processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_REGISTRADO,
                amountCents = 10_000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = contexto("req-r", "or"),
                hostApproved = true, createdAt = now, updatedAt = now, terminalPaymentRequestId = "req-r",
                serverPaymentId = "pay-r", serverOutcome = "RECORDED", serverRecordedVia = "terminal",
                serverAmountCents = 10_000, serverTipCents = 0, serverVerdictAt = now,
                serverProcessorEvidence = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED, serverProcessorEvidenceAt = now,
            ),
        )

        assertWithMessage("INDETERMINADO + marca").that(dao.esContradiccion("i")).isTrue()
        assertWithMessage("DESCARTADA liberada + marca").that(dao.esContradiccion("l")).isTrue()
        assertWithMessage("INDETERMINADO sin marca").that(dao.esContradiccion("n")).isFalse()
        assertWithMessage("REGISTRADO con Payment: la marca no lo vuelve contradicción permanente").that(dao.esContradiccion("r")).isFalse()
    }

    @Test fun `D3b F0 - la contradiccion por evidencia se fecha desde la evidencia - una liberacion de mas de 72 h no esconde el aviso, y repetir la evidencia no renueva el plazo`() = runTest {
        reclamar("req-1")
        abrir("a1", "req-1", "o1"); incierto("a1")
        assertThat(liberar("a1", "req-1", at = now - 80 * h)).isEqualTo(1)   // liberada hace 80 h
        assertThat(marcar("a1", at = now - 1 * h)).isEqualTo(1)             // el banco aprobó (tarde) hace 1 h
        assertThat(marcar("a1", at = now)).isEqualTo(1)                      // la misma evidencia otra vez: NO renueva

        val obligaciones = db.remotePaymentRequestDao().observePendingObligations(venue).first()
        assertThat(obligaciones).hasSize(1)
        assertThat(obligaciones.single().contradiccion).isEqualTo(1)
        assertWithMessage("las 72 h cuentan desde la EVIDENCIA, no desde la liberación").that(obligaciones.single().desdeMillis).isEqualTo(now - 1 * h)
        val texto = AvisoDeCobrosPendientes.texto(obligaciones, now)
        assertWithMessage("el aviso F0 tiene que verse").that(texto).isNotNull()
        assertThat(texto).contains("evidencia de cobro")
        assertThat(texto).doesNotContain("registró dinero")
        assertThat(dao.getById("a1")!!.serverProcessorEvidenceAt).isEqualTo(now - 1 * h)
    }

    @Test fun `D3b cierre y poda conservan la contradiccion por evidencia`() = runTest {
        reclamar("req-l"); abrir("l", "req-l", "ol"); incierto("l")
        liberar("l", "req-l", at = now - 30L * 24 * h); marcar("l", at = now - 30L * 24 * h)
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "r", venueId = venue, processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_REGISTRADO,
                amountCents = 10_000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = contexto("req-r", "or"),
                hostApproved = true, createdAt = now - 30L * 24 * h, updatedAt = now - 30L * 24 * h, terminalPaymentRequestId = "req-r",
                serverProcessorEvidence = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED, serverProcessorEvidenceAt = now - 30L * 24 * h,
            ),
        )

        assertThat(dao.pruneTerminalOlderThan(venue, now)).isEqualTo(0)
        assertThat(dao.getById("l")).isNotNull()
        assertThat(dao.closeRecordedOlderThan(venue, now, now)).isEqualTo(0)
        assertThat(dao.getById("r")!!.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRADO)
    }

    // ═══ D3(c) · cercas de venta y de aparato ═══

    @Test fun `D3c cerca de venta - una DESCARTADA liberada con evidencia cerca su orderId aunque llegue con OTRA solicitud, y deja cobrar otra venta`() = runTest {
        reclamar("req-1"); abrir("a1", "req-1", "o1"); incierto("a1"); liberar("a1", "req-1"); marcar("a1")

        assertWithMessage("la misma venta (o1) con otra solicitud no entra").that(abrir("a2", "req-2", "o1")).isFalse()
        assertWithMessage("otra venta (o2) sí: el negocio sigue cobrando").that(abrir("a3", "req-3", "o2")).isTrue()
    }

    @Test fun `D3c cerca de aparato - sin identidad de venta la evidencia retiene el aparato en la reserva, en findTerminalHold y en el CAS a AUTORIZANDO`() = runTest {
        reclamar("req-1", orderId = null); abrir("a1", "req-1", null); incierto("a1"); liberar("a1", "req-1")
        // Control: liberada y SIN marca, el aparato está libre.
        assertThat(dao.findTerminalHold()).isNull()
        assertThat(abrir("ctl", "req-ctl", "o2")).isTrue()
        assertThat(ledger.markDiscardedBeforeCharge("ctl", "user_cancel")).isTrue()

        marcar("a1")

        assertWithMessage("guarda 1 de reserveTerminal").that(abrir("a2", "req-2", "o2")).isFalse()
        assertWithMessage("findTerminalHold").that(dao.findTerminalHold()?.attemptId).isEqualTo("a1")
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "p", venueId = venue, processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_PREPARANDO,
                amountCents = 1, tipCents = 0, recordingRoute = "FAST", paymentContextJson = contexto("req-p", "op"),
                createdAt = now, updatedAt = now, terminalPaymentRequestId = "req-p",
            ),
        )
        assertWithMessage("CAS a AUTORIZANDO").that(dao.casTransition("p", listOf(PaymentAttemptEntity.STATE_PREPARANDO), PaymentAttemptEntity.STATE_AUTORIZANDO, now)).isEqualTo(0)
    }

    @Test fun `D3c kiosco - con identidad de venta la evidencia aparta el aparato solo en autoservicio`() = runTest {
        reclamar("req-1"); abrir("a1", "req-1", "o1"); incierto("a1"); liberar("a1", "req-1"); marcar("a1")

        assertWithMessage("kiosco: sin cajero, cualquier obligación aparta el aparato").that(abrir("k", null, "o2", esKiosco = true)).isFalse()
        assertWithMessage("mostrador: otra venta entra").that(abrir("c", null, "o2", esKiosco = false)).isTrue()
    }

    // ═══ Fix 5 · P1-A (Codex r5): la cerca de la MISMA venta también en el CAS a AUTORIZANDO ═══

    @Test fun `P1-A carrera - B reserva la misma venta liberada, llega APPROVED durable para A, y el CAS a AUTORIZANDO de B pierde - otra venta si autoriza`() = runTest {
        reclamar("req-1"); abrir("a1", "req-1", "o1"); incierto("a1"); liberar("a1", "req-1")
        // Entre la reserva y la autorización hay una espera real (el vínculo N1): B reserva la MISMA venta cuando A todavía
        // no tiene marca (guarda 2 la deja pasar)…
        assertThat(abrir("b", "req-2", "o1")).isTrue()
        // …y en ese hueco llega la aprobación bancaria tardía de A.
        assertThat(marcar("a1")).isEqualTo(1)

        assertWithMessage("el CAS a AUTORIZANDO tiene que ver la evidencia de la MISMA venta").that(ledger.markAuthorizing("b")).isFalse()
        assertThat(dao.getById("b")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
        assertThat(ledger.markDiscardedBeforeCharge("b", "user_cancel")).isTrue()   // B no autorizó nada: se descarta limpio

        // Control: otra venta reserva y AUTORIZA con normalidad (la cerca es por venta, no por aparato).
        assertThat(abrir("c", "req-3", "o2")).isTrue()
        assertThat(ledger.markAuthorizing("c")).isTrue()
        assertThat(dao.getById("c")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
    }

    // ═══ D3(e) · RECORDED posterior (controles: la marca no cambia aplicarVeredictoDelServidor) ═══

    @Test fun `D3e RECORDED posterior - una INDETERMINADO con evidencia se promueve a REGISTRADO y deja de ser contradiccion`() = runTest {
        reclamar("req-1"); abrir("a1", "req-1", "o1"); incierto("a1"); marcar("a1")

        val r = dao.aplicarVeredictoDelServidor(VeredictoDeIntento.desdeAvisoS5(venue, "req-1", "a1", "pay-1", "webhook", 10_000, 0), now)

        assertThat(r.transiciono).isTrue()
        val a1 = dao.getById("a1")!!
        assertThat(a1.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRADO)
        assertThat(a1.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)   // la marca nunca se borra…
        assertThat(dao.esContradiccion("a1")).isFalse()                                                                // …pero ya no es contradicción
        assertThat(coordinator.cancelSocketPaymentRequest("req-1").disposition).isEqualTo(RemotePaymentCancelDisposition.ALREADY_RESOLVED)
    }

    @Test fun `D3e RECORDED posterior - una DESCARTADA liberada con evidencia se conserva DESCARTADA como contradiccion`() = runTest {
        reclamar("req-1"); abrir("a1", "req-1", "o1"); incierto("a1"); liberar("a1", "req-1"); marcar("a1")

        dao.aplicarVeredictoDelServidor(VeredictoDeIntento.desdeAvisoS5(venue, "req-1", "a1", "pay-1", "webhook", 10_000, 0), now + 5_000)

        val a1 = dao.getById("a1")!!
        assertThat(a1.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(a1.serverOutcome).isEqualTo("RECORDED")
        assertThat(dao.esContradiccion("a1")).isTrue()
        assertThat(abrir("a2", "req-2", "o1")).isFalse()   // la venta sigue cercada
    }
}
