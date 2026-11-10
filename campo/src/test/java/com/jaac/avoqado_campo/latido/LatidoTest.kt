package com.jaac.avoqado_campo.latido

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.cola.huella
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.red.AparatoSinId
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.RedModule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatidoTest {
    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    /** El monotónico del celular (ms); el reloj de pared queda fijo en 0 para que se note el ancla. */
    private var mono = 1_000L
    private val reloj = RelojAnclado(almacen, { 0L }, { mono }, { 1 })
    private val server = MockWebServer()
    private lateinit var api: CampoApi
    private val cuerpos = mutableListOf<String>()
    private val cabeceras = mutableListOf<String?>()
    @Volatile private var respuesta: () -> MockResponse = { vinculado() }

    private val suc = Sucursal("v1", "Centro", null)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BaseDeCampo::class.java)
            .allowMainThreadQueries().build()
        dao = db.piezaDao()
        quienSoy.guardarActivacion(DatosDeActivacion("cel", "c".repeat(64), "s1", "Ana", Sesion("acc1", "1".repeat(64), "v1"), listOf(suc)))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                cuerpos += request.body.readUtf8()
                cabeceras += request.getHeader(CampoApi.CREDENCIAL)
                return respuesta()
            }
        }
        server.start()
        api = RedModule.crearRetrofit(server.url("/").toString(), RedModule.proveerCliente()).create(CampoApi::class.java)
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun latido() = Latido(api, quienSoy, dao, reloj, AparatoSinId("Motorola", "g54", "14", "0.2.0")) { mono }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private fun vinculado(hora: String = HORA) = ok(
        """{"estado":"VINCULADO","graciaHasta":null,"persona":{"staffId":"s1","nombre":"Ana López"},
           "sucursales":[{"venueId":"v1","nombre":"Centro","verificadorPin":{"algoritmo":"PBKDF2-SHA256","iteraciones":100000,"sal":"c2Fs","hash":"aGFzaA=="}},
                         {"venueId":"v2","nombre":"Norte","verificadorPin":null}],
           "horaDelServidor":"$hora"}""",
    )

    private suspend fun pieza(tipo: String, cuerpo: String?, estado: String = EstadoPieza.PENDIENTE): Pieza {
        val id = dao.insertar(
            Pieza(
                tipo = tipo, llave = UUID.randomUUID().toString(), staffId = "s1", venueId = "v1", cuerpoJson = cuerpo,
                huella = cuerpo?.let { huella(tipo, "v1", it) }, estado = estado, creadaAt = 1, resumen = "r",
            ),
        )
        return dao.porId(id)!!
    }

    @Test fun `manda dos huellas para una venta y una entrada, la evidencia no lleva huella pero cuenta en fotos`() = runBlocking {
        val v = pieza(TipoPieza.VENTA, """{"llave":"a","serialNumber":"S1"}""")
        val e = pieza(TipoPieza.ENTRADA, """{"llave":"b","ocurrioAt":"x"}""")
        pieza(TipoPieza.EVIDENCIA, null)
        pieza(TipoPieza.VENTA, """{"llave":"c","serialNumber":"S2"}""", EstadoPieza.HECHA) // ya subió: no cuenta

        assertTrue(latido().mandar())

        val b = JsonParser.parseString(cuerpos.single()).asJsonObject
        assertEquals(listOf(v.huella, e.huella), b["huellas"].asJsonArray.map { it.asString })
        val por = b["porSubir"].asJsonObject
        assertEquals(3, por["total"].asInt); assertEquals(1, por["ventas"].asInt)
        assertEquals(1, por["fotos"].asInt); assertEquals(1, por["checadas"].asInt); assertEquals(0, por["custodia"].asInt)
        val ap = b["aparato"].asJsonObject
        assertEquals("Motorola", ap["marca"].asString); assertEquals("0.2.0", ap["versionApp"].asString)
        assertFalse(ap.has("aparatoId"))
        assertEquals("c".repeat(64), cabeceras.single())
    }

    @Test fun `vinculado trae las sucursales con sus verificadores y el nombre`() = runBlocking {
        assertTrue(latido().mandar())
        assertEquals(listOf("v1", "v2"), quienSoy.sucursales.map { it.venueId })
        assertEquals(100_000, quienSoy.sucursales.first().verificadorPin?.iteraciones)
        assertEquals("Ana López", quienSoy.nombre)
        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
    }

    @Test fun `DESVINCULADO deja el estado en gracia y conserva las sucursales`() = runBlocking {
        respuesta = {
            ok("""{"estado":"DESVINCULADO","graciaHasta":"2026-11-11T18:00:00.000Z","persona":{"staffId":"s1","nombre":"Ana"},"sucursales":[],"horaDelServidor":"$HORA"}""")
        }
        assertTrue(latido().mandar())
        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
        assertEquals("2026-11-11T18:00:00.000Z", quienSoy.graciaHasta)
        assertEquals(listOf(suc), quienSoy.sucursales)
        assertEquals("c".repeat(64), quienSoy.credencial) // la gracia la necesita
    }

    @Test fun `DADO_DE_BAJA deja ese estado`() = runBlocking {
        respuesta = { ok("""{"estado":"DADO_DE_BAJA","graciaHasta":null,"persona":{"staffId":"s1","nombre":"Ana"},"sucursales":[],"horaDelServidor":"$HORA"}""") }
        assertTrue(latido().mandar())
        assertEquals(EstadoDelCelular.DADO_DE_BAJA, quienSoy.estado)
    }

    @Test fun `horaDelServidor ancla el reloj`() = runBlocking {
        assertTrue(latido().mandar())
        assertEquals("RELOJ_ANCLADO", reloj.ahora().origen)
        assertEquals("2026-11-10T18:00:00Z", reloj.ahora().iso)
        mono += 5_000
        assertEquals("2026-11-10T18:00:05Z", reloj.ahora().iso)
    }

    @Test fun `401 CELULAR_DESCONOCIDO marca desconocido`() = runBlocking {
        respuesta = { MockResponse().setResponseCode(401).setBody("""{"code":"CELULAR_DESCONOCIDO","message":"m"}""") }
        assertFalse(latido().mandar())
        assertEquals(EstadoDelCelular.DESCONOCIDO, quienSoy.estado)
    }

    @Test fun `un 401 sin ese codigo, un 5xx o un portal cautivo no tocan quien soy`() = runBlocking {
        for (r in listOf(
            MockResponse().setResponseCode(401).setBody("""{"code":"OTRO"}"""),
            MockResponse().setResponseCode(503),
            ok("<html>Inicia sesión en el WiFi</html>"),
        )) {
            respuesta = { r }
            mono += 61_000
            assertFalse(latido().mandar())
        }
        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
        assertEquals(listOf(suc), quienSoy.sucursales)
    }

    @Test fun `nunca mas de uno por minuto`() = runBlocking {
        val l = latido()
        assertTrue(l.mandar())
        mono += 59_000
        assertFalse(l.mandar())
        assertEquals(1, cuerpos.size)
        mono += 1_000
        assertTrue(l.mandar())
        assertEquals(2, cuerpos.size)
    }

    @Test fun `una respuesta que llega despues de otra activacion se descarta`() = runBlocking {
        respuesta = {
            quienSoy.borrarTodo()
            quienSoy.guardarActivacion(DatosDeActivacion("cel2", "d".repeat(64), "s2", "Beto", Sesion("a", "2".repeat(64), "v3"), listOf(Sucursal("v3", "Sur", null))))
            ok("""{"estado":"DESVINCULADO","graciaHasta":"g","persona":{"staffId":"s1","nombre":"Ana"},"sucursales":[],"horaDelServidor":"$HORA"}""")
        }
        assertFalse(latido().mandar())
        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
        assertEquals("Beto", quienSoy.nombre)
    }

    @Test fun `mientras se borra el celular no se manda (I2)`() = runBlocking {
        quienSoy.marcarLimpiando(true)
        assertFalse(latido().mandar())
        assertTrue(cuerpos.isEmpty())
    }

    @Test fun `sin activar no se manda`() = runBlocking {
        quienSoy.borrarTodo()
        assertFalse(latido().mandar())
        assertTrue(cuerpos.isEmpty())
    }

    private companion object { const val HORA = "2026-11-10T18:00:00.000Z" }
}
