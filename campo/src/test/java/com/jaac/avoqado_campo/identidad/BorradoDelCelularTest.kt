package com.jaac.avoqado_campo.identidad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.MutexDeSubida
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.cola.TurnoLocal
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.EstadoDelGps
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.latido.Latido
import com.jaac.avoqado_campo.red.AparatoSinId
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import com.jaac.avoqado_campo.red.RedModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BorradoDelCelularTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    private val pinLocal = PinLocal(almacen, { 0L })
    private val estadoDelGps = EstadoDelGps(almacen)
    private val server = MockWebServer()
    private lateinit var api: CampoApi
    private lateinit var copia: RepositorioDeCopia
    private val carpetaCola get() = File(context.filesDir, "cola")
    private val archivoCopia get() = File(context.filesDir, "copia.json")
    private var detenido = 0
    private val gps = object : ControlDeGps {
        override fun iniciar(): ResultadoGps = ResultadoGps.Siguiendo
        override fun detener() { detenido++ }
    }
    /** Si es true, el borrado «muere» justo después de vaciar Room. */
    private var morirTrasRoom = false
    @Volatile private var alLatido: () -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, BaseDeCampo::class.java).allowMainThreadQueries().build()
        dao = db.piezaDao()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = alLatido()
        }
        server.start()
        api = RedModule.crearRetrofit(server.url("/").toString(), RedModule.proveerCliente()).create(CampoApi::class.java)
        copia = RepositorioDeCopia(context, api, GuardiaDeSesion(api, quienSoy, MutexDeSesion()), dao, quienSoy)
        quienSoy.guardarActivacion(DatosDeActivacion("cel", "c".repeat(64), "s1", "Ana", Sesion("acc", "1".repeat(64), "v1"), listOf(Sucursal("v1", "Centro", null))))
        llenar()
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
        carpetaCola.deleteRecursively()
        archivoCopia.delete()
    }

    private fun borrado() = BorradoDelCelular(
        quienSoy, MutexDeSubida(), MutexDeSesion(), gps,
        { db.clearAllTables(); if (morirTrasRoom) { morirTrasRoom = false; throw IllegalStateException("el proceso muere") } },
        dao, carpetaCola, copia, pinLocal, estadoDelGps,
    )

    /** Lo que deja una persona en el celular: turno, foto en la cola, copia, fallos del PIN, estado del GPS y activación pendiente. */
    private fun llenar() = runBlocking {
        dao.guardarTurno(TurnoLocal(staffId = "s1", llaveEntrada = "e1", timeEntryId = null, venueId = "v1", abiertoAt = 1))
        carpetaCola.mkdirs(); File(carpetaCola, "foto.jpg").writeText("x")
        archivoCopia.writeText("{}")
        almacen.escribir(mapOf("pin.fallos" to "3", ACTIVACION_PENDIENTE to "{}"))
        estadoDelGps.publicar(ResultadoGps.NoSigue("la ubicación del teléfono está apagada"))
    }

    private suspend fun pieza(estado: String) = dao.insertar(
        Pieza(tipo = TipoPieza.VENTA, llave = "k-$estado", staffId = "s1", venueId = "v1", cuerpoJson = "{}", estado = estado, creadaAt = 1, resumen = "r"),
    )

    private suspend fun quedoVacio(aparatoAntes: String, generacionAntes: String) {
        assertTrue(dao.pendientesDeVer().first().isEmpty())
        assertNull(dao.turnoAhora())
        assertFalse(carpetaCola.exists())
        assertFalse(archivoCopia.exists())
        assertNull(copia.copia.value)
        assertNull(almacen.leer("pin.fallos"))
        assertNull(almacen.leer(ACTIVACION_PENDIENTE))
        assertEquals(EstadoDelGps.SIN_INICIAR, estadoDelGps.estado.value)
        assertNull(almacen.leer("gps.estado"))
        assertEquals(EstadoDelCelular.SIN_ACTIVAR, quienSoy.estado)
        assertNull(quienSoy.credencial)
        assertNull(quienSoy.sesion)
        assertNotEquals(aparatoAntes, quienSoy.aparatoId)
        assertNotEquals(generacionAntes, quienSoy.generacion)
        assertFalse(quienSoy.limpiando)
    }

    @Test fun `DESVINCULADO con una pieza RETENIDA no borra nada`() = runBlocking {
        pieza(EstadoPieza.RETENIDA)
        quienSoy.marcarDesvinculado(null)
        assertFalse(borrado().activarDeNuevo(false))
        assertEquals(1, dao.pendientesDeVer().first().size)
        assertEquals("c".repeat(64), quienSoy.credencial)
        assertTrue(carpetaCola.exists())
        assertEquals(0, detenido)
    }

    @Test fun `DESVINCULADO con la cola vacia borra TODO y detiene el GPS`() = runBlocking {
        pieza(EstadoPieza.HECHA) // lo terminado no cuenta como «vivo»
        quienSoy.marcarDesvinculado(null)
        val aparato = quienSoy.aparatoId
        val generacion = quienSoy.generacion
        assertTrue(borrado().activarDeNuevo(false))
        quedoVacio(aparato, generacion)
        assertNull(dao.porLlave("k-HECHA"))
        assertEquals(1, detenido)
    }

    @Test fun `DESCONOCIDO borra sólo con confirmacion`() = runBlocking {
        pieza(EstadoPieza.PENDIENTE)
        quienSoy.marcarDesconocido()
        val aparato = quienSoy.aparatoId
        val generacion = quienSoy.generacion
        assertFalse(borrado().activarDeNuevo(false))
        assertEquals(1, dao.pendientesDeVer().first().size)
        assertTrue(borrado().activarDeNuevo(true))
        quedoVacio(aparato, generacion)
    }

    @Test fun `VINCULADO no se borra por activarDeNuevo`() = runBlocking {
        assertFalse(borrado().activarDeNuevo(true))
        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
    }

    @Test fun `si el proceso muere a mitad, terminarSiQuedoAMedias deja todo vacio y quita la marca`() = runBlocking {
        val aparato = quienSoy.aparatoId
        val generacion = quienSoy.generacion
        morirTrasRoom = true
        try { borrado().borrarTodo(); fail("debía morir") } catch (_: IllegalStateException) {}
        assertTrue(quienSoy.limpiando)
        assertTrue(carpetaCola.exists()) // se quedó a medias
        borrado().terminarSiQuedoAMedias()
        quedoVacio(aparato, generacion)
    }

    @Test fun `un latido cuya respuesta llega despues de borrar y activar otra vez no escribe nada`() = runBlocking {
        val generacionVieja = quienSoy.generacion
        alLatido = {
            runBlocking { borrado().borrarTodo() }
            quienSoy.guardarActivacion(DatosDeActivacion("cel2", "d".repeat(64), "s2", "Beto", Sesion("a", "2".repeat(64), "v3"), listOf(Sucursal("v3", "Sur", null))))
            MockResponse().setResponseCode(200).setBody(
                """{"estado":"VINCULADO","graciaHasta":null,"persona":{"staffId":"s1","nombre":"Ana"},
                   "sucursales":[{"venueId":"v1","nombre":"Centro"}],"horaDelServidor":"2026-11-10T18:00:00Z"}""",
            )
        }
        val latido = Latido(api, quienSoy, dao, RelojAnclado(almacen, { 0L }, { 0L }, { 1 }), AparatoSinId("M", "g", "14", "0.2.0")) { 0L }
        assertFalse(latido.mandar())
        assertEquals("Beto", quienSoy.nombre)
        assertEquals(listOf("v3"), quienSoy.sucursales.map { it.venueId })
        assertFalse(quienSoy.guardarSi(generacionVieja) {})
    }
}
