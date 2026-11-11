package com.jaac.avoqado_campo.checada

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.SecuenciaDeCaptura
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.copia.ConfigDeSucursal
import com.jaac.avoqado_campo.copia.Copia
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.EstadoDelGps
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChecadaViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    private val reloj = RelojAnclado(almacen, ahoraDelCelular = { 1_762_797_600_000 }, transcurrido = { 0L }, arranque = { 1 })
    private lateinit var encolador: Encolador
    private val ubicacion = mockk<Ubicacion>()
    private val copia = mockk<RepositorioDeCopia>()
    private val estadoDelGps = EstadoDelGps(AlmacenEnMemoria())
    private var respuestaGps: ResultadoGps = ResultadoGps.Siguiendo
    private var detenido = 0
    private val gps = object : ControlDeGps {
        override fun iniciar() = respuestaGps
        override fun detener() { detenido++ }
    }
    private lateinit var vm: ChecadaViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(Disparador)
        every { Disparador.ahora(any()) } just runs
        db = Room.inMemoryDatabaseBuilder(context, BaseDeCampo::class.java).allowMainThreadQueries().build()
        dao = db.piezaDao()
        quienSoy.guardarActivacion(
            DatosDeActivacion("c1", "c".repeat(64), "s1", "Ana", Sesion("a", "r", "v1"), listOf(Sucursal("v1", "Centro", null))),
        )
        encolador = Encolador(dao, quienSoy, reloj, SecuenciaDeCaptura(almacen), File(context.filesDir, "cola"), env = "dev")
        every { copia.copia } returns MutableStateFlow(
            Copia(2, "v1", quienSoy.generacion, emptyList(), ConfigDeSucursal("SIM", "ICCID", false, false, false, false, false, false, true), 0L),
        )
        coEvery { ubicacion.leer(any()) } returns Ubicacion.Lectura(19.43, -99.13, 12f, falsa = false)
        vm = ChecadaViewModel(encolador, ubicacion, gps, estadoDelGps, copia, dao, quienSoy, context)
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
        unmockkObject(Disparador)
        Dispatchers.resetMain()
    }

    private fun quieto() = runBlocking { withTimeout(5_000) { vm.estado.first { !it.ocupado } } }
    private fun piezas(): List<Pieza> = runBlocking { dao.pendientesDeVer().first() }
    private fun cuerpo(p: Pieza) = JsonParser.parseString(p.cuerpoJson).asJsonObject

    @Test fun `sin ubicacion la entrada se encola sin latitude y lo dice`() {
        coEvery { ubicacion.leer(any()) } returns null
        vm.marcarEntrada()
        val e = quieto()
        val entrada = piezas().single()
        assertEquals(TipoPieza.ENTRADA, entrada.tipo)
        assertFalse(cuerpo(entrada).has("latitude"))
        assertEquals("Se checa sin ubicación", e.avisoUbicacion)
    }

    @Test fun `la ubicacion falsa va en el cuerpo`() {
        coEvery { ubicacion.leer(any()) } returns Ubicacion.Lectura(19.43, -99.13, 12f, falsa = true)
        vm.marcarEntrada(); quieto()
        val c = cuerpo(piezas().single())
        assertTrue(c["ubicacionFalsa"].asBoolean)
        assertEquals(19.43, c["latitude"].asDouble, 0.0)
    }

    @Test fun `la salida tras una entrada del celular nombra su llaveEntrada y depende de ella`() {
        vm.marcarEntrada(); quieto()
        runBlocking { withTimeout(5_000) { vm.estado.first { it.entrada != null } } }
        vm.marcarSalida(); quieto()
        val (entrada, salida) = piezas()
        assertEquals(entrada.llave, cuerpo(salida)["llaveEntrada"].asString)
        assertEquals(entrada.llave, salida.dependeDe)
        assertEquals(1, detenido)
    }

    @Test fun `tras usarEntrada la salida lleva el timeEntryId`() {
        vm.marcarEntrada(); quieto()
        val entrada = piezas().single()
        runBlocking { encolador.usarEntrada(quienSoy.generacion, entrada.id, "te1", "v1") }
        runBlocking { withTimeout(5_000) { vm.estado.first { it.entrada == RefEntrada.PorId("te1") } } }
        vm.marcarSalida(); quieto()
        val salida = piezas().single { it.tipo == TipoPieza.SALIDA }
        assertEquals("te1", cuerpo(salida)["timeEntryId"].asString)
        assertNull(salida.dependeDe)
    }

    @Test fun `dos toques a marcar entrada dejan una sola pieza`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        vm.marcarEntrada()
        vm.marcarEntrada()
        espera.complete(null)
        quieto()
        runBlocking { withTimeout(5_000) { vm.estado.first { it.entrada != null } } }
        vm.marcarEntrada(); quieto()
        assertEquals(1, piezas().count { it.tipo == TipoPieza.ENTRADA })
        assertEquals("Ya tienes una entrada abierta", vm.estado.value.error)
    }

    @Test fun `si el GPS no sigue la entrada se queda y se dice por que`() {
        respuestaGps = ResultadoGps.NoSigue("la ubicación del teléfono está apagada")
        vm.marcarEntrada()
        val e = quieto()
        assertEquals(1, piezas().count { it.tipo == TipoPieza.ENTRADA })
        assertEquals(
            "La checada quedó guardada · no estamos siguiendo tu ubicación: la ubicación del teléfono está apagada",
            e.aviso,
        )
    }

    @Test fun `sin entrada del celular la salida lo explica y no encola`() {
        vm.marcarSalida(); quieto()
        assertTrue(piezas().isEmpty())
        assertEquals("Marca tu salida donde marcaste la entrada", vm.estado.value.error)
    }

    @Test fun `con foto de entrada pedida primero abre la camara frontal`() {
        every { copia.copia } returns MutableStateFlow(
            Copia(2, "v1", quienSoy.generacion, emptyList(), ConfigDeSucursal("SIM", "ICCID", false, false, true, false, false, false, true), 0L),
        )
        vm.viewModelScope.cancel()
        vm = ChecadaViewModel(encolador, ubicacion, gps, estadoDelGps, copia, dao, quienSoy, context)
        vm.marcarEntrada()
        assertEquals(TipoChecada.ENTRADA, vm.estado.value.pideSelfie)
        assertTrue(piezas().isEmpty())
        val selfie = File(context.cacheDir, "s.jpg").apply { writeBytes(ByteArray(8) { 1 }) }
        vm.alTomarSelfie(selfie.absolutePath); quieto()
        assertTrue(cuerpo(piezas().single()).has("checkInPhotoUrl"))
    }

    @Test fun `sin copia la selfie se pide igual`() {
        every { copia.copia } returns MutableStateFlow(null)
        vm.marcarEntrada()
        assertEquals(TipoChecada.ENTRADA, vm.estado.value.pideSelfie)
        assertTrue(piezas().isEmpty())
    }

    @Test fun `salir de la pantalla durante la espera del GPS no tira la checada`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        vm.marcarEntrada()
        vm.viewModelScope.cancel()
        espera.complete(null)
        val entradas = runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { it.isNotEmpty() } } }
        assertEquals(TipoPieza.ENTRADA, entradas.single().tipo)
    }

    @Test fun `si durante la espera del GPS borran el celular y activan a otra persona, no queda ninguna pieza (H2)`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        vm.marcarEntrada()
        vm.viewModelScope.cancel() // regresó; la checada sigue (NonCancellable)
        quienSoy.empezarLimpieza(); quienSoy.borrarTodo(); quienSoy.marcarLimpiando(false)
        quienSoy.guardarActivacion(
            DatosDeActivacion("c2", "d".repeat(64), "s2", "Beto", Sesion("b", "r2", "v1"), listOf(Sucursal("v1", "Centro", null))),
        )
        espera.complete(null)
        assertEquals("Espera un momento.", runBlocking { withTimeout(5_000) { vm.estado.first { it.error != null } } }.error)
        assertTrue(piezas().isEmpty())
        assertNull(runBlocking { dao.turnoAhora() })
    }

    @Test fun `el turno de otra persona no cuenta`() {
        assertNull(refEntrada(com.jaac.avoqado_campo.cola.TurnoLocal(1, "otra", "k", null, "v1", 0L), "s1"))
        assertEquals(RefEntrada.PorLlave("k"), refEntrada(com.jaac.avoqado_campo.cola.TurnoLocal(1, "s1", "k", null, "v1", 0L), "s1"))
        assertEquals(RefEntrada.PorId("t"), refEntrada(com.jaac.avoqado_campo.cola.TurnoLocal(1, "s1", "k", "t", "v1", 0L), "s1"))
    }

    @Test fun `checar lee la generacion antes que la sesion - otra persona entre las dos no recibe la checada (H2)`() {
        every { copia.copia } returns MutableStateFlow(null) // pide selfie: la sesión se vuelve a leer al tomarla
        vm.marcarEntrada()
        val selfie = File(context.cacheDir, "h2.jpg").apply { writeBytes(ByteArray(8) { 1 }) }
        almacen.despuesDeLeer("sesion") { // la sesión se lee, y justo después borran el celular y activan a Beto
            quienSoy.borrarTodo()
            quienSoy.guardarActivacion(
                DatosDeActivacion("c2", "d".repeat(64), "s2", "Beto", Sesion("b", "r2", "v2"), listOf(Sucursal("v2", "Norte", null))),
            )
        }
        vm.alTomarSelfie(selfie.absolutePath)
        assertEquals("Espera un momento.", runBlocking { withTimeout(5_000) { vm.estado.first { it.error != null } } }.error)
        assertTrue(piezas().isEmpty())
        assertNull(runBlocking { dao.turnoAhora() })
        assertFalse(selfie.exists())
    }

    @Test fun `sin sesion al tomar la selfie no se checa y la selfie no se queda (H5)`() {
        every { copia.copia } returns MutableStateFlow(null)
        vm.marcarEntrada()
        val selfie = File(context.cacheDir, "sin-sesion.jpg").apply { writeBytes(ByteArray(8) { 1 }) }
        quienSoy.borrarTodo()
        vm.alTomarSelfie(selfie.absolutePath)
        assertFalse(selfie.exists())
        assertTrue(piezas().isEmpty())
    }

    @Test fun `si el GPS truena se checa sin ubicacion y la pantalla no se queda ocupada (B9)`() {
        coEvery { ubicacion.leer(any()) } throws SecurityException("sin permiso")
        vm.marcarEntrada()
        val e = quieto()
        assertEquals(TipoPieza.ENTRADA, piezas().single().tipo)
        assertEquals("Se checa sin ubicación", e.avisoUbicacion)
    }
}
