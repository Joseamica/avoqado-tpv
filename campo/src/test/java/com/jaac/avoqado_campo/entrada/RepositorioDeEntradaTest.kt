package com.jaac.avoqado_campo.entrada

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.MutexDeSesion
import com.jaac.avoqado_campo.identidad.PinLocal
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.identidad.VerificadorPin
import com.jaac.avoqado_campo.red.Aparato
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.RedModule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositorioDeEntradaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    private var ahora = 1_000_000L
    private val reloj = RelojAnclado(almacen, { ahora }, { 5L }, { 1 })
    private val pinLocal = PinLocal(almacen, { ahora }, { 5L }, { 1 })
    private val copia = mockk<RepositorioDeCopia>(relaxed = true)
    /** Unconfined: el refresco arranca en el acto y, si no se suspende, ya terminó al volver `activar`/`entrar`. */
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val server = MockWebServer()
    private lateinit var api: CampoApi
    private val pedidos = CopyOnWriteArrayList<Pair<String, String>>() // ruta, cuerpo
    @Volatile private var responder: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, BaseDeCampo::class.java).allowMainThreadQueries().build()
        dao = db.piezaDao()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pedidos += request.path!! to request.body.readUtf8()
                return responder(request)
            }
        }
        server.start()
        api = RedModule.crearRetrofit(server.url("/").toString(), RedModule.proveerCliente()).create(CampoApi::class.java)
    }

    @After fun tearDown() {
        alcance.cancel()
        db.close()
        server.shutdown()
    }

    private fun repo() = RepositorioDeEntrada(
        api, quienSoy, reloj, pinLocal, MutexDeSesion(), dao, almacen, copia, alcance,
        aparato = { Aparato.de(it, "Motorola", "g54", "14", "0.2.0") }, ahora = { ahora },
    )

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private fun error(status: Int, body: String) = MockResponse().setResponseCode(status).setBody(body)
    private val corta = { _: RecordedRequest -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
    private fun activaciones() = pedidos.filter { it.first.endsWith("/tpv/campo/activar") }.map { JsonParser.parseString(it.second).asJsonObject }

    private fun activado() = """{"celularId":"cel1","credencial":"$CRED","persona":{"staffId":"s1","nombre":"Ana Pérez"},
        "sesion":{"accessToken":"acc1","refreshToken":"$REFRESH","venueId":"v1","expiraEn":900},
        "sucursales":[{"venueId":"v1","nombre":"Centro","verificadorPin":$VERIFICADOR_JSON}],
        "horaDelServidor":"2026-11-10T18:00:00Z","repetida":false}"""

    private fun entrada(access: String) = """{"sesion":{"accessToken":"$access","refreshToken":"${"2".repeat(64)}","venueId":"v1","expiraEn":900},
        "sucursales":[{"venueId":"v1","nombre":"Centro","verificadorPin":$VERIFICADOR_JSON}],"horaDelServidor":"2026-11-10T18:00:00Z"}"""

    /** Un celular ya activado (como lo deja `activar`), con una sucursal con verificador. */
    private fun yaActivado(sucursales: List<Sucursal> = listOf(Sucursal("v1", "Centro", VERIFICADOR))) =
        quienSoy.guardarActivacion(DatosDeActivacion("cel1", CRED, "s1", "Ana", Sesion("acc0", REFRESH, "v1"), sucursales))

    // ── Activar ──

    @Test fun `activar guarda credencial, sesion y sucursales, ancla el reloj y refresca la copia`() = runBlocking {
        responder = { ok(activado()) }
        assertEquals(ResultadoEntrada.Dentro, repo().activar(" ab c123 ", "1234"))
        assertEquals(CRED, quienSoy.credencial)
        assertEquals("v1", quienSoy.sesion?.venueId)
        assertEquals(listOf("Centro"), quienSoy.sucursales.map { it.nombre })
        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
        assertEquals("RELOJ_ANCLADO", reloj.ahora().origen)
        val cuerpo = activaciones().single()
        assertEquals("ABC123", cuerpo["codigo"].asString)
        assertEquals(quienSoy.aparatoId, cuerpo["aparato"].asJsonObject["aparatoId"].asString)
        assertFalse(repo().activacionPendiente())
        coVerify { copia.actualizar("v1") }
    }

    @Test fun `un refresco de la copia que nunca contesta no retrasa Dentro`() = runBlocking {
        coEvery { copia.actualizar(any()) } coAnswers { awaitCancellation() }
        responder = { ok(activado()) }
        assertEquals(ResultadoEntrada.Dentro, withTimeout(5_000) { repo().activar("ABC123", "1234") })
        responder = { ok(entrada("acc9")) }
        quienSoy.marcarBloqueada(true)
        assertEquals(ResultadoEntrada.Dentro, withTimeout(5_000) { repo().entrar("1234", null) })
        coVerify(exactly = 2) { copia.actualizar("v1") }
    }

    @Test fun `respuesta perdida - el reintento manda el MISMO cuerpo y con el 200 repetida queda activado`() = runBlocking {
        responder = corta
        val r = repo()
        assertEquals(ResultadoEntrada.Rechazo("Para activar el celular necesitas señal.", null), r.activar("ABC123", "1234"))
        assertTrue(r.activacionPendiente())
        assertNull(quienSoy.credencial)

        ahora += 5 * 60_000
        responder = { ok(activado().replace("\"repetida\":false", "\"repetida\":true")) }
        assertEquals(ResultadoEntrada.Dentro, r.activar("ZZZ999", "9999")) // la pantalla trae otro: va el guardado
        val todos = activaciones()
        assertTrue(todos.size >= 2)
        todos.forEach {
            assertEquals("ABC123", it["codigo"].asString)
            assertEquals("1234", it["pin"].asString)
            assertEquals(todos.first()["aparato"], it["aparato"])
        }
        assertEquals(CRED, quienSoy.credencial)
        assertFalse(r.activacionPendiente())
    }

    @Test fun `a los 10 min el cuerpo guardado se borra solo y va el nuevo`() = runBlocking {
        responder = corta
        val r = repo()
        r.activar("ABC123", "1234")
        ahora += 10 * 60_000
        assertFalse(r.activacionPendiente())
        pedidos.clear()
        responder = { ok(activado()) }
        assertEquals(ResultadoEntrada.Dentro, r.activar("XYZ789", "5678"))
        assertEquals("XYZ789", activaciones().single()["codigo"].asString)
    }

    @Test fun `401 PIN_INCORRECTO borra el cuerpo y el siguiente lleva el PIN NUEVO`() = runBlocking {
        responder = { error(401, """{"code":"PIN_INCORRECTO","message":"El PIN no es correcto.","intentosRestantes":4}""") }
        val r = repo()
        assertEquals(ResultadoEntrada.Rechazo("PIN incorrecto. Te quedan 4 intentos.", 4), r.activar("ABC123", "1111"))
        assertFalse(r.activacionPendiente())
        responder = { ok(activado()) }
        assertEquals(ResultadoEntrada.Dentro, r.activar("ABC123", "1234"))
        assertEquals(listOf("1111", "1234"), activaciones().map { it["pin"].asString })
    }

    @Test fun `un 200 con html conserva el cuerpo y no guarda nada`() = runBlocking {
        responder = { ok("<html>Inicia sesión en el WiFi</html>") }
        val r = repo()
        assertEquals(ResultadoEntrada.Rechazo("Para activar el celular necesitas señal.", null), r.activar("ABC123", "1234"))
        assertTrue(r.activacionPendiente())
        assertEquals(EstadoDelCelular.SIN_ACTIVAR, quienSoy.estado)
        assertNull(quienSoy.credencial)
        assertNull(quienSoy.sesion)
    }

    @Test fun `un 5xx conserva el cuerpo y otro 4xx lo borra`() = runBlocking {
        val r = repo()
        responder = { error(503, "{}") }
        r.activar("ABC123", "1234")
        assertTrue(r.activacionPendiente())
        responder = { error(410, """{"code":"CODIGO_VENCIDO","message":"Ese código ya venció."}""") }
        assertEquals(ResultadoEntrada.Rechazo("Ese código ya no sirve. Pide uno nuevo.", null), r.activar("ABC123", "1234"))
        assertFalse(r.activacionPendiente())
    }

    @Test fun `textos de los codigos del bloque 1`() = runBlocking {
        val r = repo()
        suspend fun con(code: String, status: Int = 400, mensaje: String = "del servidor"): ResultadoEntrada {
            responder = { error(status, """{"code":"$code","message":"$mensaje"}""") }
            return r.activar("ABC123", "1234")
        }
        assertEquals(ResultadoEntrada.Rechazo("Ese código no existe. Pide uno nuevo a tu supervisor.", null), con("CODIGO_INVALIDO", 404))
        assertEquals(ResultadoEntrada.Rechazo("Ese código ya se usó. Pide uno nuevo.", null), con("CODIGO_USADO", 409))
        assertEquals(ResultadoEntrada.Rechazo("Ese código ya no sirve. Pide uno nuevo.", null), con("CODIGO_ANULADO", 410))
        assertEquals(ResultadoEntrada.Rechazo("Pídele a tu supervisor que te ponga uno.", null), con("SIN_PIN", 409, "Pídele a tu supervisor que te ponga uno."))
        assertEquals(ResultadoEntrada.Rechazo("No tienes sucursal asignada. Avísale a tu supervisor.", null), con("SIN_SUCURSAL", 403))
    }

    @Test fun `con piezas en la cola no se manda nada`() = runBlocking {
        dao.insertar(Pieza(tipo = TipoPieza.VENTA, llave = "k1", staffId = "s0", venueId = "v1", cuerpoJson = "{}", estado = EstadoPieza.RETENIDA, creadaAt = 1, resumen = "r"))
        responder = { ok(activado()) }
        assertEquals(ResultadoEntrada.ColaNoVacia, repo().activar("ABC123", "1234"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `dos activar a la vez - el servidor recibe una sola peticion a la vez`() = runBlocking {
        val enVuelo = AtomicInteger()
        val maximo = AtomicInteger()
        responder = {
            maximo.accumulateAndGet(enVuelo.incrementAndGet(), ::maxOf)
            Thread.sleep(300)
            enVuelo.decrementAndGet()
            error(401, """{"code":"PIN_INCORRECTO","message":"El PIN no es correcto.","intentosRestantes":3}""")
        }
        val r = repo()
        listOf(async(Dispatchers.IO) { r.activar("ABC123", "1111") }, async(Dispatchers.IO) { r.activar("ABC123", "2222") }).awaitAll()
        assertEquals(2, activaciones().size)
        assertEquals(1, maximo.get())
    }

    // ── Entrar ──

    @Test fun `entrar con señal y PIN malo`() = runBlocking {
        yaActivado()
        responder = { error(401, """{"code":"PIN_INCORRECTO","message":"El PIN no es correcto.","intentosAntesDeEsperar":3}""") }
        assertEquals(ResultadoEntrada.Rechazo("PIN incorrecto. Te quedan 3 intentos.", 3), repo().entrar("0000", null))
        assertEquals(CRED, server.takeRequest().getHeader(CampoApi.CREDENCIAL))
    }

    @Test fun `ELIGE_SUCURSAL trae las dos, con el verificador que ya habia`() = runBlocking {
        yaActivado()
        responder = {
            error(409, """{"code":"ELIGE_SUCURSAL","message":"Elige en qué sucursal vas a trabajar.",
                "sucursales":[{"venueId":"v1","nombre":"Centro"},{"venueId":"v2","nombre":"Norte"}]}""")
        }
        val r = repo().entrar("1234", null)
        assertEquals(ResultadoEntrada.EligeSucursal(listOf(Sucursal("v1", "Centro", VERIFICADOR), Sucursal("v2", "Norte", null))), r)
    }

    @Test fun `PIN_BLOQUEADO con señal`() = runBlocking {
        yaActivado()
        responder = { error(429, """{"code":"PIN_BLOQUEADO","message":"Demasiados.","reintentaEn":120}""").setHeader("Retry-After", "120") }
        assertEquals(ResultadoEntrada.Bloqueado(120), repo().entrar("1234", null))
    }

    @Test fun `con señal y PIN bueno guarda la sesion y reinicia el contador local`() = runBlocking {
        yaActivado()
        quienSoy.marcarBloqueada(true)
        responder = corta
        val r = repo()
        r.entrar("0000", null); r.entrar("0000", null)
        assertEquals("2", almacen.leer("pin.fallos"))
        responder = { ok(entrada("acc9")) }
        assertEquals(ResultadoEntrada.Dentro, r.entrar("1234", null))
        assertEquals("acc9", quienSoy.sesion?.accessToken)
        assertFalse(quienSoy.bloqueada)
        assertNull(almacen.leer("pin.fallos"))
        coVerify { copia.actualizar("v1") }
    }

    @Test fun `sin red y verificador bueno entra sin señal`() = runBlocking {
        yaActivado()
        quienSoy.marcarBloqueada(true)
        responder = corta
        assertEquals(ResultadoEntrada.DentroSinSenal("Centro"), repo().entrar("1234", null))
        assertFalse(quienSoy.bloqueada)
        assertEquals("acc0", quienSoy.sesion?.accessToken) // la que ya tenía
    }

    @Test fun `sin red no se cambia de sucursal - rechaza antes del PIN y la sesion sigue en la anterior (H1)`() = runBlocking {
        yaActivado(listOf(Sucursal("v1", "Centro", VERIFICADOR), Sucursal("v2", "Norte", VERIFICADOR)))
        quienSoy.marcarBloqueada(true)
        responder = corta
        val r = repo()
        val rechazo = ResultadoEntrada.Rechazo("Para entrar a Norte necesitas señal. Sin señal puedes entrar a Centro.", null)
        assertEquals(rechazo, r.entrar("1234", "v2"))
        assertEquals(rechazo, r.entrar("0000", "v2")) // un PIN malo tampoco cuenta: no se verificó
        assertEquals("v1", quienSoy.sesion?.venueId)
        assertTrue(quienSoy.bloqueada)
        assertNull(almacen.leer("pin.fallos"))

        assertEquals(ResultadoEntrada.DentroSinSenal("Centro"), r.entrar("1234", "v1"))
        assertFalse(quienSoy.bloqueada)
    }

    @Test fun `con necesitaPin y sin red el PIN bueno abre pero sigue necesitaPin, con red se quita`() = runBlocking {
        yaActivado()
        quienSoy.marcarSesionMuerta()
        val r = repo()
        responder = corta
        assertEquals(ResultadoEntrada.DentroSinSenal("Centro"), r.entrar("1234", "v1"))
        assertFalse(quienSoy.bloqueada)
        assertTrue(quienSoy.necesitaPin)

        responder = { ok(entrada("acc9")) }
        assertEquals(ResultadoEntrada.Dentro, r.entrar("1234", "v1"))
        assertFalse(quienSoy.necesitaPin)
    }

    @Test fun `sin red y 5 fallos queda bloqueado 60 s`() = runBlocking {
        yaActivado()
        responder = corta
        val r = repo()
        repeat(4) { i -> assertEquals(ResultadoEntrada.Rechazo(texto(4 - i), 4 - i), r.entrar("0000", null)) }
        assertEquals(ResultadoEntrada.Bloqueado(60), r.entrar("0000", null))
    }

    @Test fun `sin red y sin verificador`() = runBlocking {
        yaActivado(listOf(Sucursal("v1", "Centro", null)))
        responder = corta
        assertEquals(ResultadoEntrada.SinSenalSinVerificador, repo().entrar("1234", null))
    }

    @Test fun `401 CELULAR_DESVINCULADO al entrar deja el celular en gracia`() = runBlocking {
        yaActivado()
        responder = { error(401, """{"code":"CELULAR_DESVINCULADO","message":"Este celular ya no está vinculado."}""") }
        assertEquals(ResultadoEntrada.Rechazo("Este celular ya no está vinculado.", null), repo().entrar("1234", null))
        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
    }

    private fun texto(n: Int) = if (n == 1) "PIN incorrecto. Te queda 1 intento." else "PIN incorrecto. Te quedan $n intentos."

    private companion object {
        val CRED = "c".repeat(64)
        val REFRESH = "1".repeat(64)
        private val sal = ByteArray(16) { it.toByte() }
        val VERIFICADOR: VerificadorPin = run {
            val h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("1234".toCharArray(), sal, 1000, 256)).encoded
            VerificadorPin("PBKDF2-SHA256", 1000, Base64.getEncoder().encodeToString(sal), Base64.getEncoder().encodeToString(h))
        }
        val VERIFICADOR_JSON = """{"algoritmo":"PBKDF2-SHA256","iteraciones":1000,"sal":"${VERIFICADOR.sal}","hash":"${VERIFICADOR.hash}"}"""
    }
}
