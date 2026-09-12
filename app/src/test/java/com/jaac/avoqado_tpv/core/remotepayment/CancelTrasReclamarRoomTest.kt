package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C.5 (N3) sobre Room REAL: cancelar desde el POS un cobro que esta terminal YA reclamó.
 *
 * Lo que se fija aquí es una sola frase: **se acepta el cancel sólo cuando ninguna ejecución capaz de
 * autorizar puede estar en curso NI empezar**. El «ni empezar» es la CERCA, y por eso cada caso aceptado
 * comprueba además que el siguiente intento de esa solicitud ya no puede reservar la terminal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CancelTrasReclamarRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var inbox: RemotePaymentInbox
    private lateinit var coordinator: RemotePaymentCoordinator
    private lateinit var ledger: PaymentAttemptLedger

    private val solicitud = SocketEvent.TerminalPaymentRequest(
        requestId = "req-1", amountCents = 10_000, tipCents = 0, rating = null, skipReview = true,
        orderId = null, processedByStaffId = "staff-1", senderDeviceName = "POS", venueId = "venue-1",
        timestamp = "2026-09-11T12:00:00Z",
    )
    private val contexto = """{"terminalPaymentRequestId":"req-1"}"""

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        inbox = RemotePaymentInbox(db.remotePaymentRequestDao())
        coordinator = RemotePaymentCoordinator(inbox)
        ledger = PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true))
    }

    @After fun tearDown() = db.close()

    /** Reclama la solicitud como lo hace la navegación (con prueba de propiedad en este proceso). */
    private suspend fun reclamar(): RemotePaymentAdmission {
        inbox.receive(solicitud)
        return coordinator.prepareSocketPaymentRequest("req-1", { true }, { "venue-1" })
    }

    private suspend fun abrirIntento(attemptId: String) =
        ledger.openAttempt(attemptId, "venue-1", "BLUMON", 10_000, 0, "FAST", contexto)

    @Test fun `un intento en PREPARANDO se descarta y el cancel se acepta con la cerca puesta`() = runTest {
        assertThat(reclamar()).isEqualTo(RemotePaymentAdmission.READY)
        assertThat(abrirIntento("a1")).isTrue()

        val decision = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        assertThat(JSONObject(decision.finalResultJson!!).optString("status")).isEqualTo("cancelled")
        assertThat(JSONObject(decision.finalResultJson!!).optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
        val intento = db.paymentAttemptDao().getById("a1")!!
        assertThat(intento.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(intento.lastError).isEqualTo("remote_cancel")
        val fila = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(fila.cancelAcceptedAt).isNotNull()
        assertThat(fila.finalEmittedAt).isNotNull()

        // Ninguna barrera del intento descartado puede ganar ya, y no se puede abrir otro.
        assertThat(ledger.markKernelEntered("a1")).isFalse()
        assertThat(ledger.markAuthorizing("a1")).isFalse()
        assertThat(abrirIntento("a2")).isFalse()
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CANCELADA_POR_EL_POS)
    }

    @Test fun `con el kernel ya activo el cancel contesta ACTIVE y no toca nada`() = runTest {
        reclamar()
        abrirIntento("a1")
        assertThat(ledger.markKernelEntered("a1")).isTrue()

        val decision = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(decision.finalResultJson).isNull()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_KERNEL_ACTIVO)
        val fila = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.cancelAcceptedAt).isNull()
    }

    @Test fun `reclamada sin fila de libreta - con prueba de propiedad se acepta`() = runTest {
        assertThat(reclamar()).isEqualTo(RemotePaymentAdmission.READY)

        val decision = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        assertThat(abrirIntento("a1")).isFalse()
    }

    @Test fun `reclamada sin fila de libreta - sin prueba de propiedad contesta ACTIVE`() = runTest {
        inbox.receive(solicitud)
        assertThat(coordinator.claimSocketPaymentRequest("req-1")).isTrue() // reclamo sin dueño (proceso muerto)

        val decision = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(db.remotePaymentRequestDao().getById("req-1")!!.status)
            .isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
    }

    @Test fun `entre intentos - tras un rechazo del host el cancel se acepta y la cerca impide el reintento`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        // El banco rechaza: la pantalla sigue ofreciendo «Reintentar» y no hay fila viva.
        assertThat(ledger.markHostResponded("a1", approved = false, operationId = null, referenceNumber = null, authCode = null)).isTrue()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        // Sin la cerca, este reintento cobraría DESPUÉS de decirle «cancelado» al POS.
        assertThat(abrirIntento("a2-antes-del-cancel")).isTrue()
        ledger.markDiscardedBeforeCharge("a2-antes-del-cancel", "user_cancel")

        val decision = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        assertThat(abrirIntento("a3")).isFalse()
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CANCELADA_POR_EL_POS)
    }

    @Test fun `con efectivo o cripto ya arrancado el cancel contesta ACTIVE`() = runTest {
        reclamar()
        assertThat(ledger.iniciarEjecucionNoTarjeta("req-1")).isEqualTo(CercaDeSolicitud.LIBRE)

        val decision = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(db.remotePaymentRequestDao().getById("req-1")!!.status)
            .isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
    }

    @Test fun `un cancel aceptado impide registrar despues un efectivo de esa solicitud`() = runTest {
        reclamar()
        assertThat(coordinator.cancelSocketPaymentRequest("req-1").disposition)
            .isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)

        assertThat(ledger.iniciarEjecucionNoTarjeta("req-1")).isEqualTo(CercaDeSolicitud.CANCELADA_POR_EL_POS)
    }

    @Test fun `una fila HEREDADA correlacionada bloquea el cancel - su estado no prueba nada`() = runTest {
        reclamar()
        db.paymentAttemptDao().insert(
            PaymentAttemptEntity(
                attemptId = "heredada", venueId = "venue-1", processor = "BLUMON",
                state = PaymentAttemptEntity.STATE_DESCARTADA, amountCents = 10_000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = contexto, createdAt = 1, updatedAt = 1,
                legacyShadow = true,
            ),
        )

        val decision = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
    }

    // ═══ CERCA: lápida y desenlace final también impiden un intento nuevo ═══

    @Test fun `la lapida de la sonda impide cualquier intento de esa solicitud`() = runTest {
        assertThat(inbox.probe("req-lapida", "venue-1").disposition)
            .isEqualTo(RemotePaymentProbeDisposition.NOT_FOUND)

        val conLapida = """{"terminalPaymentRequestId":"req-lapida"}"""
        assertThat(ledger.openAttempt("a1", "venue-1", "BLUMON", 10_000, 0, "FAST", conLapida)).isFalse()
        assertThat(ledger.cercaDeSolicitud("req-lapida")).isEqualTo(CercaDeSolicitud.CERRADA)
    }

    @Test fun `un desenlace final ya emitido impide otro intento, y un rechazo SIN final no`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        ledger.markHostResponded("a1", approved = false, operationId = null, referenceNumber = null, authCode = null)

        // Mientras no salga el final, el reintento en sesión es legítimo (H.3).
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.LIBRE)
        assertThat(abrirIntento("a2")).isTrue()
        ledger.markDiscardedBeforeCharge("a2", "user_cancel")

        // Al salir, el final se escribe… y desde ahí la solicitud ya no se ejecuta.
        val escrito = inbox.persistResult("req-1", """{"requestId":"req-1","status":"cancelled"}""")
        assertThat(escrito).isNotNull()
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CERRADA)
        assertThat(abrirIntento("a3")).isFalse()
    }

    // ═══ Evidencia POR INTENTO: sale de la libreta, nunca de la pantalla ═══

    @Test fun `el ultimo intento rechazado por el host sale como failed mas PROCESSOR_DECLINED aunque pidan cancelled`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        ledger.markHostResponded("a1", approved = false, operationId = null, referenceNumber = null, authCode = null)

        val escrito = inbox.persistResult("req-1", """{"requestId":"req-1","status":"cancelled","outcomeEvidence":"PRE_AUTHORIZATION"}""")

        val json = JSONObject(escrito!!)
        assertThat(json.optString("status")).isEqualTo("failed")
        assertThat(json.optString("outcomeEvidence")).isEqualTo("PROCESSOR_DECLINED")
    }

    @Test fun `sin ningun intento el desenlace conserva su status con PRE_AUTHORIZATION`() = runTest {
        reclamar()

        val escrito = inbox.persistResult("req-1", """{"requestId":"req-1","status":"cancelled"}""")

        val json = JSONObject(escrito!!)
        assertThat(json.optString("status")).isEqualTo("cancelled")
        assertThat(json.optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
    }

    @Test fun `un rechazo viejo NUNCA certifica un intento incierto - no se escribe desenlace`() = runTest {
        reclamar()
        // A1: rechazo del banco (evidencia real). A2: contactless que se queda sin veredicto.
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        ledger.markHostResponded("a1", approved = false, operationId = null, referenceNumber = null, authCode = null)
        abrirIntento("a2")
        ledger.markKernelEntered("a2")
        ledger.markIndeterminate("a2", "TIMEOUT")

        val escrito = inbox.persistResult(
            "req-1",
            """{"requestId":"req-1","status":"failed","outcomeEvidence":"PROCESSOR_DECLINED"}""",
        )

        assertThat(escrito).isNull()
        val fila = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.finalEmittedAt).isNull()
    }

    @Test fun `un intento en PREPARANDO se descarta al escribir el final y la evidencia queda pre-autorizacion`() = runTest {
        reclamar()
        abrirIntento("a1")

        val escrito = inbox.persistResult("req-1", """{"requestId":"req-1","status":"failed"}""")

        assertThat(JSONObject(escrito!!).optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(ledger.markAuthorizing("a1")).isFalse()
    }

    // ═══ Libreta SHADOW heredada (I.6): no reserva, sigue contada, no se libera por antigüedad ═══

    @Test fun `una fila heredada no reserva la terminal pero sigue siendo obligacion`() = runTest {
        val heredada = PaymentAttemptEntity(
            attemptId = "heredada", venueId = "venue-1", processor = "BLUMON",
            state = PaymentAttemptEntity.STATE_AUTORIZANDO, amountCents = 10_000, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = "{}", createdAt = 1, updatedAt = 1,
            legacyShadow = true,
        )
        db.paymentAttemptDao().insert(heredada)

        // En 2.9.2 esa fila no reservaba nada: la caja sigue pudiendo cobrar.
        assertThat(ledger.openAttempt("nueva", "venue-1", "BLUMON", 100, 0, "FAST", "{}")).isTrue()
        assertThat(ledger.markAuthorizing("nueva")).isTrue()
        assertThat(db.paymentAttemptDao().findTerminalHold()?.attemptId).isEqualTo("nueva")
        assertThat(db.paymentAttemptDao().findUnresolvedCharge()?.attemptId).isEqualTo("nueva")
        // …pero sigue visible como obligación que conciliar.
        assertThat(db.paymentAttemptDao().observeUnresolvedCount("venue-1").first()).isEqualTo(2)
    }

    @Test fun `el barrido NUNCA libera por antiguedad una fila heredada en PREPARANDO`() = runTest {
        val dao = db.paymentAttemptDao()
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "heredada", venueId = "venue-1", processor = "BLUMON",
                state = PaymentAttemptEntity.STATE_PREPARANDO, amountCents = 10_000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = "{}", createdAt = 1, updatedAt = 1,
                legacyShadow = true,
            ),
        )
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "de-este-apk", venueId = "venue-1", processor = "BLUMON",
                state = PaymentAttemptEntity.STATE_PREPARANDO, amountCents = 10_000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = "{}", createdAt = 1, updatedAt = 1,
            ),
        )

        assertThat(dao.discardStalePreparing("venue-1", olderThan = 1_000, now = 2_000)).isEqualTo(1)

        assertThat(dao.getById("heredada")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
        assertThat(dao.getById("de-este-apk")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
    }
}
