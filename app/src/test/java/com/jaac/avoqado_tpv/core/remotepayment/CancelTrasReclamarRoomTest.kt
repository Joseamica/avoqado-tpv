package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.data.ledger.MarcaDelBuild
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.ledger.PilaDePantallas
import com.jaac.avoqado_tpv.features.payment.data.ledger.TareaDeLaApp
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.presentation.CobroRemotoDelPos
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import io.mockk.every
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
    /** N1: las banderas que publican las pantallas de cobro de ESTE proceso (la bandeja del `setUp` las lee). */
    private val pantalla = PaymentStateHolder()

    private val solicitud = SocketEvent.TerminalPaymentRequest(
        requestId = "req-1", amountCents = 10_000, tipCents = 0, rating = null, skipReview = true,
        orderId = null, processedByStaffId = "staff-1", senderDeviceName = "POS", venueId = "venue-1",
        timestamp = "2026-09-11T12:00:00Z",
    )
    private val contexto = """{"terminalPaymentRequestId":"req-1"}"""

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        // B1 (fix 1, M2): la marca se FIJA (PAX/Blumon, SDK embebido) — `src/test` lo comparten todos los sabores, y en el
        // carril Nexgo la marca real con ajustes relajados daría app-a-app. Las pruebas de Nexgo fijan la suya.
        ledger = PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true), UNA_SOLA_PANTALLA).apply { marcaDelBuild = MARCA_PAX }
        // B2: la bandeja se arma como la arma Hilt (constructor `@Inject`): su pregunta «¿cesó?» es la libreta REAL.
        inbox = RemotePaymentInbox(db.remotePaymentRequestDao(), com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), ledger, pantalla)
        coordinator = RemotePaymentCoordinator(inbox)
    }

    private companion object {
        val MARCA_PAX = MarcaDelBuild(procesador = "BLUMON", sdkAngelPayCompilado = false, respaldoAppAAppCompilado = false)
        /** Codex TPV r1 #1: la app con UNA pantalla, la nuestra — la pila con la que se certifica como antes. */
        val UNA_SOLA_PANTALLA = PilaDePantallas { listOf(TareaDeLaApp(pantallas = 1, arribaLaPrincipal = true)) }
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

    /**
     * B2 (T3, 22-oct): REEMPLAZA a «reclamada sin fila de libreta - sin prueba de propiedad contesta ACTIVE». Aquella decía
     * «tras un reinicio nadie sabe si algo quedó a medias»; la libreta es write-ahead OBLIGATORIA (`isEnabled()` siempre
     * true): CERO intentos de la solicitud prueba que nadie tocó el SDK. Es el mismo caso que la sonda ya resolvía (21-sep):
     * el cancel lo resuelve por el MISMO camino (`resolverDesenlaceNegativo`), con la cerca puesta.
     */
    @Test fun `P2 el cancel tras reiniciar sin intentos contesta ACCEPTED`() = runTest {
        assertThat(reclamar()).isEqualTo(RemotePaymentAdmission.READY)
        val nuevo = procesoNuevo()

        val decision = nuevo.coordinador.cancelSocketPaymentRequest("req-1")

        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        assertThat(JSONObject(decision.finalResultJson!!).optString("status")).isEqualTo("cancelled")
        assertThat(JSONObject(decision.finalResultJson!!).optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
        val fila = bandeja()
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(fila.finalResultJson).isEqualTo(decision.finalResultJson)
        assertThat(fila.finalEmittedAt).isNotNull()
        // Resolverla no es cerrarla para ejecución: ese certificado es para lo que NO se puede resolver.
        assertThat(fila.executionClosedAt).isNull()
        // La cerca rige: ningún intento nuevo de esa solicitud, y una reentrega reproduce el resultado.
        assertThat(nuevo.libreta.openAttempt("a1", "venue-1", "BLUMON", 10_000, 0, "FAST", contexto)).isFalse()
        assertThat(nuevo.libreta.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CERRADA)
        assertThat(nuevo.bandeja.receive(solicitud)).isEqualTo(RemotePaymentReceiveDecision.ReplayResult(decision.finalResultJson!!))
        // Un cancel repetido y la sonda contestan lo mismo, ya resuelto.
        val otraVez = nuevo.coordinador.cancelSocketPaymentRequest("req-1")
        assertThat(otraVez.disposition).isEqualTo(RemotePaymentCancelDisposition.ALREADY_RESOLVED)
        assertThat(otraVez.finalResultJson).isEqualTo(decision.finalResultJson)
        assertThat(nuevo.coordinador.probeSocketPaymentRequest("req-1", "venue-1").disposition)
            .isEqualTo(RemotePaymentProbeDisposition.RESOLVED)
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
        assertThat(ledger.retencionDelAparato()?.attemptId).isEqualTo("nueva")
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

    // ═══ B1 (21-oct, «ninguna duda de cobro sin salida»): la solicitud «cerrada para ejecución» ═══
    //
    // Un intento de la solicitud PUEDE SEGUIR VIVO sólo si es de ESTE proceso (`process_token`) y su SDK no terminó:
    // PREPARANDO/KERNEL_ACTIVO/AUTORIZANDO, o HOST_RESPONDIO/AUTORIZADO/REGISTRO_FALLIDO/INDETERMINADO sin `sdk_returned_at`.
    // Todo intento de OTRO proceso cesó: el SDK embebido murió con su proceso. El dinero NO entra en esta pregunta: lo decide
    // el SERVIDOR. Cerrar no resuelve la bandeja (sigue PROCESSING): sólo impide que la solicitud vuelva a ejecutarse.

    /** Un intento de la solicitud `req-1` que dejó OTRO proceso (la app se reinició: su token ya no es el de esta libreta). */
    private fun intentoDeOtroProceso(
        attemptId: String,
        estado: String,
        hostApproved: Boolean? = null,
        evidencia: String? = null,
        processor: String = "BLUMON",
        token: String? = "proceso-muerto",
    ) = PaymentAttemptEntity(
        attemptId = attemptId, venueId = "venue-1", processor = processor, state = estado,
        amountCents = 10_000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = contexto,
        createdAt = 1, updatedAt = 1, terminalPaymentRequestId = "req-1", processToken = token,
        hostApproved = hostApproved, serverProcessorEvidence = evidencia,
    )

    private suspend fun bandeja() = db.remotePaymentRequestDao().getById("req-1")!!

    @Test fun `P1 con el unico intento en INDETERMINADO de otro proceso se cierra y descarta el PREPARANDO de otro proceso`() = runTest {
        reclamar()
        val dao = db.paymentAttemptDao()
        dao.insert(intentoDeOtroProceso("a-duda", PaymentAttemptEntity.STATE_INDETERMINADO))
        dao.insert(intentoDeOtroProceso("a-reserva", PaymentAttemptEntity.STATE_PREPARANDO))

        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isTrue()

        val fila = bandeja()
        assertThat(fila.executionClosedAt).isNotNull()
        assertThat(db.remotePaymentRequestDao().cerradaParaEjecucionEn("req-1")).isEqualTo(fila.executionClosedAt)
        // Cerrar no es resolver: la bandeja sigue PROCESSING y sin final (el dinero tardío la resuelve por su camino).
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.finalEmittedAt).isNull()
        assertThat(fila.finalResultJson).isNull()
        // La duda queda intacta (es dinero de desenlace desconocido); la reserva del proceso muerto ya no puede avanzar.
        assertThat(dao.getById("a-duda")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(dao.getById("a-reserva")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(ledger.markKernelEntered("a-reserva")).isFalse()
        assertThat(ledger.markAuthorizing("a-reserva")).isFalse()

        // La segunda vez contesta lo mismo SIN volver a escribir.
        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isTrue()
        assertThat(bandeja().executionClosedAt).isEqualTo(fila.executionClosedAt)
    }

    @Test fun `P1 con un intento en AUTORIZANDO de OTRO proceso todavia sin cuarentena SI se cierra (ese proceso murio)`() = runTest {
        reclamar()
        db.paymentAttemptDao().insert(intentoDeOtroProceso("a-vuelo", PaymentAttemptEntity.STATE_AUTORIZANDO))

        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isTrue()

        assertThat(bandeja().executionClosedAt).isNotNull()
        // No se toca la fila: su cuarentena (y la pregunta del dinero) siguen su camino.
        assertThat(db.paymentAttemptDao().getById("a-vuelo")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
    }

    @Test fun `P1 con host_approved o evidencia APPROVED de otro proceso SI se cierra - el dinero lo decide el servidor`() = runTest {
        reclamar()
        val dao = db.paymentAttemptDao()
        dao.insert(intentoDeOtroProceso("a-aprobado", PaymentAttemptEntity.STATE_HOST_RESPONDIO, hostApproved = true))
        dao.insert(
            intentoDeOtroProceso(
                "a-evidencia", PaymentAttemptEntity.STATE_INDETERMINADO,
                evidencia = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED,
            ),
        )

        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isTrue()

        assertThat(bandeja().executionClosedAt).isNotNull()
        // El dinero sigue tal cual: cerrar para ejecución nunca afirma «no se cobró».
        assertThat(dao.getById("a-aprobado")!!.state).isEqualTo(PaymentAttemptEntity.STATE_HOST_RESPONDIO)
        assertThat(dao.getById("a-aprobado")!!.hostApproved).isTrue()
        assertThat(dao.getById("a-evidencia")!!.serverProcessorEvidence)
            .isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        assertThat(db.remotePaymentRequestDao().contarIntentosBloqueadores("req-1")).isEqualTo(2)
    }

    @Test fun `P1 mismo proceso - INDETERMINADO del catch de la corrutina (sin sdk_returned_at) NO se cierra - proceso nuevo sobre la misma base SI`() = runTest {
        reclamar()
        assertThat(abrirIntento("a1")).isTrue()
        assertThat(ledger.markAuthorizing("a1")).isTrue()
        // Lo que escribe el `catch` de la corrutina de la PAX: una excepción NO prueba que la llamada nativa terminó.
        ledger.markIndeterminate("a1", "IOException")
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)

        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isFalse()
        assertThat(bandeja().executionClosedAt).isNull()

        // La app se reinició: otra libreta (otro token) sobre la MISMA base. El SDK murió con el proceso anterior.
        val procesoNuevo = PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true), UNA_SOLA_PANTALLA).apply { marcaDelBuild = MARCA_PAX }
        assertThat(procesoNuevo.cerrarSolicitudSiCeso("req-1")).isTrue()
        assertThat(bandeja().executionClosedAt).isNotNull()
    }

    @Test fun `P1 mismo proceso - PREPARANDO (esperando el vinculo) NO se cierra y el intento puede continuar`() = runTest {
        reclamar()
        assertThat(abrirIntento("a1")).isTrue()

        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isFalse()

        assertThat(bandeja().executionClosedAt).isNull()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
        // El vínculo llegó: el cobro sigue como si la sonda no hubiera pasado.
        assertThat(ledger.markAuthorizing("a1")).isTrue()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
    }

    @Test fun `P1 mismo proceso - el SDK regreso sin veredicto (sdk_returned_at) SI se cierra`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        ledger.markIndeterminate("a1", "U101")
        // Sin la marca de regreso, una duda de ESTE proceso puede tener todavía el SDK dentro.
        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isFalse()

        ledger.marcarSdkRegreso("a1")

        assertThat(db.paymentAttemptDao().getById("a1")!!.sdkReturnedAt).isNotNull()
        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isTrue()
        assertThat(bandeja().executionClosedAt).isNotNull()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    @Test fun `P1 mismo proceso PAX - HOST_RESPONDIO con el cierre EMV pendiente (sin sdk_returned_at) NO se cierra`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        // El host aprobó; `CompleteEmvTrans` todavía no regresa: el SDK sigue dentro.
        assertThat(ledger.markHostResponded("a1", approved = true, operationId = "op-1", referenceNumber = "ref-1", authCode = "123456")).isTrue()

        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isFalse()

        assertThat(bandeja().executionClosedAt).isNull()
    }

    @Test fun `P1 mismo proceso PAX - CompleteEmvTrans regreso con error y el registro sigue pendiente (REGISTRO_FALLIDO con sdk_returned_at) SI se cierra`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        ledger.markHostResponded("a1", approved = true, operationId = "op-1", referenceNumber = "ref-1", authCode = "123456")
        // `CompleteEmvTrans` regresó (aquí, con error): termina TODA la interacción con el SDK.
        ledger.marcarSdkRegreso("a1")
        ledger.markRecordFailed("a1", "sin red")
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)

        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isTrue()

        assertThat(bandeja().executionClosedAt).isNotNull()
        // El registro pendiente es dinero: la fila no se toca.
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)
    }

    // ── Codex TPV r1 #2 (22-oct): el candado del APARATO suelta lo que el SDK ya terminó; el dinero sigue cercando SU venta ──
    //
    // Tras el certificado, el SERVIDOR suelta su ranura. Si la libreta siguiera contando como «cobro corriendo» un intento cuyo SDK
    // ya regresó, la terminal rechazaría otras ventas que el servidor ya deja pasar. Regla del founder (25-sep): sólo aparta el
    // aparato un cobro CORRIENDO; el dinero conocido cerca SU venta.

    private val contextoConVenta = """{"terminalPaymentRequestId":"req-1","orderId":"orden-1"}"""

    private suspend fun abrirIntentoConVenta(attemptId: String) =
        ledger.openAttempt(attemptId, "venue-1", "BLUMON", 10_000, 0, "FAST", contextoConVenta)

    /** OTRA venta (otra solicitud del POS, otra orden): ¿reserva el aparato y entra al lector? */
    private suspend fun otraVentaEntraAlLector(attemptId: String): Boolean =
        ledger.openAttempt(attemptId, "venue-1", "BLUMON", 5_000, 0, "FAST", """{"terminalPaymentRequestId":"req-2","orderId":"orden-2"}""") &&
            ledger.markKernelEntered(attemptId)

    /** La MISMA venta (otra solicitud del POS sobre la orden original): ¿reserva? */
    private suspend fun laMismaVentaReserva(attemptId: String): Boolean =
        ledger.openAttempt(attemptId, "venue-1", "BLUMON", 10_000, 0, "FAST", """{"terminalPaymentRequestId":"req-3","orderId":"orden-1"}""")

    /** Tras el certificado: la original sigue cerrada con su dinero, su venta cercada, y otra venta sí entra al lector. */
    private suspend fun otraVentaEntraYLaOriginalSigueCercada(estadoOriginal: String) {
        // El dinero aprobado cerca SU venta… (primero: con el aparato libre, el rechazo sólo puede ser de la venta)
        assertThat(laMismaVentaReserva("a-misma-venta")).isFalse()
        assertThat(db.paymentAttemptDao().getById("a-misma-venta")).isNull()
        // …pero no el aparato: otra venta reserva y entra al lector.
        assertThat(otraVentaEntraAlLector("b1")).isTrue()
        assertThat(db.paymentAttemptDao().getById("b1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_KERNEL_ACTIVO)
        // La original sigue cerrada para ejecución, con su dinero intacto.
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CERRADA)
        assertThat(bandeja().executionClosedAt).isNotNull()
        val original = db.paymentAttemptDao().getById("a1")!!
        assertThat(original.state).isEqualTo(estadoOriginal)
        assertThat(original.hostApproved).isTrue()
    }

    @Test fun `P1 PAX con CompleteEmvTrans en error tras el certificado otra solicitud SI reserva y la original sigue cerrada para ejecucion`() = runTest {
        reclamar()
        assertThat(abrirIntentoConVenta("a1")).isTrue()
        assertThat(ledger.markAuthorizing("a1")).isTrue()
        assertThat(ledger.markHostResponded("a1", approved = true, operationId = "op-1", referenceNumber = "ref-1", authCode = "123456")).isTrue()
        // `CompleteEmvTrans` regresó con error: el SDK terminó y la fila se queda en HOST_RESPONDIO con el dinero aprobado.
        ledger.marcarSdkRegreso("a1")
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_HOST_RESPONDIO)
        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isTrue()

        otraVentaEntraYLaOriginalSigueCercada(PaymentAttemptEntity.STATE_HOST_RESPONDIO)
    }

    @Test fun `P1 PAX con REGISTRO_FALLIDO y sdk_returned_at tras el certificado otra solicitud SI reserva y la original sigue cerrada para ejecucion`() = runTest {
        reclamar()
        assertThat(abrirIntentoConVenta("a1")).isTrue()
        assertThat(ledger.markAuthorizing("a1")).isTrue()
        assertThat(ledger.markHostResponded("a1", approved = true, operationId = "op-1", referenceNumber = "ref-1", authCode = "123456")).isTrue()
        ledger.marcarSdkRegreso("a1")
        ledger.markRecordFailed("a1", "sin red") // el registro sigue pendiente: es dinero, no ejecución
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)
        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isTrue()

        otraVentaEntraYLaOriginalSigueCercada(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)
    }

    @Test fun `P1 control - HOST_RESPONDIO y REGISTRO_FALLIDO de este proceso SIN sdk_returned_at siguen apartando el aparato`() = runTest {
        reclamar()
        assertThat(abrirIntentoConVenta("a1")).isTrue()
        assertThat(ledger.markAuthorizing("a1")).isTrue()
        assertThat(ledger.markHostResponded("a1", approved = true, operationId = "op-1", referenceNumber = "ref-1", authCode = "123456")).isTrue()

        // `CompleteEmvTrans` todavía no regresa: el SDK sigue dentro y el lector es uno.
        assertThat(otraVentaEntraAlLector("b1")).isFalse()
        assertThat(db.paymentAttemptDao().getById("b1")).isNull()

        // Registro fallido SIN la marca de regreso (una fila de antes de v45, o el regreso que no se pudo escribir): igual.
        ledger.markRecordFailed("a1", "sin red")
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)
        assertThat(otraVentaEntraAlLector("b2")).isFalse()
        assertThat(db.paymentAttemptDao().getById("b2")).isNull()
    }

    // ── Codex TPV r1, ronda 2 (N1, 22-oct): «libre» en el SERVIDOR tiene que significar que la terminal SÍ aceptaría otra venta ──
    //
    // En la PAX, `CompleteEmvTrans` falla tras la aprobación del banco: la pantalla queda en «Cobrado. Sincronización pendiente»
    // con `chargeAttemptActive` arriba, y la navegación rechaza una venta NUEVA del POS. Mientras sea así, la sonda y el cancel no
    // certifican el cese: contestan ACTIVE con la ejecución viva y no escriben nada. Se decide con la MISMA regla de la admisión
    // (`CobroRemotoDelPos.motivoParaNoIniciar`, leyendo las banderas de la pantalla).

    /** El caso de Codex #2 con la pantalla que queda: cobro aprobado, `CompleteEmvTrans` regresó con error, bandera arriba. */
    private suspend fun cobradoConSincronizacionPendiente() {
        reclamar()
        assertThat(abrirIntentoConVenta("a1")).isTrue()
        assertThat(ledger.markAuthorizing("a1")).isTrue()
        assertThat(ledger.markHostResponded("a1", approved = true, operationId = "op-1", referenceNumber = "ref-1", authCode = "123456")).isTrue()
        ledger.marcarSdkRegreso("a1")
        // Lo que deja la PAX: `showUnresolvedAuthorization()` sin soltar la bandera de cobro activo.
        pantalla.setChargeAttemptActive(true)
        // La terminal rechazaría una venta nueva del POS (la MISMA regla que usa la navegación al admitirla).
        assertThat(CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro = true, estado = pantalla))
            .isEqualTo(CobroRemotoDelPos.NO_INICIADO_POR_COBRO_EN_CURSO)
    }

    /** Nada escrito: ni el cierre para ejecución ni un final; el cobro original intacto con su dinero. */
    private suspend fun nadaEscrito() {
        val fila = bandeja()
        assertThat(fila.executionClosedAt).isNull()
        assertThat(db.remotePaymentRequestDao().cerradaParaEjecucionEn("req-1")).isNull()
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.cancelAcceptedAt).isNull()
        assertThat(fila.finalEmittedAt).isNull()
        val intento = db.paymentAttemptDao().getById("a1")!!
        assertThat(intento.state).isEqualTo(PaymentAttemptEntity.STATE_HOST_RESPONDIO)
        assertThat(intento.hostApproved).isTrue()
    }

    @Test fun `P1 con la pantalla todavia en Cobrado sincronizacion pendiente la sonda dice ejecucion viva y no cierra`() = runTest {
        cobradoConSincronizacionPendiente()

        val sonda = coordinator.probeSocketPaymentRequest("req-1", "venue-1")

        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(sonda.executionAlive).isTrue()
        assertThat(sonda.closedForExecution).isFalse()
        assertThat(sonda.finalResultJson).isNull()
        nadaEscrito()
    }

    @Test fun `P1 con la pantalla todavia en Cobrado sincronizacion pendiente el cancel dice ejecucion viva y no cierra`() = runTest {
        cobradoConSincronizacionPendiente()

        val cancel = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(cancel.executionAlive).isTrue()
        assertThat(cancel.closedForExecution).isFalse()
        assertThat(cancel.finalResultJson).isNull()
        nadaEscrito()
    }

    @Test fun `P1 control - con las banderas apagadas (tras Cancelar) certifica como antes`() = runTest {
        cobradoConSincronizacionPendiente()
        // «Cancelar»: `resetPayment()` + `navigateBack()`; al cerrarse la pantalla, `onCleared` apaga las banderas.
        pantalla.setChargeAttemptActive(false)
        pantalla.setCharging(false)
        assertThat(CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro = true, estado = pantalla)).isNull()

        val sonda = coordinator.probeSocketPaymentRequest("req-1", "venue-1")
        val cancel = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(sonda.executionAlive).isFalse()
        assertThat(sonda.closedForExecution).isTrue()
        assertThat(cancel.executionAlive).isFalse()
        assertThat(cancel.closedForExecution).isTrue()
        assertThat(bandeja().executionClosedAt).isNotNull()
        // Y ahora sí: otra venta reserva y entra al lector; la original sigue cerrada con su dinero.
        otraVentaEntraYLaOriginalSigueCercada(PaymentAttemptEntity.STATE_HOST_RESPONDIO)
    }

    @Test fun `P1 cerrada para ejecucion reserveTerminal rechaza un intento nuevo de esa solicitud`() = runTest {
        reclamar()
        db.paymentAttemptDao().insert(intentoDeOtroProceso("a-duda", PaymentAttemptEntity.STATE_INDETERMINADO))
        assertThat(ledger.cerrarSolicitudSiCeso("req-1")).isTrue()

        // Una reentrega o un reintento de ESA solicitud ya no llega al SDK…
        assertThat(abrirIntento("a-nuevo")).isFalse()
        assertThat(db.paymentAttemptDao().getById("a-nuevo")).isNull()
        assertThat(
            db.paymentAttemptDao().reserveTerminal(
                attemptId = "a-directo", venueId = "venue-1", processor = "BLUMON", kind = "SALE",
                amountCents = 10_000, tipCents = 0, recordingRoute = "FAST", contextJson = contexto,
                orderJsonFragment = null, now = 2_000, terminalPaymentRequestId = "req-1", processToken = "otro",
            ),
        ).isEqualTo(-1L)
        // …ni a efectivo/cripto, y la pantalla lo traduce como cerrada (no como «no se pudo guardar»).
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CERRADA)
        assertThat(db.paymentAttemptDao().iniciarEjecucionNoTarjeta("req-1", 3_000)).isEqualTo(0)
        assertThat(ledger.iniciarEjecucionNoTarjeta("req-1")).isEqualTo(CercaDeSolicitud.CERRADA)
        assertThat(bandeja().executionStartedAt).isNull()
    }

    // ── Pre-B (Codex r3 #9): sólo el SDK EMBEBIDO certifica el cese ──

    @Test fun `P1 en app-a-app (angelPaySdkEnabled=false) nunca se cierra aunque el intento sea de otro proceso`() = runTest {
        reclamar()
        val dao = db.paymentAttemptDao()
        dao.insert(intentoDeOtroProceso("a-duda", PaymentAttemptEntity.STATE_INDETERMINADO, processor = "ANGELPAY"))
        dao.insert(intentoDeOtroProceso("a-reserva", PaymentAttemptEntity.STATE_PREPARANDO, processor = "ANGELPAY"))
        val ajustes = mockk<TpvSettingsRepository>()
        every { ajustes.getCurrentSettings() } returns TpvSettings(angelPaySdkEnabled = false)
        val nexgo = PaymentAttemptLedger(dao, ajustes, UNA_SOLA_PANTALLA).apply {
            marcaDelBuild = MarcaDelBuild(procesador = "ANGELPAY", sdkAngelPayCompilado = true, respaldoAppAAppCompilado = false)
        }

        // La app EXTERNA de AngelPay cobra en SU proceso: que el nuestro haya muerto no prueba que ella terminó.
        assertThat(nexgo.cerrarSolicitudSiCeso("req-1")).isFalse()

        // Nada escrito: ni el cierre ni el descarte de la reserva.
        assertThat(bandeja().executionClosedAt).isNull()
        assertThat(dao.getById("a-reserva")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)

        // Contraste: la MISMA terminal con el SDK embebido sí certifica — lo que decidió fue el modo.
        every { ajustes.getCurrentSettings() } returns TpvSettings(angelPaySdkEnabled = true, angelPaySdkFallbackEnabled = false)
        assertThat(nexgo.cerrarSolicitudSiCeso("req-1")).isTrue()
        assertThat(bandeja().executionClosedAt).isNotNull()
    }

    /** Una Nexgo con el SDK embebido ENCENDIDO; el respaldo app-a-app compilado o no según [respaldoCompilado]. */
    private fun nexgoConSdk(ajustes: TpvSettingsRepository, respaldoCompilado: Boolean) =
        PaymentAttemptLedger(db.paymentAttemptDao(), ajustes, UNA_SOLA_PANTALLA).apply {
            marcaDelBuild = MarcaDelBuild(procesador = "ANGELPAY", sdkAngelPayCompilado = true, respaldoAppAAppCompilado = respaldoCompilado)
        }

    @Test fun `P1 Nexgo con SDK embebido pero respaldo app-a-app posible nunca se cierra y no escribe nada`() = runTest {
        reclamar()
        val dao = db.paymentAttemptDao()
        dao.insert(intentoDeOtroProceso("a-duda", PaymentAttemptEntity.STATE_INDETERMINADO, processor = "ANGELPAY"))
        dao.insert(intentoDeOtroProceso("a-reserva", PaymentAttemptEntity.STATE_PREPARANDO, processor = "ANGELPAY"))
        val ajustes = mockk<TpvSettingsRepository>()
        every { ajustes.getCurrentSettings() } returns TpvSettings(angelPaySdkEnabled = true, angelPaySdkFallbackEnabled = true)

        // El respaldo le entrega la MISMA llave a la app EXTERNA (otro proceso): que el nuestro muriera no prueba nada.
        assertThat(nexgoConSdk(ajustes, respaldoCompilado = true).cerrarSolicitudSiCeso("req-1")).isFalse()

        assertThat(bandeja().executionClosedAt).isNull()
        assertThat(db.remotePaymentRequestDao().cerradaParaEjecucionEn("req-1")).isNull()
        assertThat(dao.getById("a-reserva")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
    }

    @Test fun `P2 Nexgo con el respaldo compilado pero apagado en los ajustes SI se cierra`() = runTest {
        reclamar()
        val dao = db.paymentAttemptDao()
        dao.insert(intentoDeOtroProceso("a-duda", PaymentAttemptEntity.STATE_INDETERMINADO, processor = "ANGELPAY"))
        val ajustes = mockk<TpvSettingsRepository>()
        every { ajustes.getCurrentSettings() } returns TpvSettings(angelPaySdkEnabled = true, angelPaySdkFallbackEnabled = false)

        // Respaldo compilado pero apagado: nada puede caer a la app externa, el SDK embebido certifica.
        assertThat(nexgoConSdk(ajustes, respaldoCompilado = true).cerrarSolicitudSiCeso("req-1")).isTrue()

        assertThat(bandeja().executionClosedAt).isNotNull()
    }

    @Test fun `P1 modo desconocido nunca se cierra`() = runTest {
        reclamar()
        val dao = db.paymentAttemptDao()
        dao.insert(intentoDeOtroProceso("a-duda", PaymentAttemptEntity.STATE_INDETERMINADO))
        dao.insert(intentoDeOtroProceso("a-reserva", PaymentAttemptEntity.STATE_PREPARANDO))

        // Marca que la libreta no conoce.
        val otraMarca = PaymentAttemptLedger(dao, mockk(relaxed = true), UNA_SOLA_PANTALLA).apply {
            marcaDelBuild = MarcaDelBuild(procesador = "OTRA", sdkAngelPayCompilado = true, respaldoAppAAppCompilado = false)
        }
        assertThat(otraMarca.cerrarSolicitudSiCeso("req-1")).isFalse()

        // Nexgo con los ajustes ilegibles: no se sabe si cobra embebido o con la app externa.
        val ajustesRotos = mockk<TpvSettingsRepository>()
        every { ajustesRotos.getCurrentSettings() } throws IllegalStateException("ajustes ilegibles")
        val nexgoSinAjustes = PaymentAttemptLedger(dao, ajustesRotos, UNA_SOLA_PANTALLA).apply {
            marcaDelBuild = MarcaDelBuild(procesador = "ANGELPAY", sdkAngelPayCompilado = true, respaldoAppAAppCompilado = false)
        }
        assertThat(nexgoSinAjustes.cerrarSolicitudSiCeso("req-1")).isFalse()

        assertThat(bandeja().executionClosedAt).isNull()
        assertThat(dao.getById("a-reserva")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
    }

    // ── Codex TPV r1 #1 (22-oct): sólo se certifica con UNA pantalla por tarea, y es nuestra MainActivity ──
    //
    // `PaymentActivity.onCreate` del AAR de AngelPay 1.0.21 relanza el cobro con `angelpay_req` cuando Android la RESTAURA. Con la
    // pantalla del SDK apilada bajo otra, un proceso nuevo podía certificar y, al volver atrás, el SDK cobraba una solicitud ya
    // cerrada sin pasar por la libreta. En la app la pila sale de `ActivityManager.appTasks`; aquí, de un verificador falso.

    /** La libreta de un proceso nuevo (otro token), con la marca fija y la pila que se le dé. */
    private fun libretaConPila(pila: PilaDePantallas) =
        PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true), pila).apply { marcaDelBuild = MARCA_PAX }

    @Test fun `P1 con la pantalla del SDK apilada bajo otra no se certifica y no escribe nada`() = runTest {
        reclamar()
        val dao = db.paymentAttemptDao()
        dao.insert(intentoDeOtroProceso("a-duda", PaymentAttemptEntity.STATE_INDETERMINADO))
        dao.insert(intentoDeOtroProceso("a-reserva", PaymentAttemptEntity.STATE_PREPARANDO))
        // Dos MainActivity con la del SDK en medio (una tarea de tres pantallas, la nuestra arriba)…
        val apilada = libretaConPila { listOf(TareaDeLaApp(pantallas = 3, arribaLaPrincipal = true)) }
        // …y la del SDK sola en otra tarea, encima de nada nuestro.
        val sdkEnOtraTarea = libretaConPila {
            listOf(TareaDeLaApp(pantallas = 1, arribaLaPrincipal = true), TareaDeLaApp(pantallas = 1, arribaLaPrincipal = false))
        }

        assertThat(apilada.cerrarSolicitudSiCeso("req-1")).isFalse()
        assertThat(sdkEnOtraTarea.cerrarSolicitudSiCeso("req-1")).isFalse()
        // Por la sonda, el servidor no recibe la evidencia: ACTIVE, ejecución viva y sin cerrar.
        val sonda = RemotePaymentCoordinator(RemotePaymentInbox(db.remotePaymentRequestDao(), com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), apilada, PaymentStateHolder()))
            .probeSocketPaymentRequest("req-1", "venue-1")
        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(sonda.executionAlive).isTrue()
        assertThat(sonda.closedForExecution).isFalse()

        // Nada escrito: ni el cierre ni el descarte de la reserva del proceso muerto.
        assertThat(bandeja().executionClosedAt).isNull()
        assertThat(dao.getById("a-reserva")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
    }

    @Test fun `P1 con la pila ilegible no se certifica`() = runTest {
        reclamar()
        val dao = db.paymentAttemptDao()
        dao.insert(intentoDeOtroProceso("a-duda", PaymentAttemptEntity.STATE_INDETERMINADO))
        dao.insert(intentoDeOtroProceso("a-reserva", PaymentAttemptEntity.STATE_PREPARANDO))

        // El sistema no deja leer las tareas, la tarea desapareció a media lectura, o la clase no existe en este Android.
        assertThat(libretaConPila { throw SecurityException("sin permiso") }.cerrarSolicitudSiCeso("req-1")).isFalse()
        assertThat(libretaConPila { throw IllegalArgumentException("la tarea ya no existe") }.cerrarSolicitudSiCeso("req-1")).isFalse()
        assertThat(libretaConPila { throw NoClassDefFoundError("android/app/TaskInfo") }.cerrarSolicitudSiCeso("req-1")).isFalse()
        // Sin cablear (construida a mano, sin Hilt) tampoco.
        assertThat(PaymentAttemptLedger(dao, mockk(relaxed = true)).apply { marcaDelBuild = MARCA_PAX }.cerrarSolicitudSiCeso("req-1")).isFalse()

        assertThat(bandeja().executionClosedAt).isNull()
        assertThat(dao.getById("a-reserva")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
    }

    @Test fun `P1 control - con una sola pantalla (MainActivity) se certifica como antes`() = runTest {
        reclamar()
        val dao = db.paymentAttemptDao()
        dao.insert(intentoDeOtroProceso("a-duda", PaymentAttemptEntity.STATE_INDETERMINADO))
        dao.insert(intentoDeOtroProceso("a-reserva", PaymentAttemptEntity.STATE_PREPARANDO))

        assertThat(libretaConPila { listOf(TareaDeLaApp(pantallas = 1, arribaLaPrincipal = true)) }.cerrarSolicitudSiCeso("req-1")).isTrue()

        assertThat(bandeja().executionClosedAt).isNotNull()
        assertThat(dao.getById("a-reserva")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
    }

    /**
     * D2 (QA 2-nov) con la pantalla del SDK restaurada encima (Codex TPV r1 #1). Android revive el proceso con `PaymentActivity`
     * arriba: su `onCreate` (AAR 1.0.22) relanza el cobro con `angelpay_req` sin mirar `savedInstanceState`. Al terminar, `MainActivity`
     * se crea SIN estado y su resultado no le llega a nadie (`EstadoDelProcesoTest`). Lo que tiene que quedar: el intento en DUDA, sin
     * «no se cobró» y sin otro lector de esa solicitud.
     */
    @Test fun `D2 con el SDK restaurado encima no se certifica, y al terminar queda el intento en duda y ningun otro cobro`() = runTest {
        assertThat(reclamar()).isEqualTo(RemotePaymentAdmission.READY)
        assertThat(abrirIntento("a1")).isTrue()
        assertThat(ledger.markAuthorizing("a1")).isTrue() // el proceso 1 muere con el SDK dentro
        var pila = listOf(TareaDeLaApp(pantallas = 2, arribaLaPrincipal = false))
        val libreta = libretaConPila { pila }
        val coordinador = RemotePaymentCoordinator(RemotePaymentInbox(db.remotePaymentRequestDao(), com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), libreta, PaymentStateHolder()))

        // Al arrancar el proceso nuevo (`LedgerRecoveryTrigger.instalar`), lo que dejó el muerto pasa a «en duda».
        libreta.cuarentenaDeHuerfanos()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)

        // Con el SDK encima la terminal no dice «ya no cobra», aunque le llegara una sonda.
        coordinador.probeSocketPaymentRequest("req-1", "venue-1").let {
            assertThat(it.executionAlive).isTrue()
            assertThat(it.closedForExecution).isFalse()
        }

        // El SDK termina: sólo queda la principal, en el Inicio, y ahí sí se certifica.
        pila = listOf(TareaDeLaApp(pantallas = 1, arribaLaPrincipal = true))
        coordinador.probeSocketPaymentRequest("req-1", "venue-1").let {
            assertThat(it.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
            assertThat(it.executionAlive).isFalse()
            assertThat(it.closedForExecution).isTrue()
        }

        // No es un cobro perdido: sigue en duda y nadie dijo «no se cobró»…
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(libreta.cobroSinResolver()?.attemptId).isEqualTo("a1")
        assertThat(bandeja().finalResultJson).isNull()
        // …ni un segundo cobro: esa solicitud ya no admite otro intento.
        assertThat(libreta.openAttempt("a2", "venue-1", "BLUMON", 10_000, 0, "FAST", contexto)).isFalse()
    }

    // ═══ B2 (22-oct, «ninguna duda de cobro sin salida»): la sonda y el cancel dicen la verdad ═══
    //
    // En PROCESSING sin efectivo/cripto arrancado:
    //  1. con propiedad en ESTE proceso y CERO intentos (la pantalla espera el toque de «Tarjeta») ⇒ ACTIVE, ejecución viva,
    //     y NUNCA se le pregunta a la libreta (con cero intentos `cerrarSolicitudSiCeso` cerraría: no ve la pantalla);
    //  2. sin propiedad ⇒ `resolverDesenlaceNegativo` (sonda: RECEIVED_CANCELLED; cancel: ACCEPTED);
    //  3. si no resolvió (o con propiedad e intentos) ⇒ `cerrarSolicitudSiCeso`: true ⇒ ACTIVE + `executionAlive=false` +
    //     `closedForExecution=true` (la evidencia con la que el SERVIDOR suelta SÓLO la ranura); false ⇒ ACTIVE viva.

    /** Lo que queda tras reiniciar la app: otra libreta (otro token), otra bandeja y otro coordinador SIN propiedad en memoria. */
    private class Proceso(val libreta: PaymentAttemptLedger, val bandeja: RemotePaymentInbox, val coordinador: RemotePaymentCoordinator)

    private fun procesoNuevo(): Proceso {
        val libreta = PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true), UNA_SOLA_PANTALLA).apply { marcaDelBuild = MARCA_PAX }
        val bandeja = RemotePaymentInbox(db.remotePaymentRequestDao(), com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), libreta, PaymentStateHolder())
        return Proceso(libreta, bandeja, RemotePaymentCoordinator(bandeja))
    }

    @Test fun `P1 tras reiniciar con el intento muerto la sonda contesta ACTIVE sin ejecucion viva y cerrada`() = runTest {
        assertThat(reclamar()).isEqualTo(RemotePaymentAdmission.READY)
        assertThat(abrirIntento("a1")).isTrue()
        assertThat(ledger.markAuthorizing("a1")).isTrue() // la app muere con el SDK dentro (Amaena, 20-oct)
        val nuevo = procesoNuevo()

        val respuesta = nuevo.coordinador.probeSocketPaymentRequest("req-1", "venue-1")

        assertThat(respuesta.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(respuesta.executionAlive).isFalse()
        assertThat(respuesta.closedForExecution).isTrue()
        assertThat(respuesta.finalResultJson).isNull()
        val fila = bandeja()
        assertThat(fila.executionClosedAt).isNotNull()
        // Cerrar no es resolver: nada dice «no se cobró»; la bandeja sigue PROCESSING y la duda, intacta.
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.finalEmittedAt).isNull()
        assertThat(fila.finalResultJson).isNull()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
        // Y ya no se ejecuta: el proceso nuevo no puede abrir otro intento de esa solicitud.
        assertThat(nuevo.libreta.openAttempt("a2", "venue-1", "BLUMON", 10_000, 0, "FAST", contexto)).isFalse()
        assertThat(nuevo.libreta.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CERRADA)
    }

    @Test fun `P1 la segunda sonda repite la misma respuesta sin volver a escribir`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        ledger.markIndeterminate("a1", "IOException")
        val nuevo = procesoNuevo()
        val primera = nuevo.coordinador.probeSocketPaymentRequest("req-1", "venue-1")
        val filaTrasLaPrimera = bandeja()
        val intentoTrasLaPrimera = db.paymentAttemptDao().getById("a1")!!
        assertThat(filaTrasLaPrimera.executionClosedAt).isNotNull()
        Thread.sleep(5) // si la segunda volviera a escribir, `execution_closed_at`/`updated_at` cambiarían

        val segunda = nuevo.coordinador.probeSocketPaymentRequest("req-1", "venue-1")

        assertThat(segunda).isEqualTo(primera)
        assertThat(segunda.executionAlive).isFalse()
        assertThat(segunda.closedForExecution).isTrue()
        assertThat(bandeja()).isEqualTo(filaTrasLaPrimera)
        assertThat(db.paymentAttemptDao().getById("a1")).isEqualTo(intentoTrasLaPrimera)
        // El cancel posterior contesta lo mismo, también sin escribir.
        val cancel = nuevo.coordinador.cancelSocketPaymentRequest("req-1")
        assertThat(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(cancel.executionAlive).isFalse()
        assertThat(cancel.closedForExecution).isTrue()
        assertThat(bandeja()).isEqualTo(filaTrasLaPrimera)
    }

    @Test fun `P1 en este proceso con el cobro corriendo la sonda dice ejecucion viva`() = runTest {
        reclamar()
        abrirIntento("a1")
        assertThat(ledger.markKernelEntered("a1")).isTrue()

        val respuesta = coordinator.probeSocketPaymentRequest("req-1", "venue-1")

        assertThat(respuesta.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(respuesta.executionAlive).isTrue()
        assertThat(respuesta.closedForExecution).isFalse()
        assertThat(bandeja().executionClosedAt).isNull()
        // El cobro sigue como si la sonda no hubiera pasado.
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_KERNEL_ACTIVO)
        assertThat(ledger.markAuthorizing("a1")).isTrue()
    }

    @Test fun `P1 mismo proceso - el SDK regreso sin veredicto, se perdio el aviso y el socket reconecto - la sonda certifica sin ejecucion viva`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        ledger.markIndeterminate("a1", "U101")
        ledger.marcarSdkRegreso("a1") // el SDK regresó: ya no ejecuta nada (el registro pendiente es dinero, no ejecución)

        // Mismo proceso (la propiedad sigue en memoria): el socket reconecta y el servidor sondea.
        val respuesta = coordinator.probeSocketPaymentRequest("req-1", "venue-1")

        assertThat(respuesta.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(respuesta.executionAlive).isFalse()
        assertThat(respuesta.closedForExecution).isTrue()
        assertThat(bandeja().executionClosedAt).isNotNull()
        assertThat(bandeja().status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        // La pantalla ya no puede reintentar esa solicitud: lo dice como «cerrada», no como «no se pudo guardar».
        assertThat(abrirIntento("a2")).isFalse()
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CERRADA)
    }

    @Test fun `P1 mismo proceso - INDETERMINADO escrito en el catch de la corrutina NO se certifica`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        // Lo que escribe el `catch` de la corrutina de la PAX: una excepción no prueba que la llamada nativa terminó.
        ledger.markIndeterminate("a1", "IOException")

        val respuesta = coordinator.probeSocketPaymentRequest("req-1", "venue-1")

        assertThat(respuesta.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(respuesta.executionAlive).isTrue()
        assertThat(respuesta.closedForExecution).isFalse()
        val fila = bandeja()
        assertThat(fila.executionClosedAt).isNull()
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.finalEmittedAt).isNull()
        // El cancel tampoco lo certifica.
        val cancel = coordinator.cancelSocketPaymentRequest("req-1")
        assertThat(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(cancel.executionAlive).isTrue()
        assertThat(cancel.closedForExecution).isFalse()
        assertThat(bandeja().executionClosedAt).isNull()
    }

    @Test fun `P1 PREPARANDO de este proceso + reconexion + sonda - no se aborta y el intento continua`() = runTest {
        reclamar()
        assertThat(abrirIntento("a1")).isTrue() // reserva hecha, esperando el vínculo previo al SDK

        // El socket reconecta (mismo proceso, la propiedad sigue en memoria) y el servidor sondea dos veces.
        repeat(2) {
            val respuesta = coordinator.probeSocketPaymentRequest("req-1", "venue-1")
            assertThat(respuesta.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
            assertThat(respuesta.executionAlive).isTrue()
            assertThat(respuesta.closedForExecution).isFalse()
        }

        val fila = bandeja()
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.finalEmittedAt).isNull()
        assertThat(fila.executionClosedAt).isNull()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
        // El vínculo llegó: el cobro continúa como si la sonda no hubiera pasado.
        assertThat(ledger.markKernelEntered("a1")).isTrue()
        assertThat(ledger.markAuthorizing("a1")).isTrue()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
    }

    @Test fun `P1 con la pantalla abierta esperando Tarjeta (cero intentos) la sonda dice ejecucion viva`() = runTest {
        var preguntasALaLibreta = 0
        val bandejaContada = RemotePaymentInbox(db.remotePaymentRequestDao(), com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), laTerminalRechazariaUnaVentaNueva = { false }) { requestId ->
            preguntasALaLibreta++
            ledger.cerrarSolicitudSiCeso(requestId)
        }
        val coordinador = RemotePaymentCoordinator(bandejaContada)
        bandejaContada.receive(solicitud)
        assertThat(coordinador.prepareSocketPaymentRequest("req-1", { true }, { "venue-1" })).isEqualTo(RemotePaymentAdmission.READY)

        val respuesta = coordinador.probeSocketPaymentRequest("req-1", "venue-1")

        assertThat(respuesta.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(respuesta.executionAlive).isTrue()
        assertThat(respuesta.closedForExecution).isFalse()
        // Con CERO intentos la libreta cerraría (no ve la pantalla): por eso la sonda nunca le pregunta.
        assertThat(preguntasALaLibreta).isEqualTo(0)
        assertThat(db.remotePaymentRequestDao().cerradaParaEjecucionEn("req-1")).isNull()
        assertThat(bandeja().executionClosedAt).isNull()
        assertThat(bandeja().status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        // El cajero toca «Tarjeta»: la reserva entra.
        assertThat(abrirIntento("a1")).isTrue()
    }

    // ── Extras del cancel (B2): el caso 3 también por el cancel, y tras reiniciar ──

    @Test fun `P1 el cancel tras reiniciar con el intento muerto contesta ACTIVE sin ejecucion viva y cerrada`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        val nuevo = procesoNuevo()

        val decision = nuevo.coordinador.cancelSocketPaymentRequest("req-1")

        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(decision.finalResultJson).isNull()
        assertThat(decision.executionAlive).isFalse()
        assertThat(decision.closedForExecution).isTrue()
        val fila = bandeja()
        assertThat(fila.executionClosedAt).isNotNull()
        // Un cancel con la duda viva no se acepta ni fabrica un negativo: sólo certifica el cese.
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.cancelAcceptedAt).isNull()
        assertThat(fila.finalEmittedAt).isNull()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
    }

    // ── Fix round 0 (decisión del controlador, duda 2): con PROPIEDAD y la pantalla todavía capaz de «Reintentar», la ejecución
    //    SIGUE viva. «Cerrada para ejecución» tiene que ser verdad: no se cierra lo que la pantalla de este proceso puede reintentar.

    @Test fun `P1 dueno con el banco rechazando y la pantalla ofreciendo Reintentar - la sonda dice ejecucion viva y no cierra`() = runTest {
        var preguntasALaLibreta = 0
        val bandejaContada = RemotePaymentInbox(db.remotePaymentRequestDao(), com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), laTerminalRechazariaUnaVentaNueva = { false }) { requestId ->
            preguntasALaLibreta++
            ledger.cerrarSolicitudSiCeso(requestId)
        }
        val coordinador = RemotePaymentCoordinator(bandejaContada)
        bandejaContada.receive(solicitud)
        assertThat(coordinador.prepareSocketPaymentRequest("req-1", { true }, { "venue-1" })).isEqualTo(RemotePaymentAdmission.READY)
        // El banco rechaza (rechazo FINAL, sin dinero) y el SDK regresó; la pantalla ofrece «Reintentar».
        assertThat(abrirIntento("a1")).isTrue()
        assertThat(ledger.markAuthorizing("a1")).isTrue()
        assertThat(ledger.markHostResponded("a1", approved = false, operationId = null, referenceNumber = null, authCode = null)).isTrue()
        ledger.marcarSdkRegreso("a1")
        val rechazo = db.paymentAttemptDao().getById("a1")!!
        assertThat(rechazo.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(rechazo.hostApproved).isFalse()
        // Un reintento que el cajero canceló antes de cobrar tampoco es dinero pendiente.
        assertThat(abrirIntento("a2")).isTrue()
        assertThat(ledger.markDiscardedBeforeCharge("a2", "user_cancel")).isTrue()

        // El socket reconecta (mismo proceso) y el servidor sondea.
        val respuesta = coordinador.probeSocketPaymentRequest("req-1", "venue-1")

        assertThat(respuesta.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(respuesta.executionAlive).isTrue()
        assertThat(respuesta.closedForExecution).isFalse()
        assertThat(preguntasALaLibreta).isEqualTo(0)
        val fila = bandeja()
        assertThat(fila.executionClosedAt).isNull()
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(fila.finalEmittedAt).isNull()
        // «Reintentar» sigue funcionando: la reserva entra y el cobro avanza.
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.LIBRE)
        assertThat(abrirIntento("a3")).isTrue()
        assertThat(ledger.markAuthorizing("a3")).isTrue()
    }

    @Test fun `P1 dueno con el banco rechazando y la pantalla ofreciendo Reintentar - el cancel del cajero se acepta y no cierra para ejecucion`() = runTest {
        var preguntasALaLibreta = 0
        val bandejaContada = RemotePaymentInbox(db.remotePaymentRequestDao(), com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), laTerminalRechazariaUnaVentaNueva = { false }) { requestId ->
            preguntasALaLibreta++
            ledger.cerrarSolicitudSiCeso(requestId)
        }
        val coordinador = RemotePaymentCoordinator(bandejaContada)
        bandejaContada.receive(solicitud)
        assertThat(coordinador.prepareSocketPaymentRequest("req-1", { true }, { "venue-1" })).isEqualTo(RemotePaymentAdmission.READY)
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        ledger.markHostResponded("a1", approved = false, operationId = null, referenceNumber = null, authCode = null)
        ledger.marcarSdkRegreso("a1")

        val decision = coordinador.cancelSocketPaymentRequest("req-1")

        // El cajero PIDIÓ cancelar: lo acepta el CAS de siempre (C.5), con su cerca; nunca el certificado de cese.
        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        assertThat(decision.closedForExecution).isFalse()
        assertThat(preguntasALaLibreta).isEqualTo(0)
        val fila = bandeja()
        assertThat(fila.executionClosedAt).isNull()
        assertThat(fila.cancelAcceptedAt).isNotNull()
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CANCELADA_POR_EL_POS)
    }

    // ── Fix round 1 (M2): ya CERRADA para ejecución, la pantalla dueña ya no puede reintentar (la cerca rige): no hay caso 1.

    @Test fun `P1 dueno con la bandeja ya cerrada y el intento liberado por el servidor - la sonda repite sin ejecucion viva y cerrada sin escribir`() = runTest {
        reclamar()
        abrirIntento("a1")
        ledger.markAuthorizing("a1")
        ledger.markIndeterminate("a1", "U101")
        ledger.marcarSdkRegreso("a1")
        // Sonda 1 (mismo proceso, con propiedad): el SDK regresó sin veredicto ⇒ certifica y cierra.
        val primera = coordinator.probeSocketPaymentRequest("req-1", "venue-1")
        assertThat(primera.executionAlive).isFalse()
        assertThat(primera.closedForExecution).isTrue()
        // El servidor libera ese intento (declaración del cajero): INDETERMINADO → DESCARTADA. Ya no hay bloqueadores.
        assertThat(
            db.paymentAttemptDao().cerrarPorLiberacionDelServidor(
                "a1", "venue-1", "req-1", "OPERATOR_NO_INSTRUMENT", "liberada_por_el_servidor:OPERATOR_RECONCILED", 2_000,
            ),
        ).isEqualTo(1)
        assertThat(db.remotePaymentRequestDao().contarIntentosBloqueadores("req-1")).isEqualTo(0)
        val filaCerrada = bandeja()
        val intentoLiberado = db.paymentAttemptDao().getById("a1")!!
        Thread.sleep(5) // si alguna sonda volviera a escribir, `execution_closed_at`/`updated_at` cambiarían

        // Sondas 2 y 3: la pantalla dueña ya no puede reintentar (la cerca rige) ⇒ no es el caso 1; repiten la respuesta.
        repeat(2) {
            val respuesta = coordinator.probeSocketPaymentRequest("req-1", "venue-1")
            assertThat(respuesta.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
            assertThat(respuesta.executionAlive).isFalse()
            assertThat(respuesta.closedForExecution).isTrue()
            assertThat(respuesta).isEqualTo(primera)
        }
        assertThat(bandeja()).isEqualTo(filaCerrada)
        assertThat(db.paymentAttemptDao().getById("a1")).isEqualTo(intentoLiberado)
        assertThat(abrirIntento("a2")).isFalse()
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CERRADA)
    }

    // ═══ D6 (2-nov, «ninguna duda de cobro sin salida»): sólo la libreta de una fila que NACIÓ bajo la barrera prueba algo ═══
    //
    // Una versión vieja de la app pudo cobrar sin anotarlo. En una fila heredada (`durable_barrier_version` = 0), «la libreta no
    // tiene nada que bloquee» no prueba «no se cobró»: ni con cero intentos, ni con un PREPARANDO/DESCARTADA nuevo, ni con un
    // rechazo A rastreado (un intento B invisible no deja rastro). Lo que sí se puede certificar es que ya nada corre: el «no se
    // cobró» de esa venta lo dice una persona.

    private enum class Historia { CERO_INTENTOS, PREPARANDO, DESCARTADA, RECHAZO_DEL_BANCO }

    /** Una solicitud reclamada por un proceso que ya murió, con su procedencia y su historia en la libreta. */
    private suspend fun sembrarSinDueno(id: String, barrera: Int, historia: Historia) {
        db.remotePaymentRequestDao().insert(
            RemotePaymentRequestEntity.from(solicitud.copy(requestId = id), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
                .copy(durableBarrierVersion = barrera),
        )
        val estado = when (historia) {
            Historia.CERO_INTENTOS -> return
            Historia.PREPARANDO -> PaymentAttemptEntity.STATE_PREPARANDO
            Historia.DESCARTADA, Historia.RECHAZO_DEL_BANCO -> PaymentAttemptEntity.STATE_DESCARTADA
        }
        db.paymentAttemptDao().insert(
            PaymentAttemptEntity(
                attemptId = "a-$id", venueId = "venue-1", processor = "BLUMON", state = estado,
                amountCents = 10_000, tipCents = 0, recordingRoute = "FAST",
                paymentContextJson = """{"terminalPaymentRequestId":"$id"}""", createdAt = 1, updatedAt = 1,
                terminalPaymentRequestId = id, processToken = "proceso-muerto",
                hostApproved = false.takeIf { historia == Historia.RECHAZO_DEL_BANCO },
            ),
        )
    }

    /** Sin propiedad y sin la pregunta a la libreta: lo que queda a la vista es sólo el camino del negativo. */
    private fun bandejaSinCertificarElCese() = RemotePaymentInbox(
        db.remotePaymentRequestDao(), com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(),
        laTerminalRechazariaUnaVentaNueva = { false },
    )

    private suspend fun sinNingunFinal(id: String, contexto: String) {
        val fila = db.remotePaymentRequestDao().getById(id)!!
        assertWithMessage(contexto).that(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertWithMessage(contexto).that(fila.finalResultJson).isNull()
        assertWithMessage(contexto).that(fila.finalEmittedAt).isNull()
        assertWithMessage(contexto).that(fila.cancelAcceptedAt).isNull()
    }

    @Test fun `D6 P1 heredada sin dueno - ni la sonda ni el cancel fabrican un negativo y no descartan nada`() = runTest {
        val bandejaSinDueno = bandejaSinCertificarElCese()
        for (historia in Historia.values()) {
            val enSonda = "sonda-$historia"
            val enCancel = "cancel-$historia"
            sembrarSinDueno(enSonda, barrera = 0, historia)
            sembrarSinDueno(enCancel, barrera = 0, historia)
            val intentoEnSonda = db.paymentAttemptDao().getById("a-$enSonda")
            val intentoEnCancel = db.paymentAttemptDao().getById("a-$enCancel")

            val sonda = bandejaSinDueno.probe(enSonda, "venue-1")
            val cancel = bandejaSinDueno.cancel(enCancel)

            assertWithMessage("sonda $historia").that(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
            assertWithMessage("sonda $historia").that(sonda.finalResultJson).isNull()
            assertWithMessage("cancel $historia").that(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
            assertWithMessage("cancel $historia").that(cancel.finalResultJson).isNull()
            sinNingunFinal(enSonda, "sonda $historia")
            sinNingunFinal(enCancel, "cancel $historia")
            // Ese negativo tampoco se llevó la reserva: un PREPARANDO sigue PREPARANDO y nadie le escribió un motivo.
            assertWithMessage("sonda $historia").that(db.paymentAttemptDao().getById("a-$enSonda")).isEqualTo(intentoEnSonda)
            assertWithMessage("cancel $historia").that(db.paymentAttemptDao().getById("a-$enCancel")).isEqualTo(intentoEnCancel)
        }
    }

    @Test fun `D6 P1 control - la MISMA historia nacida bajo la barrera conserva las reglas de hoy`() = runTest {
        val bandejaSinDueno = bandejaSinCertificarElCese()
        val nueva = RemotePaymentRequestEntity.VERSION_DE_LA_BARRERA_DURABLE
        for (historia in Historia.values()) {
            val enSonda = "sonda-$historia"
            val enCancel = "cancel-$historia"
            sembrarSinDueno(enSonda, nueva, historia)
            sembrarSinDueno(enCancel, nueva, historia)

            val sonda = bandejaSinDueno.probe(enSonda, "venue-1")
            val cancel = bandejaSinDueno.cancel(enCancel)

            // La sonda pasa por el resolutor: rechazo final sin veto ⇒ PROCESSOR_DECLINED; lo demás, PRE_AUTHORIZATION.
            assertWithMessage("sonda $historia").that(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.RECEIVED_CANCELLED)
            val rechazoAcreditado = historia == Historia.RECHAZO_DEL_BANCO
            val jsonSonda = JSONObject(sonda.finalResultJson!!)
            assertWithMessage("sonda $historia").that(jsonSonda.optString("status")).isEqualTo(if (rechazoAcreditado) "failed" else "cancelled")
            assertWithMessage("sonda $historia").that(jsonSonda.optString("outcomeEvidence"))
                .isEqualTo(if (rechazoAcreditado) "PROCESSOR_DECLINED" else "PRE_AUTHORIZATION")
            // El cancel acepta con la cerca puesta y sin dejar viva ninguna reserva.
            assertWithMessage("cancel $historia").that(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
            assertWithMessage("cancel $historia").that(JSONObject(cancel.finalResultJson!!).optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
            assertWithMessage("cancel $historia").that(ledger.openAttempt("otro-$enCancel", "venue-1", "BLUMON", 10_000, 0, "FAST", """{"terminalPaymentRequestId":"$enCancel"}""")).isFalse()
            if (historia == Historia.PREPARANDO) {
                assertThat(db.paymentAttemptDao().getById("a-$enSonda")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
                assertThat(db.paymentAttemptDao().getById("a-$enCancel")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
            }
        }
    }

    @Test fun `D6 P1 una fila que nacio por receive y cuyo proceso murio antes de abrir intento sigue saliendo por PRE_AUTHORIZATION`() = runTest {
        assertThat(reclamar()).isEqualTo(RemotePaymentAdmission.READY)

        val sonda = procesoNuevo().coordinador.probeSocketPaymentRequest("req-1", "venue-1")

        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.RECEIVED_CANCELLED)
        assertThat(JSONObject(sonda.finalResultJson!!).optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
    }

    @Test fun `D6 P1 heredada sin dueno - lo unico que la libreta certifica es que ya nada corre`() = runTest {
        for (historia in Historia.values()) {
            val enSonda = "sonda-$historia"
            val enCancel = "cancel-$historia"
            sembrarSinDueno(enSonda, barrera = 0, historia)
            sembrarSinDueno(enCancel, barrera = 0, historia)
            val nuevo = procesoNuevo()

            val sonda = nuevo.coordinador.probeSocketPaymentRequest(enSonda, "venue-1")
            val cancel = nuevo.coordinador.cancelSocketPaymentRequest(enCancel)

            // El servidor suelta SÓLO la ranura; el «no se cobró» sigue siendo de una persona.
            assertWithMessage("sonda $historia").that(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
            assertWithMessage("sonda $historia").that(sonda.executionAlive).isFalse()
            assertWithMessage("sonda $historia").that(sonda.closedForExecution).isTrue()
            assertWithMessage("cancel $historia").that(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
            assertWithMessage("cancel $historia").that(cancel.executionAlive).isFalse()
            assertWithMessage("cancel $historia").that(cancel.closedForExecution).isTrue()
            for (id in listOf(enSonda, enCancel)) {
                sinNingunFinal(id, "$id $historia")
                assertWithMessage("$id $historia").that(db.remotePaymentRequestDao().getById(id)!!.executionClosedAt).isNotNull()
                assertWithMessage("$id $historia").that(db.remotePaymentRequestDao().getById(id)!!.durableBarrierVersion).isEqualTo(0)
                assertWithMessage("$id $historia").that(nuevo.libreta.cercaDeSolicitud(id)).isEqualTo(CercaDeSolicitud.CERRADA)
            }
        }
    }

    @Test fun `D6 P1 heredada con dueno vivo - el cancel conserva su contrato y la propiedad no sube la procedencia`() = runTest {
        db.remotePaymentRequestDao().insert(RemotePaymentRequestEntity.from(solicitud).copy(durableBarrierVersion = 0))
        assertThat(coordinator.prepareSocketPaymentRequest("req-1", { true }, { "venue-1" })).isEqualTo(RemotePaymentAdmission.READY)

        // La pantalla de este proceso sigue esperando «Tarjeta»: viva, como siempre.
        val sonda = coordinator.probeSocketPaymentRequest("req-1", "venue-1")
        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(sonda.executionAlive).isTrue()
        assertThat(sonda.closedForExecution).isFalse()

        val cancel = coordinator.cancelSocketPaymentRequest("req-1")

        assertThat(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        assertThat(bandeja().durableBarrierVersion).isEqualTo(0)
        assertThat(bandeja().cancelAcceptedAt).isNotNull()
        assertThat(abrirIntento("a1")).isFalse()
    }

    @Test fun `D6 P1 heredada - el negativo de la pantalla tampoco se escribe y un exito real si`() = runTest {
        db.remotePaymentRequestDao().insert(RemotePaymentRequestEntity.from(solicitud).copy(durableBarrierVersion = 0))
        assertThat(coordinator.prepareSocketPaymentRequest("req-1", { true }, { "venue-1" })).isEqualTo(RemotePaymentAdmission.READY)
        assertThat(abrirIntento("a1")).isTrue()

        assertThat(inbox.persistResult("req-1", """{"requestId":"req-1","status":"cancelled"}""")).isNull()

        sinNingunFinal("req-1", "negativo de la pantalla")
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
        // La guarda es de los NEGATIVOS: el dinero acreditado cierra la solicitud igual.
        val exito = """{"requestId":"req-1","status":"success","paymentId":"pay-1"}"""
        assertThat(inbox.persistResult("req-1", exito)).isEqualTo(exito)
        assertThat(bandeja().status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(bandeja().durableBarrierVersion).isEqualTo(0)
    }

    @Test fun `D6 P1 lo que ya bloqueaba sigue bloqueando con las dos procedencias, y la procedencia nunca amplia lo elegible`() = runTest {
        val bandejaSinDueno = bandejaSinCertificarElCese()
        for (barrera in listOf(0, RemotePaymentRequestEntity.VERSION_DE_LA_BARRERA_DURABLE)) {
            for (caso in listOf("kernel", "autorizando", "aprobado", "heredado", "evidencia")) {
                val id = "$caso-$barrera"
                db.remotePaymentRequestDao().insert(
                    RemotePaymentRequestEntity.from(solicitud.copy(requestId = id), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
                        .copy(durableBarrierVersion = barrera),
                )
                val base = intentoDeOtroProceso("a-$id", PaymentAttemptEntity.STATE_DESCARTADA)
                    .copy(paymentContextJson = """{"terminalPaymentRequestId":"$id"}""", terminalPaymentRequestId = id)
                db.paymentAttemptDao().insert(
                    when (caso) {
                        "kernel" -> base.copy(state = PaymentAttemptEntity.STATE_KERNEL_ACTIVO)
                        "autorizando" -> base.copy(state = PaymentAttemptEntity.STATE_AUTORIZANDO)
                        "aprobado" -> base.copy(state = PaymentAttemptEntity.STATE_HOST_RESPONDIO, hostApproved = true)
                        "heredado" -> base.copy(legacyShadow = true)
                        else -> base.copy(serverProcessorEvidence = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
                    },
                )

                val sonda = bandejaSinDueno.probe(id, "venue-1")
                val cancel = bandejaSinDueno.cancel(id)

                assertWithMessage(id).that(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
                assertWithMessage(id).that(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
                sinNingunFinal(id, id)
            }
            // Efectivo o cripto ya arrancado tampoco se resuelve solo.
            val conEfectivo = "efectivo-$barrera"
            db.remotePaymentRequestDao().insert(
                RemotePaymentRequestEntity.from(solicitud.copy(requestId = conEfectivo), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
                    .copy(durableBarrierVersion = barrera, executionStartedAt = 1),
            )
            assertThat(bandejaSinDueno.probe(conEfectivo, "venue-1").disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
            assertThat(bandejaSinDueno.cancel(conEfectivo).disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
            sinNingunFinal(conEfectivo, conEfectivo)
        }
    }
}
