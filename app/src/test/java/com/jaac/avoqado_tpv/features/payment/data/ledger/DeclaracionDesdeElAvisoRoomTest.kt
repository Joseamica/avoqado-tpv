package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion.Declarado
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion.FaltaConsultar
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion.HayEvidencia
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion.PidePin
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion.Rechazado
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion.SinPermiso
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion.SinRed
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion.UsaElPos
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.data.task5RoomShiftRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.UUID

/**
 * Botón «No se cobró» del banner verde (founder, 3-nov: «A»). Lo que escribe es una afirmación de que un cobro NO ocurrió, así que aquí
 * corre todo de verdad —Room, libreta, recuperación, bandeja y el resolvedor— y sólo el transporte es de mentira.
 *
 * `runBlocking` y no `runTest`: la recuperación lleva topes de reloj y Room corre en sus propios hilos. El tope del refresco se prueba
 * aparte, con reloj virtual y sin Room, en `DeclaracionDesdeElAvisoTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class DeclaracionDesdeElAvisoRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private val api = mockk<TerminalAttemptApiService>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val socket = mockk<SocketManager>(relaxed = true)
    private val venue = "venue-1"
    private val generico = "Este cobro todavía no se puede declarar. Vuelve a consultar en un momento."
    private var servidor: MockWebServer? = null

    private val paxEmbebida = MarcaDelBuild(procesador = "BLUMON", sdkAngelPayCompilado = false, respaldoAppAAppCompilado = false)

    /** Una libreta = un proceso (su propio `process_token`). */
    private fun libreta() = PaymentAttemptLedger(
        dao,
        mockk<TpvSettingsRepository>(relaxed = true),
        PilaDePantallas { listOf(TareaDeLaApp(pantallas = 1, arribaLaPrincipal = true)) },
    ).apply { marcaDelBuild = paxEmbebida }

    private lateinit var ledger: PaymentAttemptLedger
    private lateinit var declaracion: DeclaracionDesdeElAviso

    private fun declaracionCon(libreta: PaymentAttemptLedger, transporte: TerminalAttemptApiService = api): DeclaracionDesdeElAviso {
        val recuperacion = LedgerServerRecovery(dao, libreta, transporte)
        return DeclaracionDesdeElAviso(
            libreta,
            BlumonAttemptResolver(libreta, recuperacion, transporte, storage),
            recuperacion,
            BandejaServerRecovery(db.remotePaymentRequestDao(), transporte),
            transporte,
            dagger.Lazy { socket },
        )
    }

    private fun conPermisos(vararg permisos: String) {
        every { storage.getString("permissions_cache", null) } returns "$venue|cajera\n${permisos.joinToString(",")}"
    }

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        every { storage.getVenueId() } returns venue
        every { storage.getStaffId() } returns "cajera"
        conPermisos("payments:create", "payments:reconcile-uncharged")
        ledger = libreta()
        declaracion = declaracionCon(ledger)
    }

    @After fun cerrar() {
        servidor?.shutdown()
        db.close()
    }

    // ── Las filas y las respuestas del servidor, tal cual ──────────────────────────────────────────────────────────────

    private suspend fun duda(
        id: String,
        requestId: String? = null,
        processor: String = "ANGELPAY",
        serverAnsweredAt: Long? = null,
    ): CobroDeclarable {
        val creado = System.currentTimeMillis() - 60_000
        dao.insert(
            PaymentAttemptEntity(
                attemptId = id, venueId = venue, processor = processor, state = "INDETERMINADO", amountCents = 10_000, tipCents = 0,
                recordingRoute = "FAST",
                paymentContextJson = if (requestId != null) """{"terminalPaymentRequestId":"$requestId"}""" else """{"amount":100.00}""",
                terminalPaymentRequestId = requestId, serverAnsweredAt = serverAnsweredAt, createdAt = creado, updatedAt = creado,
            ),
        )
        return declarable(requestId ?: id)
    }

    private suspend fun enBandeja(requestId: String): CobroDeclarable {
        val creado = System.currentTimeMillis() - 60_000
        db.remotePaymentRequestDao().insert(
            RemotePaymentRequestEntity(
                requestId = requestId, venueId = venue, amountCents = 5_000, tipCents = 0, rating = null, skipReview = true, orderId = null,
                processedByStaffId = null, senderDeviceName = "POS", sourceTimestamp = "t", status = RemotePaymentRequestEntity.STATUS_PROCESSING,
                createdAt = creado, updatedAt = creado,
            ),
        )
        return declarable(requestId)
    }

    private suspend fun declarables(libreta: PaymentAttemptLedger = ledger) = libreta.observarCobrosDeclarables(venue).first()
    private suspend fun declarable(identidad: String) = declarables().single { it.identidad == identidad }
    private suspend fun obligaciones() = db.remotePaymentRequestDao().observePendingObligations(venue).first()

    /** S6 sin vínculo ni dinero: lo que ve la PAX, y un Pago rápido. */
    private fun s6Limpia(id: String) = s6(
        """{"success":true,"attemptId":"$id","requestId":null,"attempt":{"attemptId":"$id","outcome":"NOT_RECORDED","paymentId":null,""" +
            """"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,"resolution":null},"request":null}""",
    )

    /** S6 de la Nexgo ya declarada: el vínculo trae la solicitud con la declaración de una persona. */
    private fun s6Declarada(id: String, requestId: String) = s6(
        """{"success":true,"attemptId":"$id","requestId":"$requestId","attempt":{"attemptId":"$id","outcome":"NOT_RECORDED","paymentId":null,""" +
            """"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,"resolution":null},""" +
            """"request":{"requestId":"$requestId","status":"FAILED","failureCode":"OPERATOR_RECONCILED_NO_CHARGE","outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}}""",
    )

    /** S6 que contradice: hay un Payment con esa llave que no se puede atribuir a este intento. */
    private fun s6Contradiccion(id: String) = s6(
        """{"success":true,"attemptId":"$id","requestId":null,"attempt":{"attemptId":"$id","outcome":"NOT_RECORDED","paymentId":null,""" +
            """"processorEvidence":"NONE","paymentContradiction":true,"evidenceContradiction":false,"unattributedEvidence":false,"resolution":null},"request":null}""",
    )

    private fun s6Aprobada(id: String) = s6(
        """{"success":true,"attemptId":"$id","requestId":null,"attempt":{"attemptId":"$id","outcome":"NOT_RECORDED","paymentId":null,""" +
            """"processorEvidence":"APPROVED","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,"resolution":null},"request":null}""",
    )

    private fun s6(json: String): Response<TerminalAttemptStatusResponse> =
        Response.success(Gson().fromJson(json, TerminalAttemptStatusResponse::class.java))

    /** El GET por solicitud cuando una persona ya declaró «no se cobró». */
    private fun solicitudDeclarada(requestId: String) = Response.success(
        Gson().fromJson(
            """{"success":true,"requestId":"$requestId","resuelta":true,"request":{"requestId":"$requestId","venueId":"$venue","status":"FAILED",""" +
                """"failureCode":"OPERATOR_RECONCILED_NO_CHARGE","outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}}""",
            TerminalRequestStatusResponse::class.java,
        ),
    )

    private fun liberada(requestId: String): Response<DeclaracionSinCobroResponse> = Response.success(
        Gson().fromJson(
            """{"success":true,"released":true,"status":"FAILED","requestId":"$requestId","paymentId":null,"outcome":"NOT_CHARGED","message":"Terminal liberada."}""",
            DeclaracionSinCobroResponse::class.java,
        ),
    )

    private fun error(codigo: Int, cuerpo: String): Response<DeclaracionSinCobroResponse> =
        Response.error(codigo, cuerpo.toResponseBody("application/json".toMediaTypeOrNull()))

    private fun idDe(requestId: String) = UUID.nameUUIDFromBytes("tpv:uncharged:$venue:$requestId".toByteArray(Charsets.UTF_8)).toString()

    // ── Con solicitud del POS: la MISMA declaración, por el servidor ───────────────────────────────────────────────────

    @Test fun `P1 2xx con intento ligado - declara, cierra la fila sin gastar el turno y sale de los declarables y del aviso`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        val cuerpo = slot<DeclaracionSinCobroRequest>()
        coEvery { api.declararSinCobro(venue, "r-1", capture(cuerpo)) } returns liberada("r-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Declarada("a-1", "r-1")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Declarado)

        assertThat(cuerpo.captured).isEqualTo(DeclaracionSinCobroRequest(resolutionId = idDe("r-1")))
        assertThat(cuerpo.captured.statement).isEqualTo("UNCHARGED_VERIFIED")
        assertThat(cuerpo.captured.statementVersion).isEqualTo(1)
        assertThat(cuerpo.captured.supervisorPin).isNull()
        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("DESCARTADA")
        assertThat(fila.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
        assertThat(fila.serverCheckCount).isEqualTo(0) // estampar = false: no gasta el turno de la recuperación
        assertThat(declarables()).isEmpty()
        assertThat(obligaciones()).isEmpty()
    }

    @Test fun `P1 2xx de una PAX sin vinculo - cierra por la solicitud`() = runBlocking<Unit> {
        val cobro = duda("a-pax", "r-pax", processor = "BLUMON")
        coEvery { api.declararSinCobro(venue, "r-pax", any()) } returns liberada("r-pax")
        coEvery { api.getAttemptStatus(venue, "a-pax") } returns s6Limpia("a-pax")
        coEvery { api.getRequestStatus(venue, "r-pax") } returns solicitudDeclarada("r-pax")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Declarado)

        assertThat(dao.getById("a-pax")!!.state).isEqualTo("DESCARTADA")
        assertThat(declarables()).isEmpty()
    }

    @Test fun `P1 2xx sin intento - concilia esa solicitud y sale de los declarables y del aviso`() = runBlocking<Unit> {
        val cobro = enBandeja("r-sola")
        coEvery { api.declararSinCobro(venue, "r-sola", any()) } returns liberada("r-sola")
        coEvery { api.getRequestStatus(venue, "r-sola") } returns solicitudDeclarada("r-sola")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Declarado)

        assertThat(db.remotePaymentRequestDao().getById("r-sola")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(declarables()).isEmpty()
        assertThat(obligaciones()).isEmpty()
    }

    @Test fun `2xx con la consulta posterior caida sigue siendo Declarado - el refresco termina el trabajo`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns liberada("r-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } throws java.io.IOException("se cayó la red")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Declarado)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")

        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Declarada("a-1", "r-1")
        declaracion.refrescarUno(venue, listOf(cobro))
        assertThat(dao.getById("a-1")!!.state).isEqualTo("DESCARTADA")
    }

    @Test fun `P1 2xx pero la consulta posterior trae evidencia - se dice que hay evidencia, no que quedo declarado`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns liberada("r-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Aprobada("a-1")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(HayEvidencia)

        assertThat(dao.getById("a-1")!!.serverProcessorEvidence).isEqualTo("APPROVED")
    }

    @Test fun `P1 released false con COMPLETED - hay evidencia, y lo que trae la consulta se emite como en el trigger`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns Response.success(
            DeclaracionSinCobroResponse(success = false, released = false, status = "COMPLETED", requestId = "r-1", paymentId = "pay-1", message = "No se liberó"),
        )
        // S6: el Payment de ESTE intento, ganador de la solicitud: la bandeja queda resuelta y eso se le emite al servidor.
        db.remotePaymentRequestDao().insert(
            RemotePaymentRequestEntity(
                requestId = "r-1", venueId = venue, amountCents = 10_000, tipCents = 0, rating = null, skipReview = true, orderId = null,
                processedByStaffId = null, senderDeviceName = "POS", sourceTimestamp = "t", status = RemotePaymentRequestEntity.STATUS_PROCESSING,
                createdAt = 1, updatedAt = 1,
            ),
        )
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6(
            """{"success":true,"attemptId":"a-1","requestId":"r-1","attempt":{"attemptId":"a-1","outcome":"RECORDED","paymentId":"pay-1",""" +
                """"paymentStatus":"COMPLETED","recordedVia":"webhook","amountCents":10000,"tipCents":0,"isWinner":true,"winnerPaymentId":"pay-1",""" +
                """"processorEvidence":"APPROVED"},"request":{"requestId":"r-1","status":"COMPLETED","paymentId":"pay-1"}}""",
        )

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(HayEvidencia)

        assertThat(dao.getById("a-1")!!.serverOutcome).isEqualTo("RECORDED")
        verify(exactly = 1) { socket.emitDurableTerminalPaymentResult(match { "pay-1" in it }) }
    }

    @Test fun `released false sin COMPLETED - viaja el mensaje del servidor, nunca dice declarado`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns Response.success(
            DeclaracionSinCobroResponse(success = false, released = false, status = "FAILED", requestId = "r-1", message = "No se liberó: falta confirmar."),
        )
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        coEvery { api.getRequestStatus(venue, "r-1") } returns Response.error(404, "{}".toResponseBody(null))

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Rechazado("No se liberó: falta confirmar."))
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `P1 403 de supervisor sin PIN pide PIN, con PIN equivocado lo vuelve a pedir, y con el bueno declara - siempre con el mismo id`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        val cuerpos = mutableListOf<DeclaracionSinCobroRequest>()
        coEvery { api.declararSinCobro(venue, "r-1", capture(cuerpos)) } returnsMany listOf(
            error(403, """{"success":false,"released":false,"code":"SUPERVISOR_AUTHORIZATION_REQUIRED","message":"Pide a un gerente"}"""),
            error(403, """{"success":false,"released":false,"code":"SUPERVISOR_AUTHORIZATION_REQUIRED","message":"Pide a un gerente"}"""),
            liberada("r-1"),
        )
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Declarada("a-1", "r-1")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(PidePin)
        assertThat(declaracion.declarar(venue, cobro, supervisorPin = "0000")).isEqualTo(PidePin)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
        assertThat(declaracion.declarar(venue, cobro, supervisorPin = "918273")).isEqualTo(Declarado)

        assertThat(cuerpos.map { it.supervisorPin }).containsExactly(null, "0000", "918273").inOrder()
        assertThat(cuerpos.map { it.resolutionId }.toSet()).containsExactly(idDe("r-1"))
    }

    @Test fun `403 de la puerta sin ninguno de los dos permisos - falta el permiso, sin mostrar el texto tecnico`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns
            error(403, """{"error":"Forbidden","message":"Permission 'payments:reconcile-uncharged' required","required":"payments:reconcile-uncharged","userRole":"WAITER"}""")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(SinPermiso)
    }

    @Test fun `otro 403 con mensaje viaja tal cual y no pide PIN`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns
            error(403, """{"success":false,"message":"No tienes acceso a este establecimiento"}""")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Rechazado("No tienes acceso a este establecimiento"))
    }

    @Test fun `P1 404 manda al POS y NUNCA se vuelve declaracion local, aunque Avoqado ya haya contestado`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1", serverAnsweredAt = 1L)
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns error(404, """<html>Cannot POST</html>""")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(UsaElPos)

        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("INDETERMINADO")
        assertThat(fila.lastError).isNull()
        assertThat(declarables().map { it.attemptId }).containsExactly("a-1")
    }

    @Test fun `P1 409 con mensaje viaja tal cual y la fila no se mueve`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        val casos = mapOf(
            "POSITIVE_EVIDENCE_EXISTS" to "Este cobro sí tiene señales de haber pasado. No lo declares: consulta su resultado.",
            "EXECUTION_STILL_ACTIVE" to "La terminal dice que este cobro sigue en curso. Espera unos segundos y vuelve a consultar.",
            "RESOLUTION_CONFLICT" to "Esta declaración ya se registró con otros datos. Vuelve a consultar el cobro.",
        )
        for ((codigo, mensaje) in casos) {
            coEvery { api.declararSinCobro(venue, "r-1", any()) } returns
                error(409, """{"success":false,"released":false,"code":"$codigo","message":"$mensaje"}""")
            assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Rechazado(mensaje))
        }
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `un rechazo sin mensaje legible usa el generico - y nunca Validation error`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        for (cuerpo in listOf("{}", "no es json", """{"message":""}""", """{"message":"   "}""", """{"message":null}""",
            """{"message":"Validation error: statement: Required"}""", """{"success":false,"message":"validation error"}""")) {
            coEvery { api.declararSinCobro(venue, "r-1", any()) } returns error(400, cuerpo)
            assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Rechazado(generico))
        }
    }

    @Test fun `un 401 no ensena su texto tecnico`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns error(401, """{"error":"Unauthorized","message":"Token has been revoked"}""")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Rechazado(generico))
    }

    @Test fun `el limite de intentos del PIN llega con su mensaje`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns
            error(429, """{"code":"RATE_LIMIT_EXCEEDED","message":"Demasiados intentos de autorización. Espera 15 minutos."}""")

        assertThat(declaracion.declarar(venue, cobro, supervisorPin = "1234")).isEqualTo(Rechazado("Demasiados intentos de autorización. Espera 15 minutos."))
    }

    @Test fun `P1 5xx y sin respuesta dicen sin red, y la fila no se mueve`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        for (codigo in listOf(500, 502, 503)) {
            coEvery { api.declararSinCobro(venue, "r-1", any()) } returns error(codigo, "{}")
            assertThat(declaracion.declarar(venue, cobro)).isEqualTo(SinRed)
        }
        coEvery { api.declararSinCobro(venue, "r-1", any()) } throws java.io.IOException("sin red")
        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(SinRed)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `un 2xx sin cuerpo no afirma nada - se vuelve a intentar`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns Response.success(null)

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(SinRed)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `P1 doble toque - un solo POST, y el segundo no contesta nada`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        val puerta = CompletableDeferred<Unit>()
        val posts = java.util.concurrent.atomic.AtomicInteger()
        coEvery { api.declararSinCobro(venue, "r-1", any()) } coAnswers {
            posts.incrementAndGet()
            puerta.await()
            liberada("r-1")
        }
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Declarada("a-1", "r-1")

        val primero = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { declaracion.declarar(venue, cobro) }
        while (posts.get() == 0) kotlinx.coroutines.delay(5)
        assertThat(declaracion.declarar(venue, cobro)).isNull()
        puerta.complete(Unit)

        assertThat(primero.await()).isEqualTo(Declarado)
        assertThat(posts.get()).isEqualTo(1)
    }

    @Test fun `al terminar un envio se puede volver a intentar, con el mismo id, tambien en otro proceso`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        val cuerpos = mutableListOf<DeclaracionSinCobroRequest>()
        coEvery { api.declararSinCobro(venue, "r-1", capture(cuerpos)) } throws java.io.IOException("sin red")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(SinRed)
        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(SinRed)
        assertThat(declaracionCon(libreta()).declarar(venue, cobro)).isEqualTo(SinRed)

        assertThat(cuerpos).hasSize(3)
        assertThat(cuerpos.map { it.resolutionId }.toSet()).containsExactly(idDe("r-1"))
        assertThat(idDe("r-2")).isNotEqualTo(idDe("r-1"))
    }

    @Test fun `P1 lo que llego entre ofrecer el boton y tocarlo manda - sin POST`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        // El aprobado del host aterriza con el diálogo abierto: el servidor todavía no lo sabe, la libreta sí.
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET host_approved = 1 WHERE attempt_id = 'a-1'")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(HayEvidencia)

        coVerify(exactly = 0) { api.declararSinCobro(any(), any(), any()) }
    }

    @Test fun `lo que ya no es declarable sin dinero tampoco hace POST, y no afirma nada`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state = 'AUTORIZANDO' WHERE attempt_id = 'a-1'")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Rechazado(generico))

        coVerify(exactly = 0) { api.declararSinCobro(any(), any(), any()) }
    }

    @Test fun `con solicitud no hay nada que preparar - no consulta ni pide permiso local, el servidor decide`() = runBlocking<Unit> {
        conPermisos("payments:create") // un mesero: el PIN se lo pedirá el servidor
        val cobro = duda("a-1", "r-1")

        assertThat(declaracion.preparar(venue, cobro)).isNull()

        coVerify(exactly = 0) { api.getAttemptStatus(any(), any()) }
    }

    // ── Sin solicitud (Pago rápido): la declaración de la libreta, con sus defensas ────────────────────────────────────

    @Test fun `P1 Pago rapido en la Nexgo ya contestado - sin red se declara y queda quien, igual que en la PAX`() = runBlocking<Unit> {
        for ((id, procesador) in listOf("a-nexgo" to "ANGELPAY", "a-pax" to "BLUMON")) {
            val cobro = duda(id, processor = procesador, serverAnsweredAt = 1L)
            coEvery { api.getAttemptStatus(venue, id) } throws java.io.IOException("sin red")

            assertThat(declaracion.preparar(venue, cobro)).isNull()
            assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Declarado)

            val fila = dao.getById(id)!!
            assertThat(fila.state).isEqualTo("DESCARTADA")
            assertThat(fila.lastError).isEqualTo("declarado_sin_cobro:cajera")
        }
        assertThat(declarables()).isEmpty()
        assertThat(obligaciones()).isEmpty()
        coVerify(exactly = 0) { api.declararSinCobro(any(), any(), any()) }
    }

    @Test fun `P1 Codex 2 - Pago rapido sin respuesta previa y sin red dice falta consultar en preparar, y declarar tampoco pasa`() = runBlocking<Unit> {
        val cobro = duda("a-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } throws java.io.IOException("sin red")

        assertThat(declaracion.preparar(venue, cobro)).isEqualTo(FaltaConsultar)
        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(FaltaConsultar)

        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `un 503 tampoco cuenta como respuesta de Avoqado`() = runBlocking<Unit> {
        val cobro = duda("a-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } returns Response.error(503, "{}".toResponseBody(null))

        assertThat(declaracion.preparar(venue, cobro)).isEqualTo(FaltaConsultar)
    }

    @Test fun `al conectarse, la misma consulta deja que se ofrezca confirmar`() = runBlocking<Unit> {
        val cobro = duda("a-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")

        assertThat(declaracion.preparar(venue, cobro)).isNull()
        assertThat(dao.getById("a-1")!!.serverAnsweredAt).isNotNull()
        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Declarado)
    }

    @Test fun `P1 Codex 1 - falla SOLO la escritura del veto y la siguiente escritura funciona - no se declara y dice hay evidencia`() = runBlocking<Unit> {
        val cobro = duda("a-1", serverAnsweredAt = 1L)
        val sinDisco = spyk(ledger)
        coEvery { sinDisco.marcarVetoDelServidor(venue, "a-1", any(), any()) } returns Result.failure(java.io.IOException("disco lleno"))
        val conFalla = declaracionCon(sinDisco)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Contradiccion("a-1")

        assertThat(conFalla.preparar(venue, cobro)).isEqualTo(HayEvidencia)
        assertThat(conFalla.declarar(venue, cobro)).isEqualTo(HayEvidencia)

        val fila = dao.getById("a-1")!!
        assertThat(fila.serverVeto).isNull() // el veto no quedó escrito…
        assertThat(fila.state).isEqualTo("INDETERMINADO") // …y aun así no se declaró
        assertThat(fila.lastError).isNull()

        // Se recuerda: sin red, y con la fila limpia, sigue sin poder declararse.
        coEvery { api.getAttemptStatus(venue, "a-1") } throws java.io.IOException("sin red")
        assertThat(conFalla.declarar(venue, cobro)).isEqualTo(HayEvidencia)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `P1 con evidencia positiva sin guardar tampoco - el dinero que dijo el servidor manda`() = runBlocking<Unit> {
        val cobro = duda("a-1", serverAnsweredAt = 1L)
        val sinDisco = spyk(ledger)
        coEvery { sinDisco.marcarEvidenciaPositivaDelServidor(venue, "a-1", any()) } returns Result.failure(java.io.IOException("disco lleno"))
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Aprobada("a-1")

        assertThat(declaracionCon(sinDisco).declarar(venue, cobro)).isEqualTo(HayEvidencia)

        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `P1 sin el permiso de conciliar no consulta ni declara - sale antes de confirmar`() = runBlocking<Unit> {
        conPermisos("payments:create")
        val cobro = duda("a-1", serverAnsweredAt = 1L)

        assertThat(declaracion.preparar(venue, cobro)).isEqualTo(SinPermiso)
        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(SinPermiso)

        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
        coVerify(exactly = 0) { api.getAttemptStatus(any(), any()) }
    }

    @Test fun `si el CAS ya no aplica no afirma nada`() = runBlocking<Unit> {
        val cobro = duda("a-1", serverAnsweredAt = 1L)
        val conCasPerdido = spyk(ledger)
        coEvery { conCasPerdido.declararSinCobroLocal("a-1", venue, any()) } returns false
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")

        assertThat(declaracionCon(conCasPerdido).declarar(venue, cobro)).isEqualTo(Rechazado(generico))
    }

    @Test fun `si mientras tanto el servidor ya libero la duda, ya no hay nada que declarar`() = runBlocking<Unit> {
        val cobro = duda("a-1", serverAnsweredAt = 1L)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET state = 'DESCARTADA', server_outcome = 'RELEASED_NO_EVIDENCE', last_error = 'liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW' WHERE attempt_id = 'a-1'",
        )
        coEvery { api.getAttemptStatus(venue, "a-1") } throws java.io.IOException("sin red")

        assertThat(declaracion.preparar(venue, cobro)).isEqualTo(Declarado)
    }

    // ── Caja: tras declarar, el cierre ya no la cuenta ─────────────────────────────────────────────────────────────────

    private suspend fun cajaAbierta(): Pair<ShiftRepository, LocalShiftSessionEntity> {
        val shifts = task5RoomShiftRepository(db, storage, mockk<ApiService>(), venue, "cajera", enabled = true)
        return shifts to (shifts.guardarAperturaLocal(venue, "cajera", BigDecimal("100.00")) as AppResult.Success).data
    }

    private fun solicitudDelPos(requestId: String) = SocketEvent.TerminalPaymentRequest(
        requestId = requestId, amountCents = 10_000, tipCents = 0, rating = null, skipReview = true, orderId = null,
        processedByStaffId = "cajera", senderDeviceName = "POS", venueId = venue, timestamp = "2026-11-03T12:00:00Z",
    )

    /** La venta de la tablet en una Caja de verdad: reclamada, y la app muere con el lector dentro. Devuelve la libreta del proceso nuevo. */
    private suspend fun ventaQueDejoElProcesoMuerto(shifts: ShiftRepository, requestId: String, attemptId: String): PaymentAttemptLedger {
        val libreta1 = libreta()
        val bandeja1 = RemotePaymentInbox(db.remotePaymentRequestDao(), shifts, libreta1, PaymentStateHolder())
        bandeja1.receive(solicitudDelPos(requestId))
        assertThat(RemotePaymentCoordinator(bandeja1).prepareSocketPaymentRequest(requestId, { true }, { venue })).isEqualTo(RemotePaymentAdmission.READY)
        assertThat(libreta1.openAttemptForShift(shifts, attemptId, venue, "BLUMON", 10_000, 0, "FAST", """{"terminalPaymentRequestId":"$requestId"}""")).isTrue()
        assertThat(libreta1.markAuthorizing(attemptId)).isTrue()
        return libreta().also { assertThat(it.cuarentenaDeHuerfanos()).isEqualTo(1) }
    }

    @Test fun `P1 Caja - con el intento de un proceso muerto, tras declarar el turno se deja cerrar aunque no haya llegado la sonda`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaAbierta()
        val libreta2 = ventaQueDejoElProcesoMuerto(shifts, "r-caja", "a-caja")
        val turno = db.localShiftSessionDao()
        assertThat(turno.intentosSinRepresentar(venue, sesion.localSessionId)).isEqualTo(1)
        assertThat(shifts.guardarCierreLocal(venue, sesion.localSessionId)).isInstanceOf(AppResult.Error::class.java)

        coEvery { api.declararSinCobro(venue, "r-caja", any()) } returns liberada("r-caja")
        coEvery { api.getAttemptStatus(venue, "a-caja") } returns s6Limpia("a-caja")
        coEvery { api.getRequestStatus(venue, "r-caja") } returns solicitudDeclarada("r-caja")
        val cobro = declarables(libreta2).single()
        assertThat(declaracionCon(libreta2).declarar(venue, cobro)).isEqualTo(Declarado)

        assertThat(dao.getById("a-caja")!!.state).isEqualTo("DESCARTADA")
        assertThat(declarables(libreta2)).isEmpty()
        assertThat(obligaciones()).isEmpty()
        assertThat(turno.intentosSinRepresentar(venue, sesion.localSessionId)).isEqualTo(0)
        assertThat(turno.ejecucionesRemotas(venue, sesion.localSessionId)).isEqualTo(0)
        assertThat(shifts.obligacionesDelCierreLocal(venue, sesion.localSessionId).rows).isEmpty()
        assertThat(shifts.guardarCierreLocal(venue, sesion.localSessionId)).isInstanceOf(AppResult.Success::class.java)
    }

    @Test fun `P1 Caja - una solicitud sin intento deja de contar al conciliarse`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaAbierta()
        val entrada = RemotePaymentInbox(db.remotePaymentRequestDao(), shifts, ledger, PaymentStateHolder())
        entrada.receive(solicitudDelPos("r-sola"))
        assertThat(RemotePaymentCoordinator(entrada).prepareSocketPaymentRequest("r-sola", { true }, { venue })).isEqualTo(RemotePaymentAdmission.READY)
        val turno = db.localShiftSessionDao()
        assertThat(turno.ejecucionesRemotas(venue, sesion.localSessionId)).isEqualTo(1)

        coEvery { api.declararSinCobro(venue, "r-sola", any()) } returns liberada("r-sola")
        coEvery { api.getRequestStatus(venue, "r-sola") } returns solicitudDeclarada("r-sola")
        assertThat(declaracion.declarar(venue, declarable("r-sola"))).isEqualTo(Declarado)

        assertThat(turno.ejecucionesRemotas(venue, sesion.localSessionId)).isEqualTo(0)
        assertThat(shifts.guardarCierreLocal(venue, sesion.localSessionId)).isInstanceOf(AppResult.Success::class.java)
    }

    @Test fun `P1 Caja - un Pago rapido declarado deja de contar`() = runBlocking<Unit> {
        val (shifts, sesion) = cajaAbierta()
        conPermisos("payments:create", "payments:reconcile-uncharged", "tpv-shifts:close")
        val libreta1 = libreta()
        assertThat(libreta1.openAttemptForShift(shifts, "a-local", venue, "ANGELPAY", 10_000, 0, "FAST", """{"amount":100.00}""")).isTrue()
        assertThat(libreta1.markAuthorizing("a-local")).isTrue()
        val libreta2 = libreta().also { assertThat(it.cuarentenaDeHuerfanos()).isEqualTo(1) }
        val turno = db.localShiftSessionDao()
        assertThat(turno.intentosSinRepresentar(venue, sesion.localSessionId)).isEqualTo(1)

        coEvery { api.getAttemptStatus(venue, "a-local") } returns s6Limpia("a-local")
        assertThat(declaracionCon(libreta2).declarar(venue, declarables(libreta2).single())).isEqualTo(Declarado)

        assertThat(turno.intentosSinRepresentar(venue, sesion.localSessionId)).isEqualTo(0)
        assertThat(shifts.guardarCierreLocal(venue, sesion.localSessionId)).isInstanceOf(AppResult.Success::class.java)
    }

    // ── El contrato con el servidor: su esquema es `.strict()` ─────────────────────────────────────────────────────────

    private fun apiReal(): TerminalAttemptApiService {
        val s = MockWebServer().also { it.start(); servidor = it }
        return Retrofit.Builder().baseUrl(s.url("/api/v1/")).addConverterFactory(GsonConverterFactory.create()).build()
            .create(TerminalAttemptApiService::class.java)
    }

    private fun json(codigo: Int, cuerpo: String) = MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(cuerpo)

    @Test fun `P1 Retrofit real - la ruta y el cuerpo exactos, sin requestId y sin PIN cuando no lo hay`() = runBlocking<Unit> {
        val transporte = apiReal()
        servidor!!.enqueue(json(200, """{"success":true,"released":true,"status":"FAILED","requestId":"r-1","paymentId":null,"outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED","resolution":{"id":"x","acceptedAt":"y"},"message":"Terminal liberada."}"""))
        servidor!!.enqueue(json(200, """{"success":true,"attemptId":"a-1","requestId":"r-1","attempt":{"attemptId":"a-1","outcome":"NOT_RECORDED"},"request":{"requestId":"r-1","status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}}"""))
        val cobro = duda("a-1", "r-1")

        assertThat(declaracionCon(ledger, transporte).declarar(venue, cobro)).isEqualTo(Declarado)

        val post = servidor!!.takeRequest()
        assertThat(post.method).isEqualTo("POST")
        assertThat(post.path).isEqualTo("/api/v1/tpv/venues/$venue/terminal-payment/requests/r-1/uncharged-declaration")
        val cuerpo = Gson().fromJson(post.body.readUtf8(), Map::class.java)
        assertThat(cuerpo).containsExactly("resolutionId", idDe("r-1"), "statement", "UNCHARGED_VERIFIED", "statementVersion", 1.0)
    }

    @Test fun `P1 Retrofit real - el PIN viaja cuando lo hay, y el 403 del servidor pide PIN`() = runBlocking<Unit> {
        val transporte = apiReal()
        servidor!!.enqueue(json(403, """{"success":false,"released":false,"code":"SUPERVISOR_AUTHORIZATION_REQUIRED","message":"Pide a un gerente que ingrese su PIN para confirmar que este cobro no pasó."}"""))
        val cobro = duda("a-1", "r-1")

        assertThat(declaracionCon(ledger, transporte).declarar(venue, cobro, supervisorPin = "9182737465")).isEqualTo(PidePin)

        val cuerpo = Gson().fromJson(servidor!!.takeRequest().body.readUtf8(), Map::class.java)
        assertThat(cuerpo["supervisorPin"]).isEqualTo("9182737465")
    }
}
