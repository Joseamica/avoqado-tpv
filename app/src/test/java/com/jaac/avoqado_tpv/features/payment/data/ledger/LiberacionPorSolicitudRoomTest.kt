package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.domain.models.Result as AppResult
import com.jaac.avoqado_tpv.core.remotepayment.BandejaServerRecovery
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentAdmission
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentCoordinator
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentInbox
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentProbeDisposition
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentReceiveDecision
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ConsultaDelCierreDeCaja
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.data.task5RoomShiftRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * D12 («ninguna duda de cobro sin salida», hallazgo #3 de Codex TPV r1): la PAX NUNCA liga sus intentos de cobro remoto con su
 * solicitud (sólo la Nexgo emite `terminal:payment_attempt_opened`). Sin vínculo ni Payment, S6 contesta
 * `requestId:null, request:null`, y cuando el cajero declara «no se cobró» en el POS (E2) la terminal no se entera: su
 * INDETERMINADO se queda en el aviso hasta que el banco concilie. No aparta la terminal ni la venta y no hay dinero en juego:
 * es ruido que confunde.
 *
 * La terminal pregunta por la SOLICITUD con el GET que ya existe (`…/requests/{id}`), con el id del CONTEXTO DURABLE del intento, y
 * sólo la declaración de una PERSONA (`NOT_CHARGED` + `OPERATOR_RECONCILED`, con `resuelta`) cierra la duda: nunca el silencio, el
 * reloj ni el monto. Todo lo que S6 aporta (Payment, PENDING, APPROVED, colisión, evidencia sin dueño, contradicción) sigue
 * mandando, y el cierre es el CAS remoto de siempre, que vuelve a vetar la evidencia positiva al guardar.
 *
 * Las respuestas se arman con el JSON LITERAL del servidor (con sus `null`) y el mismo `Gson()` de `GsonConverterFactory.create()`;
 * tres pruebas pasan además por Retrofit real contra un servidor de mentiras (ruta, conversor, red cortada).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LiberacionPorSolicitudRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var ledger: PaymentAttemptLedger
    private val api = mockk<TerminalAttemptApiService>()
    private val ahora = 1_700_000_000_000L
    private val venue = "venue-1"
    private var servidor: MockWebServer? = null

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
    }

    @After fun tearDown() {
        servidor?.shutdown()
        db.close()
    }

    // ── Las respuestas del SERVIDOR, tal cual ───────────────────────────────────────────────────────────────────────────

    /** S6 de un intento que el servidor NO ligó (la PAX): `requestId:null`, `request:null`, sin Payment (copiada de `RespuestasRealesDelServidorTest`). */
    private fun s6Literal(attemptId: String) =
        """{"success":true,"attemptId":"$attemptId","requestId":null,""" +
            """"attempt":{"attemptId":"$attemptId","outcome":"NOT_RECORDED","paymentId":null,"paymentStatus":null,""" +
            """"recordedVia":null,"amountCents":null,"tipCents":null,"isWinner":false,"winnerPaymentId":null,""" +
            """"processorEvidence":"NONE","processorEvidenceAt":null,"paymentContradiction":false,""" +
            """"evidenceContradiction":false,"unattributedEvidence":false,"linkedAt":null,"resolution":null},"request":null}"""

    /** El GET por SOLICITUD cuando el cajero declaró «no se cobró» en el POS (E2): `FAILED / OPERATOR_RECONCILED_NO_CHARGE`. */
    private fun getLiteral(requestId: String) =
        """{"success":true,"requestId":"$requestId","request":{""" +
            """"requestId":"$requestId","venueId":"$venue","terminalId":"n860w173397",""" +
            """"status":"FAILED","amount":100,"tip":0,"orderId":null,"paymentId":null,"senderDevice":"POS","lateResult":false,""" +
            """"cancelDisposition":null,"failureCode":"OPERATOR_RECONCILED_NO_CHARGE","outcome":"NOT_CHARGED",""" +
            """"outcomeEvidence":"OPERATOR_RECONCILED","evidenceClass":"OPERATOR","createdAt":"2026-10-20T19:00:00.000Z",""" +
            """"updatedAt":"2026-10-20T19:36:00.000Z","closedVia":null},"resuelta":true}"""

    /** El GET cuando el servidor soltó SÓLO la ranura (`TIMED_OUT / AUTO_RELEASED`): la venta sigue en duda y `resuelta` es false. */
    private fun getRanuraSoltada(requestId: String) = getLiteral(requestId)
        .cambia(""""status":"FAILED"""", """"status":"TIMED_OUT"""")
        .cambia(""""failureCode":"OPERATOR_RECONCILED_NO_CHARGE"""", """"failureCode":"AUTO_RELEASED"""")
        .cambia(""""outcome":"NOT_CHARGED"""", """"outcome":"UNRESOLVED"""")
        .cambia(""""outcomeEvidence":"OPERATOR_RECONCILED","evidenceClass":"OPERATOR"""", """"outcomeEvidence":null,"evidenceClass":null""")
        .cambia(""""resuelta":true""", """"resuelta":false""")

    /** Cambia [de] por [a] y FALLA si [de] no estaba: una variante que no cambió nada probaría lo mismo que la base. */
    private fun String.cambia(de: String, a: String): String {
        check(de in this) { "la variante no encontró «$de» en $this" }
        return replace(de, a)
    }

    private fun s6(json: String): Response<TerminalAttemptStatusResponse> =
        Response.success(Gson().fromJson(json, TerminalAttemptStatusResponse::class.java))

    private fun porSolicitud(json: String): Response<TerminalRequestStatusResponse> =
        Response.success(Gson().fromJson(json, TerminalRequestStatusResponse::class.java))

    private fun httpS6(code: Int): Response<TerminalAttemptStatusResponse> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

    private fun httpPorSolicitud(code: Int): Response<TerminalRequestStatusResponse> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

    // ── Las filas ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Una duda de un cobro remoto: INDETERMINADO, de la PAX (BLUMON) por defecto, con la solicitud en la COLUMNA y en el CONTEXTO. */
    private suspend fun duda(
        attemptId: String,
        requestId: String? = "req-$attemptId",
        processor: String = "BLUMON",
        state: String = "INDETERMINADO",
        contexto: String? = null,
        hostApproved: Boolean? = null,
        venueDeLaFila: String = venue,
    ) = dao.insert(
        PaymentAttemptEntity(
            attemptId = attemptId, venueId = venueDeLaFila, processor = processor, state = state, amountCents = 10_000, tipCents = 0,
            recordingRoute = "FAST",
            paymentContextJson = contexto ?: if (requestId != null) """{"terminalPaymentRequestId":"$requestId"}""" else """{"amount":100.00}""",
            hostApproved = hostApproved, createdAt = ahora - 600_000, updatedAt = ahora - 600_000, terminalPaymentRequestId = requestId,
        ),
    )

    private suspend fun bandeja(requestId: String, venueDeLaBandeja: String = venue) = db.remotePaymentRequestDao().insert(
        RemotePaymentRequestEntity(
            requestId = requestId, venueId = venueDeLaBandeja, amountCents = 10000, tipCents = 0, rating = null, skipReview = true, orderId = null,
            processedByStaffId = null, senderDeviceName = null, sourceTimestamp = "t", status = RemotePaymentRequestEntity.STATUS_PROCESSING,
            createdAt = ahora - 600_000, updatedAt = ahora - 600_000,
        ),
    )

    private fun recuperacion(libreta: PaymentAttemptLedger = ledger) = LedgerServerRecovery(dao, libreta, api)

    // ── La declaración del cajero en el POS cierra la duda de la PAX ──────────────────────────────────────────────────

    @Test fun `P1 la declaracion del cajero en el POS cierra la duda de una PAX sin vinculo (pasada del worker)`() = runTest {
        duda("a-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6(s6Literal("a-1"))
        coEvery { api.getRequestStatus(venue, "req-a-1") } returns porSolicitud(getLiteral("req-a-1"))
        assertThat(dao.observeUnresolvedCount(venue).first()).isEqualTo(1)
        assertThat(ledger.cobroSinResolver()?.attemptId).isEqualTo("a-1")

        val r = recuperacion().recover(venue, ahora)

        assertThat(r.liberadas).isEqualTo(1)
        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("DESCARTADA")
        assertThat(fila.lastError).isEqualTo("liberada_por_el_servidor:OPERATOR_RECONCILED")
        assertThat(fila.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
        // El aviso de la terminal se apaga y la libreta ya no dice que hay un cobro sin resolver.
        assertThat(dao.observeUnresolvedCount(venue).first()).isEqualTo(0)
        assertThat(ledger.cobroSinResolver()).isNull()
        coVerify(exactly = 1) { api.getRequestStatus(venue, "req-a-1") }
    }

    @Test fun `P1 la consulta interactiva (Consultar) la cierra igual y no gasta el turno de la pasada`() = runTest {
        duda("a-2")
        coEvery { api.getAttemptStatus(venue, "a-2") } returns s6(s6Literal("a-2"))
        coEvery { api.getRequestStatus(venue, "req-a-2") } returns porSolicitud(getLiteral("req-a-2"))

        val lectura = recuperacion().recoverOne(venue, "a-2", ahora, estampar = false)

        val fila = dao.getById("a-2")!!
        assertThat(fila.state).isEqualTo("DESCARTADA")
        assertThat(fila.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
        assertThat(fila.serverCheckedAt).isNull() // estampar=false: el sondeo de la pantalla no gasta el turno de E3
        assertThat(lectura.servidorContesto).isTrue()
        assertThat(lectura.evidenciaPositivaSinRegistro).isFalse()
        assertThat(lectura.vetoDelServidor).isNull()
    }

    @Test fun `P1 mientras nadie declara la duda sigue y la pasada siguiente la cierra cuando el cajero declara`() = runTest {
        duda("a-3")
        coEvery { api.getAttemptStatus(venue, "a-3") } returns s6(s6Literal("a-3"))
        coEvery { api.getRequestStatus(venue, "req-a-3") } returns porSolicitud(getRanuraSoltada("req-a-3"))
        val recuperador = recuperacion()

        // 1. El servidor soltó sólo la ranura: la VENTA sigue en duda, y la terminal no la cierra por eso.
        assertThat(recuperador.recover(venue, ahora).liberadas).isEqualTo(0)
        assertThat(dao.getById("a-3")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("a-3")!!.serverCheckedAt).isEqualTo(ahora) // contestó: gasta el turno

        // 2. El cajero declara en el POS y la pasada siguiente (cuando le toca otra vez) la cierra.
        coEvery { api.getRequestStatus(venue, "req-a-3") } returns porSolicitud(getLiteral("req-a-3"))
        val r = recuperador.recover(venue, ahora + LedgerServerRecovery.ESPACIADO_TOPE_MS + 1)

        assertThat(r.liberadas).isEqualTo(1)
        assertThat(dao.getById("a-3")!!.state).isEqualTo("DESCARTADA")
    }

    @Test fun `P2 una duda ya cerrada no vuelve a preguntar por la solicitud en las pasadas siguientes`() = runTest {
        duda("a-4")
        coEvery { api.getAttemptStatus(venue, "a-4") } returns s6(s6Literal("a-4"))
        coEvery { api.getRequestStatus(venue, "req-a-4") } returns porSolicitud(getLiteral("req-a-4"))
        val recuperador = recuperacion()
        assertThat(recuperador.recover(venue, ahora).liberadas).isEqualTo(1)

        // La fila DESCARTADA sigue en la recuperación (S6 puede traer dinero tardío), pero ya no hay duda que cerrar.
        val segunda = recuperador.recover(venue, ahora + LedgerServerRecovery.ESPACIADO_TOPE_MS + 1)

        assertThat(segunda.liberadas).isEqualTo(0)
        assertThat(dao.getById("a-4")!!.state).isEqualTo("DESCARTADA")
        coVerify(exactly = 1) { api.getRequestStatus(venue, "req-a-4") }
    }

    // ── La venta de la tablet completa: bandeja, cerca y libreta ───────────────────────────────────────────────────

    private val paxEmbebida = MarcaDelBuild(procesador = "BLUMON", sdkAngelPayCompilado = false, respaldoAppAAppCompilado = false)

    /** Una libreta = un proceso (su propio `process_token`), con la marca de la PAX fija. */
    private fun libretaDeLaPax() = PaymentAttemptLedger(
        dao,
        mockk<TpvSettingsRepository>(relaxed = true),
        PilaDePantallas { listOf(TareaDeLaApp(pantallas = 1, arribaLaPrincipal = true)) },
    ).apply { marcaDelBuild = paxEmbebida }

    private fun solicitudDelPos(requestId: String) = SocketEvent.TerminalPaymentRequest(
        requestId = requestId, amountCents = 10_000, tipCents = 0, rating = null, skipReview = true, orderId = null,
        processedByStaffId = "staff-1", senderDeviceName = "POS", venueId = venue, timestamp = "2026-10-20T19:00:00Z",
    )

    /**
     * La venta de la tablet que la PAX dejó a medias, hasta que la sonda la CIERRA para ejecución (B1/B2): el proceso 1 reclama la
     * solicitud y entra al lector; muere; el proceso 2 contesta la sonda con el cese certificado. Devuelve la libreta del proceso 2.
     * [caja] = el turno de Caja de verdad (sesión MANAGED); sin él, la frontera legacy de pruebas (sin Caja).
     */
    private suspend fun ventaDeLaTabletDeLaPax(requestId: String, attemptId: String, caja: ShiftRepository? = null): PaymentAttemptLedger {
        val frontera = caja ?: com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture()
        val libreta1 = libretaDeLaPax()
        val bandeja1 = RemotePaymentInbox(db.remotePaymentRequestDao(), frontera, libreta1, PaymentStateHolder())
        assertThat(bandeja1.receive(solicitudDelPos(requestId))).isInstanceOf(RemotePaymentReceiveDecision.Deliver::class.java)
        assertThat(RemotePaymentCoordinator(bandeja1).prepareSocketPaymentRequest(requestId, { true }, { venue }))
            .isEqualTo(RemotePaymentAdmission.READY)
        val contexto = """{"terminalPaymentRequestId":"$requestId"}"""
        val reservado = if (caja == null) libreta1.openAttempt(attemptId, venue, "BLUMON", 10_000, 0, "FAST", contexto)
            else libreta1.openAttemptForShift(caja, attemptId, venue, "BLUMON", 10_000, 0, "FAST", contexto)
        assertThat(reservado).isTrue()
        assertThat(libreta1.markAuthorizing(attemptId)).isTrue() // la app muere con el lector dentro

        val libreta2 = libretaDeLaPax()
        val sonda = RemotePaymentCoordinator(RemotePaymentInbox(db.remotePaymentRequestDao(), frontera, libreta2, PaymentStateHolder()))
            .probeSocketPaymentRequest(requestId, venue)
        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(sonda.executionAlive).isFalse()
        assertThat(sonda.closedForExecution).isTrue()
        return libreta2
    }

    @Test fun `P1 con la bandeja real se apaga el aviso, la cerca de la solicitud sigue y el POS cobra con una solicitud nueva`() = runTest {
        val libreta = ventaDeLaTabletDeLaPax("req-pax", "a-pax")
        coEvery { api.getAttemptStatus(venue, "a-pax") } returns s6(s6Literal("a-pax"))
        coEvery { api.getRequestStatus(venue, "req-pax") } returns porSolicitud(getLiteral("req-pax"))

        val r = LedgerServerRecovery(dao, libreta, api).recover(venue, System.currentTimeMillis()) // la venta nació con el reloj real

        assertThat(r.liberadas).isEqualTo(1)
        val fila = dao.getById("a-pax")!!
        assertThat(fila.state).isEqualTo("DESCARTADA")
        assertThat(fila.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
        assertThat(dao.observeUnresolvedCount(venue).first()).isEqualTo(0) // el aviso desaparece
        // Liberar la VENTA no reabre la SOLICITUD: la bandeja sigue como estaba (PROCESSING y cerrada para ejecución), sin
        // inventarle un final, y ESA solicitud no puede volver a ejecutarse.
        val bandeja = db.remotePaymentRequestDao().getById("req-pax")!!
        assertThat(bandeja.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(bandeja.executionClosedAt).isNotNull()
        assertThat(bandeja.finalResultJson).isNull()
        assertThat(libreta.cercaDeSolicitud("req-pax")).isEqualTo(CercaDeSolicitud.CERRADA)
        assertThat(libreta.openAttempt("a-otra-vez", venue, "BLUMON", 10_000, 0, "FAST", """{"terminalPaymentRequestId":"req-pax"}""")).isFalse()
        // …y la terminal sigue lista para una venta nueva del POS.
        assertThat(libreta.openAttempt("a-nueva", venue, "BLUMON", 5_000, 0, "FAST", """{"terminalPaymentRequestId":"req-nueva"}""")).isTrue()
    }

    // ── Caja: tras la declaración, la solicitud cerrada para ejecución ya no cuenta como una ejecución pendiente ───────
    //
    // Codex final-r1 (LA-PROMESA-LO-NECESITA): D12 descarta el intento, pero la bandeja conserva PROCESSING y su cerca A PROPÓSITO.
    // La rama `REMOTE` de `SQL_SHIFT_OBLIGATIONS` la seguía contando (su NOT EXISTS sólo excluye intentos activos o con dinero) y
    // `guardarCierreLocal` no dejaba cerrar el turno. Una solicitud CERRADA PARA EJECUCIÓN ya no puede arrancar nada: lo que tenga
    // dinero o siga en duda lo cuenta la rama `ATTEMPT`. Aquí el turno es una sesión MANAGED de verdad (Room, autoridad y
    // transacción de producto; sólo el transporte es de mentira), no la frontera legacy del resto de la clase.

    private val storage = mockk<SecureStorage>(relaxed = true)

    private suspend fun cajaAbierta(): Pair<ShiftRepository, LocalShiftSessionEntity> {
        val shifts = task5RoomShiftRepository(db, storage, mockk<ApiService>(), venue, "staff-a", enabled = true)
        return shifts to (shifts.guardarAperturaLocal(venue, "staff-a", BigDecimal("100.00")) as AppResult.Success).data
    }

    @Test fun `P1 Caja MANAGED real, tras la declaracion del cajero la solicitud cerrada ya no cuenta y el turno se deja cerrar`() = runTest {
        val (shifts, sesion) = cajaAbierta()
        val libreta = ventaDeLaTabletDeLaPax("req-caja", "a-caja", caja = shifts)
        val turno = db.localShiftSessionDao()
        coEvery { api.getAttemptStatus(venue, "a-caja") } returns s6(s6Literal("a-caja"))
        coEvery { api.getRequestStatus(venue, "req-caja") } returns porSolicitud(getRanuraSoltada("req-caja"))
        val recuperador = LedgerServerRecovery(dao, libreta, api)
        val t0 = System.currentTimeMillis()

        // 1. Antes de la declaración la duda es del INTENTO (INDETERMINADO): Caja no deja cerrar el turno.
        assertThat(recuperador.recover(venue, t0).liberadas).isEqualTo(0)
        assertThat(dao.getById("a-caja")!!.state).isEqualTo("INDETERMINADO")
        assertThat(turno.intentosSinRepresentar(venue, sesion.localSessionId)).isEqualTo(1)
        assertThat(shifts.guardarCierreLocal(venue, sesion.localSessionId)).isInstanceOf(AppResult.Error::class.java)

        // 2. El cajero declara en el POS: D12 descarta el intento; la solicitud sigue PROCESSING y cerrada para ejecución.
        coEvery { api.getRequestStatus(venue, "req-caja") } returns porSolicitud(getLiteral("req-caja"))
        assertThat(recuperador.recover(venue, t0 + LedgerServerRecovery.ESPACIADO_TOPE_MS + 1).liberadas).isEqualTo(1)
        assertThat(dao.getById("a-caja")!!.state).isEqualTo("DESCARTADA")
        val bandeja = db.remotePaymentRequestDao().getById("req-caja")!!
        assertThat(bandeja.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        assertThat(bandeja.executionClosedAt).isNotNull()
        assertThat(bandeja.finalResultJson).isNull()
        assertThat(bandeja.shiftMembership).isEqualTo("MANAGED")
        assertThat(bandeja.localShiftSessionId).isEqualTo(sesion.localSessionId) // es de ESTE turno

        // …y Caja ya no la cuenta: ni como ejecución remota ni como intento, y el turno se deja cerrar.
        assertThat(turno.ejecucionesRemotas(venue, sesion.localSessionId)).isEqualTo(0)
        assertThat(turno.intentosSinRepresentar(venue, sesion.localSessionId)).isEqualTo(0)
        assertThat(shifts.obligacionesDelCierreLocal(venue, sesion.localSessionId).rows).isEmpty()
        assertThat(shifts.guardarCierreLocal(venue, sesion.localSessionId)).isInstanceOf(AppResult.Success::class.java)
    }

    @Test fun `P1 Caja MANAGED real, una solicitud PROCESSING sin cerrar y sin intentos sigue bloqueando el turno`() = runTest {
        val (shifts, sesion) = cajaAbierta()
        val bandeja = RemotePaymentInbox(db.remotePaymentRequestDao(), shifts, libretaDeLaPax(), PaymentStateHolder())
        assertThat(bandeja.receive(solicitudDelPos("req-viva"))).isInstanceOf(RemotePaymentReceiveDecision.Deliver::class.java)
        assertThat(RemotePaymentCoordinator(bandeja).prepareSocketPaymentRequest("req-viva", { true }, { venue }))
            .isEqualTo(RemotePaymentAdmission.READY)
        assertThat(db.remotePaymentRequestDao().getById("req-viva")!!.executionClosedAt).isNull() // todavía puede arrancar un cobro

        assertThat(db.localShiftSessionDao().ejecucionesRemotas(venue, sesion.localSessionId)).isEqualTo(1)
        assertThat(shifts.guardarCierreLocal(venue, sesion.localSessionId)).isInstanceOf(AppResult.Error::class.java)
    }

    @Test fun `P1 Caja MANAGED real, cerrada para ejecucion pero con su intento aun en duda sigue bloqueando el turno`() = runTest {
        val (shifts, sesion) = cajaAbierta()
        val libreta = ventaDeLaTabletDeLaPax("req-duda", "a-duda", caja = shifts)
        libreta.cuarentenaDeHuerfanos() // lo que dejó el proceso muerto pasa a «en duda»
        assertThat(dao.getById("a-duda")!!.state).isEqualTo("INDETERMINADO")
        assertThat(db.remotePaymentRequestDao().getById("req-duda")!!.executionClosedAt).isNotNull()

        assertThat(db.localShiftSessionDao().intentosSinRepresentar(venue, sesion.localSessionId)).isEqualTo(1)
        assertThat(shifts.guardarCierreLocal(venue, sesion.localSessionId)).isInstanceOf(AppResult.Error::class.java)
    }

    @Test fun `P1 Caja MANAGED real, cerrada con el intento descartado pero con dinero o veto del servidor sigue bloqueando el turno`() = runTest {
        val (shifts, sesion) = cajaAbierta()
        val libreta = ventaDeLaTabletDeLaPax("req-dinero", "a-dinero", caja = shifts)
        libreta.cuarentenaDeHuerfanos()
        val turno = db.localShiftSessionDao()
        val fallos = mutableListOf<String>()
        listOf(
            "aprobado del host" to "host_approved = 1",
            "aprobacion del banco" to "server_processor_evidence = 'APPROVED'",
            "veto del servidor" to "server_veto = 'PAYMENT_CONTRADICTION'",
        ).forEach { (nombre, marca) ->
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE payment_attempts SET state = 'DESCARTADA', host_approved = NULL, server_processor_evidence = NULL, server_veto = NULL WHERE attempt_id = 'a-dinero'")
            sql.execSQL("UPDATE payment_attempts SET $marca WHERE attempt_id = 'a-dinero'")
            if (turno.intentosSinRepresentar(venue, sesion.localSessionId) != 1) fallos += "«$nombre»: Caja ya no cuenta el intento descartado"
            if (shifts.guardarCierreLocal(venue, sesion.localSessionId) !is AppResult.Error) fallos += "«$nombre»: el turno se dejó cerrar"
        }
        assertWithMessage("descartadas con dinero o veto que debían seguir bloqueando el turno").that(fallos).isEmpty()
    }

    // ── D3 (QA 2-nov): al intentar cerrar el turno, primero se PREGUNTA ─────────────────────────────────────────────────
    //
    // Tras declarar «no se cobró» en el POS, la duda seguía en la terminal hasta una pasada del worker que nadie dispara: el
    // re-consulto se espacia 10 min × 2^consultas (medido: 23 min en la PAX). Caja decía «Validation error: … Consúltala» y no había
    // dónde consultar. El cierre hace lo que hace «Consultar»: pregunta sin gastar el turno, y vuelve a contar.
    //
    // `runBlocking` y no `runTest`: la consulta lleva un tope de reloj y Room corre en sus propios hilos. Con el reloj virtual, el tope
    // vencería en cuanto el cuerpo esperara a Room. (El tope se prueba aparte, sin Room, en `ConsultaDelCierreDeCajaTest`.)

    private var consulta: ConsultaDelCierreDeCaja? = null
    private var consultasDelCierre = 0
    private val socket = mockk<SocketManager>(relaxed = true)

    private suspend fun cajaQuePregunta(alTerminarDePreguntar: () -> Unit = {}): Pair<ShiftRepository, LocalShiftSessionEntity> {
        val shifts = task5RoomShiftRepository(db, storage, mockk<ApiService>(), venue, "staff-a", enabled = true) { venueId, pendientes ->
            consultasDelCierre++
            consulta!!.preguntar(venueId, pendientes).also { alTerminarDePreguntar() }
        }
        return shifts to (shifts.guardarAperturaLocal(venue, "staff-a", BigDecimal("100.00")) as AppResult.Success).data
    }

    private fun preguntaCon(libreta: PaymentAttemptLedger) {
        consulta = ConsultaDelCierreDeCaja(
            LedgerServerRecovery(dao, libreta, api),
            BandejaServerRecovery(db.remotePaymentRequestDao(), api),
            dagger.Lazy { socket },
        )
    }

    private fun AppResult<*>.mensaje() = (this as AppResult.Error).exception.userMessage

    @Test fun `D3 P1 tras declarar en el POS el cierre pregunta, apaga la duda y la caja se cierra, aunque a la fila no le toque turno`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaQuePregunta()
        val libreta = ventaDeLaTabletDeLaPax("req-d3", "a-d3", caja = shifts)
        preguntaCon(libreta)
        coEvery { api.getAttemptStatus(venue, "a-d3") } returns s6(s6Literal("a-d3"))
        coEvery { api.getRequestStatus(venue, "req-d3") } returns porSolicitud(getRanuraSoltada("req-d3"))
        val recuperador = LedgerServerRecovery(dao, libreta, api)
        val t0 = System.currentTimeMillis()

        // La pasada del worker preguntó antes de la declaración y gastó el turno de la fila…
        assertThat(recuperador.recover(venue, t0).liberadas).isEqualTo(0)
        assertThat(dao.getById("a-d3")!!.serverCheckCount).isEqualTo(1)
        // …el cajero declara en el POS, y otra pasada dentro del espaciado ni pregunta: lo que se midió en la PAX (23 min).
        coEvery { api.getRequestStatus(venue, "req-d3") } returns porSolicitud(getLiteral("req-d3"))
        assertThat(recuperador.recover(venue, t0 + 60_000).liberadas).isEqualTo(0)
        assertThat(dao.getById("a-d3")!!.state).isEqualTo("INDETERMINADO")

        val cierre = shifts.closeShift(venue, sesion.localSessionId)

        assertThat(cierre).isInstanceOf(AppResult.Success::class.java)
        assertThat(db.localShiftSessionDao().leer(venue, sesion.localSessionId)!!.localStatus).isEqualTo("CLOSED")
        val fila = dao.getById("a-d3")!!
        assertThat(fila.state).isEqualTo("DESCARTADA")
        assertThat(fila.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
        assertThat(fila.serverCheckCount).isEqualTo(1) // como «Consultar»: no gasta el turno ni inventa otro espaciado
    }

    @Test fun `D3 P1 sin declaracion no cierra, y el mensaje dice en español qué falta y qué hacer`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaQuePregunta()
        val libreta = ventaDeLaTabletDeLaPax("req-d3b", "a-d3b", caja = shifts)
        libreta.cuarentenaDeHuerfanos()
        preguntaCon(libreta)
        coEvery { api.getAttemptStatus(venue, "a-d3b") } returns s6(s6Literal("a-d3b"))
        coEvery { api.getRequestStatus(venue, "req-d3b") } returns porSolicitud(getRanuraSoltada("req-d3b"))

        val mensaje = shifts.closeShift(venue, sesion.localSessionId).mensaje()

        assertThat(mensaje).doesNotContain("Validation error")
        assertThat(mensaje).contains("1 operación con tarjeta")
        assertThat(mensaje).contains("POS") // dónde se resuelve
        assertThat(mensaje).doesNotContain("Sin conexión") // Avoqado sí contestó
        coVerify(exactly = 1) { api.getAttemptStatus(venue, "a-d3b") }
        val turno = db.localShiftSessionDao().leer(venue, sesion.localSessionId)!!
        assertThat(turno.localStatus).isEqualTo("OPEN")
        assertThat(turno.closeOperationId).isNull()
        assertThat(dao.getById("a-d3b")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `D3 P1 sin red no cierra y lo dice con todas sus letras`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaQuePregunta()
        val libreta = ventaDeLaTabletDeLaPax("req-d3c", "a-d3c", caja = shifts)
        libreta.cuarentenaDeHuerfanos()
        preguntaCon(libreta)
        coEvery { api.getAttemptStatus(venue, "a-d3c") } throws java.io.IOException("sin red")

        val mensaje = shifts.closeShift(venue, sesion.localSessionId).mensaje()

        assertThat(mensaje).contains("Sin conexión")
        assertThat(mensaje).contains("1 operación con tarjeta")
        assertThat(mensaje).doesNotContain("Validation error")
        assertThat(db.localShiftSessionDao().leer(venue, sesion.localSessionId)!!.localStatus).isEqualTo("OPEN")
        assertThat(dao.getById("a-d3c")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `D3 P1 sin nada pendiente no le pregunta nada a nadie y cierra como siempre`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaQuePregunta()

        assertThat(shifts.closeShift(venue, sesion.localSessionId)).isInstanceOf(AppResult.Success::class.java)

        assertThat(consultasDelCierre).isEqualTo(0)
    }

    @Test fun `D3 P1 lo demas que el cierre espera no dispara la consulta y sigue sin bloquear al guardar`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaQuePregunta()
        db.localCryptoOperationDao().insertar(
            com.jaac.avoqado_tpv.core.data.local.entity.LocalCryptoOperationEntity(
                idempotencyKey = "c000000000000000000000099", venueId = venue, staffId = "staff-a", requestJson = "{}",
                shiftMembership = "MANAGED", localShiftSessionId = sesion.localSessionId, createdAt = 1, updatedAt = 1,
            ),
        )
        assertThat(db.localShiftSessionDao().contarObligaciones(venue, sesion.localSessionId)).isEqualTo(1)

        assertThat(shifts.closeShift(venue, sesion.localSessionId)).isInstanceOf(AppResult.Success::class.java)

        assertThat(consultasDelCierre).isEqualTo(0)
    }

    @Test fun `D3 P1 un cobro del POS que el servidor ya resolvio se concilia y deja cerrar`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaQuePregunta()
        val libreta = libretaDeLaPax()
        preguntaCon(libreta)
        val bandeja = RemotePaymentInbox(db.remotePaymentRequestDao(), shifts, libreta, PaymentStateHolder())
        assertThat(bandeja.receive(solicitudDelPos("req-d3d"))).isInstanceOf(RemotePaymentReceiveDecision.Deliver::class.java)
        assertThat(RemotePaymentCoordinator(bandeja).prepareSocketPaymentRequest("req-d3d", { true }, { venue })).isEqualTo(RemotePaymentAdmission.READY)
        assertThat(db.localShiftSessionDao().ejecucionesRemotas(venue, sesion.localSessionId)).isEqualTo(1)
        coEvery { api.getRequestStatus(venue, "req-d3d") } returns porSolicitud(getLiteral("req-d3d"))

        assertThat(shifts.closeShift(venue, sesion.localSessionId)).isInstanceOf(AppResult.Success::class.java)

        assertThat(db.remotePaymentRequestDao().getById("req-d3d")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
    }

    @Test fun `D3 P1 un cobro del POS que el servidor no ha resuelto sigue bloqueando, y lo nombra`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaQuePregunta()
        val libreta = libretaDeLaPax()
        preguntaCon(libreta)
        val bandeja = RemotePaymentInbox(db.remotePaymentRequestDao(), shifts, libreta, PaymentStateHolder())
        assertThat(bandeja.receive(solicitudDelPos("req-d3e"))).isInstanceOf(RemotePaymentReceiveDecision.Deliver::class.java)
        assertThat(RemotePaymentCoordinator(bandeja).prepareSocketPaymentRequest("req-d3e", { true }, { venue })).isEqualTo(RemotePaymentAdmission.READY)
        coEvery { api.getRequestStatus(venue, "req-d3e") } returns porSolicitud(getRanuraSoltada("req-d3e"))

        val mensaje = shifts.closeShift(venue, sesion.localSessionId).mensaje()

        assertThat(mensaje).contains("1 cobro del POS")
        assertThat(mensaje).doesNotContain("Sin conexión")
        assertThat(db.remotePaymentRequestDao().getById("req-d3e")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
    }

    @Test fun `D3 P1 el dinero manda - si el banco aprobo, preguntar no libera ni deja cerrar`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaQuePregunta()
        val libreta = ventaDeLaTabletDeLaPax("req-d3f", "a-d3f", caja = shifts)
        libreta.cuarentenaDeHuerfanos()
        preguntaCon(libreta)
        coEvery { api.getAttemptStatus(venue, "a-d3f") } returns
            s6(s6Literal("a-d3f").cambia(""""processorEvidence":"NONE"""", """"processorEvidence":"APPROVED""""))

        val mensaje = shifts.closeShift(venue, sesion.localSessionId).mensaje()

        assertThat(mensaje).contains("1 operación con tarjeta")
        val fila = dao.getById("a-d3f")!!
        assertThat(fila.state).isEqualTo("INDETERMINADO")
        assertThat(fila.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        coVerify(exactly = 0) { api.getRequestStatus(any(), any()) }
    }

    @Test fun `D3 P2 lo que no se le pregunta al servidor tampoco se anuncia como falta de conexion`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaQuePregunta()
        val libreta = ventaDeLaTabletDeLaPax("req-d3g", "a-d3g", caja = shifts)
        libreta.cuarentenaDeHuerfanos()
        preguntaCon(libreta)
        // Avoqado ya acreditó este cobro y no se pudo aplicar: `recoverOne` no vuelve a preguntar por él.
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET server_outcome = 'RECORDED', server_payment_id = 'p-1', server_processor_evidence = 'APPROVED', state = 'DESCARTADA' WHERE attempt_id = 'a-d3g'")

        val mensaje = shifts.closeShift(venue, sesion.localSessionId).mensaje()

        coVerify(exactly = 0) { api.getAttemptStatus(any(), any()) }
        assertThat(mensaje).contains("1 operación con tarjeta")
        assertThat(mensaje).doesNotContain("Sin conexión")
    }

    @Test fun `D3 P1 si la sesion cambia mientras se pregunta, el cierre no se guarda a nombre de otro`() = runBlocking<Unit> {
        // Con hasta 5 s de pregunta de por medio, otro cajero pudo entrar con su PIN: el conteo es de quien tocó «Cerrar».
        val (shifts, sesion) = cajaQuePregunta {
            every { storage.getStaffId() } returns "staff-b"
            every { storage.getString("permissions_cache", null) } returns "$venue|staff-b\ntpv-shifts:create,tpv-shifts:close,shifts:read,payments:create"
        }
        val libreta = ventaDeLaTabletDeLaPax("req-d3h", "a-d3h", caja = shifts)
        libreta.cuarentenaDeHuerfanos()
        preguntaCon(libreta)
        coEvery { api.getAttemptStatus(venue, "a-d3h") } returns s6(s6Literal("a-d3h"))
        coEvery { api.getRequestStatus(venue, "req-d3h") } returns porSolicitud(getLiteral("req-d3h"))

        val mensaje = shifts.closeShift(venue, sesion.localSessionId).mensaje()

        assertThat(mensaje).contains("La sesión")
        assertThat(db.localShiftSessionDao().leer(venue, sesion.localSessionId)!!.localStatus).isEqualTo("OPEN")
    }

    @Test fun `D3 la causa del prefijo - un mensaje de Caja anidado ya no sale como Validation error`() = runBlocking<Unit> {
        // Con Caja apagada, `openShift` falla en el MISMO anidamiento que el cierre: `resultadoLocal` dentro de `resultadoLocal`.
        val shifts = task5RoomShiftRepository(db, storage, mockk<ApiService>(), venue, "staff-a", enabled = false)

        val mensaje = shifts.openShift(venue, "staff-a", 100.0).mensaje()

        assertThat(mensaje).isEqualTo("Verifica la sesión y la configuración de Caja.")
    }

    // ── Lo que NO es la declaración de una persona no cierra nada ───────────────────────────────────────────────────

    @Test fun `P1 el GET que no es la declaracion de una persona sobre ESA solicitud no cierra la duda`() = runTest {
        val base = getLiteral("req-base")
        val variantes: List<Pair<String, String>> = listOf(
            "resuelta false" to base.cambia(""""resuelta":true""", """"resuelta":false"""),
            "resuelta ausente" to base.cambia(""","resuelta":true""", ""),
            "resuelta null" to base.cambia(""""resuelta":true""", """"resuelta":null"""),
            "success false" to base.cambia(""""success":true""", """"success":false"""),
            "success ausente" to base.cambia(""""success":true,""", ""),
            "ranura soltada sin desenlace" to getRanuraSoltada("req-base").cambia(""""resuelta":false""", """"resuelta":true"""),
            "silencio del banco" to base.cambia(""""outcomeEvidence":"OPERATOR_RECONCILED"""", """"outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW""""),
            "sin evidencia" to base.cambia(""""outcomeEvidence":"OPERATOR_RECONCILED"""", """"outcomeEvidence":null"""),
            "outcome distinto" to base.cambia(""""outcome":"NOT_CHARGED"""", """"outcome":"COMPLETED""""),
            "request null" to """{"success":true,"requestId":"req-base","request":null,"resuelta":true}""",
            "request que no es objeto" to """{"success":true,"requestId":"req-base","request":"FAILED","resuelta":true}""",
            "identidad de la raiz ajena" to base.cambia("""{"success":true,"requestId":"req-base",""", """{"success":true,"requestId":"req-OTRA","""),
            "identidad de la raiz ausente" to base.cambia("""{"success":true,"requestId":"req-base",""", """{"success":true,"""),
            "identidad de la raiz con espacios" to base.cambia("""{"success":true,"requestId":"req-base",""", """{"success":true,"requestId":" req-base ","""),
            "identidad anidada ajena" to base.cambia("""{"requestId":"req-base","venueId""", """{"requestId":"req-OTRA","venueId"""),
            "identidad anidada ausente" to base.cambia("""{"requestId":"req-base","venueId""", """{"venueId"""),
            "negocio anidado ajeno" to base.cambia(""""venueId":"$venue"""", """"venueId":"venue-2""""),
        )
        val fallos = mutableListOf<String>()
        variantes.forEachIndexed { i, (nombre, json) ->
            val id = "a-v$i"
            duda(id, requestId = "req-base")
            coEvery { api.getAttemptStatus(venue, id) } returns s6(s6Literal(id))
            coEvery { api.getRequestStatus(venue, "req-base") } returns porSolicitud(json)

            val r = recuperacion().recover(venue, ahora)

            val fila = dao.getById(id)!!
            if (!preguntoPorLaSolicitud()) fallos += "«$nombre»: ni siquiera preguntó por la solicitud (la variante no llegó al predicado)"
            if (r.liberadas != 0) fallos += "«$nombre»: LIBERÓ la duda"
            if (fila.state != "INDETERMINADO") fallos += "«$nombre»: la fila ya no está en duda (${fila.state})"
            if (fila.serverCheckedAt != ahora) fallos += "«$nombre»: el servidor contestó y no se gastó el turno"
            olvidar(id)
        }
        assertWithMessage("variantes del GET que NO debían cerrar la duda y la cerraron o se comportaron distinto").that(fallos).isEmpty()
    }

    /** Borra la fila de la base EN MEMORIA de la prueba, para que la siguiente variante del mismo test sólo vea la suya. */
    private fun olvidar(attemptId: String) {
        db.openHelper.writableDatabase.execSQL("DELETE FROM payment_attempts WHERE attempt_id = ?", arrayOf<Any>(attemptId))
    }

    /** ¿Se llamó a `getRequestStatus` desde la última vez que se preguntó? Limpia el registro de llamadas para la variante siguiente. */
    private fun preguntoPorLaSolicitud(): Boolean {
        val pregunto = runCatching { coVerify(exactly = 0) { api.getRequestStatus(any(), any()) } }.isFailure
        io.mockk.clearMocks(api, answers = false, recordedCalls = true)
        return pregunto
    }

    // ── Todo lo que S6 aporta sigue mandando: ni se pregunta por la solicitud ──────────────────────────────────────

    @Test fun `P1 una S6 con dinero, vinculo ajeno o ilegible no pregunta por la solicitud aunque el POS haya declarado`() = runTest {
        // Cada variante se arma para el intento de SU fila: la S6 de OTRO intento ya es motivo para no preguntar y taparía a las demás.
        fun vinculada(id: String, solicitud: String) =
            """{"success":true,"attemptId":"$id","requestId":"$solicitud","attempt":{"attemptId":"$id","outcome":"NOT_RECORDED",""" +
                """"paymentId":null,"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,""" +
                """"unattributedEvidence":false,"linkedAt":"2026-10-20T19:00:01.000Z","resolution":null},""" +
                """"request":{"requestId":"$solicitud","venueId":"$venue","status":"TIMED_OUT","failureCode":"AUTO_RELEASED","outcome":"UNRESOLVED"}}"""
        fun variante(nombre: String, armar: (String) -> Response<TerminalAttemptStatusResponse>) = nombre to armar
        val variantes = listOf(
            variante("Payment PENDING sin outcome") { id ->
                s6(s6Literal(id).cambia(""""paymentId":null,"paymentStatus":null""", """"paymentId":"pay-1","paymentStatus":"PENDING""""))
            },
            variante("banco APPROVED sin Payment") { id -> s6(s6Literal(id).cambia(""""processorEvidence":"NONE"""", """"processorEvidence":"APPROVED"""")) },
            variante("colision de referencia") { id ->
                s6(s6Literal(id).cambia(""""outcome":"NOT_RECORDED","paymentId":null""", """"outcome":"REFERENCE_COLLISION_EVIDENCE","paymentId":"pay-2""""))
            },
            variante("RECORDED con Payment") { id ->
                s6(
                    s6Literal(id).cambia(
                        """"outcome":"NOT_RECORDED","paymentId":null,"paymentStatus":null,"recordedVia":null,"amountCents":null,"tipCents":null,"isWinner":false""",
                        """"outcome":"RECORDED","paymentId":"pay-3","paymentStatus":"COMPLETED","recordedVia":"webhook","amountCents":10000,"tipCents":0,"isWinner":true""",
                    ),
                )
            },
            variante("evidencia sin dueno") { id -> s6(s6Literal(id).cambia(""""unattributedEvidence":false""", """"unattributedEvidence":true""")) },
            variante("pago no atribuible") { id -> s6(s6Literal(id).cambia(""""paymentContradiction":false""", """"paymentContradiction":true""")) },
            variante("evidencia de otra terminal") { id -> s6(s6Literal(id).cambia(""""evidenceContradiction":false""", """"evidenceContradiction":true""")) },
            variante("success false") { id -> s6(s6Literal(id).cambia(""""success":true""", """"success":false""")) },
            variante("success ausente") { id -> s6(s6Literal(id).cambia(""""success":true,""", "")) },
            variante("intento de la raiz ajeno") { id -> s6(s6Literal(id).cambia("""{"success":true,"attemptId":"$id",""", """{"success":true,"attemptId":"OTRO",""")) },
            variante("intento anidado ajeno") { id -> s6(s6Literal(id).cambia("""{"attemptId":"$id","outcome""", """{"attemptId":"OTRO","outcome""")) },
            variante("ligada a la misma solicitud (la ruta de siempre)") { id -> s6(vinculada(id, "req-base")) },
            variante("ligada a OTRA solicitud") { id -> s6(vinculada(id, "req-OTRA")) },
            variante("requestId sin request") { id -> s6(s6Literal(id).cambia(""""requestId":null,"attempt":""", """"requestId":"req-base","attempt":""")) },
            variante("request sin requestId") { id ->
                s6(s6Literal(id).cambia(""""request":null}""", """"request":{"requestId":"req-base","outcome":"UNRESOLVED"}}"""))
            },
            variante("HTTP 404") { httpS6(404) },
            variante("HTTP 503") { httpS6(503) },
        )
        val fallos = mutableListOf<String>()
        variantes.forEachIndexed { i, (nombre, armar) ->
            val id = "a-s$i"
            duda(id, requestId = "req-base")
            coEvery { api.getAttemptStatus(venue, id) } returns armar(id)
            coEvery { api.getRequestStatus(any(), any()) } returns porSolicitud(getLiteral("req-base"))

            val r = recuperacion().recover(venue, ahora)

            if (preguntoPorLaSolicitud()) fallos += "«$nombre»: PREGUNTÓ por la solicitud aunque S6 no lo permitía"
            if (r.liberadas != 0) fallos += "«$nombre»: LIBERÓ la duda"
            if (dao.getById(id)!!.state == "DESCARTADA") fallos += "«$nombre»: la duda se cerró por la solicitud"
            olvidar(id)
        }
        assertWithMessage("variantes de S6 que NO debían dejar preguntar por la solicitud").that(fallos).isEmpty()
    }

    // ── La identidad sale del contexto durable DE ESA fila ──────────────────────────────────────────────────────────

    @Test fun `P1 sin una solicitud durable que coincida en columna y contexto no se pregunta por ninguna`() = runTest {
        val casos: List<Triple<String, String, suspend (String) -> Unit>> = listOf(
            Triple("cobro local sin solicitud", "a-loc", { id -> duda(id, requestId = null) }),
            Triple("solo la columna", "a-col", { id -> duda(id, requestId = "req-base", contexto = """{"amount":100.00}""") }),
            Triple("solo el contexto", "a-ctx", { id -> duda(id, requestId = null, contexto = """{"terminalPaymentRequestId":"req-base"}""") }),
            Triple("columna y contexto distintos", "a-dis", { id -> duda(id, requestId = "req-base", contexto = """{"terminalPaymentRequestId":"req-OTRA"}""") }),
            Triple("contexto ilegible", "a-ile", { id -> duda(id, requestId = "req-base", contexto = "no es json") }),
            Triple("solicitud en blanco", "a-bla", { id -> duda(id, requestId = " ", contexto = """{"terminalPaymentRequestId":" "}""") }),
            Triple("la bandeja es de otro negocio", "a-neg", { id -> duda(id, requestId = "req-base"); bandeja("req-base", venueDeLaBandeja = "venue-2") }),
        )
        val fallos = mutableListOf<String>()
        for ((nombre, id, preparar) in casos) {
            preparar(id)
            coEvery { api.getAttemptStatus(venue, id) } returns s6(s6Literal(id))
            coEvery { api.getRequestStatus(any(), any()) } returns porSolicitud(getLiteral("req-base"))

            val r = recuperacion().recover(venue, ahora)

            if (preguntoPorLaSolicitud()) fallos += "«$nombre»: PREGUNTÓ por una solicitud sin identidad durable completa"
            if (r.liberadas != 0) fallos += "«$nombre»: LIBERÓ la duda"
            if (dao.getById(id)!!.state != "INDETERMINADO") fallos += "«$nombre»: la duda ya no está"
            olvidar(id)
        }
        assertWithMessage("casos sin una solicitud durable que debían quedarse como estaban").that(fallos).isEmpty()
    }

    @Test fun `P1 un cobro de la Nexgo ligado sigue por la ruta de S6 y no pregunta por la solicitud`() = runTest {
        duda("a-nx", processor = "ANGELPAY")
        val ligada = """{"success":true,"attemptId":"a-nx","requestId":"req-a-nx","attempt":{"attemptId":"a-nx","outcome":"NOT_RECORDED",""" +
            """"paymentId":null,"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,""" +
            """"unattributedEvidence":false,"linkedAt":"2026-10-20T19:00:01.000Z","resolution":null},""" +
            """"request":{"requestId":"req-a-nx","venueId":"$venue","status":"FAILED","failureCode":"OPERATOR_RECONCILED_NO_CHARGE",""" +
            """"outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}}"""
        coEvery { api.getAttemptStatus(venue, "a-nx") } returns s6(ligada)

        val r = recuperacion().recover(venue, ahora)

        assertThat(r.liberadas).isEqualTo(1)
        assertThat(dao.getById("a-nx")!!.state).isEqualTo("DESCARTADA")
        coVerify(exactly = 0) { api.getRequestStatus(any(), any()) }
    }

    // ── Un GET que falla conserva la duda ───────────────────────────────────────────────────────────────────────────

    @Test fun `P1 el GET con 404, 401, 403, 500 o 503 conserva la duda y gasta el turno`() = runTest {
        val fallos = mutableListOf<String>()
        listOf(404, 401, 403, 500, 503).forEachIndexed { i, codigo ->
            val id = "a-h$i"
            duda(id)
            coEvery { api.getAttemptStatus(venue, id) } returns s6(s6Literal(id))
            coEvery { api.getRequestStatus(venue, "req-$id") } returns httpPorSolicitud(codigo)

            val r = recuperacion().recover(venue, ahora)

            val fila = dao.getById(id)!!
            if (r.liberadas != 0) fallos += "HTTP $codigo: LIBERÓ la duda"
            if (fila.state != "INDETERMINADO") fallos += "HTTP $codigo: la duda ya no está (${fila.state})"
            if (fila.serverCheckedAt != ahora) fallos += "HTTP $codigo: el servidor contestó y no se gastó el turno"
            if (r.sinRespuesta != 0) fallos += "HTTP $codigo: se contó como «sin respuesta»"
            olvidar(id)
        }
        assertWithMessage("códigos HTTP del GET que debían conservar la duda y gastar el turno").that(fallos).isEmpty()
    }

    @Test fun `P1 una respuesta ilegible del GET conserva la duda sin tumbar la pasada`() = runTest {
        duda("a-il")
        duda("a-ok", requestId = "req-ok")
        coEvery { api.getAttemptStatus(venue, "a-il") } returns s6(s6Literal("a-il"))
        coEvery { api.getAttemptStatus(venue, "a-ok") } returns s6(s6Literal("a-ok"))
        coEvery { api.getRequestStatus(venue, "req-a-il") } throws com.google.gson.JsonSyntaxException("ilegible")
        coEvery { api.getRequestStatus(venue, "req-ok") } returns porSolicitud(getLiteral("req-ok"))

        val r = recuperacion().recover(venue, ahora)

        assertThat(dao.getById("a-il")!!.state).isEqualTo("INDETERMINADO")
        // La ilegible no se lleva a la otra: la pasada siguió.
        assertThat(dao.getById("a-ok")!!.state).isEqualTo("DESCARTADA")
        assertThat(r.liberadas).isEqualTo(1)
    }

    @Test fun `P1 sin respuesta del GET (red cortada) conserva la duda, NO gasta el turno y la pasada siguiente la cierra`() = runTest {
        duda("a-red")
        coEvery { api.getAttemptStatus(venue, "a-red") } returns s6(s6Literal("a-red"))
        coEvery { api.getRequestStatus(venue, "req-a-red") } throws java.io.IOException("sin red") andThen porSolicitud(getLiteral("req-a-red"))
        val recuperador = recuperacion()

        val primera = recuperador.recover(venue, ahora)

        assertThat(primera.liberadas).isEqualTo(0)
        assertThat(primera.sinRespuesta).isEqualTo(1) // el worker se repite en 31 s
        val fila = dao.getById("a-red")!!
        assertThat(fila.state).isEqualTo("INDETERMINADO")
        assertThat(fila.serverCheckedAt).isNull() // el fallo del GET no es un GET negativo ni consume el turno
        assertThat(fila.serverAnsweredAt).isEqualTo(ahora) // S6 sí contestó

        val segunda = recuperador.recover(venue, ahora + 31_000)

        assertThat(segunda.liberadas).isEqualTo(1)
        assertThat(dao.getById("a-red")!!.state).isEqualTo("DESCARTADA")
    }

    @Test fun `P1 sin respuesta del GET (tope de tiempo) tampoco gasta el turno`() = runTest {
        duda("a-tope")
        coEvery { api.getAttemptStatus(venue, "a-tope") } returns s6(s6Literal("a-tope"))
        coEvery { api.getRequestStatus(venue, "req-a-tope") } coAnswers {
            delay(LedgerServerRecovery.CONSULTA_TIMEOUT_MS + 1_000)
            porSolicitud(getLiteral("req-a-tope"))
        }

        val r = recuperacion().recover(venue, ahora)

        assertThat(r.liberadas).isEqualTo(0)
        assertThat(r.sinRespuesta).isEqualTo(1)
        assertThat(dao.getById("a-tope")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("a-tope")!!.serverCheckedAt).isNull()
    }

    @Test fun `P1 la consulta interactiva sin respuesta del GET no gasta el turno aunque estampe`() = runTest {
        duda("a-ri")
        coEvery { api.getAttemptStatus(venue, "a-ri") } returns s6(s6Literal("a-ri"))
        coEvery { api.getRequestStatus(venue, "req-a-ri") } throws java.io.IOException("sin red")

        val lectura = recuperacion().recoverOne(venue, "a-ri", ahora) // estampar = true (el trigger y el worker)

        assertThat(lectura.servidorContesto).isTrue() // S6 sí contestó: la lectura no se pierde por el fallo del GET
        assertThat(dao.getById("a-ri")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("a-ri")!!.serverCheckedAt).isNull()
    }

    // ── El dinero manda: llega entre el GET y el CAS, o ya estaba guardado ───────────────────────────────────────────

    @Test fun `P1 la evidencia del banco que llega entre el GET y el CAS gana, el CAS pierde y la duda queda como contradiccion`() = runTest {
        duda("a-ev")
        coEvery { api.getAttemptStatus(venue, "a-ev") } returns s6(s6Literal("a-ev"))
        coEvery { api.getRequestStatus(venue, "req-a-ev") } coAnswers {
            // mientras el GET viaja, el aviso del banco de ESTE intento se escribe en la fila
            dao.marcarEvidenciaPositivaDelServidor("a-ev", venue, ahora)
            porSolicitud(getLiteral("req-a-ev"))
        }

        val r = recuperacion().recover(venue, ahora)

        assertThat(r.liberadas).isEqualTo(0)
        val fila = dao.getById("a-ev")!!
        assertThat(fila.state).isEqualTo("INDETERMINADO")
        assertThat(fila.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED) // no se borra
    }

    @Test fun `P1 el veto del servidor que llega entre el GET y el CAS gana y no se borra`() = runTest {
        duda("a-vt")
        coEvery { api.getAttemptStatus(venue, "a-vt") } returns s6(s6Literal("a-vt"))
        coEvery { api.getRequestStatus(venue, "req-a-vt") } coAnswers {
            dao.marcarVetoDelServidor("a-vt", venue, PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION, ahora)
            porSolicitud(getLiteral("req-a-vt"))
        }

        val r = recuperacion().recover(venue, ahora)

        assertThat(r.liberadas).isEqualTo(0)
        val fila = dao.getById("a-vt")!!
        assertThat(fila.state).isEqualTo("INDETERMINADO")
        assertThat(fila.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
    }

    @Test fun `P1 el aprobado del host que ya estaba en la fila impide el cierre`() = runTest {
        duda("a-ho", hostApproved = true)
        coEvery { api.getAttemptStatus(venue, "a-ho") } returns s6(s6Literal("a-ho"))
        coEvery { api.getRequestStatus(venue, "req-a-ho") } returns porSolicitud(getLiteral("req-a-ho"))

        val r = recuperacion().recover(venue, ahora)

        assertThat(r.liberadas).isEqualTo(0)
        assertThat(dao.getById("a-ho")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `P1 un veto durable de una pasada anterior sigue impidiendo el cierre despues de reiniciar`() = runTest {
        duda("a-vd")
        // Pasada 1 (proceso 1): S6 trae evidencia sin dueño; se vuelve veto DURABLE.
        coEvery { api.getAttemptStatus(venue, "a-vd") } returns s6(s6Literal("a-vd").cambia(""""unattributedEvidence":false""", """"unattributedEvidence":true"""))
        recuperacion().recover(venue, ahora)
        assertThat(dao.getById("a-vd")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE)

        // Pasada 2 (proceso 2, todo nuevo): S6 viene limpia y el POS ya declaró.
        coEvery { api.getAttemptStatus(venue, "a-vd") } returns s6(s6Literal("a-vd"))
        coEvery { api.getRequestStatus(venue, "req-a-vd") } returns porSolicitud(getLiteral("req-a-vd"))
        val r = LedgerServerRecovery(dao, PaymentAttemptLedger(dao, mockk(relaxed = true)), api)
            .recover(venue, ahora + LedgerServerRecovery.ESPACIADO_TOPE_MS + 1)

        assertThat(r.liberadas).isEqualTo(0)
        assertThat(dao.getById("a-vd")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("a-vd")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE)
    }

    @Test fun `P1 el veto de S6 que no se pudo guardar sigue impidiendo preguntar por la solicitud (recoverOne)`() = runTest {
        // I-1 de la revisión: sin la guarda del CUERPO de S6 no hay otra capa. El veto de ESTA respuesta no se escribe (falla el
        // guardado, y esa escritura no deja nada en memoria), así que ni el prefiltro de la fila ni el CAS lo pueden ver.
        duda("a-vf")
        val sinDisco = spyk(ledger)
        coEvery { sinDisco.marcarVetoDelServidor(any(), any(), any(), any()) } returns Result.failure(java.io.IOException("disco lleno"))
        coEvery { api.getAttemptStatus(venue, "a-vf") } returns
            s6(s6Literal("a-vf").cambia(""""unattributedEvidence":false""", """"unattributedEvidence":true"""))
        coEvery { api.getRequestStatus(venue, "req-a-vf") } returns porSolicitud(getLiteral("req-a-vf")) // el POS ya declaró

        val lectura = LedgerServerRecovery(dao, sinDisco, api).recoverOne(venue, "a-vf", ahora, estampar = false)

        coVerify(exactly = 0) { api.getRequestStatus(any(), any()) }
        val fila = dao.getById("a-vf")!!
        assertThat(fila.state).isEqualTo("INDETERMINADO")
        assertThat(fila.serverVeto).isNull() // la escritura NO quedó…
        assertThat(lectura.vetoDelServidor).isEqualTo(PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE) // …y el veto llega igual a quien consultó
    }

    // ── El proceso muere o la escritura falla: nada se pierde ni se cierra a medias ─────────────────────────────────

    @Test fun `P1 si el proceso muere con el GET ya recibido y antes del CAS la duda sigue y la reconexion la cierra una sola vez`() = runTest {
        duda("a-mu")
        coEvery { api.getAttemptStatus(venue, "a-mu") } returns s6(s6Literal("a-mu"))
        coEvery { api.getRequestStatus(venue, "req-a-mu") } returns porSolicitud(getLiteral("req-a-mu"))
        val muriendo = spyk(ledger)
        coEvery { muriendo.aplicarLiberacionDelServidor(any(), any()) } throws CancellationException("el proceso murió antes del CAS")

        val murio = runCatching { LedgerServerRecovery(dao, muriendo, api).recover(venue, ahora) }.exceptionOrNull()

        assertThat(murio).isInstanceOf(CancellationException::class.java)
        assertThat(dao.getById("a-mu")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("a-mu")!!.serverCheckedAt).isNull()

        // Proceso nuevo: vuelve a preguntar y cierra. Y una tercera pasada ya no cambia nada.
        val nuevo = LedgerServerRecovery(dao, PaymentAttemptLedger(dao, mockk(relaxed = true)), api)
        assertThat(nuevo.recover(venue, ahora + 1).liberadas).isEqualTo(1)
        assertThat(dao.getById("a-mu")!!.state).isEqualTo("DESCARTADA")
        assertThat(nuevo.recover(venue, ahora + LedgerServerRecovery.ESPACIADO_TOPE_MS + 1).liberadas).isEqualTo(0)
        coVerify(exactly = 2) { api.getRequestStatus(venue, "req-a-mu") }
    }

    @Test fun `P1 si la escritura del CAS falla la duda sigue, no gasta el turno y la pasada siguiente la cierra`() = runTest {
        duda("a-es")
        coEvery { api.getAttemptStatus(venue, "a-es") } returns s6(s6Literal("a-es"))
        coEvery { api.getRequestStatus(venue, "req-a-es") } returns porSolicitud(getLiteral("req-a-es"))
        val sinDisco = spyk(ledger)
        coEvery { sinDisco.aplicarLiberacionDelServidor(any(), any()) } coAnswers { Result.failure(RuntimeException("disco lleno")) }

        val primera = LedgerServerRecovery(dao, sinDisco, api).recover(venue, ahora)

        assertThat(primera.liberadas).isEqualTo(0)
        assertThat(primera.sinRespuesta).isEqualTo(1)
        assertThat(dao.getById("a-es")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("a-es")!!.serverCheckedAt).isNull()

        val segunda = recuperacion().recover(venue, ahora + 31_000)

        assertThat(segunda.liberadas).isEqualTo(1)
        assertThat(dao.getById("a-es")!!.state).isEqualTo("DESCARTADA")
    }

    // ── Por Retrofit real, con el JSON literal ───────────────────────────────────────────────────────────────────────

    private fun apiReal(): TerminalAttemptApiService {
        val s = MockWebServer().also { it.start(); servidor = it }
        return Retrofit.Builder().baseUrl(s.url("/api/v1/")).addConverterFactory(GsonConverterFactory.create()).build()
            .create(TerminalAttemptApiService::class.java)
    }

    private fun json(cuerpo: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(cuerpo)

    @Test fun `P1 Retrofit real y el JSON del servidor tal cual · S6 sin vinculo y la declaracion del POS cierran la duda de la PAX`() = runBlocking<Unit> {
        val apiDeVerdad = apiReal()
        servidor!!.enqueue(json(s6Literal("a-rt")))
        servidor!!.enqueue(json(getLiteral("req-a-rt")))
        duda("a-rt")

        val r = LedgerServerRecovery(dao, ledger, apiDeVerdad).recover(venue, ahora)

        val primera = servidor!!.takeRequest(2, TimeUnit.SECONDS)
        val segunda = servidor!!.takeRequest(2, TimeUnit.SECONDS)
        assertThat(primera?.path).isEqualTo("/api/v1/tpv/venues/$venue/terminal-payment/attempts/a-rt")
        assertThat(segunda?.path).isEqualTo("/api/v1/tpv/venues/$venue/terminal-payment/requests/req-a-rt")
        assertThat(segunda?.method).isEqualTo("GET")
        assertThat(r.liberadas).isEqualTo(1)
        assertThat(dao.getById("a-rt")!!.state).isEqualTo("DESCARTADA")
    }

    @Test fun `P1 Retrofit real · un request null y resuelta false (el JSON con sus null) conserva la duda`() = runBlocking<Unit> {
        val apiDeVerdad = apiReal()
        servidor!!.enqueue(json(s6Literal("a-nu")))
        servidor!!.enqueue(json("""{"success":true,"requestId":"req-a-nu","request":null,"resuelta":false}"""))
        duda("a-nu")

        val r = LedgerServerRecovery(dao, ledger, apiDeVerdad).recover(venue, ahora)

        assertThat(servidor!!.requestCount).isEqualTo(2)
        assertThat(r.liberadas).isEqualTo(0)
        assertThat(dao.getById("a-nu")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("a-nu")!!.serverCheckedAt).isEqualTo(ahora)
    }

    @Test fun `P1 Retrofit real · el socket cortado al pedir la solicitud conserva la duda sin gastar el turno`() = runBlocking<Unit> {
        val apiDeVerdad = apiReal()
        servidor!!.enqueue(json(s6Literal("a-co")))
        // OkHttp reintenta un GET cuya conexión se cortó (otra ruta, otra dirección de localhost): que cada intento también se corte.
        repeat(3) { servidor!!.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        duda("a-co")

        val r = LedgerServerRecovery(dao, ledger, apiDeVerdad).recover(venue, ahora)

        assertThat(servidor!!.requestCount).isAtLeast(2)
        assertThat(r.liberadas).isEqualTo(0)
        assertThat(r.sinRespuesta).isEqualTo(1)
        assertThat(dao.getById("a-co")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("a-co")!!.serverCheckedAt).isNull()
    }
}
