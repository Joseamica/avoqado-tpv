package com.jaac.avoqado_campo.identidad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.ayuda.AyudaViewModel
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BorradoDelCelularTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    /** Se corre cuando el borrado pone su marca (para colar una pieza justo después de la revisión de afuera). */
    @Volatile private var alPonerLaMarca: () -> Unit = {}
    private val almacen = object : AlmacenSeguro {
        val datos = mutableMapOf<String, String>()
        override fun leer(clave: String) = datos[clave]
        override fun escribir(cambios: Map<String, String?>) {
            cambios.forEach { (k, v) -> if (v == null) datos.remove(k) else datos[k] = v }
            if (cambios["limpiando"] == "true") alPonerLaMarca()
        }
    }
    private val quienSoy = QuienSoy(almacen)
    private val pinLocal = PinLocal(almacen, { 0L })
    private val estadoDelGps = EstadoDelGps(almacen)
    private val server = MockWebServer()
    private lateinit var api: CampoApi
    private lateinit var copia: RepositorioDeCopia
    private val carpetaCola get() = File(context.filesDir, "cola")
    private val archivoCopia get() = File(context.filesDir, "copia.json")
    private val carpetaFotos get() = File(context.cacheDir, "fotos")
    private var detenido = 0
    private val gps = object : ControlDeGps {
        override suspend fun iniciar(): ResultadoGps = ResultadoGps.Siguiendo
        override fun detener() { detenido++ }
        override suspend fun revisar() = Unit
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
        carpetaFotos.deleteRecursively()
        archivoCopia.delete()
    }

    private fun borrado() = BorradoDelCelular(
        quienSoy, MutexDeSubida(), MutexDeSesion(), gps,
        { db.clearAllTables(); if (morirTrasRoom) { morirTrasRoom = false; throw IllegalStateException("el proceso muere") } },
        dao, carpetaCola, copia, pinLocal, estadoDelGps, carpetaFotos,
    )

    /** Lo que deja una persona en el celular: turno, foto en la cola, copia, fallos del PIN, estado del GPS y activación pendiente. */
    private fun llenar() = runBlocking {
        dao.guardarTurno(TurnoLocal(staffId = "s1", llaveEntrada = "e1", timeEntryId = null, venueId = "v1", abiertoAt = 1))
        carpetaCola.mkdirs(); File(carpetaCola, "foto.jpg").writeText("x")
        carpetaFotos.mkdirs(); File(carpetaFotos, "sin-registrar.jpg").writeText("x")
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
        assertFalse(carpetaFotos.exists())
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
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo())
        assertEquals(1, dao.pendientesDeVer().first().size)
        assertEquals("c".repeat(64), quienSoy.credencial)
        assertTrue(carpetaCola.exists())
        assertEquals(0, detenido)
    }

    @Test fun `una pieza que aparece justo antes de la marca frena el borrado y quita la marca`() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        alPonerLaMarca = { runBlocking { pieza(EstadoPieza.PENDIENTE) } } // ya pasó la revisión de afuera
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo())
        assertFalse(quienSoy.limpiando)
        assertEquals(1, dao.pendientesDeVer().first().size)
        assertEquals("c".repeat(64), quienSoy.credencial)
        assertTrue(carpetaCola.exists())
        assertNotNull(dao.turnoAhora())
        assertEquals(0, detenido)
    }

    @Test fun `DESVINCULADO con la cola vacia borra TODO y detiene el GPS`() = runBlocking {
        pieza(EstadoPieza.HECHA) // lo terminado no cuenta como «vivo»
        quienSoy.marcarDesvinculado(null)
        val aparato = quienSoy.aparatoId
        val generacion = quienSoy.generacion
        assertEquals(ResultadoDelBorrado.BORRADO, borrado().activarDeNuevo())
        quedoVacio(aparato, generacion)
        assertNull(dao.porLlave("k-HECHA"))
        assertEquals(1, detenido)
    }

    @Test fun `DESCONOCIDO borra sólo con confirmacion`() = runBlocking {
        pieza(EstadoPieza.PENDIENTE)
        quienSoy.marcarDesconocido()
        val aparato = quienSoy.aparatoId
        val generacion = quienSoy.generacion
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo())
        assertEquals(1, dao.pendientesDeVer().first().size)
        assertEquals(ResultadoDelBorrado.BORRADO, borrado().activarDeNuevo(confirmadas = 1))
        quedoVacio(aparato, generacion)
    }

    @Test fun `VINCULADO no se borra por activarDeNuevo`() = runBlocking {
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo(confirmadas = 0))
        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
    }

    @Test fun `si el proceso muere a mitad, terminarSiQuedoAMedias deja todo vacio y quita la marca`() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        val aparato = quienSoy.aparatoId
        val generacion = quienSoy.generacion
        morirTrasRoom = true
        try { borrado().activarDeNuevo(); fail("debía morir") } catch (_: IllegalStateException) {}
        assertTrue(quienSoy.limpiando)
        assertTrue(carpetaCola.exists()) // se quedó a medias
        borrado().terminarSiQuedoAMedias()
        quedoVacio(aparato, generacion)
    }

    @Test fun `desvincular - una checada guardada entre el 0 pendientes y el 200 no se borra y el celular queda DESVINCULADO (r3 1)`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            // Mientras vuela el POST, la checada que esperaba el GPS (NonCancellable) termina y queda en Room.
            alLatido = {
                runBlocking { dao.insertar(Pieza(tipo = TipoPieza.SALIDA, llave = "salida", staffId = "s1", venueId = "v1", cuerpoJson = "{}", creadaAt = 1, resumen = "Salida")) }
                MockResponse().setBody("""{"yaEstaba":false}""")
            }
            val vm = AyudaViewModel(quienSoy, dao, copia, mockk(relaxed = true), api, borrado(), mockk { every { hayRed() } returns true })
            vm.desvincular()
            withTimeout(5_000) { while (vm.estado.value.trabajando) delay(10) }
            vm.viewModelScope.cancel()
            assertEquals("/tpv/campo/desvincular", server.takeRequest().path)
            assertEquals(EstadoPieza.PENDIENTE, dao.porLlave("salida")?.estado) // sigue viva: sube por la gracia
            assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado) // el SERVIDOR ya lo desvinculó
            assertEquals("c".repeat(64), quienSoy.credencial) // la gracia la necesita
            assertTrue(carpetaCola.exists())
            assertFalse(quienSoy.limpiando)
            assertEquals(0, detenido)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `si el proceso muere ANTES de revisar la cola, al arrancar no se borra nada y se quita la marca`() = runBlocking {
        pieza(EstadoPieza.PENDIENTE)
        quienSoy.marcarDesvinculado(null)
        quienSoy.marcarLimpiando(true) // la marca puesta y la cola sin revisar: el proceso murió aquí
        borrado().terminarSiQuedoAMedias()
        assertFalse(quienSoy.limpiando)
        assertEquals(1, dao.pendientesDeVer().first().size)
        assertEquals("c".repeat(64), quienSoy.credencial)
        assertTrue(carpetaCola.exists())
        assertEquals(0, detenido)
    }

    @Test fun `DESCONOCIDO - si cambio lo que queda sin subir desde que se confirmo, no se borra y se vuelve a preguntar`() = runBlocking {
        pieza(EstadoPieza.PENDIENTE)
        quienSoy.marcarDesconocido()
        val aparato = quienSoy.aparatoId
        val generacion = quienSoy.generacion
        // La persona vio «1»; con la marca ya puesta se guarda otra (una checada que esperaba el GPS).
        alPonerLaMarca = { runBlocking { pieza(EstadoPieza.RECHAZADA) } }
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo(confirmadas = 1))
        assertFalse(quienSoy.limpiando)
        assertEquals(2, dao.pendientesDeVer().first().size)
        assertEquals(EstadoDelCelular.DESCONOCIDO, quienSoy.estado)
        assertEquals(0, detenido)
        // La pantalla vuelve a preguntar con «2»; ahora sí es lo que se borra.
        alPonerLaMarca = {}
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo(confirmadas = 1)) // un número viejo no borra, ni sin la marca
        assertEquals(ResultadoDelBorrado.BORRADO, borrado().activarDeNuevo(confirmadas = 2))
        quedoVacio(aparato, generacion)
    }

    @Test fun `desvincular con la cola vacia borra TODO, y el de una persona que ya no esta no borra a la nueva`() = runBlocking {
        val aparato = quienSoy.aparatoId
        val deAna = quienSoy.generacion
        pieza(EstadoPieza.AVISO) // ya subió: no cuenta como «por subir»
        assertEquals(ResultadoDelBorrado.BORRADO, borrado().trasDesvincular(deAna))
        quedoVacio(aparato, deAna)
        // Un 200 tardío de Ana con Beto ya activado: no se toca nada de Beto.
        quienSoy.guardarActivacion(DatosDeActivacion("cel2", "d".repeat(64), "s2", "Beto", Sesion("a", "2".repeat(64), "v3"), listOf(Sucursal("v3", "Sur", null))))
        assertEquals(ResultadoDelBorrado.ESPERA, borrado().trasDesvincular(deAna))
        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
        assertEquals("Beto", quienSoy.nombre)
        assertFalse(quienSoy.limpiando)
    }

    @Test fun `una checada apartada (esperaba el GPS cuando murio la app) cuenta como viva para el borrado (r5)`() = runBlocking {
        dao.insertar(Pieza(tipo = TipoPieza.ENTRADA, llave = "apartada", staffId = "s1", venueId = "v1", cuerpoJson = "{}", estado = "APARTADA", creadaAt = 1, resumen = "Entrada"))
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().trasDesvincular(quienSoy.generacion))
        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
        assertEquals("APARTADA", dao.porLlave("apartada")?.estado)
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo())
        quienSoy.marcarDesconocido()
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo(confirmadas = 0)) // la persona vio «1»
        assertEquals("APARTADA", dao.porLlave("apartada")?.estado)
        assertTrue(carpetaCola.exists())
    }

    @Test fun `desvincular - algo que se guarda justo antes de la marca tampoco se borra y el celular queda DESVINCULADO`() = runBlocking {
        alPonerLaMarca = { runBlocking { pieza(EstadoPieza.PENDIENTE) } } // ya pasó la revisión de afuera
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().trasDesvincular(quienSoy.generacion))
        assertFalse(quienSoy.limpiando)
        assertEquals(EstadoPieza.PENDIENTE, dao.porLlave("k-PENDIENTE")?.estado)
        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
        assertEquals("c".repeat(64), quienSoy.credencial)
        assertEquals(0, detenido)
    }

    @Test fun `dos borrados a la vez no se pisan - el segundo ve la marca del primero y no hace nada`() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        val g = quienSoy.generacion
        var segundo: ResultadoDelBorrado? = null
        alPonerLaMarca = { runBlocking { segundo = borrado().activarDeNuevo() } } // el segundo toque, ya con la marca puesta
        assertEquals(ResultadoDelBorrado.BORRADO, borrado().trasDesvincular(g))
        assertEquals(ResultadoDelBorrado.ESPERA, segundo)
        assertEquals(EstadoDelCelular.SIN_ACTIVAR, quienSoy.estado)
        assertFalse(quienSoy.limpiando)
    }

    @Test fun `un latido cuya respuesta llega despues de borrar y activar otra vez no escribe nada`() = runBlocking {
        val generacionVieja = quienSoy.generacion
        alLatido = {
            runBlocking { quienSoy.marcarDesvinculado(null); borrado().activarDeNuevo() }
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
