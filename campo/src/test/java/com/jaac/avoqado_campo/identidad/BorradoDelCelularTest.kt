package com.jaac.avoqado_campo.identidad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.ayuda.AyudaViewModel
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.MutexDeSubida
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.SecuenciaDeCaptura
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.cola.TurnoLocal
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.entrada.RepositorioDeEntrada
import com.jaac.avoqado_campo.entrada.ResultadoEntrada
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.EstadoDelGps
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.latido.Latido
import com.jaac.avoqado_campo.red.AparatoSinId
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import com.jaac.avoqado_campo.red.RedModule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
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
    /** r9: una escritura del almacén cifrado que falla (antes de escribir nada). */
    @Volatile private var fallarAlEscribir: (Map<String, String?>) -> Boolean = { false }
    private val almacen = object : AlmacenSeguro {
        val datos = mutableMapOf<String, String>()
        override fun leer(clave: String) = datos[clave]
        override fun escribir(cambios: Map<String, String?>) {
            if (fallarAlEscribir(cambios)) throw IllegalStateException("el almacén cifrado falló")
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
    private var morirTrasRoom = 0 // cuántas veces seguidas truena vaciar Room
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

    private fun borrado(conDao: PiezaDao = dao) = BorradoDelCelular(
        quienSoy, MutexDeSubida(), MutexDeSesion(), gps,
        { db.clearAllTables(); if (morirTrasRoom > 0) { morirTrasRoom--; throw IllegalStateException("el proceso muere") } },
        conDao, carpetaCola, copia, pinLocal, estadoDelGps, carpetaFotos,
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

    @Test fun `r8 - si Room falla al recontar con la marca puesta, se quita la marca y la generacion vuelve`() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        val generacionAntes = quienSoy.generacion
        // El segundo conteo (ya con la marca puesta) truena, como si Room fallara.
        val daoQueFalla = spyk(dao)
        coEvery { daoQueFalla.cuantasVivas() } answers { if (quienSoy.limpiando) throw IllegalStateException("Room falló") else 0 }
        var resultado: Any? = null
        val error = runCatching { resultado = borrado(daoQueFalla).activarDeNuevo() }.exceptionOrNull()
        assertNotNull("el error de Room debía subir; regresó $resultado", error)
        assertFalse("la marca sigue puesta tras ${error?.javaClass?.simpleName}", quienSoy.limpiando)
        assertEquals("la generación no volvió", generacionAntes, quienSoy.generacion)
        assertEquals("c".repeat(64), quienSoy.credencial)
        assertEquals(0, detenido)
    }

    @Test fun `r9 - si falla siQuedaAlgo con la marca puesta, se quita la marca y la generacion vuelve`() = runBlocking {
        val generacionAntes = quienSoy.generacion
        alPonerLaMarca = { runBlocking { pieza(EstadoPieza.PENDIENTE) } } // la segunda cuenta ve algo: siQuedaAlgo
        fallarAlEscribir = { it["estado"] == EstadoDelCelular.DESVINCULADO.name }
        val error = runCatching { borrado().trasDesvincular(generacionAntes) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(quienSoy.limpiando)
        assertEquals(generacionAntes, quienSoy.generacion)
        assertEquals(1, dao.pendientesDeVer().first().size)
        assertEquals(0, detenido)
    }

    @Test fun `r9 - si la escritura de la marca falla despues de guardarse, se deshace`() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        val generacionAntes = quienSoy.generacion
        alPonerLaMarca = { throw IllegalStateException("falló al publicar la marca ya escrita") }
        assertNotNull(runCatching { borrado().activarDeNuevo() }.exceptionOrNull())
        assertFalse(quienSoy.limpiando)
        assertEquals(generacionAntes, quienSoy.generacion)
        assertEquals(0, detenido)
    }

    @Test fun `r9 - si vaciar falla una vez con el proceso vivo, se reintenta y el borrado termina`() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        morirTrasRoom = 1
        assertEquals(ResultadoDelBorrado.BORRADO, borrado().activarDeNuevo())
        assertEquals(EstadoDelCelular.SIN_ACTIVAR, quienSoy.estado)
        assertFalse(quienSoy.limpiando)
    }

    // r10 · el arranque retoma una marca CONFIRMADA y `vaciar` falla al final (identidad ya vaciada y publicada, marca pendiente).
    private fun entrada() = RepositorioDeEntrada(
        api, quienSoy, RelojAnclado(almacen, { 0L }, { 0L }, { 1 }), pinLocal, MutexDeSesion(), dao, almacen, copia, CoroutineScope(Dispatchers.Unconfined),
    )
    private val activado = MockResponse().setBody(
        """{"celularId":"cel2","credencial":"${"d".repeat(64)}","persona":{"staffId":"s2","nombre":"Beto"},
           "sesion":{"accessToken":"a2","refreshToken":"${"2".repeat(64)}","venueId":"v3","expiraEn":900},
           "sucursales":[{"venueId":"v3","nombre":"Sur","verificadorPin":null}],"horaDelServidor":"2026-11-10T18:00:00Z","repetida":false}""",
    )
    private fun quedaLaMarcaConfirmada() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        assertTrue(quienSoy.empezarLimpiezaSi(quienSoy.generacion))
        quienSoy.confirmarLimpieza()
        fallarAlEscribir = { it.keys == setOf("limpiando") && it["limpiando"] == null } // el último paso de vaciar
        assertNotNull(runCatching { borrado().terminarSiQuedoAMedias() }.exceptionOrNull()) // el arranque sigue (R-C)
        assertEquals(EstadoDelCelular.SIN_ACTIVAR, quienSoy.estado)
        assertTrue(quienSoy.limpiezaConfirmada)
    }

    @Test fun `r10 - con la marca de un borrado pendiente no se activa a nadie - dice que no se pudo terminar de borrar`() = runBlocking {
        quedaLaMarcaConfirmada()
        alLatido = { activado }
        val r = entrada().activar("ABC123", "1234")
        assertEquals(ResultadoEntrada.Rechazo("No se pudo terminar de borrar el celular. Intenta otra vez.", null), r)
        assertEquals(0, server.requestCount)
        assertEquals(EstadoDelCelular.SIN_ACTIVAR, quienSoy.estado)
        assertNull(quienSoy.credencial)
    }

    @Test fun `r10 - con la marca confirmada y vaciar que ya funciona, activar termina ese borrado y luego activa`() = runBlocking {
        quedaLaMarcaConfirmada()
        fallarAlEscribir = { false }
        alLatido = { activado }
        val repo = RepositorioDeEntrada(
            api, quienSoy, RelojAnclado(almacen, { 0L }, { 0L }, { 1 }), pinLocal, MutexDeSesion(), dao, almacen, copia,
            CoroutineScope(Dispatchers.Unconfined), terminarBorrado = { borrado().terminarSiQuedoAMedias() },
        )
        assertEquals(ResultadoEntrada.Dentro, repo.activar("ABC123", "1234"))
        assertFalse(quienSoy.limpiando)
        assertEquals("Beto", quienSoy.nombre)
        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
    }

    @Test fun `r10 - si commit no guarda la marca, no se borra nada y la marca queda deshecha`() = runBlocking {
        pieza(EstadoPieza.HECHA)
        quienSoy.marcarDesvinculado(null)
        val generacionAntes = quienSoy.generacion
        alPonerLaMarca = { throw AlmacenNoGuardo() } // la marca quedó en memoria; commit() dijo false
        assertTrue(runCatching { borrado().activarDeNuevo() }.exceptionOrNull() is AlmacenNoGuardo)
        assertFalse(quienSoy.limpiando)
        assertEquals(generacionAntes, quienSoy.generacion)
        assertNotNull(dao.porLlave("k-HECHA")) // vaciarBase no corrió
        assertTrue(carpetaCola.exists())
        assertEquals("c".repeat(64), quienSoy.credencial)
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
        morirTrasRoom = 2 // también el reintento: el proceso muere
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

    // r6 · el cruce: la checada se aparta entre la primera cuenta del borrado y su marca (con la generación del toque).
    private fun encolador() = Encolador(dao, quienSoy, RelojAnclado(almacen, { 0L }, { 0L }, { 1 }), SecuenciaDeCaptura(almacen), carpetaCola, env = "dev")
    private suspend fun apartada(generacion: String, foto: File? = null) = dao.insertar(
        Pieza(
            tipo = TipoPieza.SALIDA, llave = "apartada", staffId = "s1", venueId = "v1", estado = "APARTADA", creadaAt = 1, resumen = "Salida",
            cuerpoJson = """{"generacion":"$generacion","ocurrioAt":"2026-11-10T18:00:00Z","origenHora":"RELOJ_DEL_CELULAR"}""",
            fotosJson = foto?.let { """[{"archivo":"${it.absolutePath}","ruta":"r","url":"u","bytes":1,"subida":false}]""" } ?: "[]",
        ),
    )

    @Test fun `r6 - un borrado que se niega deja la generacion como estaba y el GPS completa la checada apartada en el cruce`() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        val g = quienSoy.generacion
        var id = 0L
        alPonerLaMarca = { runBlocking { id = apartada(g) } } // ya pasó la primera cuenta (0)
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo())
        assertEquals(g, quienSoy.generacion) // nada cambió: tampoco la generación
        assertFalse(quienSoy.limpiando)
        encolador().completarChecada(g, id, null) // llega el GPS: con la generación del toque
        assertEquals(EstadoPieza.PENDIENTE, dao.porLlave("apartada")?.estado)
        assertEquals("e1", dao.porLlave("apartada")?.dependeDe)
    }

    @Test fun `r6 - si la app muere despues de un borrado negado, el arranque completa la checada apartada`() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        val g = quienSoy.generacion
        alPonerLaMarca = { runBlocking { apartada(g) } }
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrado().activarDeNuevo())
        alPonerLaMarca = {}
        assertEquals(1, encolador().completarApartadas()) // otro arranque
        assertEquals(EstadoPieza.PENDIENTE, dao.porLlave("apartada")?.estado)
    }

    @Test fun `r6 - si la app muere con la marca puesta antes de contar, el arranque devuelve la generacion y completa la apartada`() = runBlocking {
        quienSoy.marcarDesvinculado(null)
        val g = quienSoy.generacion
        apartada(g)
        assertTrue(quienSoy.empezarLimpiezaSi(g)) // la marca, y el proceso muere aquí
        borrado().terminarSiQuedoAMedias()
        assertEquals(g, quienSoy.generacion)
        assertFalse(quienSoy.limpiando)
        assertEquals(1, encolador().completarApartadas())
        assertEquals(EstadoPieza.PENDIENTE, dao.porLlave("apartada")?.estado)
    }

    @Test fun `r6 - DESCONOCIDO confirmado con su numero si borra la checada apartada y su foto`() = runBlocking {
        val foto = File(carpetaCola, "apartada_salida.jpg").apply { writeText("x") }
        apartada(quienSoy.generacion, foto)
        quienSoy.marcarDesconocido()
        assertEquals(ResultadoDelBorrado.BORRADO, borrado().activarDeNuevo(confirmadas = 1))
        assertNull(dao.porLlave("apartada"))
        assertFalse(foto.exists())
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
