package com.jaac.avoqado_campo.red

import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.MutexDeSesion
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GuardiaDeSesionTest {
    private val cred = "c".repeat(64)
    private val refresh1 = "1".repeat(64)
    private val refresh2 = "2".repeat(64)
    private val sesion1 = Sesion("acc1", refresh1, "v1")

    private val quienSoy = QuienSoy(AlmacenEnMemoria()).also {
        it.guardarActivacion(DatosDeActivacion("cel", cred, "s1", "Ana", sesion1, listOf(Sucursal("v1", "Centro", null))))
    }
    private val server = MockWebServer().apply { start() }
    private val api = RedModule.crearRetrofit(server.url("/").toString(), RedModule.proveerCliente()).create(CampoApi::class.java)
    private val guardia = GuardiaDeSesion(api, quienSoy, MutexDeSesion())
    private val pedidos: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val renovaciones = AtomicInteger()

    @After fun cerrar() = server.shutdown()

    private fun error(status: Int, code: String) =
        MockResponse().setResponseCode(status).setBody("""{"code":"$code","message":"m"}""")
    private val renovado = MockResponse().setBody("""{"accessToken":"acc2","refreshToken":"$refresh2","venueId":"v1"}""")
    private val venta = """{"llave":"x"}""".toRequestBody("application/json; charset=utf-8".toMediaType())

    /** Ruta de venta: con `acc2` contesta 201; con otro token, [conTokenViejo]. `renovar` contesta [alRenovar]. */
    private fun servidor(conTokenViejo: () -> MockResponse = { error(401, "SESION_INVALIDA") }, alRenovar: () -> MockResponse = { renovado }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pedidos += request
                return when {
                    request.path == "/tpv/campo/renovar" -> { renovaciones.incrementAndGet(); alRenovar() }
                    request.getHeader("Authorization") == "Bearer acc2" -> MockResponse().setResponseCode(201).setBody("""{"ok":true}""")
                    else -> conTokenViejo()
                }
            }
        }
    }

    private suspend fun vender(venueId: String = "v1") = guardia.conSesion(venueId) { api.ventas(it, venueId, venta) }

    @Test fun `401 SESION_INVALIDA renueva, guarda los dos tokens y repite con el nuevo`() = runBlocking {
        servidor()
        val r = vender() as ResultadoConSesion.Respuesta
        assertEquals(201, r.r.code())
        assertEquals(Sesion("acc2", refresh2, "v1"), quienSoy.sesion)
        assertEquals(1, renovaciones.get())
        val ren = pedidos.first { it.path == "/tpv/campo/renovar" }
        assertEquals(cred, ren.getHeader("X-Celular-Credencial"))
        assertEquals("""{"refreshToken":"$refresh1"}""", ren.body.readUtf8())
        assertEquals(3, pedidos.size)
        pedidos.forEach { assertNull(it.getHeader("X-App-Version-Name")) }
    }

    @Test fun `dos llamadas a la vez con 401 hacen UN solo renovar`() = runBlocking {
        val ambas = CountDownLatch(2)
        servidor(conTokenViejo = { ambas.countDown(); ambas.await(5, TimeUnit.SECONDS); error(401, "SESION_INVALIDA") })
        val rs = List(2) { async(Dispatchers.IO) { vender() } }.awaitAll()
        rs.forEach { assertEquals(201, (it as ResultadoConSesion.Respuesta).r.code()) }
        assertEquals(1, renovaciones.get())
        assertEquals("acc2", quienSoy.sesion!!.accessToken)
    }

    @Test fun `renovar sin red es SIN_RED y la sesion no cambia`() = runBlocking {
        servidor(alRenovar = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) })
        assertEquals(MotivoSinSesion.SIN_RED, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertEquals(sesion1, quienSoy.sesion)
        assertFalse(quienSoy.necesitaPin)
    }

    @Test fun `renovar con 5xx, html o vacio es SIN_RED y los tokens no cambian`() = runBlocking {
        listOf(
            MockResponse().setResponseCode(503),
            MockResponse().setBody("<html><body>WiFi</body></html>"),
            MockResponse().setBody("{}"),
        ).forEach { resp ->
            servidor(alRenovar = { resp })
            assertEquals(MotivoSinSesion.SIN_RED, (vender() as ResultadoConSesion.SinSesion).motivo)
            assertEquals(sesion1, quienSoy.sesion)
            assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
        }
    }

    @Test fun `renovar 429 es ESPERA con Retry-After y sin el es 60 s`() = runBlocking {
        servidor(alRenovar = { error(429, "RATE_LIMIT").setHeader("Retry-After", "12") })
        val r = vender() as ResultadoConSesion.SinSesion
        assertEquals(MotivoSinSesion.ESPERA, r.motivo); assertEquals(12_000L, r.reintentaEnMs)
        servidor(alRenovar = { error(429, "RATE_LIMIT") })
        assertEquals(60_000L, (vender() as ResultadoConSesion.SinSesion).reintentaEnMs)
        assertEquals(sesion1, quienSoy.sesion)
    }

    @Test fun `renovar 401 SESION_INVALIDA es NECESITA_PIN, bloquea y no borra los tokens`() = runBlocking {
        servidor(alRenovar = { error(401, "SESION_INVALIDA") })
        assertEquals(MotivoSinSesion.NECESITA_PIN, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertTrue(quienSoy.necesitaPin); assertTrue(quienSoy.bloqueada)
        assertEquals(sesion1, quienSoy.sesion)
        // Con la bandera puesta ya no se llama al servidor hasta un `entrar` con señal.
        val antes = pedidos.size
        assertEquals(MotivoSinSesion.NECESITA_PIN, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertEquals(antes, pedidos.size)
    }

    @Test fun `renovar 401 SIN_ACCESO_A_LA_SUCURSAL es NECESITA_PIN`() = runBlocking {
        servidor(alRenovar = { error(401, "SIN_ACCESO_A_LA_SUCURSAL") })
        assertEquals(MotivoSinSesion.NECESITA_PIN, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertTrue(quienSoy.necesitaPin); assertTrue(quienSoy.bloqueada)
    }

    @Test fun `401 CELULAR_DESVINCULADO en renovar o en la ruta es DESVINCULADO`() = runBlocking {
        servidor(alRenovar = { error(401, "CELULAR_DESVINCULADO") })
        assertEquals(MotivoSinSesion.DESVINCULADO, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
        assertEquals(sesion1, quienSoy.sesion)
    }

    @Test fun `401 CELULAR_DESVINCULADO en la ruta no renueva`() = runBlocking {
        servidor(conTokenViejo = { error(401, "CELULAR_DESVINCULADO") })
        assertEquals(MotivoSinSesion.DESVINCULADO, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertEquals(0, renovaciones.get())
        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
    }

    @Test fun `renovar 401 CELULAR_DESCONOCIDO es DESCONOCIDO`() = runBlocking {
        servidor(alRenovar = { error(401, "CELULAR_DESCONOCIDO") })
        assertEquals(MotivoSinSesion.DESCONOCIDO, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertEquals(EstadoDelCelular.DESCONOCIDO, quienSoy.estado)
    }

    @Test fun `403 PERSONA_DADA_DE_BAJA es DADO_DE_BAJA`() = runBlocking {
        servidor(conTokenViejo = { error(403, "PERSONA_DADA_DE_BAJA") })
        assertEquals(MotivoSinSesion.DADO_DE_BAJA, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertEquals(0, renovaciones.get())
    }

    @Test fun `403 SIN_ACCESO_A_LA_SUCURSAL bloquea y devuelve la respuesta, que clasificar da como Definitiva`() = runBlocking {
        servidor(conTokenViejo = { error(403, "SIN_ACCESO_A_LA_SUCURSAL") })
        val r = (vender() as ResultadoConSesion.Respuesta).r
        assertTrue(quienSoy.necesitaPin); assertTrue(quienSoy.bloqueada)
        val c = clasificar(r.code(), r.errorBody()?.string(), r.headers()["Retry-After"])
        assertEquals("SIN_ACCESO_A_LA_SUCURSAL", (c as RespuestaDelServidor.Definitiva).code)
    }

    @Test fun `si el reintento tras renovar vuelve a dar 401, es NECESITA_PIN y nunca una respuesta de sesion`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path == "/tpv/campo/renovar") renovado else error(401, "SESION_INVALIDA")
        }
        assertEquals(MotivoSinSesion.NECESITA_PIN, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertTrue(quienSoy.necesitaPin)
    }

    @Test fun `otra sesion gano mientras se renovaba - se repite con el token vigente sin renovar otra vez`() = runBlocking {
        servidor(alRenovar = {
            quienSoy.guardarTokens("acc2", "3".repeat(64), "v1") // un `entrar` terminó antes
            MockResponse().setBody("""{"accessToken":"accX","refreshToken":"${"4".repeat(64)}","venueId":"v1"}""")
        })
        assertEquals(201, (vender() as ResultadoConSesion.Respuesta).r.code())
        assertEquals(Sesion("acc2", "3".repeat(64), "v1"), quienSoy.sesion)
        assertEquals(1, renovaciones.get())
    }

    @Test fun `venueId distinto del de la sesion es OTRA_SUCURSAL y el servidor no recibe nada`() = runBlocking {
        servidor()
        assertEquals(MotivoSinSesion.OTRA_SUCURSAL, (vender("v2") as ResultadoConSesion.SinSesion).motivo)
        assertEquals(0, server.requestCount)
    }

    @Test fun `la llamada sin red es SIN_RED`() = runBlocking {
        servidor(conTokenViejo = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) })
        assertEquals(MotivoSinSesion.SIN_RED, (vender() as ResultadoConSesion.SinSesion).motivo)
        assertEquals(0, renovaciones.get())
    }
}
