package com.jaac.avoqado_campo.gps

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.MutexDeSesion
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import com.jaac.avoqado_campo.red.RedModule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnviadorDePingsTest {
    private val ahora = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli()
    private val hora = 3_600_000L
    private lateinit var db: BaseDeCampo
    private val quienSoy = QuienSoy(AlmacenEnMemoria()).also {
        it.guardarActivacion(
            DatosDeActivacion("cel", "c".repeat(64), "s1", "Ana", Sesion("acc1", "1".repeat(64), "v1"), listOf(Sucursal("v1", "Centro", null))),
        )
    }
    private val server = MockWebServer()
    private val pedidos: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    @Volatile private var status = 201
    private lateinit var api: CampoApi

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BaseDeCampo::class.java).allowMainThreadQueries().build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pedidos += request
                return MockResponse().setResponseCode(status)
                    .setBody(if (status < 300) """{"success":true,"data":{"id":"p1"}}""" else """{"code":"X","message":"m"}""")
            }
        }
        server.start()
        api = RedModule.crearRetrofit(server.url("/").toString(), RedModule.proveerCliente()).create(CampoApi::class.java)
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun enviador() = EnviadorDePings(db.pingDao(), api, GuardiaDeSesion(api, quienSoy, MutexDeSesion()), quienSoy) { ahora }
    private suspend fun ping(capturadoAt: Long = ahora - hora, venueId: String = "v1") =
        db.pingDao().insertar(Ping(venueId = venueId, lat = 19.43, lon = -99.13, precision = 12f, capturadoAt = capturadoAt))

    @Test fun `201 lo manda con su hora y lo borra`() = runBlocking {
        ping()
        enviador().mandar()
        val r = pedidos.single()
        assertEquals("/tpv/campo/venues/v1/ubicacion", r.path)
        assertEquals("Bearer acc1", r.getHeader("Authorization"))
        val b = JsonParser.parseString(r.body.readUtf8()).asJsonObject
        assertEquals(19.43, b["latitude"].asDouble, 0.0)
        assertEquals(-99.13, b["longitude"].asDouble, 0.0)
        assertEquals(12.0, b["accuracy"].asDouble, 0.0)
        assertEquals("2026-11-10T17:00:00Z", b["capturedAt"].asString)
        assertEquals("PERIODIC", b["source"].asString)
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `un 4xx (sucursal sin seguimiento) lo borra`() = runBlocking {
        status = 403
        ping()
        enviador().mandar()
        assertEquals(1, pedidos.size)
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `500 lo conserva y no sigue con los demas`() = runBlocking {
        status = 500
        ping(); ping()
        enviador().mandar()
        assertEquals(1, pedidos.size)
        assertEquals(2, db.pingDao().todos().size)
    }

    @Test fun `un ping de hace 25 h se borra sin mandarse`() = runBlocking {
        ping(capturadoAt = ahora - 25 * hora)
        enviador().mandar()
        assertTrue(pedidos.isEmpty())
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `solo manda los de la sucursal de la sesion`() = runBlocking {
        ping(venueId = "v2")
        enviador().mandar()
        assertTrue(pedidos.isEmpty())
        assertEquals(1, db.pingDao().todos().size)
    }

    @Test fun `con el celular DESVINCULADO se borran todos sin mandarse`() = runBlocking {
        ping(); ping(venueId = "v2")
        quienSoy.marcarDesvinculado(null)
        enviador().mandar()
        assertTrue(pedidos.isEmpty())
        assertTrue(db.pingDao().todos().isEmpty())
    }
}
