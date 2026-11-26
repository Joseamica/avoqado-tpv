package com.avoqado.cobropropio.servidor

import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.fixtures.arrancarEnIpv4
import com.avoqado.cobropropio.fixtures.baseLiteral
import com.avoqado.cobropropio.libreta.EstadoDelReporte
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.ResultadoParaReportar
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.CertificatePinner
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Una prueba por fila de CON §2.2 (`/resultado`), con los JSON reales del SERVIDOR (`responderErrorDelReverso`: detalles al mismo nivel). */
class ReversoDelServidorHttpTest {

    private val server = MockWebServer().apply { arrancarEnIpv4() }

    /** La credencial falsa: el JWT vigente y lo que contesta `renovar` (cuenta las renovaciones). */
    private class CredencialFalsa(var jwt: String? = "jwt-1", var nuevo: suspend () -> String? = { "jwt-2" }) : CredencialDelServidor {
        val rechazados = mutableListOf<String>()
        override fun vigente() = jwt
        override suspend fun renovar(rechazado: String, espera: Duration): String? {
            rechazados += rechazado
            return nuevo()?.also { jwt = it }
        }
    }

    private val credencial = CredencialFalsa()
    private val servidor = ReversoDelServidorHttp(server.baseLiteral(), credencial)
    private val id = AttemptId("0f8fad5bd9cb469fa165b0867728950e")
    private val venue = "cm3x9k2ab0001qz8f7h6d5s4t"

    @After fun cerrar() = server.shutdown()

    private fun contesta(http: Int, json: String? = null) = server.enqueue(
        MockResponse().setResponseCode(http).apply { if (json != null) setHeader("Content-Type", "application/json").setBody(json) },
    )

    private fun resultado(r: ResultadoDelReverso = confirmado) = runBlocking { servidor.reportarResultado(venue, id, r) }

    private val confirmado = ResultadoDelReverso("REVERSADA", null, null, "2026-11-18T18:00:07.123Z")

    /** R6-2: un campo PRESENTE con forma inválida invalida el acuse entero; nunca cuenta como ausente: se vuelve a mandar. */
    @Test fun `un campo presente pero invalido invalida el acuse`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1","paymentId":"con espacio"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
    }

    // ---- R1-3 / R3-3: el cliente propio y la credencial (v5 §2.2 y §2.5) ----

    @Test fun `cada peticion lleva el JWT vigente de la credencial`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}""")
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}""")
        resultado()
        resultado()
        assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
    }

    /** Ola final, 4ª parte · 5 (r25): una credencial que LANZA es «sin credencial»: la entrega se reintenta y no sale ningún POST. */
    @Test fun `una credencial que lanza es sin credencial y no sale ninguna peticion`() {
        val rota = object : CredencialDelServidor {
            override fun vigente(): String? = throw IllegalStateException("almacén de llaves caído")
            override suspend fun renovar(rechazado: String, espera: Duration): String? = null
        }
        val conRota = ReversoDelServidorHttp(server.baseLiteral(), rota)
        assertEquals(AcuseDelResultado.Reintentar, runBlocking { conRota.reportarResultado(venue, id, confirmado) })
        assertEquals(0, server.requestCount)
    }

    /** Un `renovar` que lanza cuenta como `null`: el 401 de `/resultado` se reintenta y no sale otro POST. */
    @Test fun `resultado con 401 y una renovacion que truena se reintenta`() {
        credencial.nuevo = { throw IllegalStateException("la app falló") }
        contesta(401, """{"message":"Unauthorized"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        assertEquals(1, server.requestCount)
    }

    /** R4-4: una entrega de `/resultado` lenta tiene plazo total; el barrido no se queda esperándola. */
    @Test(timeout = 20_000) fun `resultado con el cuerpo goteando vence en su plazo total`() {
        val rapido = ReversoDelServidorHttp(server.baseLiteral(), credencial, esperaDelResultado = 1.seconds)
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}""")
                .throttleBody(4, 500, TimeUnit.MILLISECONDS), // ~10 s para el cuerpo entero, sin que venza ninguna lectura
        )
        val inicio = System.nanoTime()
        assertEquals(AcuseDelResultado.Reintentar, runBlocking { rapido.reportarResultado(venue, id, confirmado) })
        // Ola final, 4ª parte: margen amplio (el cuerpo entero tardaría ~10 s; antes eran 600 ms, frágil en una máquina lenta).
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio) < 1_000 + 3_000)
    }

    /** Siempre contesta [r]: si OkHttp repitiera el POST, la cuenta de peticiones lo diría. */
    private fun siempre(r: () -> MockResponse) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = r()
        }
    }

    @Test fun `un 503 con Retry-After 0 del SERVIDOR sale una sola vez`() {
        siempre { MockResponse().setResponseCode(503).setHeader("Retry-After", "0") }
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        assertEquals(1, server.requestCount)
    }

    @Test fun `ni redirecciones ni 408 ni 401 con WWW-Authenticate repiten el POST`() {
        credencial.nuevo = { null }
        for (r in listOf<() -> MockResponse>(
            { MockResponse().setResponseCode(307).setHeader("Location", "/otra") },
            { MockResponse().setResponseCode(308).setHeader("Location", "/otra") },
            { MockResponse().setResponseCode(408) },
            { MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer") },
        )) {
            siempre(r)
            val antes = server.requestCount
            resultado()
            assertEquals(r().status, 1, server.requestCount - antes)
        }
    }

    @Test fun `el cliente efectivo no trae nada ajeno`() {
        val c = servidor.cliente
        assertTrue(c.interceptors.isEmpty() && c.networkInterceptors.isEmpty())
        assertSame(Authenticator.NONE, c.authenticator)
        assertSame(Authenticator.NONE, c.proxyAuthenticator)
        assertSame(CookieJar.NO_COOKIES, c.cookieJar)
        assertNull(c.cache)
        assertFalse(c.followRedirects || c.followSslRedirects || c.retryOnConnectionFailure)
        assertTrue(c.dispatcher.maxRequestsPerHost >= 64)
        assertSame(EventListener.NONE, c.eventListenerFactory.create(c.newCall(Request.Builder().url(server.baseLiteral()).build())))
        assertTrue(c.certificatePinner.pins.isEmpty())
        val conPinning = CertificatePinner.Builder().add("api.avoqado.io", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").build()
        assertEquals(conPinning.pins, ReversoDelServidorHttp(server.baseLiteral(), credencial, conPinning).cliente.certificatePinner.pins)
    }

    /** v5-9: nada de lo que llega del SERVIDOR (ids, paymentId, autorización, códigos) sale en un `toString()`. */
    @Test fun `lo que llega del SERVIDOR no se imprime`() {
        for (o in listOf<Any>(
            AcuseDelResultado.Guardado(EstadoDelReversoEnServidor.REVERSED, "cevt-secreto", "cpay-secreto"),
            AcuseDelResultado.Final(403, "SECRETO_CODE"),
        )) {
            assertFalse(o.toString(), Regex("(?i)secreto").containsMatchIn(o.toString()))
        }
    }

    @Test fun `resultado sin credencial se reintenta sin POST`() {
        credencial.jwt = null
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        assertEquals(0, server.requestCount)
    }

    /** R7-2: el 401 renueva por su código, antes de validar el cuerpo; un `code` sin forma no lo impide. */
    @Test fun `resultado 401 con code invalido tambien renueva`() {
        contesta(401, """{"message":"Unauthorized","code":"no valido"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        assertEquals(listOf("jwt-1"), credencial.rechazados)
    }

    @Test fun `resultado 401 renueva y la siguiente entrega sale con el token nuevo`() {
        contesta(401, """{"message":"Unauthorized"}""")
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        assertEquals(1, server.requestCount)
        assertEquals(listOf("jwt-1"), credencial.rechazados)
        assertEquals(AcuseDelResultado.Guardado(EstadoDelReversoEnServidor.REVERSED, "cevt1", null), resultado())
        assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer jwt-2", server.takeRequest().getHeader("Authorization"))
    }

    /** Un `code` sin forma (aquí, con forma de PAN) nunca sale en el acuse: invalida la respuesta y se vuelve a mandar. */
    @Test fun `un code sin forma no sale`() {
        contesta(403, """{"success":false,"code":"4000001234567899"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
    }

    // ---- §2.2 /resultado ----

    @Test fun `resultado manda la hora observada y no la de la cola`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}""")
        val r = ResultadoDelReverso.deLaFila(fila(ResultadoParaReportar.CONFIRMADO, hora = "2020-01-02T03:04:05.678Z"))!!
        resultado(r)
        val p = server.takeRequest()
        assertEquals("/api/v1/tpv/venues/$venue/terminal-payment/attempts/${id.valor}/reverso/resultado", p.path)
        val j = JsonParser.parseString(p.body.readUtf8()).asJsonObject
        assertEquals(setOf("descripcion", "reversadoEn"), j.keySet())
        assertEquals("REVERSADA", j["descripcion"].asString)
        assertEquals("2020-01-02T03:04:05.678Z", j["reversadoEn"].asString)
    }

    @Test fun `pudo entrar manda SIN_RESPUESTA`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversing","eventLogId":"cevt1"}""")
        resultado(ResultadoDelReverso.deLaFila(fila(ResultadoParaReportar.PUDO_ENTRAR))!!)
        val j = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals(setOf("codigo", "reversadoEn"), j.keySet())
        assertEquals("SIN_RESPUESTA", j["codigo"].asString)
    }

    @Test fun `rechazado manda su codigo`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reverse_failed","eventLogId":"cevt1"}""")
        resultado(ResultadoDelReverso.deLaFila(fila(ResultadoParaReportar.RECHAZADO, codigo = "TX_008"))!!)
        val j = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals(setOf("codigo", "reversadoEn"), j.keySet())
        assertEquals("TX_008", j["codigo"].asString)
    }

    @Test fun `una fila sin reporte o sin hora no arma resultado`() {
        assertNull(ResultadoDelReverso.deLaFila(fila(null)))
        assertNull(ResultadoDelReverso.deLaFila(fila(ResultadoParaReportar.CONFIRMADO, hora = null)))
    }

    @Test fun `operationNumber va como numero`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}""")
        resultado(confirmado.copy(operationNumber = 123_456))
        val j = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertTrue(j["operationNumber"].asJsonPrimitive.isNumber)
        assertEquals(123_456L, j["operationNumber"].asLong)
    }

    @Test fun `200 guarda el estado del SERVIDOR`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}""")
        assertEquals(AcuseDelResultado.Guardado(EstadoDelReversoEnServidor.REVERSED, "cevt1", null), resultado())
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"contradiction","eventLogId":"cevt1","paymentId":"cpay9"}""")
        assertEquals(AcuseDelResultado.Guardado(EstadoDelReversoEnServidor.CONTRADICTION, "cevt1", "cpay9"), resultado())
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reverse_failed","eventLogId":"cevt1"}""")
        assertEquals(AcuseDelResultado.Guardado(EstadoDelReversoEnServidor.REVERSE_FAILED, "cevt1", null), resultado())
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reverse_unconfirmed","eventLogId":"cevt1"}""")
        assertEquals(AcuseDelResultado.Guardado(EstadoDelReversoEnServidor.REVERSE_UNCONFIRMED, "cevt1", null), resultado())
    }

    @Test fun `200 sin forma se reintenta`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"otro","eventLogId":"cevt1"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        contesta(200, "basura")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
    }

    @Test fun `409 REVERSAL_RESULT_CONFLICT es Conflicto con el estado guardado`() {
        contesta(409, """{"estado":"reverse_failed","success":false,"code":"REVERSAL_RESULT_CONFLICT","message":"x"}""")
        assertEquals(AcuseDelResultado.Conflicto(EstadoDelReversoEnServidor.REVERSE_FAILED), resultado())
    }

    @Test fun `404 con o sin code es NoEncontrado`() {
        contesta(404, """{"success":false,"code":"ATTEMPT_NOT_FOUND","message":"x"}""")
        assertEquals(AcuseDelResultado.NoEncontrado, resultado())
        contesta(404, """{"message":"Not Found"}""")
        assertEquals(AcuseDelResultado.NoEncontrado, resultado())
    }

    @Test fun `401 en resultado se reintenta`() {
        contesta(401, """{"message":"Unauthorized"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
    }

    @Test fun `400 403 y 409 no blumon son Final`() {
        contesta(400, """{"message":"Validation failed","code":"VALIDATION_ERROR"}""")
        assertEquals(AcuseDelResultado.Final(400, "VALIDATION_ERROR"), resultado())
        contesta(403, """{"success":false,"code":"TERMINAL_IDENTITY_REQUIRED"}""")
        assertEquals(AcuseDelResultado.Final(403, "TERMINAL_IDENTITY_REQUIRED"), resultado())
        contesta(409, """{"success":false,"code":"ATTEMPT_NOT_BLUMON"}""")
        assertEquals(AcuseDelResultado.Final(409, "ATTEMPT_NOT_BLUMON"), resultado())
        assertTrue(credencial.rechazados.isEmpty()) // un 403 no renueva el JWT
    }

    @Test fun `503 5xx y sin red se reintentan`() {
        contesta(503, """{"success":false,"code":"REVERSO_NO_DISPONIBLE","message":"x"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        contesta(500, """{"message":"x"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        server.shutdown()
        assertEquals(AcuseDelResultado.Reintentar, resultado())
    }

    @Test fun `nada de la peticion lleva datos de la tarjeta`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}""")
        resultado()
        val p = server.takeRequest()
        assertFalse(Regex("[0-9]{12,}").containsMatchIn(p.body.readUtf8() + p.path))
    }

    private fun fila(r: ResultadoParaReportar?, hora: String? = "2026-11-18T18:00:07Z", codigo: String? = null) = IntentoEntity(
        attemptId = id.valor, operacion = Operacion.VENTA, venueId = venue, importeCentavos = 12_300, vinculado = true,
        posId = "1001", serieBlumon = "9000000001", ambiente = Ambiente.SANDBOX, versionSdk = "fase2", origen = Origen.VINCULADO_AL_POS,
        reporte = if (r == null) EstadoDelReporte.NO_APLICA else EstadoDelReporte.PENDIENTE, resultadoParaReportar = r,
        codigoParaReportar = codigo, horaObservadaIso = hora,
    )
}
