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
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.fotosDe
import com.jaac.avoqado_campo.cola.MutexDeSubida
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
import com.jaac.avoqado_campo.identidad.BorradoDelCelular
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.MutexDeSesion
import com.jaac.avoqado_campo.identidad.PinLocal
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.identidad.ResultadoDelBorrado
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
import kotlinx.coroutines.delay
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
        override suspend fun iniciar() = respuestaGps
        override fun detener() { detenido++ }
        override suspend fun revisar() = Unit
    }
    private lateinit var vm: ChecadaViewModel
    /** Main de la prueba: su reloj es virtual (el `delay` de la pantalla sólo avanza con `advanceTimeBy`). */
    private val main = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(main)
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

    private fun borrado() = BorradoDelCelular(
        quienSoy, MutexDeSubida(), MutexDeSesion(), gps, db::clearAllTables, dao, File(context.filesDir, "cola"), copia,
        PinLocal(almacen, { 0L }), estadoDelGps, File(context.cacheDir, "fotos"),
    )
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

    @Test fun `r6 - si el GPS llega con un borrado en su ventana, la checada espera a que se niegue y se completa con su ubicacion`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        vm.marcarEntrada()
        runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { it.isNotEmpty() } } }
        val g = quienSoy.generacion
        assertTrue(quienSoy.empezarLimpiezaSi(g)) // un borrado pone su marca…
        espera.complete(Ubicacion.Lectura(19.43, -99.13, 12f, falsa = false)) // …justo cuando llega el GPS
        runBlocking { delay(700) } // el completar ya chocó con la marca y espera
        assertEquals(EstadoPieza.APARTADA, piezas().single().estado) // no la borró
        quienSoy.cancelarLimpieza() // …y el borrado se niega (vio la APARTADA)
        main.scheduler.advanceTimeBy(1_000); main.scheduler.runCurrent() // la pantalla vuelve a revisar
        val entrada = runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { l -> l.any { it.estado == EstadoPieza.PENDIENTE } } } }.single()
        assertEquals(g, quienSoy.generacion)
        assertEquals(19.43, cuerpo(entrada)["latitude"].asDouble, 0.0)
    }

    @Test fun `r7 - con dos borrados negados seguidos la checada se completa con su ubicacion y sube`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        vm.marcarEntrada()
        runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { it.isNotEmpty() } } }
        val g = quienSoy.generacion
        assertTrue(quienSoy.empezarLimpiezaSi(g)) // el borrado A pone su marca…
        espera.complete(Ubicacion.Lectura(19.43, -99.13, 12f, falsa = false)) // …llega el GPS: primer choque
        runBlocking { delay(700) }
        quienSoy.cancelarLimpieza() // A se niega
        // B pone su marca justo cuando el segundo completar lee «Quién soy», y se niega antes de que la pantalla lo revise.
        almacen.despuesDeLeer("credencial") {
            quienSoy.empezarLimpiezaSi(quienSoy.generacion)
            almacen.despuesDeLeer("limpiando") { quienSoy.cancelarLimpieza() }
        }
        main.scheduler.advanceTimeBy(1_000); main.scheduler.runCurrent()
        val entrada = runBlocking { withTimeout(5_000) { vm.estado.first { !it.ocupado } }; dao.pendientesDeVer().first() }.single()
        assertEquals(EstadoPieza.PENDIENTE, entrada.estado) // no RECHAZADA: la persona y la generación son las mismas
        assertEquals(g, quienSoy.generacion)
        assertEquals(19.43, cuerpo(entrada)["latitude"].asDouble, 0.0)
        assertEquals(entrada, runBlocking { dao.siguientesParaSubir() }.single()) // sube
    }

    @Test fun `r9 - con la marca CONFIRMADA (el vaciado fallo con la app viva) la checada deja de esperar y sigue APARTADA`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        vm.marcarEntrada()
        runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { it.isNotEmpty() } } }
        assertTrue(quienSoy.empezarLimpiezaSi(quienSoy.generacion))
        quienSoy.confirmarLimpieza() // el borrado siguió, pero vaciar falló dos veces: lo termina el arranque
        espera.complete(null)
        runBlocking { withTimeout(5_000) { vm.estado.first { !it.ocupado } } } // no se cuelga
        assertEquals(EstadoPieza.APARTADA, piezas().single().estado)
    }

    @Test fun `r9 - una marca sin confirmar que nunca se quita no cuelga la checada - al tope sigue APARTADA`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        vm.marcarEntrada()
        runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { it.isNotEmpty() } } }
        assertTrue(quienSoy.empezarLimpiezaSi(quienSoy.generacion)) // y deshacerla falló: se queda hasta el arranque
        espera.complete(null)
        runBlocking { delay(700) }
        main.scheduler.advanceTimeBy(61_000); main.scheduler.runCurrent()
        runBlocking { withTimeout(5_000) { vm.estado.first { !it.ocupado } } }
        val p = piezas().single()
        assertEquals(EstadoPieza.APARTADA, p.estado) // la completa el arranque; nunca RECHAZADA por esto
    }

    @Test fun `r6 - si completar la checada apartada falla, no se borra - queda en Mis pendientes con su foto`() {
        vm.marcarEntrada(); quieto()
        runBlocking { withTimeout(5_000) { vm.estado.first { it.entrada != null } } }
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        every { copia.copia } returns MutableStateFlow(null) // pide selfie
        vm.marcarSalida()
        vm.alTomarSelfie(File(context.cacheDir, "fotos/r6.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8) { 1 }) }.absolutePath)
        val salida = runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { l -> l.any { it.tipo == TipoPieza.SALIDA } } } }
            .single { it.tipo == TipoPieza.SALIDA }
        // Mientras espera el GPS, la gracia descarta su entrada (cierra el turno): la salida ya no se puede completar.
        val entrada = piezas().single { it.tipo == TipoPieza.ENTRADA }
        runBlocking { dao.descartarConHijos(entrada.id, "SU_ENTRADA_SE_DESCARTO") }
        espera.complete(null)
        quieto()
        val p = runBlocking { dao.porId(salida.id) }!!
        assertEquals(EstadoPieza.RECHAZADA, p.estado) // a la vista, con «Quitar»; nunca se borra sola
        assertTrue(File(fotosDe(p.fotosJson).single().archivo).isFile)
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

    // Codex D2 r2: entrada sin permiso de ubicación y, sin salir de «Checar», el promotor lo concede: el GPS ya sigue y el
    // aviso de esa entrada no debe seguir diciendo que no.
    @Test fun `si el GPS empieza a seguir despues, el aviso de la entrada deja de decir que no sigue`() {
        respuestaGps = ResultadoGps.NoSigue("no diste permiso de ubicación")
        vm.marcarEntrada()
        quieto()
        estadoDelGps.publicar(ResultadoGps.Siguiendo)
        val e = runBlocking { withTimeout(5_000) { vm.estado.first { it.gps == ResultadoGps.Siguiendo } } }
        assertEquals("Entrada guardada · se sube sola", e.aviso)
    }

    @Test fun `H-A1 - si la tienda no usa el seguimiento el texto de la entrada guardada no habla del GPS`() {
        respuestaGps = ResultadoGps.NoLoUsaLaTienda
        vm.marcarEntrada()
        val e = quieto()
        assertEquals(1, piezas().count { it.tipo == TipoPieza.ENTRADA })
        assertEquals("Entrada guardada · se sube sola", e.aviso)
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

    @Test fun `una checada que espera el GPS cuando llega un borrado no se pierde - el borrado la ve y no borra (r4 1, r5)`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        coEvery { copia.borrar() } just runs
        vm.marcarEntrada()
        // D5 (20-nov): apartarChecada guarda en el hilo de Room; sin esta espera, con el CI cargado el borrado llegaba ANTES de que
        // existiera la APARTADA, no la veía y borraba (falló 1 vez en campo-release.yml, línea del QUEDA_ALGO). El caso que se
        // prueba es «la checada YA espera el GPS cuando llega el borrado», así que se espera a que esté guardada, como las vecinas.
        runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { l -> l.any { it.estado == EstadoPieza.APARTADA } } } }
        // Mientras espera el GPS llega un latido DESVINCULADO: la navegación cambia a la gracia (la pantalla se va) y la persona
        // toca «Activar con un código nuevo».
        vm.viewModelScope.cancel()
        quienSoy.marcarDesvinculado(null)
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, runBlocking { borrado().activarDeNuevo() }) // «Todavía queda algo por subir»
        espera.complete(null) // llega el GPS (sin ubicación): la checada se completa
        val entrada = runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { l -> l.any { it.estado == EstadoPieza.PENDIENTE } } } }.single()
        assertEquals(TipoPieza.ENTRADA, entrada.tipo) // sube por la gracia
        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
        assertEquals("c".repeat(64), quienSoy.credencial)
        assertFalse(quienSoy.limpiando)
    }

    @Test fun `la checada tocada queda guardada en el celular ANTES de esperar el GPS (r5)`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        every { copia.copia } returns MutableStateFlow(null) // pide selfie
        vm.marcarEntrada()
        val selfie = File(context.cacheDir, "fotos/r5.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8) { 1 }) }
        vm.alTomarSelfie(selfie.absolutePath)
        // Android mata la app aquí, mientras «Buscando tu ubicación…»: lo único que sobrevive es lo que ya está en Room.
        val apartada = runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { it.isNotEmpty() } } }.single()
        assertEquals(EstadoPieza.APARTADA, apartada.estado)
        assertEquals(TipoPieza.ENTRADA, apartada.tipo)
        assertEquals("s1", apartada.staffId)
        assertEquals("v1", apartada.venueId)
        assertNull(apartada.huella) // el latido no la reporta
        assertTrue(runBlocking { dao.siguientesParaSubir() }.isEmpty()) // el Subidor no la manda
        assertFalse(selfie.exists()) // ya no está en cacheDir/fotos, donde la barrería el barrido de 24 h
        val foto = File(fotosDe(apartada.fotosJson).single().archivo)
        assertTrue(foto.isFile)
        // Al volver a abrir (otra app, otro Encolador): se completa sin ubicación, con la hora del toque, y queda para subir.
        val otroArranque = Encolador(dao, quienSoy, reloj, SecuenciaDeCaptura(almacen), File(context.filesDir, "cola"), env = "dev")
        assertEquals(1, runBlocking { otroArranque.completarApartadas() })
        val entrada = piezas().single()
        assertEquals(EstadoPieza.PENDIENTE, entrada.estado)
        assertEquals(apartada.llave, entrada.llave)
        assertEquals(apartada.creadaAt, entrada.creadaAt)
        val c = cuerpo(entrada)
        assertFalse(c.has("latitude"))
        assertEquals(fotosDe(apartada.fotosJson).single().url, c["checkInPhotoUrl"].asString)
        assertEquals(entrada, runBlocking { dao.siguientesParaSubir() }.single())
        assertEquals(entrada.huella, runBlocking { dao.huellasPorSubir() }.single())
        assertEquals(apartada.llave, runBlocking { dao.turnoAhora() }!!.llaveEntrada)
        assertTrue(foto.isFile)
        // Doble arranque: no la duplica ni la toca.
        assertEquals(0, runBlocking { otroArranque.completarApartadas() })
        assertEquals(listOf(entrada), piezas())
    }

    @Test fun `el arranque no completa la checada apartada de otra activacion - queda en Mis pendientes, no se pierde (r5)`() {
        val espera = CompletableDeferred<Ubicacion.Lectura?>()
        coEvery { ubicacion.leer(any()) } coAnswers { espera.await() }
        vm.marcarEntrada()
        runBlocking { withTimeout(5_000) { dao.pendientesDeVer().first { it.isNotEmpty() } } }
        quienSoy.empezarLimpiezaSi(quienSoy.generacion); quienSoy.marcarLimpiando(false) // otra generación (no debería pasar)
        assertEquals(0, runBlocking { encolador.completarApartadas() })
        val p = piezas().single()
        assertEquals(EstadoPieza.RECHAZADA, p.estado)
        assertEquals("CHECADA_SIN_TERMINAR", p.rechazoCode)
        assertTrue(runBlocking { dao.siguientesParaSubir() }.isEmpty())
    }

    // ── D3 · salida olvidada ────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `D3 - un turno local de un dia ya terminado no ofrece salida, lo dice, y la entrada de hoy se guarda`() {
        val haceDosDias = 1_762_797_600_000 - 2 * 24 * 3_600_000L
        runBlocking { dao.guardarTurno(com.jaac.avoqado_campo.cola.TurnoLocal(1, "s1", "vieja", "te-viejo", "v1", haceDosDias)) }
        val e = runBlocking { withTimeout(5_000) { vm.estado.first { it.olvidadaAt != null } } }
        assertNull("no ofrece «Marcar salida» de un turno olvidado", e.entrada)
        assertEquals(haceDosDias, e.olvidadaAt)

        vm.marcarEntrada()
        quieto()
        val entrada = piezas().single { it.tipo == TipoPieza.ENTRADA }
        assertEquals(EstadoPieza.PENDIENTE, entrada.estado)
        val t = runBlocking { dao.turnoAhora() }!!
        assertEquals("la entrada nueva reemplaza al turno olvidado", entrada.llave, t.llaveEntrada)
    }

    @Test fun `D3 Codex r1 #4 - una salida apartada antes del corte se completa con la hora del toque aunque ya paso el corte`() {
        // El turno abrió hace 2 h: a la hora del toque sigue vivo; 30 h después ya pasó el límite del celular en cualquier zona.
        val abierto = 1_762_797_600_000 - 2 * 3_600_000L
        runBlocking { dao.guardarTurno(com.jaac.avoqado_campo.cola.TurnoLocal(1, "s1", "e1", "te-1", "v1", abierto)) }
        val generacion = quienSoy.generacion
        val id = runBlocking { encolador.apartarChecada(generacion, TipoPieza.SALIDA, "v1", null, "Salida") }
        // Android mató la app; al volver a abrir el reloj ya pasó el límite del celular. Se completa igual con la hora del toque.
        val tarde = Encolador(dao, quienSoy, RelojAnclado(almacen, { 1_762_797_600_000 + 30 * 3_600_000L }, { 0L }, { 1 }),
            SecuenciaDeCaptura(almacen), File(context.filesDir, "cola"), env = "dev")
        assertEquals(1, runBlocking { tarde.completarApartadas() })
        val salida = piezas().single { it.id == id }
        assertEquals(EstadoPieza.PENDIENTE, salida.estado)
        assertEquals("te-1", cuerpo(salida).get("timeEntryId").asString)
    }

    @Test fun `D3 - el limite del turno es el corte de las 4 y nunca antes de 12 h`() {
        val mx = java.time.ZoneId.of("America/Mexico_City")
        fun ms(s: String) = java.time.ZonedDateTime.of(java.time.LocalDateTime.parse(s), mx).toInstant().toEpochMilli()
        assertEquals(ms("2026-11-04T04:00"), limiteDelTurno(ms("2026-11-03T09:00"), mx))
        assertEquals("turno nocturno: 12 h", ms("2026-11-04T10:00"), limiteDelTurno(ms("2026-11-03T22:00"), mx))
        val t = com.jaac.avoqado_campo.cola.TurnoLocal(1, "s1", "k", null, "v1", ms("2026-11-03T09:00"))
        assertTrue(turnoVigente(t, ms("2026-11-03T23:59"), mx))
        // Codex r1 #3: el celular espera 3 h más que el SERVIDOR (zonas distintas, horarios largos): nunca se adelanta.
        assertTrue("con el margen del celular, a las 04:00 sigue vivo", turnoVigente(t, ms("2026-11-04T04:00"), mx))
        assertFalse(turnoVigente(t, ms("2026-11-04T07:00"), mx))
        assertEquals(ms("2026-11-04T07:00"), limiteEnElCelular(ms("2026-11-03T09:00"), mx))
        assertTrue("sin hora (fila vieja) cuenta como vivo", turnoVigente(t.copy(abiertoAt = null), ms("2026-12-01T00:00"), mx))
        assertNull(refEntrada(t, "s1", "v1", ms("2026-11-04T09:00")))
        assertEquals(ms("2026-11-03T09:00"), turnoOlvidado(t, "s1", ms("2026-11-04T09:00"), mx))
        assertNull("del mismo día no es olvidado", turnoOlvidado(t, "s1", ms("2026-11-03T20:00"), mx))
        assertTrue(textoDeSalidaOlvidada(ms("2026-11-09T09:00"), mx).contains("9-nov"))
    }

    // Codex r5 #1: promotor de dos sucursales. Con el turno local de A y la sesión en B, Checar ofrece la entrada de B (no la
    // salida de A), la entrada de B se encola normal sin cerrar A y después se puede vender en B.
    @Test fun `con el turno local de A y la sesion en B, Checar ofrece la entrada de B y despues se vende en B`() {
        runBlocking {
            dao.guardarTurno(com.jaac.avoqado_campo.cola.TurnoLocal(staffId = "s1", llaveEntrada = null, timeEntryId = "teA", venueId = "v1", abiertoAt = 1_762_797_600_000 - 60_000))
        }
        quienSoy.guardarEntrada(
            com.jaac.avoqado_campo.identidad.DatosDeEntrada(Sesion("a2", "r", "v2"), listOf(Sucursal("v1", "Centro", null), Sucursal("v2", "Norte", null))),
        )
        vm.viewModelScope.cancel()
        vm = ChecadaViewModel(encolador, ubicacion, gps, estadoDelGps, copia, dao, quienSoy, context)
        assertNull("no ofrece la salida de A", vm.estado.value.entrada)
        vm.marcarEntrada()
        assertNull(quieto().error)
        val entrada = piezas().single()
        assertEquals(TipoPieza.ENTRADA, entrada.tipo)
        assertEquals("v2", entrada.venueId)
        assertEquals("v2", runBlocking { dao.turnoAhora() }!!.venueId)
        runBlocking {
            encolador.venta(
                quienSoy.generacion,
                com.jaac.avoqado_campo.cola.VentaNueva("8952140000001", java.math.BigDecimal("50"), false, "v2", emptyList(), "SIM"),
                exigirEntrada = true,
            )
        }
        assertEquals(listOf(TipoPieza.ENTRADA, TipoPieza.VENTA), piezas().map { it.tipo })
    }

    @Test fun `el turno de otra persona no cuenta`() {
        assertNull(refEntrada(com.jaac.avoqado_campo.cola.TurnoLocal(1, "otra", "k", null, "v1", 0L), "s1", "v1", 1_000L))
        assertEquals(RefEntrada.PorLlave("k"), refEntrada(com.jaac.avoqado_campo.cola.TurnoLocal(1, "s1", "k", null, "v1", 0L), "s1", "v1", 1_000L))
        assertEquals(RefEntrada.PorId("t"), refEntrada(com.jaac.avoqado_campo.cola.TurnoLocal(1, "s1", "k", "t", "v1", 0L), "s1", "v1", 1_000L))
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
