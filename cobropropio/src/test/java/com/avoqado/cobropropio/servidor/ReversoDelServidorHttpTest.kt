package com.avoqado.cobropropio.servidor

import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Una prueba por fila de CON §2.1 y §2.2, con los JSON reales del SERVIDOR (`responderErrorDelReverso`: detalles al mismo nivel). */
class ReversoDelServidorHttpTest {

    private val server = MockWebServer().apply { start() }

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
    private val servidor = ReversoDelServidorHttp(server.url("/").toString(), credencial)
    private val id = AttemptId("0f8fad5bd9cb469fa165b0867728950e")
    private val venue = "cm3x9k2ab0001qz8f7h6d5s4t"

    @After fun cerrar() = server.shutdown()

    private fun contesta(http: Int, json: String? = null) = server.enqueue(
        MockResponse().setResponseCode(http).apply { if (json != null) setHeader("Content-Type", "application/json").setBody(json) },
    )

    private fun permiso() = runBlocking { servidor.pedirPermiso(venue, id, 2.seconds) }

    private fun resultado(r: ResultadoDelReverso = confirmado) = runBlocking { servidor.reportarResultado(venue, id, r) }

    private val confirmado = ResultadoDelReverso("REVERSADA", null, null, "2026-11-18T18:00:07.123Z")

    // ---- §2.1 /intencion ----

    @Test fun `intencion manda cuerpo vacio exacto`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversing","eventLogId":"cevt1"}""")
        permiso()
        val p = server.takeRequest()
        assertEquals("POST", p.method)
        assertEquals("/api/v1/tpv/venues/$venue/terminal-payment/attempts/${id.valor}/reverso/intencion", p.path)
        assertEquals("{}", p.body.readUtf8())
        assertTrue(p.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun `200 reversing es Concedido`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversing","eventLogId":"cevt1"}""")
        assertEquals(PermisoDeReverso.Concedido("cevt1"), permiso())
    }

    @Test fun `200 con otro estado es YaResuelto y no autoriza`() {
        for (estado in EstadoDelReversoEnServidor.entries - EstadoDelReversoEnServidor.REVERSING) {
            contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"${estado.name.lowercase()}","eventLogId":"cevt2"}""")
            assertEquals(PermisoDeReverso.YaResuelto(estado, "cevt2"), permiso())
        }
    }

    @Test fun `200 de otro intento o sin estado conocido es ErrorDelServidor`() {
        contesta(200, """{"success":true,"attemptId":"1f8fad5bd9cb469fa165b0867728950e","estado":"reversing","eventLogId":"cevt1"}""")
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"otro","eventLogId":"cevt1"}""")
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
        contesta(200, "no es json")
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
    }

    @Test fun `409 ya registrado es YaCobrado con authorizationNumber null`() {
        contesta(
            409,
            """{"payment":{"paymentId":"cpay123","amountCents":12000,"tipCents":300,"authorizationNumber":null},""" +
                """"success":false,"code":"ATTEMPT_ALREADY_RECORDED","message":"El cobro de este intento ya está registrado: no se reversa."}""",
        )
        assertEquals(PermisoDeReverso.YaCobrado("cpay123", 12_000, 300, null), permiso())
    }

    @Test fun `409 ya registrado con autorizacion y sin propina`() {
        contesta(
            409,
            """{"payment":{"paymentId":"cpay123","amountCents":12000,"tipCents":null,"authorizationNumber":"A1B2C3"},""" +
                """"success":false,"code":"ATTEMPT_ALREADY_RECORDED","message":"x"}""",
        )
        assertEquals(PermisoDeReverso.YaCobrado("cpay123", 12_000, null, "A1B2C3"), permiso())
    }

    /**
     * R5-2 y R6-2: un campo PRESENTE con forma inválida invalida la respuesta entera; nunca cuenta como ausente ni como cero.
     * Base 10,000 + propina `500.0` para una venta de 10,000 nunca da `YaCobrado` (que el motor tomaría como aprobado).
     */
    @Test fun `un campo presente pero invalido invalida la respuesta`() {
        for (payment in listOf(
            """{"paymentId":"cpay123","amountCents":10000,"tipCents":500.0}""",
            """{"paymentId":"cpay123","amountCents":12000.0}""",
            """{"paymentId":"cpay123","amountCents":"10000"}""",
            """{"paymentId":"cpay123","amountCents":10000,"authorizationNumber":"con espacio"}""",
            """{"paymentId":"cpay123","amountCents":10000,"tipCents":true}""",
        )) {
            contesta(409, """{"payment":$payment,"success":false,"code":"ATTEMPT_ALREADY_RECORDED"}""")
            assertEquals(payment, PermisoDeReverso.ErrorDelServidor, permiso())
        }
        contesta(409, """{"payment":{"paymentId":"cpay123","amountCents":10000,"tipCents":null},"success":false,"code":"ATTEMPT_ALREADY_RECORDED"}""")
        assertEquals(PermisoDeReverso.YaCobrado("cpay123", 10_000, null, null), permiso()) // null de JSON = ausente
        contesta(409, """{"evidencia":{"paymentStatus":"no-es-enum","tipo":null},"success":false,"code":"ATTEMPT_HAS_PENDING_EVIDENCE"}""")
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
        contesta(404, """{"success":false,"code":"codigo con espacios"}""") // nunca «404 sin code» (que deja reversar lo local)
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1","paymentId":"con espacio"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
    }

    @Test fun `409 ya registrado sin paymentId con forma es ErrorDelServidor`() {
        contesta(409, """{"payment":{"paymentId":"pago con espacios"},"success":false,"code":"ATTEMPT_ALREADY_RECORDED"}""")
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
    }

    @Test fun `409 evidencia pendiente`() {
        contesta(
            409,
            """{"evidencia":{"paymentStatus":"COMPLETED","tipo":"POSSIBLE_SECOND_CAPTURE"},"success":false,""" +
                """"code":"ATTEMPT_HAS_PENDING_EVIDENCE","message":"x"}""",
        )
        assertEquals(PermisoDeReverso.EvidenciaPendiente("COMPLETED", "POSSIBLE_SECOND_CAPTURE"), permiso())
        contesta(409, """{"evidencia":{"paymentStatus":null,"tipo":null},"success":false,"code":"ATTEMPT_HAS_PENDING_EVIDENCE"}""")
        assertEquals(PermisoDeReverso.EvidenciaPendiente(null, null), permiso())
    }

    @Test fun `409 no es blumon`() {
        contesta(409, """{"success":false,"code":"ATTEMPT_NOT_BLUMON","message":"x"}""")
        assertEquals(PermisoDeReverso.NoEsBlumon, permiso())
    }

    @Test fun `404 ATTEMPT_NOT_FOUND es NoEncontrado`() {
        contesta(404, """{"success":false,"code":"ATTEMPT_NOT_FOUND","message":"x"}""")
        assertEquals(PermisoDeReverso.NoEncontrado, permiso())
    }

    @Test fun `404 sin code es ServidorSinSdkPropio`() {
        contesta(404, """{"message":"Not Found"}""")
        assertEquals(PermisoDeReverso.ServidorSinSdkPropio, permiso())
        contesta(404, "<html>Cannot POST</html>")
        assertEquals(PermisoDeReverso.ServidorSinSdkPropio, permiso())
    }

    @Test fun `503 REVERSO_NO_DISPONIBLE es SinRespuesta`() {
        contesta(503, """{"success":false,"code":"REVERSO_NO_DISPONIBLE","message":"No se pudo confirmar el permiso."}""")
        assertEquals(PermisoDeReverso.SinRespuesta, permiso())
    }

    @Test fun `500 es ErrorDelServidor`() {
        contesta(500, """{"message":"Ocurrió un error inesperado en el servidor."}""")
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
        contesta(502)
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
    }

    @Test fun `400 y 403 son Invalido con su code`() {
        contesta(400, """{"message":"Validation failed","code":"VALIDATION_ERROR","details":[]}""")
        assertEquals(PermisoDeReverso.Invalido(400, "VALIDATION_ERROR"), permiso())
        contesta(403, """{"success":false,"code":"TERMINAL_IDENTITY_REQUIRED"}""")
        assertEquals(PermisoDeReverso.Invalido(403, "TERMINAL_IDENTITY_REQUIRED"), permiso())
        assertTrue("un 403 no renueva", credencial.rechazados.isEmpty())
    }

    // ---- R1-3 / R3-3: el cliente propio y la credencial (v5 §2.2 y §2.5) ----

    @Test fun `cada peticion lleva el JWT vigente de la credencial`() {
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversing","eventLogId":"cevt1"}""")
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}""")
        permiso()
        resultado()
        assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `permiso sin credencial veta sin POST`() {
        credencial.jwt = null
        assertEquals(PermisoDeReverso.SinCredencial, permiso())
        assertEquals(0, server.requestCount)
    }

    /**
     * Ola final, 4ª parte · 5 (r25): una credencial que LANZA es «sin credencial», no «sin respuesta». Si la excepción salía, el
     * motor la volvía `SinRespuesta` y reversaba sin haberle preguntado al SERVIDOR.
     */
    @Test fun `una credencial que lanza es sin credencial y no sale ninguna peticion`() {
        val rota = object : CredencialDelServidor {
            override fun vigente(): String? = throw IllegalStateException("almacén de llaves caído")
            override suspend fun renovar(rechazado: String, espera: Duration): String? = null
        }
        val conRota = ReversoDelServidorHttp(server.url("/").toString(), rota)
        assertEquals(PermisoDeReverso.SinCredencial, runBlocking { conRota.pedirPermiso(venue, id, 2.seconds) })
        assertEquals(AcuseDelResultado.Reintentar, runBlocking { conRota.reportarResultado(venue, id, confirmado) })
        assertEquals(0, server.requestCount)
    }

    @Test fun `permiso con 401 renueva y pregunta una vez mas con el token nuevo`() {
        contesta(401, """{"message":"Unauthorized"}""")
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversing","eventLogId":"cevt1"}""")
        assertEquals(PermisoDeReverso.Concedido("cevt1"), permiso())
        assertEquals(listOf("jwt-1"), credencial.rechazados)
        assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer jwt-2", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    /** Un `renovar` que lanza cuenta como `null`: el 401 que ya llegó sigue vetando, nunca `SinRespuesta`. */
    @Test fun `permiso con 401 y una renovacion que truena veta`() {
        credencial.nuevo = { throw IllegalStateException("la app falló") }
        contesta(401, """{"message":"Unauthorized"}""")
        assertEquals(PermisoDeReverso.Invalido(401, null), permiso())
        contesta(401, """{"message":"Unauthorized"}""")
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        assertEquals(2, server.requestCount)
    }

    /** R4-4: una entrega de `/resultado` lenta tiene plazo total; el barrido no se queda esperándola. */
    @Test(timeout = 20_000) fun `resultado con el cuerpo goteando vence en su plazo total`() {
        val rapido = ReversoDelServidorHttp(server.url("/").toString(), credencial, esperaDelResultado = 1.seconds)
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

    @Test fun `permiso con 401 sin token nuevo veta sin otro POST`() {
        credencial.nuevo = { null }
        contesta(401, """{"message":"Unauthorized"}""")
        assertEquals(PermisoDeReverso.Invalido(401, null), permiso())
        assertEquals(1, server.requestCount)
    }

    @Test fun `permiso con 401 dos veces veta y no renueva mas`() {
        contesta(401, """{"message":"Unauthorized"}""")
        contesta(401, """{"message":"Unauthorized"}""")
        contesta(200, """{"success":true,"attemptId":"${id.valor}","estado":"reversing","eventLogId":"cevt1"}""")
        assertEquals(PermisoDeReverso.Invalido(401, null), permiso())
        assertEquals(2, server.requestCount)
        assertEquals(1, credencial.rechazados.size)
    }

    /** Llegó un 401: lo que siga nunca es `SinRespuesta` (que deja reversar sin permiso). */
    @Test(timeout = 20_000) fun `permiso con 401 y la segunda sin respuesta o 503 veta`() {
        contesta(401, """{"message":"Unauthorized"}""")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // Ola final, 4ª parte: 3 s (antes 1 s) para que el primer 401 llegue antes del plazo en una máquina lenta.
        assertEquals(PermisoDeReverso.Invalido(401, null), runBlocking { servidor.pedirPermiso(venue, id, 3.seconds) })
        contesta(401, """{"message":"Unauthorized"}""")
        contesta(503, """{"success":false,"code":"REVERSO_NO_DISPONIBLE"}""")
        assertEquals(PermisoDeReverso.Invalido(401, null), permiso())
    }

    /** La renovación cuenta contra el mismo plazo del permiso: una que se cuelga no lo alarga. */
    @Test(timeout = 20_000) fun `permiso con 401 y una renovacion colgada vence dentro del plazo y veta`() {
        credencial.nuevo = { kotlinx.coroutines.delay(60_000); "jwt-2" }
        contesta(401, """{"message":"Unauthorized"}""")
        val inicio = System.nanoTime()
        // Ola final, 4ª parte: plazo de 3 s (antes 1 s: el 401 debe llegar antes de vencer) y margen amplio (la renovación
        // colgada tarda 60 s; antes eran 400 ms, frágil en una máquina lenta).
        assertEquals(PermisoDeReverso.Invalido(401, null), runBlocking { servidor.pedirPermiso(venue, id, 3.seconds) })
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio) < 3_000 + 3_000)
        assertEquals(1, server.requestCount)
    }

    /** Siempre contesta [r]: si OkHttp repitiera el POST, la cuenta de peticiones lo diría. */
    private fun siempre(r: () -> MockResponse) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = r()
        }
    }

    @Test fun `un 503 con Retry-After 0 del SERVIDOR sale una sola vez`() {
        siempre { MockResponse().setResponseCode(503).setHeader("Retry-After", "0") }
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
        assertEquals(1, server.requestCount)
        assertEquals(AcuseDelResultado.Reintentar, resultado())
        assertEquals(2, server.requestCount)
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
        assertSame(EventListener.NONE, c.eventListenerFactory.create(c.newCall(Request.Builder().url(server.url("/")).build())))
        assertTrue(c.certificatePinner.pins.isEmpty())
        val conPinning = CertificatePinner.Builder().add("api.avoqado.io", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").build()
        assertEquals(conPinning.pins, ReversoDelServidorHttp(server.url("/").toString(), credencial, conPinning).cliente.certificatePinner.pins)
    }

    /** v5-9: nada de lo que llega del SERVIDOR (ids, paymentId, autorización, códigos) sale en un `toString()`. */
    @Test fun `lo que llega del SERVIDOR no se imprime`() {
        for (o in listOf<Any>(
            PermisoDeReverso.Concedido("cevt-secreto"),
            PermisoDeReverso.YaResuelto(EstadoDelReversoEnServidor.REVERSED, "cevt-secreto"),
            PermisoDeReverso.YaCobrado("cpay-secreto", 1, 2, "AUTSECRETO"),
            PermisoDeReverso.EvidenciaPendiente("SECRETO_UNO", "SECRETO_DOS"),
            PermisoDeReverso.Invalido(403, "SECRETO_CODE"),
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

    @Test fun `un code sin forma no sale y veta`() {
        contesta(403, """{"success":false,"code":"4000001234567899"}""")
        assertEquals(PermisoDeReverso.ErrorDelServidor, permiso())
    }

    @Test fun `tiempo vencido es SinRespuesta`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val inicio = System.nanoTime()
        assertEquals(PermisoDeReverso.SinRespuesta, runBlocking { servidor.pedirPermiso(venue, id, 300.milliseconds) })
        assertTrue("debió vencer por la espera", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio) < 5_000)
    }

    /**
     * R1-3: si llegó el código HTTP, el permiso nunca es `SinRespuesta`, aunque el cuerpo se atore: la lectura del cuerpo
     * cuenta contra el plazo de la llamada y, si no se alcanza a leer, el código se queda con `json = null` y veta.
     */
    @Test(timeout = 20_000) fun `409 o 500 con el cuerpo atorado nunca es SinRespuesta`() {
        val yaCobrado = """{"payment":{"paymentId":"cpay123","amountCents":12000},"success":false,"code":"ATTEMPT_ALREADY_RECORDED"}"""
        for ((http, esperado) in listOf(409 to PermisoDeReverso.Invalido(409, null), 500 to PermisoDeReverso.ErrorDelServidor)) {
            server.enqueue(
                MockResponse().setResponseCode(http).setHeader("Content-Type", "application/json").setBody(yaCobrado)
                    .throttleBody(4, 1, TimeUnit.SECONDS), // ~25 s para el cuerpo entero
            )
            val inicio = System.nanoTime()
            // Ola final, 4ª parte: plazo de 3 s (antes 1 s: el código debe llegar antes de vencer); el cuerpo entero tarda ~25 s.
            val p = runBlocking { servidor.pedirPermiso(venue, id, 3.seconds) }
            assertEquals("$http", esperado, p)
            assertTrue("$http: el cuerpo se lee dentro del plazo", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio) < 3_000 + 5_000)
        }
    }

    /**
     * r2 familia B: UN solo reloj decide, el de la corrutina, que arranca al pedir. OkHttp arranca el suyo al EJECUTAR: con la
     * única vía del `Dispatcher` ocupada, la llamada espera en su cola. El código que llegó se guarda antes de leer el cuerpo,
     * así que al vencer el plazo sale el 409 sin cuerpo, a tiempo y nunca `SinRespuesta`.
     */
    @Test fun `sin red es SinRespuesta`() {
        server.shutdown()
        assertEquals(PermisoDeReverso.SinRespuesta, permiso())
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
