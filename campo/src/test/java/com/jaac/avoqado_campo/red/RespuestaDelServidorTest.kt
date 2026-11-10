package com.jaac.avoqado_campo.red

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RespuestaDelServidorTest {

    private fun cuerpo(code: String, extra: String = "") = """{"code":"$code","message":"m"$extra}"""

    @Test fun `2xx con JSON es Ok`() {
        assertEquals(RespuestaDelServidor.Ok(201, """{"a":1}"""), clasificar(201, """{"a":1}""", null))
        assertEquals(RespuestaDelServidor.Ok(200, """{"a":1}"""), clasificar(200, """{"a":1}""", null))
    }

    @Test fun `2xx que no es JSON (portal cautivo) es Reintentable`() {
        assertEquals(RespuestaDelServidor.Reintentable(null, null), clasificar(200, "<html><body>Inicia sesión</body></html>", null))
        assertEquals(RespuestaDelServidor.Reintentable(null, null), clasificar(200, "", null))
    }

    @Test fun `5xx, 429 y 408 son Reintentable, con Retry-After en ms`() {
        assertEquals(RespuestaDelServidor.Reintentable(null, null), clasificar(502, "<html>", null))
        assertEquals(RespuestaDelServidor.Reintentable("X", null), clasificar(500, cuerpo("X"), null))
        assertEquals(RespuestaDelServidor.Reintentable("RATE_LIMIT", 30_000L), clasificar(429, cuerpo("RATE_LIMIT"), "30"))
        assertEquals(RespuestaDelServidor.Reintentable(null, null), clasificar(429, null, null))
        assertEquals(RespuestaDelServidor.Reintentable(null, null), clasificar(408, "<html>Request Timeout</html>", null))
    }

    @Test fun `codigos en proceso, OTRA_SUCURSAL y VERIFICATION_MOVED son Reintentable`() {
        listOf(
            "VENTA_EN_PROCESO", "CUSTODIA_EN_PROCESO", "CHECADA_EN_PROCESO", "ENTRADA_EN_PROCESO", "RETENIDOS_LLENOS",
            "APLICACION_EN_CURSO", "RETENIDO_EN_PROCESO", "VERIFICATION_MOVED",
        ).forEach { assertEquals(it, RespuestaDelServidor.Reintentable(it, null), clasificar(409, cuerpo(it), null)) }
        assertEquals(RespuestaDelServidor.Reintentable("OTRA_SUCURSAL", null), clasificar(403, cuerpo("OTRA_SUCURSAL"), null))
    }

    @Test fun `401 con cualquier codigo y 403 PERSONA_DADA_DE_BAJA son Sesion`() {
        listOf("SESION_INVALIDA", "SESION_CERRADA", "CELULAR_DESVINCULADO", "CELULAR_DESCONOCIDO").forEach {
            assertEquals(RespuestaDelServidor.Sesion(401, it), clasificar(401, cuerpo(it), null))
        }
        assertEquals(RespuestaDelServidor.Sesion(401, null), clasificar(401, null, null))
        assertEquals(RespuestaDelServidor.Sesion(403, "PERSONA_DADA_DE_BAJA"), clasificar(403, cuerpo("PERSONA_DADA_DE_BAJA"), null))
    }

    @Test fun `403 SIN_ACCESO_A_LA_SUCURSAL es Definitiva (R5-1)`() {
        val r = clasificar(403, cuerpo("SIN_ACCESO_A_LA_SUCURSAL"), null) as RespuestaDelServidor.Definitiva
        assertEquals(403, r.status); assertEquals("SIN_ACCESO_A_LA_SUCURSAL", r.code)
    }

    @Test fun `422 PRECIO_CAMBIO trae precioActual en datosJson`() {
        val r = clasificar(422, """{"code":"PRECIO_CAMBIO","message":"El precio cambió","precioActual":"150.00"}""", null)
            as RespuestaDelServidor.Definitiva
        assertEquals("PRECIO_CAMBIO", r.code); assertEquals("El precio cambió", r.message)
        assertEquals("150.00", JsonParser.parseString(r.datosJson).asJsonObject["precioActual"].asString)
    }

    @Test fun `400 sin cuerpo es Definitiva sin code y con mensaje generico`() {
        assertEquals(RespuestaDelServidor.Definitiva(400, null, "No se pudo (HTTP 400)", "{}"), clasificar(400, null, null))
        assertEquals(RespuestaDelServidor.Definitiva(404, null, "No se pudo (HTTP 404)", "{}"), clasificar(404, "<html>", null))
    }

    @Test fun `la evidencia con success false tambien da su code`() {
        val r = clasificar(400, """{"success":false,"message":"Falta la foto","code":"FOTO"}""", null) as RespuestaDelServidor.Definitiva
        assertEquals("FOTO", r.code); assertEquals("Falta la foto", r.message)
    }

    // ── llamarSeguro con Retrofit real ──

    private val server = MockWebServer().apply { start() }
    private val api = RedModule.crearRetrofit(server.url("/").toString(), RedModule.proveerCliente()).create(CampoApi::class.java)
    private val html = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html><body>WiFi</body></html>")
    private val cred = "c".repeat(64)

    @After fun cerrar() = server.shutdown()

    @Test fun `200 con html en entrar, renovar y modulos es Ilegible`() = runBlocking {
        repeat(3) { server.enqueue(html) }
        assertTrue(llamarSeguro { api.entrar(cred, EntrarBody("1234")) } is Llamada.Ilegible)
        assertTrue(llamarSeguro { api.renovar(cred, RenovarBody("a".repeat(64))) } is Llamada.Ilegible)
        assertTrue(llamarSeguro { api.modulos("v1") } is Llamada.Ilegible)
    }

    @Test fun `200 vacio o incompleto es Ilegible (valida)`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(llamarSeguro { api.renovar(cred, RenovarBody("a".repeat(64))) } is Llamada.Ilegible)
        server.enqueue(MockResponse().setBody("""{"accessToken":"x","refreshToken":"corto","venueId":"v1"}"""))
        assertTrue(llamarSeguro { api.renovar(cred, RenovarBody("a".repeat(64))) } is Llamada.Ilegible)
        server.enqueue(MockResponse().setBody("""{"estado":"RARO","horaDelServidor":"2026-11-10T00:00:00Z"}"""))
        assertTrue(llamarSeguro { api.latido(cred, LatidoBody(PorSubir(0), emptyList())) } is Llamada.Ilegible)
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"s"}]}"""))
        assertTrue(llamarSeguro { api.misSims("Bearer a", "v1") } is Llamada.Ilegible)
    }

    @Test fun `sin red es SinRed y un 401 se contesta sin leerse`() = runBlocking {
        val muerto = MockWebServer().apply { start() }
        val sinRed = RedModule.crearRetrofit(muerto.url("/").toString(), RedModule.proveerCliente()).create(CampoApi::class.java)
        muerto.shutdown()
        assertTrue(llamarSeguro { sinRed.modulos("v1") } is Llamada.SinRed)
        server.enqueue(MockResponse().setResponseCode(401).setBody("<html>"))
        val r = llamarSeguro { api.entrar(cred, EntrarBody("1234")) }
        assertEquals(401, (r as Llamada.Contesto).r.code())
    }

    @Test(expected = IllegalStateException::class)
    fun `un IllegalStateException que no es de Gson no se disfraza de Ilegible`() {
        runBlocking { llamarSeguro<Unit> { check(false) { "bug de quien llama" }; error("no llega") } }
    }

    @Test fun `entrar valido lleva la credencial, sin X-App-Version-Name, y se mapea a DatosDeEntrada`() = runBlocking {
        val refresh = "b".repeat(64)
        server.enqueue(MockResponse().setBody(
            """{"sesion":{"accessToken":"acc","refreshToken":"$refresh","venueId":"v1","expiraEn":86400},
               "sucursales":[{"venueId":"v1","nombre":"Centro","verificadorPin":{"algoritmo":"PBKDF2-SHA256","iteraciones":100000,"sal":"c2Fs","hash":"aA=="}},
                             {"venueId":"v2","nombre":"Norte","verificadorPin":null}],
               "horaDelServidor":"2026-11-10T00:00:00Z"}""",
        ))
        val r = llamarSeguro { api.entrar(cred, EntrarBody("1234")) } as Llamada.Contesto
        val req = server.takeRequest()
        assertEquals(cred, req.getHeader("X-Celular-Credencial"))
        assertNull(req.getHeader("X-App-Version-Name"))
        assertEquals("""{"pin":"1234"}""", req.body.readUtf8())
        val d = r.r.body()!!.aDatos()
        assertEquals("acc", d.sesion.accessToken); assertEquals(refresh, d.sesion.refreshToken)
        assertEquals(listOf("v1", "v2"), d.sucursales.map { it.venueId })
        assertEquals(100_000, d.sucursales[0].verificadorPin!!.iteraciones)
        assertNull(d.sucursales[1].verificadorPin)
    }

    @Test fun `activar valido se mapea y el aparato se recorta a 60`() = runBlocking {
        val hex = "d".repeat(64)
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"celularId":"cel1","credencial":"$hex","persona":{"staffId":"s1","nombre":"Ana"},
               "sesion":{"accessToken":"acc","refreshToken":"$hex","venueId":"v1","expiraEn":86400},
               "sucursales":[],"horaDelServidor":"2026-11-10T00:00:00Z","repetida":false}""",
        ))
        val r = llamarSeguro { api.activar(ActivarBody("ABC123", "1234", Aparato.de("ap-12345", "x".repeat(80), null, "14", "0.2.0"))) }
        val d = (r as Llamada.Contesto).r.body()!!.aDatos()
        assertEquals("cel1", d.celularId); assertEquals("s1", d.staffId); assertEquals("Ana", d.nombre)
        val enviado = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject["aparato"].asJsonObject
        assertEquals(60, enviado["marca"].asString.length)
        assertTrue(!enviado.has("modelo"))
    }
}
