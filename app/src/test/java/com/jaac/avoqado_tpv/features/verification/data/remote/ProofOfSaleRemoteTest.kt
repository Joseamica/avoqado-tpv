package com.jaac.avoqado_tpv.features.verification.data.remote

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorCode
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// runBlocking, no runTest: el plazo de isDuplicate espera una respuesta REAL y el reloj virtual lo vencería al instante.
class ProofOfSaleRemoteTest {
    private lateinit var servidor: MockWebServer
    private lateinit var remote: ProofOfSaleRemote

    @Before fun setUp() {
        servidor = MockWebServer().also { it.start() }
        val api = Retrofit.Builder().baseUrl(servidor.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
        remote = ProofOfSaleRemote(api)
    }

    @After fun tearDown() { runCatching { servidor.shutdown() } }

    private fun fila(slot: String = "VINCULACION", kind: String = "PHOTO") = ProofOfSaleQueueEntity(
        idempotencyKey = "idem-1", kind = kind, venueId = "v1", staffId = "s1", paymentId = "pay-1",
        verificationId = "ver-1", slot = slot, localPath = null,
        linkingNumber = "5512345678", portedNumber = "5587654321",
        capturedAt = 1_760_000_000_000L, captureSeq = 7, captureDeviceId = "dev-A", createdAt = 1L,
    )

    private fun json(code: Int, body: String) =
        servidor.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    private fun fallo(block: suspend () -> Unit): BackendHttpException =
        assertThrows(BackendHttpException::class.java) { runBlocking { block() } }

    @Test fun `la foto manda captura y el numero de su ranura`() = runBlocking {
        json(200, """{"success":true,"verificationId":"ver-1","status":"PENDING","message":"ok","outcome":"APPLIED","photoOutcome":"APPLIED","linkingNumberOutcome":"APPLIED","linkingDuplicate":true}""")
        val r = remote.submitPhoto(fila(), "https://x/y.jpg")
        val req = servidor.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/tpv/verification/proof-of-sale")
        val b = JsonParser.parseString(req.body.readUtf8()).asJsonObject
        assertThat(b.get("paymentId").asString).isEqualTo("pay-1")
        assertThat(b.get("photoUrls").asJsonArray[0].asString).isEqualTo("https://x/y.jpg")
        assertThat(b.get("photoLabel").asString).isEqualTo("Vinculacion")
        assertThat(b.get("linkingNumber").asString).isEqualTo("5512345678")
        assertThat(b.has("portedNumber")).isFalse()
        assertThat(b.get("captureSeq").asLong).isEqualTo(7)
        assertThat(b.get("captureDeviceId").asString).isEqualTo("dev-A")
        assertThat(b.get("idempotencyKey").asString).isEqualTo("idem-1")
        assertThat(b.get("capturedAt").asString).isEqualTo("2025-10-09T08:53:20Z")
        assertThat(r.outcome).isEqualTo("APPLIED")
        assertThat(r.linkingNumberOutcome).isEqualTo("APPLIED")
        assertThat(r.linkingDuplicate).isTrue()
        assertThat(r.portedDuplicate).isNull()
    }

    @Test fun `la foto de portabilidad manda el portado y no el de vinculacion`() = runBlocking {
        json(200, """{"success":true,"verificationId":"ver-1","message":null}""")
        remote.submitPhoto(fila(slot = "PORTABILIDAD"), "https://x/p.jpg")
        val b = JsonParser.parseString(servidor.takeRequest().body.readUtf8()).asJsonObject
        assertThat(b.get("photoLabel").asString).isEqualTo("Portabilidad")
        assertThat(b.get("portedNumber").asString).isEqualTo("5587654321")
        assertThat(b.has("linkingNumber")).isFalse()
    }

    @Test fun `la respuesta de un servidor viejo sin campos nuevos se lee`() = runBlocking {
        json(200, """{"success":true,"verificationId":"ver-1","status":"COMPLETED","message":"ok"}""")
        val r = remote.submitPhoto(fila(), "u")
        assertThat(r.outcome).isNull()
        assertThat(r.status).isEqualTo("COMPLETED")
    }

    @Test fun `el numero suelto va a su ruta con la ranura y la captura`() = runBlocking {
        json(200, """{"success":true,"message":"Número guardado","verificationId":"ver-1","status":"PENDING","linkingNumberOutcome":"LOCKED_BY_BACKOFFICE"}""")
        val r = remote.submitNumber(fila(kind = "NUMBER_ONLY"))
        val req = servidor.takeRequest()
        assertThat(req.path).isEqualTo("/tpv/verification/ver-1/number")
        val b = JsonParser.parseString(req.body.readUtf8()).asJsonObject
        assertThat(b.get("slot").asString).isEqualTo("VINCULACION")
        assertThat(b.get("number").asString).isEqualTo("5512345678")
        assertThat(b.get("captureSeq").asLong).isEqualTo(7)
        assertThat(b.get("idempotencyKey").asString).isEqualTo("idem-1")
        assertThat(r.linkingNumberOutcome).isEqualTo("LOCKED_BY_BACKOFFICE")
    }

    @Test fun `400 SALE_REJECTED lleva su code y es permanente`() {
        json(400, """{"success":false,"message":"La venta fue rechazada","code":"SALE_REJECTED"}""")
        val e = fallo { remote.submitPhoto(fila(), "u") }
        assertThat(e.statusCode).isEqualTo(400)
        assertThat(e.errorCode).isEqualTo("SALE_REJECTED")
        assertThat(e.message).isEqualTo("La venta fue rechazada")
        assertThat(classifySyncFailure(e)).isInstanceOf(SyncOutcome.Permanent::class.java)
    }

    @Test fun `409 VERIFICATION_MOVED se reintenta`() {
        json(409, """{"success":false,"message":"La venta cambió","code":"VERIFICATION_MOVED"}""")
        val e = fallo { remote.submitNumber(fila(kind = "NUMBER_ONLY")) }
        assertThat(e.errorCode).isEqualTo("VERIFICATION_MOVED")
        assertThat(classifySyncFailure(e)).isEqualTo(SyncOutcome.Retryable)
    }

    @Test fun `500 se reintenta aunque no traiga code`() {
        json(500, "<html>boom</html>")
        val e = fallo { remote.submitPhoto(fila(), "u") }
        assertThat(e.statusCode).isEqualTo(500)
        assertThat(e.errorCode).isNull()
        assertThat(classifySyncFailure(e)).isEqualTo(SyncOutcome.Retryable)
    }

    @Test fun `el code del cuerpo es lo unico que se lee`() {
        assertThat(parseBackendErrorCode("""{"code":"MODULE_DISABLED"}""")).isEqualTo("MODULE_DISABLED")
        assertThat(parseBackendErrorCode("""{"message":"SALE_REJECTED"}""")).isNull()
        assertThat(parseBackendErrorCode("no es json")).isNull()
        assertThat(parseBackendErrorCode(null)).isNull()
    }

    @Test fun `isDuplicate contesta true y false con sus parametros`() = runBlocking {
        json(200, """{"success":true,"duplicate":true}""")
        json(200, """{"success":true,"duplicate":false}""")
        assertThat(remote.isDuplicate("5512345678", "linking", "ver-1")).isTrue()
        val req = servidor.takeRequest()
        assertThat(req.path).isEqualTo("/tpv/verification/number-check?number=5512345678&kind=linking&verificationId=ver-1")
        assertThat(remote.isDuplicate("5512345678", "ported", null)).isFalse()
        assertThat(servidor.takeRequest().path).isEqualTo("/tpv/verification/number-check?number=5512345678&kind=ported")
    }

    @Test fun `isDuplicate es null con error, sin red o pasado el plazo`() = runBlocking {
        json(403, """{"code":"LINKING_NUMBER_DISABLED","message":"x"}""")
        assertThat(remote.isDuplicate("5512345678", "linking", null)).isNull()

        remote.duplicateTimeoutMs = 300
        servidor.enqueue(MockResponse().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS).setBody("""{"duplicate":true}"""))
        assertThat(remote.isDuplicate("5512345678", "linking", null)).isNull()

        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        remote.duplicateTimeoutMs = 3_000
        assertThat(remote.isDuplicate("5512345678", "linking", null)).isNull()
    }

    @Test fun `actionable trae la pagina con total y cursor`() = runBlocking {
        json(200, """{"success":true,"total":205,"nextCursor":"abc","data":[{"id":"v1","paymentId":"p1","amount":100.0,"orderNumber":null,"date":"2026-11-01T00:00:00.000Z","serialNumbers":["8952"],"isPortabilidad":false,"photos":[],"requiredPhotos":1,"status":"FAILED","rejectionReasons":["X"],"reviewNotes":"n","linkingNumber":"5512345678","portedNumber":null,"linkingNumberLocked":true,"portedNumberLocked":false,"alerts":["LINKING_DUPLICATE"]}]}""")
        val page = remote.actionable("cur1")
        assertThat(servidor.takeRequest().path).isEqualTo("/tpv/verification/actionable?cursor=cur1")
        assertThat(page.total).isEqualTo(205)
        assertThat(page.nextCursor).isEqualTo("abc")
        val d = page.data.single()
        assertThat(d.status).isEqualTo("FAILED")
        assertThat(d.linkingNumberLocked).isTrue()
        assertThat(d.alerts).containsExactly("LINKING_DUPLICATE")
        json(200, """{"success":true,"total":0,"nextCursor":null,"data":[]}""")
        remote.actionable(null)
        assertThat(servidor.takeRequest().path).isEqualTo("/tpv/verification/actionable")
    }

    @Test fun `actionable sin modulo lleva MODULE_DISABLED`() {
        json(403, """{"success":false,"message":"x","code":"MODULE_DISABLED"}""")
        assertThat(fallo { remote.actionable(null) }.errorCode).isEqualTo("MODULE_DISABLED")
    }

    @Test fun `statuses devuelve un mapa y parte en bloques de 100`() = runBlocking {
        json(200, """{"success":true,"data":[{"paymentId":"a","status":"COMPLETED"},{"paymentId":"b","status":"NONE"}]}""")
        assertThat(remote.statuses(listOf("a", "b"))).containsExactly("a", "COMPLETED", "b", "NONE")
        assertThat(servidor.takeRequest().path).isEqualTo("/tpv/verification/status?paymentIds=a%2Cb")

        assertThat(remote.statuses(emptyList())).isEmpty()
        assertThat(servidor.requestCount).isEqualTo(1)

        json(200, """{"success":true,"data":[]}""")
        json(200, """{"success":true,"data":[]}""")
        remote.statuses((1..101).map { "p$it" })
        assertThat(servidor.requestCount).isEqualTo(3)
    }
}
