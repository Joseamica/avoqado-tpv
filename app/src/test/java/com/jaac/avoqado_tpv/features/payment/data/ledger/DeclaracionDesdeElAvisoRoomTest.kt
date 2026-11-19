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
    // F1 bloque 5: el renglón es del INTENTO; las pruebas viejas lo nombran por su solicitud (una sola por solicitud aquí).
    private suspend fun declarable(identidad: String) = declarables().single { it.clave == identidad || it.requestId == identidad }
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

    /** D22: el 2xx de la declaración revisada de un Pago rápido, con el testimonio que guardó Avoqado. */
    private fun s6Revisada(id: String, resolutionId: String) = s6(
        """{"success":true,"attemptId":"$id","requestId":null,"attempt":{"attemptId":"$id","outcome":"NOT_RECORDED","paymentId":null,""" +
            """"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,""" +
            """"resolution":{"id":"$resolutionId","kind":"UNCHARGED_VERIFIED","staffId":"cajera","by":"SESSION"}},"request":null,""" +
            """"resolution":{"id":"$resolutionId","acceptedAt":"2026-11-17T18:00:00.000Z","by":"SESSION"}}""",
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

    /**
     * D20 (Codex r2): «Ver anteriores» en Pagos amplía la lista de 50 en 50. La declaración por el servidor vuelve a buscar el cobro
     * antes de mandarlo (`yaNoEsDeclarable`): si buscara sólo en los 50 más recientes, un cobro viejo del POS se vería y fallaría.
     */
    @Test fun `D20 una solicitud del POS mas vieja que las 50 recientes se declara desde Ver anteriores`() = runBlocking<Unit> {
        val viejo = System.currentTimeMillis() - 10 * 60 * 60_000L
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "a-viejo", venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 10_000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = """{"terminalPaymentRequestId":"r-viejo"}""",
                terminalPaymentRequestId = "r-viejo", createdAt = viejo, updatedAt = viejo,
            ),
        )
        repeat(DeclaracionDesdeElAviso.TOPE_DE_LA_CONSULTA) { duda("local-$it") }
        assertThat(declarables().map { it.requestId }).doesNotContain("r-viejo")
        val cobro = ledger.observarCobrosDeclarables(venue, 2 * DeclaracionDesdeElAviso.TOPE_DE_LA_CONSULTA).first().single { it.requestId == "r-viejo" }
        coEvery { api.declararSinCobro(venue, "r-viejo", any()) } returns liberada("r-viejo")
        coEvery { api.getAttemptStatus(venue, "a-viejo") } returns s6Declarada("a-viejo", "r-viejo")

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Declarado)

        assertThat(dao.getById("a-viejo")!!.state).isEqualTo("DESCARTADA")
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
            "EXECUTION_STILL_ACTIVE" to "La terminal dice que este cobro sigue en curso. Espera unos segundos y vuelve a consultar.",
            "RESOLUTION_CONFLICT" to "Esta declaración ya se registró con otros datos. Vuelve a consultar el cobro.",
        )
        for ((codigo, mensaje) in casos) {
            coEvery { api.declararSinCobro(venue, "r-1", any()) } returns
                error(409, """{"success":false,"released":false,"code":"$codigo","message":"$mensaje"}""")
            assertThat(declaracion.declarar(venue, cobro)).isEqualTo(Rechazado(mensaje))
        }
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.getById("a-1")!!.serverVeto).isNull()
    }

    /**
     * F1 bloque 7 (I4, R5): el `409 POSITIVE_EVIDENCE_EXISTS` es GENÉRICO (puede ser evidencia de otra terminal). Antes su mensaje
     * («este cobro sí tiene señales de haber pasado») viajaba tal cual; ahora la terminal dice el impedimento neutro, deja el
     * impedimento en el intento (cerca la cuenta) y consulta. La fila no se mueve y nunca queda la aprobación del banco.
     */
    @Test fun `409 POSITIVE_EVIDENCE_EXISTS del POS es un impedimento - texto neutro, cerca la cuenta y consulta`() = runBlocking<Unit> {
        val cobro = duda("a-1", "r-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns error(409,
            """{"success":false,"released":false,"code":"POSITIVE_EVIDENCE_EXISTS","message":"Este cobro sí tiene señales de haber pasado."}""")
        val dijo = declaracion.declarar(venue, cobro)
        assertThat(dijo).isEqualTo(ResultadoDeLaDeclaracion.HayEvidenciaEnAvoqado)
        assertThat(dijo!!.texto()).isEqualTo("Avoqado encontró información que impide liberar este cobro.")
        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("INDETERMINADO")
        assertThat(fila.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_IMPEDIMENTO_DEL_SERVIDOR)
        assertThat(fila.serverProcessorEvidence).isNull()
        coVerify { api.getAttemptStatus(venue, "a-1") }
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

    // F1 (tarea 3): reemplaza «sin red se declara y queda quien»: la firma del gerente necesita internet y A se conserva.
    @Test fun `F1 Pago rapido en la Nexgo y en la PAX ya contestado - sin red no se firma y A sigue por revisar`() = runBlocking<Unit> {
        for ((id, procesador) in listOf("a-nexgo" to "ANGELPAY", "a-pax" to "BLUMON")) {
            val cobro = duda(id, processor = procesador, serverAnsweredAt = 1L)
            coEvery { api.getAttemptStatus(venue, id) } throws java.io.IOException("sin red")
            coEvery { api.resolveNoInstrument(venue, id, any()) } throws java.io.IOException("sin red")

            assertThat(declaracion.preparar(venue, cobro)).isNull()
            assertThat(declaracion.declarar(venue, cobro, "918273")).isEqualTo(SinRed)

            assertThat(dao.getById(id)!!.state).isEqualTo("INDETERMINADO")
        }
        assertThat(declarables().map { it.clave }).containsExactly("a-nexgo", "a-pax")
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        coVerify(exactly = 0) { api.declararSinCobro(any(), any(), any()) }
    }

    @Test fun `P1 Codex 2 - Pago rapido sin respuesta previa y sin red dice falta consultar en preparar, y declarar tampoco pasa`() = runBlocking<Unit> {
        val cobro = duda("a-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } throws java.io.IOException("sin red")

        assertThat(declaracion.preparar(venue, cobro)).isEqualTo(FaltaConsultar)
        assertThat(declaracion.declarar(venue, cobro, "918273")).isEqualTo(FaltaConsultar)

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
        // D22: con red, Avoqado guarda la declaración y la libreta lo marca; el aviso dice que quedó en Avoqado.
        val cuerpo = slot<NoInstrumentResolutionRequest>()
        coEvery { api.resolveNoInstrument(venue, "a-1", capture(cuerpo)) } answers { s6Firmada("a-1", cuerpo.captured.resolutionId) }

        assertThat(declaracion.preparar(venue, cobro)).isNull()
        assertThat(dao.getById("a-1")!!.serverAnsweredAt).isNotNull()
        assertThat(declaracion.declarar(venue, cobro, "918273")).isEqualTo(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado)
        assertThat(dao.getById("a-1")!!.lastError).isEqualTo("declarado_sin_cobro:gerente-1:avoqado")
        assertThat(cuerpo.captured.amountCents).isEqualTo(10_000)
        assertThat(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado.texto()).contains("la firma del gerente")
    }

    @Test fun `P1 Codex 1 - falla SOLO la escritura del veto y la siguiente escritura funciona - no se declara y dice hay evidencia`() = runBlocking<Unit> {
        val cobro = duda("a-1", serverAnsweredAt = 1L)
        val sinDisco = spyk(ledger)
        coEvery { sinDisco.marcarVetoDelServidor(venue, "a-1", any(), any()) } returns Result.failure(java.io.IOException("disco lleno"))
        val conFalla = declaracionCon(sinDisco)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Contradiccion("a-1")

        assertThat(conFalla.preparar(venue, cobro)).isEqualTo(HayEvidencia)
        assertThat(conFalla.declarar(venue, cobro, "918273")).isEqualTo(HayEvidencia)

        val fila = dao.getById("a-1")!!
        assertThat(fila.serverVeto).isNull() // el veto no quedó escrito…
        assertThat(fila.state).isEqualTo("INDETERMINADO") // …y aun así no se declaró
        assertThat(fila.lastError).isNull()

        // Se recuerda: sin red, y con la fila limpia, sigue sin poder declararse.
        coEvery { api.getAttemptStatus(venue, "a-1") } throws java.io.IOException("sin red")
        assertThat(conFalla.declarar(venue, cobro, "918273")).isEqualTo(HayEvidencia)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `P1 Codex codigo r1 - el refresco recuerda el veto que no pudo guardar y sin red tampoco se declara`() = runBlocking<Unit> {
        val cobro = duda("a-1", serverAnsweredAt = 1L)
        val sinDisco = spyk(ledger)
        coEvery { sinDisco.marcarVetoDelServidor(venue, "a-1", any(), any()) } returns Result.failure(java.io.IOException("disco lleno"))
        val conFalla = declaracionCon(sinDisco)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Contradiccion("a-1")

        conFalla.refrescarUno(venue, listOf(cobro)) // la consulta de cada 15 s oye el veto y no lo puede escribir

        coEvery { api.getAttemptStatus(venue, "a-1") } throws java.io.IOException("sin red")
        assertThat(conFalla.preparar(venue, cobro)).isEqualTo(HayEvidencia)
        assertThat(conFalla.declarar(venue, cobro, "918273")).isEqualTo(HayEvidencia)

        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("INDETERMINADO")
        assertThat(fila.lastError).isNull()
    }

    @Test fun `P1 con evidencia positiva sin guardar tampoco - el dinero que dijo el servidor manda`() = runBlocking<Unit> {
        val cobro = duda("a-1", serverAnsweredAt = 1L)
        val sinDisco = spyk(ledger)
        coEvery { sinDisco.marcarEvidenciaPositivaDelServidor(venue, "a-1", any()) } returns Result.failure(java.io.IOException("disco lleno"))
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Aprobada("a-1")

        assertThat(declaracionCon(sinDisco).declarar(venue, cobro, "918273")).isEqualTo(HayEvidencia)

        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    // F1 (tarea 3): reemplaza «sin el permiso de conciliar no consulta ni declara»: ya no decide el permiso de la sesión sino el PIN.
    @Test fun `F1 sin el permiso de conciliar la consulta se ofrece igual, y sin PIN no se declara`() = runBlocking<Unit> {
        conPermisos("payments:create")
        val cobro = duda("a-1", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")

        assertThat(declaracion.preparar(venue, cobro)).isNull()
        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(PidePin)

        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    @Test fun `si el CAS ya no aplica no afirma nada`() = runBlocking<Unit> {
        val cobro = duda("a-1", serverAnsweredAt = 1L)
        val conCasPerdido = spyk(ledger)
        // F1 cierre TPV (I4): la firma cierra por el escritor único; ahí es donde el CAS ya no aplica.
        coEvery { conCasPerdido.aplicarLiberacionDelServidor(match { it.attemptId == "a-1" }, any()) } returns Result.success(false)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } answers { s6Firmada("a-1", thirdArg<NoInstrumentResolutionRequest>().resolutionId) }

        assertThat(declaracionCon(conCasPerdido).declarar(venue, cobro, "918273")).isEqualTo(Rechazado(generico))
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
        // F1: con la firma del gerente en Avoqado (antes, también sin red).
        coEvery { api.resolveNoInstrument(venue, "a-local", any()) } answers {
            s6Firmada("a-local", thirdArg<NoInstrumentResolutionRequest>().resolutionId)
        }
        assertThat(declaracionCon(libreta2).declarar(venue, declarables(libreta2).single(), "918273"))
            .isEqualTo(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado)

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

    /**
     * D22: el cuerpo EXACTO que la terminal manda al declarar un Pago rápido. El esquema del servidor es `.strict()`: una llave de
     * más (`requestId: null`, `supervisorPin: null`, `merchantAccountId`) sería un 409 y la declaración se quedaría sin llegar.
     */
    @Test fun `P1 Retrofit real D22 - un Pago rapido manda la clase nueva con el monto de la libreta y nada mas`() = runBlocking<Unit> {
        val transporte = apiReal()
        servidor!!.enqueue(json(200, """{"success":true,"attemptId":"a-1","requestId":null,"attempt":{"attemptId":"a-1","outcome":"NOT_RECORDED","processorEvidence":"NONE","resolution":null},"request":null}"""))
        servidor!!.enqueue(json(200, """{"success":true,"attemptId":"a-1","requestId":null,"attempt":{"attemptId":"a-1","outcome":"NOT_RECORDED","processorEvidence":"NONE","resolution":{"id":"${UUID.nameUUIDFromBytes("tpv:checked:$venue:a-1".toByteArray(Charsets.UTF_8))}","kind":"UNCHARGED_VERIFIED","staffId":"gerente-1","by":"SUPERVISOR_PIN"}},"request":null,"resolution":{"id":"x","acceptedAt":"y","by":"SUPERVISOR_PIN"}}"""))
        val cobro = duda("a-1", serverAnsweredAt = 1L)

        assertThat(declaracionCon(ledger, transporte).declarar(venue, cobro, "918273")).isEqualTo(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado)

        servidor!!.takeRequest() // la consulta S6 de antes
        val post = servidor!!.takeRequest()
        assertThat(post.method).isEqualTo("POST")
        assertThat(post.path).isEqualTo("/api/v1/tpv/venues/$venue/terminal-payment/attempts/a-1/no-instrument-resolution")
        val cuerpo = Gson().fromJson(post.body.readUtf8(), Map::class.java)
        assertThat(cuerpo).containsExactly(
            "resolutionId", UUID.nameUUIDFromBytes("tpv:checked:$venue:a-1".toByteArray(Charsets.UTF_8)).toString(),
            "statement", "UNCHARGED_VERIFIED", "statementVersion", 1.0, "amountCents", 10_000.0, "tipCents", 0.0,
            "supervisorPin", "918273", // F1: la firma del gerente
        )
    }

    @Test fun `P1 Retrofit real - el PIN viaja cuando lo hay, y el 403 del servidor pide PIN`() = runBlocking<Unit> {
        val transporte = apiReal()
        servidor!!.enqueue(json(403, """{"success":false,"released":false,"code":"SUPERVISOR_AUTHORIZATION_REQUIRED","message":"Pide a un gerente que ingrese su PIN para confirmar que este cobro no pasó."}"""))
        val cobro = duda("a-1", "r-1")

        assertThat(declaracionCon(ledger, transporte).declarar(venue, cobro, supervisorPin = "9182737465")).isEqualTo(PidePin)

        val cuerpo = Gson().fromJson(servidor!!.takeRequest().body.readUtf8(), Map::class.java)
        assertThat(cuerpo["supervisorPin"]).isEqualTo("9182737465")
    }

    // ── F1 mínimo, tarea 3 (17-nov): la firma del gerente llega a Avoqado con su PIN; hasta el acuse, A sigue pendiente ──────────

    /** El 2xx de la declaración revisada FIRMADA: el testimonio de Avoqado dice que lo firmó un PIN y de quién es. */
    private fun s6Firmada(id: String, resolutionId: String, gerente: String = "gerente-1", by: String = "SUPERVISOR_PIN") = s6(
        """{"success":true,"attemptId":"$id","requestId":null,"attempt":{"attemptId":"$id","outcome":"NOT_RECORDED","paymentId":null,""" +
            """"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,""" +
            """"resolution":{"id":"$resolutionId","kind":"UNCHARGED_VERIFIED","staffId":"$gerente","by":"$by"}},"request":null}""",
    )

    private val pinDelGerente = "918273"

    private fun idDeLaRevisada(attemptId: String) =
        UUID.nameUUIDFromBytes("tpv:checked:$venue:$attemptId".toByteArray(Charsets.UTF_8)).toString()

    @Test fun `F1 la firma del gerente viaja con su PIN y cierra A a nombre del gerente - aunque la cajera no tenga el permiso`() = runBlocking<Unit> {
        conPermisos("payments:create") // F1: la cajera ya no trae «conciliar»; firma el gerente con su PIN
        val cobro = duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        val cuerpo = slot<NoInstrumentResolutionRequest>()
        coEvery { api.resolveNoInstrument(venue, "a-1", capture(cuerpo)) } answers { s6Firmada("a-1", cuerpo.captured.resolutionId) }

        assertThat(declaracion.preparar(venue, cobro)).isNull()
        assertThat(declaracion.declarar(venue, cobro, pinDelGerente)).isEqualTo(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado)

        assertThat(cuerpo.captured.supervisorPin).isEqualTo(pinDelGerente)
        assertThat(cuerpo.captured.resolutionId).isEqualTo(idDeLaRevisada("a-1"))
        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("DESCARTADA")
        assertThat(fila.lastError).isEqualTo("declarado_sin_cobro:gerente-1:avoqado")
    }

    @Test fun `F1 sin PIN no se consulta ni se manda nada - se pide el PIN`() = runBlocking<Unit> {
        val cobro = duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)

        assertThat(declaracion.declarar(venue, cobro)).isEqualTo(PidePin)

        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    @Test fun `F1 firmar, perder la respuesta y reiniciar - A sigue pendiente hasta su acuse, y repetir deja una sola declaracion del gerente`() = runBlocking<Unit> {
        val cobro = duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        val cuerpos = mutableListOf<NoInstrumentResolutionRequest>()
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } coAnswers {
            cuerpos += thirdArg<NoInstrumentResolutionRequest>()
            throw java.net.SocketTimeoutException("la respuesta se perdió")
        }

        assertThat(declaracion.declarar(venue, cobro, pinDelGerente)).isEqualTo(SinRed)
        assertThat(SinRed.texto()).contains("firma del gerente necesita internet")
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")

        // Reinicio: otro proceso, la misma base. A sigue pendiente; repetir manda la MISMA declaración (replay en Avoqado).
        val otroProceso = libreta()
        val trasReiniciar = declaracionCon(otroProceso)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } coAnswers {
            cuerpos += thirdArg<NoInstrumentResolutionRequest>()
            s6Firmada("a-1", thirdArg<NoInstrumentResolutionRequest>().resolutionId)
        }
        assertThat(trasReiniciar.declarar(venue, cobro, pinDelGerente)).isEqualTo(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado)
        assertThat(cuerpos.map { it.resolutionId }.distinct()).containsExactly(idDeLaRevisada("a-1"))
        assertThat(dao.getById("a-1")!!.lastError).isEqualTo("declarado_sin_cobro:gerente-1:avoqado")
    }

    @Test fun `F1 el acuse tambien llega por la consulta - la recuperacion cierra A con el acuse firmado`() = runBlocking<Unit> {
        duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Firmada("a-1", idDeLaRevisada("a-1"))
        LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "a-1", System.currentTimeMillis(), estampar = false)
        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("DESCARTADA")
        assertThat(fila.lastError).isEqualTo("declarado_sin_cobro:gerente-1:avoqado")
    }

    /**
     * Codex código r1 #3: la cajera declaró ANTES de F1 (Avoqado la guardó por la sesión) y la respuesta se perdió. El SERVIDOR real
     * contesta SIEMPRE ese mismo acuse viejo: en la consulta y en el replay de la firma del gerente (mismo `resolutionId` y cuerpo).
     * Rechazarlo dejaba la cuenta sin salida; la terminal lo acepta, a nombre de quien lo declaró entonces.
     */
    @Test fun `F1 Codex r1 3 - el acuse viejo de la sesion que Avoqado ya guardo cierra A, por la consulta y por el replay de la firma`() = runBlocking<Unit> {
        duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Firmada("a-1", idDeLaRevisada("a-1"), gerente = "cajera", by = "SESSION")
        LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "a-1", System.currentTimeMillis(), estampar = false)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("DESCARTADA")
        assertThat(dao.getById("a-1")!!.lastError).isEqualTo("declarado_sin_cobro:cajera:avoqado")

        // El gerente firma B y Avoqado contesta el replay del acuse viejo de la cajera (lo que hace el servidor real).
        val cobro = duda("a-2", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-2") } returns s6Limpia("a-2")
        coEvery { api.resolveNoInstrument(venue, "a-2", any()) } answers {
            s6Firmada("a-2", thirdArg<NoInstrumentResolutionRequest>().resolutionId, gerente = "cajera", by = "SESSION")
        }
        // Codex código r2 #5: no firmó este gerente: la pantalla dice que ya estaba declarado, no «la firma del gerente».
        val dijo = declaracion.declarar(venue, cobro, pinDelGerente)
        assertThat(dijo).isEqualTo(ResultadoDeLaDeclaracion.YaDeclaradoEnAvoqado)
        // F1 bloque 7 (R5): el comprobante guardado se dice neutro: «Avoqado tiene guardada una declaración».
        assertThat(dijo!!.texto()).isEqualTo("Avoqado tiene guardada una declaración de este cobro. Ya se puede volver a cobrar.")
        assertThat(dijo.texto()).doesNotContain("gerente")
        assertThat(dao.getById("a-2")!!.lastError).isEqualTo("declarado_sin_cobro:cajera:avoqado")

        // Y si la consulta de ANTES de mandar el PIN ya trae el acuse viejo, tampoco se le atribuye al gerente.
        val otro = duda("a-3", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-3") } returns s6Firmada("a-3", idDeLaRevisada("a-3"), gerente = "cajera", by = "SESSION")
        assertThat(declaracion.declarar(venue, otro, pinDelGerente)).isEqualTo(ResultadoDeLaDeclaracion.YaDeclaradoEnAvoqado)
        coVerify(exactly = 0) { api.resolveNoInstrument(venue, "a-3", any()) }
    }

    /**
     * Codex código r2 #8: «no presentó tarjeta» también exige quién la declaró (`by` = SESSION o SUPERVISOR_PIN). Un acuse sin `by` o
     * `AUTOMATIC` no cierra A por la consulta.
     */
    @Test fun `F1 Codex r2 8 - no presento tarjeta sin by o AUTOMATIC no cierra A`() = runBlocking<Unit> {
        for ((id, by) in listOf("a-1" to null, "a-2" to "AUTOMATIC")) {
            duda(id, processor = "BLUMON", serverAnsweredAt = 1L)
            val conBy = if (by == null) "" else ""","by":"$by""""
            coEvery { api.getAttemptStatus(venue, id) } returns s6(
                """{"success":true,"attemptId":"$id","requestId":null,"attempt":{"attemptId":"$id","outcome":"NOT_RECORDED","paymentId":null,""" +
                    """"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,""" +
                    """"resolution":{"id":"x","kind":"NO_INSTRUMENT_PRESENTED","staffId":"cajera"$conBy}},"request":null}""",
            )
            LedgerServerRecovery(dao, ledger, api).recoverOne(venue, id, System.currentTimeMillis(), estampar = false)
            assertThat(dao.getById(id)!!.state).isEqualTo("INDETERMINADO")
        }
    }

    /** Codex código r2 #6: un veto (evidencia de otra terminal) no se presenta como del banco. */
    @Test fun `F1 Codex r2 6 - un veto con datos de otra terminal dice que Avoqado tiene evidencia, no que el banco registro algo`() = runBlocking<Unit> {
        val cobro = duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET server_veto = 'EVIDENCE_CONTRADICTION' WHERE attempt_id = 'a-1'")
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        val texto = declaracion.resultadoDe(venue, cobro).texto()
        // F1 bloque 7 (I4): el veto ajeno es un impedimento de Avoqado, no «evidencia de este cobro».
        assertThat(texto).isEqualTo("Avoqado encontró información que impide liberar este cobro.")
        assertThat(texto).doesNotContain("banco")
        // Con la aprobación del banco para ESTE intento, sí.
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET server_processor_evidence = 'APPROVED' WHERE attempt_id = 'a-1'")
        assertThat(declaracion.resultadoDe(venue, cobro)).isEqualTo(HayEvidencia)
    }

    /**
     * Codex código r2 #4 (**POS → TPV**): el primer intento de la solicitud quedó «no se cobró», el reintento se registró y después
     * llegó evidencia bancaria del primero. El cierre de caja lo cuenta: «Por revisar» también lo muestra (sólo para consultar).
     */
    @Test fun `F1 Codex r2 4 - un intento con dinero se ve aunque otro de su misma solicitud ya se registro`() = runBlocking<Unit> {
        val creado = System.currentTimeMillis() - 60_000
        for ((id, estado) in listOf("a-1" to "DESCARTADA", "a-2" to "REGISTRADO")) dao.insert(
            PaymentAttemptEntity(
                attemptId = id, venueId = venue, processor = "ANGELPAY", state = estado, amountCents = 10_000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = """{"terminalPaymentRequestId":"r-1"}""", terminalPaymentRequestId = "r-1",
                createdAt = creado, updatedAt = creado,
            ),
        )
        assertThat(ledger.marcarEvidenciaPositivaDelServidor(venue, "a-1").getOrThrow()).isTrue()

        val fila = declarables().single()
        assertThat(fila.attemptId).isEqualTo("a-1")
        assertThat(fila.declarable).isFalse()
    }

    /** Las dos declaraciones humanas, por la consulta: «no presentó tarjeta» también se acepta por la sesión (decisión de F1). */
    @Test fun `F1 Codex r1 2 - no presento tarjeta por la sesion que Avoqado guardo tambien cierra A`() = runBlocking<Unit> {
        duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6(
            """{"success":true,"attemptId":"a-1","requestId":null,"attempt":{"attemptId":"a-1","outcome":"NOT_RECORDED","paymentId":null,""" +
                """"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,""" +
                """"resolution":{"id":"x","kind":"NO_INSTRUMENT_PRESENTED","staffId":"cajera","by":"SESSION"}},"request":null}""",
        )
        LedgerServerRecovery(dao, ledger, api).recoverOne(venue, "a-1", System.currentTimeMillis(), estampar = false)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("DESCARTADA")
    }

    /**
     * Codex código r1 #1 (N86, **TPV SOLA**): la terminal consulta a AngelPay por el intento A; mientras espera, el gerente firma y
     * Avoqado acusa; DESPUÉS el historial contesta «cobrado». Antes ese aprobado se perdía (la fila ya era DESCARTADA) y la cuenta
     * aceptaba otra tarjeta. Ahora queda la marca «hubo dinero»: la cuenta sigue apartada y la fila sale en «Por revisar» (#9).
     */
    @Test fun `F1 Codex r1 1 - el aprobado de AngelPay que llega despues de la firma deja la cuenta apartada y la fila a la vista`() = runBlocking<Unit> {
        val contexto = """{"venueId":"$venue","staffId":"cajera","orderId":"orden-7","idempotencyKey":"a-1","deviceSerialNumber":"terminal",""" +
            """"processorAffiliation":"afiliacion","amount":100.00,"tip":0,"merchantAccountId":"merchant"}"""
        val creado = System.currentTimeMillis() - 60_000
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "a-1", venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 10_000, tipCents = 0,
                recordingRoute = "ORDER", paymentContextJson = contexto, serverAnsweredAt = 1L, createdAt = creado, updatedAt = creado,
            ),
        )
        val cobro = declarable("a-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } answers { s6Firmada("a-1", thirdArg<NoInstrumentResolutionRequest>().resolutionId) }
        val verificador = mockk<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier>()
        coEvery { verificador.verificar("a-1", "terminal", any(), any(), any(), "afiliacion") } coAnswers {
            assertThat(declaracion.declarar(venue, cobro, pinDelGerente)).isEqualTo(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado)
            com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro.Cobrado("AUTH", "REF", null)
        }

        LedgerUnknownRecovery(dao, ledger, verificador).recover(venue, System.currentTimeMillis() + 600_000)

        // Codex código r2 #1: no sólo la marca — la fila se REABRE con la referencia y la autorización, para registrar la venta.
        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("HOST_RESPONDIO")
        assertThat(fila.hostApproved).isTrue()
        assertThat(fila.referenceNumber).isEqualTo("REF")
        assertThat(fila.authCode).isEqualTo("AUTH")
        assertThat(ledger.retencionDeLaVenta("orden-7")?.attemptId).isEqualTo("a-1")
        assertThat(ledger.openAttempt("a-2", venue, "ANGELPAY", 10_000, 0, "ORDER", """{"orderId":"orden-7"}""")).isFalse()
    }

    /** El contexto completo, como lo guarda la N86 (Gson del contexto + la afiliación): la recuperación de aprobaciones lo reconstruye. */
    private fun contextoTpvSola(id: String): String = Gson().toJsonTree(
        com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment(
            venueId = venue, staffId = "cajera", amount = BigDecimal("100.00"), tip = BigDecimal.ZERO, merchantAccountId = "merchant",
            deviceSerialNumber = "terminal", idempotencyKey = id, orderId = "orden-7",
        ),
    ).asJsonObject.apply { addProperty("processorAffiliation", "afiliacion") }.toString()

    /**
     * Codex código r2 #1, orden O1 con reinicio (`pasada-aprobado-tardio.md`, E1 × O1): la app se reinicia tras el resultado incierto y el
     * gerente firma ANTES de la primera consulta a AngelPay. Antes la fila declarada ya no se consultaba: otra tarjeta de la cuenta
     * entraba y, si el banco sí había cobrado, nadie registraba la venta (en TPV SOLA el aviso de AngelPay no crea nada). Ahora la
     * recuperación la sigue consultando; con «cobrado» la reabre con referencia y autorización, la cuenta queda frenada y
     * `LedgerApprovalRecovery` registra UNA venta con la llave del intento (el SERVIDOR deduplica por esa llave).
     */
    @Test fun `F1 Codex r2 1 - O1 con reinicio - AngelPay sigue consultando la fila firmada, la reabre y se registra UNA venta`() = runBlocking<Unit> {
        val creado = System.currentTimeMillis() - 10 * 60_000
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "a-1", venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 10_000, tipCents = 0,
                recordingRoute = "ORDER", paymentContextJson = contextoTpvSola("a-1"), serverAnsweredAt = 1L, createdAt = creado, updatedAt = creado,
            ),
        )
        val reiniciada = libreta()
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } answers { s6Firmada("a-1", thirdArg<NoInstrumentResolutionRequest>().resolutionId) }
        assertThat(declaracionCon(reiniciada).declarar(venue, declarables(reiniciada).single(), pinDelGerente))
            .isEqualTo(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado)
        assertThat(reiniciada.retencionDeLaVenta("orden-7")).isNull() // lo decidió una persona

        val verificador = mockk<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier>()
        coEvery { verificador.verificar("a-1", "terminal", any(), any(), any(), "afiliacion") } returns
            com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro.Cobrado("AUTH", "REF", null)
        LedgerUnknownRecovery(dao, reiniciada, verificador).recover(venue, System.currentTimeMillis() + 600_000)

        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("HOST_RESPONDIO")
        assertThat(fila.hostApproved).isTrue()
        assertThat(fila.referenceNumber).isEqualTo("REF")
        assertThat(fila.authCode).isEqualTo("AUTH")
        assertThat(fila.serverOutcome).isNull()
        assertThat(fila.lastError).isEqualTo("aprobado_tras_declaracion:declarado_sin_cobro:gerente-1:avoqado")
        assertThat(reiniciada.retencionDeLaVenta("orden-7")?.attemptId).isEqualTo("a-1")
        assertThat(reiniciada.openAttempt("a-2", venue, "ANGELPAY", 10_000, 0, "ORDER", """{"orderId":"orden-7"}""")).isFalse()
        assertThat(reiniciada.retencionDelAparato()).isNull() // la fila reabierta no aparta la terminal

        // El camino de siempre: una venta, con la llave del intento, la referencia y la autorización. La segunda pasada no repite.
        val fast = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder>()
        val order = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder>()
        val llaves = mutableListOf<String?>()
        coEvery { order.recordPayment(any(), any(), "AUTH", "REF") } answers {
            llaves += firstArg<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>().idempotencyKey
            Result.success(com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt("payment", "url", "key", BigDecimal("100.00"), BigDecimal("0.00")))
        }
        val despues = System.currentTimeMillis() + 900_000
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover(venue, despues)).isEqualTo(1)
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover(venue, despues + 1)).isEqualTo(0)
        assertThat(llaves).containsExactly("a-1")
        assertThat(dao.getById("a-1")!!.state).isEqualTo("REGISTRADO")
    }

    /** E1: lo que no cerró una persona (la ventana, «no salió al banco», un rechazo) no se vuelve a consultar. */
    @Test fun `F1 Codex r2 1 - lo que no cerro una persona no se vuelve a consultar en AngelPay`() = runBlocking<Unit> {
        val creado = System.currentTimeMillis() - 10 * 60_000
        for ((id, ajuste) in listOf(
            "v" to "server_outcome = 'RELEASED_NO_EVIDENCE'",
            "s" to "last_error = 'sin_autorizacion:sdk'",
            "r" to "host_approved = 0, last_error = 'declarado_sin_cobro:gerente-1:avoqado'",
        )) {
            dao.insert(
                PaymentAttemptEntity(
                    attemptId = id, venueId = venue, processor = "ANGELPAY", state = "DESCARTADA", amountCents = 10_000, tipCents = 0,
                    recordingRoute = "ORDER", paymentContextJson = contextoTpvSola(id), createdAt = creado, updatedAt = creado,
                ),
            )
            db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET $ajuste WHERE attempt_id = '$id'")
        }
        val verificador = mockk<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier>()
        LedgerUnknownRecovery(dao, ledger, verificador).recover(venue, System.currentTimeMillis() + 600_000)
        coVerify(exactly = 0) { verificador.verificar(any(), any(), any(), any(), any(), any()) }
        for (id in listOf("v", "s", "r")) assertThat(dao.getById(id)!!.state).isEqualTo("DESCARTADA")
    }

    /**
     * F1 bloque 2 (R1; sustituye la reapertura de r2 #1, rama `nad53a00`): la respuesta del banco EN ESTE PROCESO llega para una venta de
     * la PAX que una persona ya declaró. Ya no se reabre: con las puertas del cese una firma nueva no puede adelantarse al SDK, y reabrir
     * dejaba registrar una venta que el chip podía revertir (Codex código r3 #4). Lo que acredite el SERVIDOR la marca por su camino.
     */
    @Test fun `F1 bloque 2 - E2 la aprobacion del banco sobre una venta declarada de la PAX no la reabre`() = runBlocking<Unit> {
        val creado = System.currentTimeMillis() - 60_000
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "a-1", venueId = venue, processor = "BLUMON", state = "DESCARTADA", amountCents = 10_000, tipCents = 0,
                recordingRoute = "ORDER", paymentContextJson = """{"orderId":"orden-7"}""", serverAnsweredAt = 1L,
                lastError = "declarado_sin_cobro:gerente-1:avoqado", createdAt = creado, updatedAt = creado,
            ),
        )
        assertThat(ledger.markHostResponded("a-1", approved = true, operationId = "op-1", referenceNumber = "REF", authCode = "AUTH")).isFalse()
        val declarada = dao.getById("a-1")!!
        assertThat(declarada.state).isEqualTo("DESCARTADA")
        assertThat(declarada.hostApproved).isNull()

        // Un rechazo explícito del banco no se reabre con una aprobación: se queda como estaba.
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "a-2", venueId = venue, processor = "BLUMON", state = "DESCARTADA", amountCents = 10_000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = "{}", hostApproved = false, createdAt = creado, updatedAt = creado,
            ),
        )
        assertThat(ledger.markHostResponded("a-2", approved = true, operationId = "op-2", referenceNumber = "REF2", authCode = "AUTH2")).isFalse()
        assertThat(dao.getById("a-2")!!.state).isEqualTo("DESCARTADA")
    }

    /**
     * Codex código r1 #9: el SERVIDOR confirma evidencia bancaria DESPUÉS de que la fila quedó DESCARTADA. La fila frena la tarjeta de
     * su cuenta y el cierre de caja: también tiene que salir en «Por revisar», sólo para consultarla.
     */
    @Test fun `F1 Codex r1 9 - lo que frena el cierre por evidencia tardia sale en Por revisar, solo para consultar`() = runBlocking<Unit> {
        duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET state = 'DESCARTADA', last_error = 'declarado_sin_cobro:gerente-1:avoqado' WHERE attempt_id = 'a-1'")
        assertThat(declarables()).isEmpty()

        assertThat(ledger.marcarEvidenciaPositivaDelServidor(venue, "a-1").getOrThrow()).isTrue()

        val fila = declarables().single()
        assertThat(fila.clave).isEqualTo("a-1")
        assertThat(fila.declarable).isFalse()
    }

    /** Codex código r1 #11: una revisión de una persona no se presenta como respuesta del banco. */
    @Test fun `F1 Codex r1 11 - si otra persona ya lo reviso se dice eso, no que quedo como no cobrado`() = runBlocking<Unit> {
        val revisado = duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET state = 'DESCARTADA', last_error = 'declarado_sin_cobro:gerente-1:avoqado' WHERE attempt_id = 'a-1'")
        assertThat(declaracion.resultadoDe(venue, revisado).texto()).isEqualTo("Este cobro ya lo revisó otra persona: ya no está por revisar.")

        // Lo que soltó el silencio del banco (la ventana) no lo revisó nadie: no se dice que sí.
        val porVentana = duda("a-2", serverAnsweredAt = 1L)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET state = 'DESCARTADA', server_outcome = 'RELEASED_NO_EVIDENCE' WHERE attempt_id = 'a-2'")
        val texto = declaracion.resultadoDe(venue, porVentana).texto()
        assertThat(texto).doesNotContain("revisó otra persona")
        assertThat(texto).doesNotContain("no cobrado")
    }

    @Test fun `F1 un 403, un acuse de otro intento o una aprobacion simultanea nunca cierran A`() = runBlocking<Unit> {
        val cobro = duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")

        // 1 · El PIN no firma (de otro local, de un cajero o sin el permiso): se vuelve a pedir.
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } returns
            Response.error(403, """{"success":false,"code":"SUPERVISOR_AUTHORIZATION_REQUIRED"}""".toResponseBody("application/json".toMediaTypeOrNull()))
        assertThat(declaracion.declarar(venue, cobro, "0000")).isEqualTo(PidePin)
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")

        // 2 · Un 2xx con el acuse de OTRA declaración (otro intento), o sin quién lo declaró: no se cierra.
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } answers { s6Firmada("a-1", idDeLaRevisada("otro-intento")) }
        assertThat(declaracion.declarar(venue, cobro, pinDelGerente)).isEqualTo(Rechazado(generico))
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } answers {
            s6Firmada("a-1", thirdArg<NoInstrumentResolutionRequest>().resolutionId, by = "AUTOMATIC")
        }
        assertThat(declaracion.declarar(venue, cobro, pinDelGerente)).isEqualTo(Rechazado(generico))
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")

        // 3 · El banco aprueba mientras viaja la firma: la libreta conserva A con su dinero.
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } coAnswers {
            db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET server_processor_evidence = 'APPROVED' WHERE attempt_id = 'a-1'")
            s6Firmada("a-1", thirdArg<NoInstrumentResolutionRequest>().resolutionId)
        }
        assertThat(declaracion.declarar(venue, cobro, pinDelGerente)).isNotEqualTo(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado)
        val fila = dao.getById("a-1")!!
        assertThat(fila.state).isEqualTo("INDETERMINADO")
        assertThat(fila.serverProcessorEvidence).isEqualTo("APPROVED")
    }

    @Test fun `F1 el PIN nunca se guarda - ni en la libreta ni en el almacen`() = runBlocking<Unit> {
        val cobro = duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } answers { s6Firmada("a-1", thirdArg<NoInstrumentResolutionRequest>().resolutionId) }

        declaracion.declarar(venue, cobro, pinDelGerente)

        assertThat(Gson().toJson(dao.getById("a-1"))).doesNotContain(pinDelGerente)
        verify(exactly = 0) { storage.putString(any(), match { it.contains(pinDelGerente) }) }
    }

    // ═══════════════════ F1 bloque 7 (I4): el comprobante y el veto ═══════════════════

    /** Dos gerentes con el MISMO id: el segundo recibe el comprobante guardado del primero (`alreadyStored`), nunca «quedó tu firma». */
    @Test fun `dos_gerentes_mismo_id_se_muestra_el_acuse_del_primero`() = runBlocking<Unit> {
        val cobro = duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } answers {
            val id = thirdArg<NoInstrumentResolutionRequest>().resolutionId
            s6(
                """{"success":true,"attemptId":"a-1","requestId":null,"attempt":{"attemptId":"a-1","outcome":"NOT_RECORDED","paymentId":null,""" +
                    """"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,""" +
                    """"resolution":{"id":"$id","kind":"UNCHARGED_VERIFIED","staffId":"gerente-1","by":"SUPERVISOR_PIN"}},"request":null,""" +
                    """"declarationReceipt":{"id":"$id","kind":"UNCHARGED_VERIFIED","by":"SUPERVISOR_PIN","staffId":"gerente-1",""" +
                    """"acceptedAt":"2026-11-18T19:02:11.481Z","scope":"ATTEMPT","attemptId":"a-1","requestId":null,""" +
                    """"humanSignature":true,"alreadyStored":true}}""",
            )
        }
        val resultado = declaracion.declarar(venue, cobro, pinDelGerente)
        assertThat(resultado).isEqualTo(ResultadoDeLaDeclaracion.YaDeclaradoEnAvoqado)
        assertThat(resultado!!.texto()).isEqualTo("Avoqado tiene guardada una declaración de este cobro. Ya se puede volver a cobrar.")
        assertThat(resultado.texto()).doesNotContain("firma del gerente")
    }

    /**
     * 🔴 Codex final r1 #6 · POS → TPV: la primera respuesta a la firma se perdió; el gerente reintenta y Avoqado devuelve el comprobante
     * ya guardado (`alreadyStored`). La relectura de ANTES de decir nada trae el aprobado tardío: manda el dinero, nunca «Ya se puede
     * volver a cobrar». Orden: dinero/aprobado → impedimento → comprobante.
     */
    @Test fun `comprobante_repetido_con_aprobado_tardio_no_dice_volver_a_cobrar`() = runBlocking<Unit> {
        val cobro = duda("a-1", requestId = "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns Response.success(
            Gson().fromJson(
                """{"success":true,"released":true,"status":"FAILED","requestId":"r-1","paymentId":null,"outcome":"NOT_CHARGED",""" +
                    """"message":"Terminal liberada.","declarationReceipt":{"id":"${idDe("r-1")}","kind":"UNCHARGED_VERIFIED",""" +
                    """"by":"SUPERVISOR_PIN","staffId":"gerente-1","acceptedAt":"2026-11-19T12:00:00.000Z","scope":"REQUEST",""" +
                    """"attemptId":null,"requestId":"r-1","humanSignature":true,"alreadyStored":true}}""",
                DeclaracionSinCobroResponse::class.java,
            ),
        )
        // El banco aprobó tarde: la consulta de después del POST lo trae.
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6(
            """{"success":true,"attemptId":"a-1","requestId":"r-1","attempt":{"attemptId":"a-1","outcome":"NOT_RECORDED","paymentId":null,""" +
                """"processorEvidence":"APPROVED","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,""" +
                """"resolution":null},"request":{"requestId":"r-1","status":"FAILED","failureCode":"OPERATOR_RECONCILED_NO_CHARGE",""" +
                """"outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}}""",
        )
        val resultado = declaracion.declarar(venue, cobro, pinDelGerente)
        assertWithMessage("con evidencia de cobro no se dice «ya estaba declarado»").that(resultado)
            .isNotEqualTo(ResultadoDeLaDeclaracion.YaDeclaradoEnAvoqado)
        assertThat(resultado).isEqualTo(HayEvidencia)
        assertThat(resultado!!.texto()).doesNotContain("volver a cobrar")
        assertThat(dao.getById("a-1")!!.state).isEqualTo("INDETERMINADO")
    }

    /**
     * 🔴 Codex final r1 #7 · TPV · PAGO RÁPIDO · N86: el gerente pulsa «Firmar» y la consulta previa al envío encuentra que la ventana
     * automática acaba de cerrar el intento. Nadie firmó: no se presenta como «Avoqado tiene guardada una declaración».
     */
    @Test fun `cierre_automatico_al_firmar_no_se_presenta_como_declaracion`() = runBlocking<Unit> {
        val cobro = duda("a-1", processor = "ANGELPAY", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } coAnswers {
            // Mientras se consulta, la ventana del banco (30 s sin evidencia) libera el Pago rápido.
            assertThat(ledger.aplicarLiberacionDelServidor(LiberacionDelServidor(venue, "a-1", null, "NO_EVIDENCE_AFTER_WINDOW")).getOrThrow())
                .isTrue()
            s6Limpia("a-1")
        }
        val resultado = declaracion.declarar(venue, cobro, pinDelGerente)
        assertThat(dao.getById("a-1")!!.serverOutcome).isEqualTo("RELEASED_NO_EVIDENCE")
        assertThat(resultado).isEqualTo(ResultadoDeLaDeclaracion.YaNoEstaPorRevisar)
        assertThat(resultado!!.texto())
            .isEqualTo("Este cobro ya no está por revisar: se resolvió en el POS o en Avoqado. Revisa Pagos para ver cómo quedó.")
        assertThat(resultado.texto()).doesNotContain("declaración")
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    /** El 409 genérico (evidencia de OTRA terminal entre la consulta y la firma) es un impedimento, nunca una aprobación del banco. */
    @Test fun `409_ajeno_entre_consulta_y_firma_local_no_es_APPROVED`() = runBlocking<Unit> {
        val cobro = duda("a-1", processor = "BLUMON", serverAnsweredAt = 1L)
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Limpia("a-1")
        coEvery { api.resolveNoInstrument(venue, "a-1", any()) } returns
            Response.error(409, """{"success":false,"code":"POSITIVE_EVIDENCE_EXISTS","message":"x"}""".toResponseBody("application/json".toMediaTypeOrNull()))
        val resultado = declaracion.declarar(venue, cobro, pinDelGerente)
        assertThat(resultado!!.texto()).isEqualTo("Avoqado encontró información que impide liberar este cobro.")
        val fila = dao.getById("a-1")!!
        assertWithMessage("no se escribe la aprobación del banco").that(fila.serverProcessorEvidence).isNull()
        assertThat(fila.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_IMPEDIMENTO_DEL_SERVIDOR)
        assertThat(fila.state).isEqualTo("INDETERMINADO")
    }

    /** POS → TPV: Avoqado libera, pero la relectura trae un veto (pago de otra terminal): no se afirma que el banco cobró. */
    @Test fun `veto_remoto_en_relectura_no_afirma_cobro`() = runBlocking<Unit> {
        val cobro = duda("a-1", requestId = "r-1")
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns liberada("r-1")
        coEvery { api.getAttemptStatus(venue, "a-1") } returns s6Contradiccion("a-1")
        val resultado = declaracion.declarar(venue, cobro, pinDelGerente)
        assertThat(resultado!!.texto()).isEqualTo("Avoqado encontró información que impide liberar este cobro.")
        assertThat(resultado.texto()).doesNotContain("banco")
        assertThat(dao.getById("a-1")!!.serverProcessorEvidence).isNull()
    }

    /** Un aviso S5 de OTRA solicitud no marca este intento antes de validar a quién pertenece. */
    @Test fun `S5_de_otra_solicitud_no_marca_el_intento_antes_de_validar`() = runBlocking<Unit> {
        duda("a-1", requestId = "r-1")
        val ajeno = VeredictoDeIntento.desdeAvisoS5(
            venueId = venue, requestId = "r-OTRA", attemptId = "a-1", paymentId = "pay-x", via = "webhook", amountCents = 10_000, tipCents = 0,
        )
        val r = ledger.aplicarVeredictoDelServidor(ajeno).getOrThrow()
        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.RECHAZADO_PERTENENCIA)
        val fila = dao.getById("a-1")!!
        assertWithMessage("no queda la marca «hubo dinero» de otra solicitud").that(fila.serverProcessorEvidence).isNull()
        assertThat(fila.serverPaymentId).isNull()
    }

    /** Lo que firma una persona dice eso: que una persona revisó; nunca «el banco dijo que no». */
    @Test fun `firma_humana_no_dice_negativa_del_banco`() {
        for (r in listOf(ResultadoDeLaDeclaracion.DeclaradoEnAvoqado, ResultadoDeLaDeclaracion.YaDeclaradoEnAvoqado,
                ResultadoDeLaDeclaracion.YaResuelto, ResultadoDeLaDeclaracion.DeclaradoSoloEnLaTerminal)) {
            assertWithMessage(r.toString()).that(r.texto()).doesNotContain("banco")
            assertWithMessage(r.toString()).that(r.texto().lowercase()).doesNotContain("no se cobró")
        }
    }
}
