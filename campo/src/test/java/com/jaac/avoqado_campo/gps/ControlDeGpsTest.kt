package com.jaac.avoqado_campo.gps

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.location.LocationManager
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_campo.checada.Ubicacion
import com.jaac.avoqado_campo.checada.limiteEnElCelular
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.TurnoLocal
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.DatosDeEntrada
import com.jaac.avoqado_campo.identidad.DatosDeLatido
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlDeGpsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val estadoAlmacen = AlmacenEnMemoria()
    private val estado = EstadoDelGps(estadoAlmacen)
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen).also {
        it.guardarActivacion(DatosDeActivacion("c1", "c".repeat(64), "s1", "Ana", Sesion("a", "r", "v1"), listOf(Sucursal("v1", "Centro", null))))
    }
    private lateinit var db: BaseDeCampo
    private val ubicacion = mockk<Ubicacion>()
    private val puerta = PuertaDelServicio()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, BaseDeCampo::class.java).allowMainThreadQueries().build()
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        coEvery { ubicacion.leer(any()) } returns Ubicacion.Lectura(19.43, -99.13, 12f, falsa = false)
    }

    @After fun tearDown() = db.close()

    private fun conPermiso() = shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun control(ahora: Long = AHORA, arrancar: ((Intent) -> Unit)? = null) =
        if (arrancar == null) ControlDeGpsReal(app, estado, puerta, quienSoy, db.piezaDao(), ahora = { ahora })
        else ControlDeGpsReal(app, estado, puerta, quienSoy, db.piezaDao(), ahora = { ahora }, arrancar = arrancar)
    /** Lo que trae un latido: la sucursal v1 con el ajuste de seguimiento que diga el SERVIDOR. */
    private fun sucursalSigue(sigue: Boolean?) =
        quienSoy.guardarLatido(DatosDeLatido("VINCULADO", null, null, listOf(Sucursal("v1", "Centro", null, sigue))))
    private fun seguimiento(ahora: Long = AHORA) =
        SeguimientoDelTurno(puerta, estado, ubicacion, db.pingDao(), db.piezaDao(), quienSoy) { ahora }

    /** Lo que hizo el servicio en `onStartCommand`, en orden. */
    private val pasos = mutableListOf<String>()
    private suspend fun arrancarServicio(tienePermiso: Boolean = true, primerPlano: () -> Unit = { pasos += "primerPlano" }) =
        seguimiento().arrancar(tienePermiso, primerPlano) { pasos += "quitar" }
    /** El turno se abrió hace un minuto (hoy): vigente. [haceMs] grande = un turno de un día ya terminado (D3, olvidado). */
    private suspend fun abrirTurno(staffId: String = "s1", haceMs: Long = 60_000L) =
        db.piezaDao().guardarTurno(TurnoLocal(staffId = staffId, llaveEntrada = "e1", timeEntryId = null, venueId = "v1", abiertoAt = AHORA - haceMs))

    @Test fun `sin permiso no arranca nada y dice por que`() = runBlocking<Unit> {
        val r = control().iniciar()
        assertEquals(ResultadoGps.NoSigue("no diste permiso de ubicación"), r)
        assertEquals(r, estado.estado.value)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `con permiso y la ubicacion apagada no sigue`() = runBlocking<Unit> {
        conPermiso()
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        val r = control().iniciar()
        assertEquals(ResultadoGps.NoSigue("la ubicación del teléfono está apagada"), r)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `si Android no deja arrancar el servicio no sigue y no truena`() = runBlocking<Unit> {
        conPermiso()
        val r = control(arrancar = { throw SecurityException("Android 14") }).iniciar()
        assertTrue(r is ResultadoGps.NoSigue)
        assertEquals(r, estado.estado.value)
        val r2 = control(arrancar = { throw IllegalStateException("ForegroundServiceStartNotAllowedException") }).iniciar()
        assertTrue(r2 is ResultadoGps.NoSigue)
    }

    @Test fun `con permiso y ubicacion arranca el servicio de ubicacion`() = runBlocking<Unit> {
        conPermiso()
        assertEquals(ResultadoGps.Siguiendo, control().iniciar())
        assertEquals(ResultadoGps.Siguiendo, estado.estado.value)
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test fun `H-A1 - la tienda no usa el seguimiento - no arranca el servicio y el estado no es un aviso`() = runBlocking<Unit> {
        sucursalSigue(false)
        // Ni sin permiso: lo que pide la tienda gana, no hay nada que arreglar.
        assertEquals(ResultadoGps.NoLoUsaLaTienda, control().iniciar())
        conPermiso()
        val r = control().iniciar()
        assertEquals(ResultadoGps.NoLoUsaLaTienda, r)
        assertFalse(r is ResultadoGps.NoSigue)
        assertEquals(r, estado.estado.value)
        assertNull(shadowOf(app).nextStartedService)
        // Se recuerda al abrir la app otra vez.
        assertEquals(ResultadoGps.NoLoUsaLaTienda, EstadoDelGps(estadoAlmacen).estado.value)
    }

    @Test fun `H-A1 - con true o sin el dato (SERVIDOR viejo) arranca como hoy`() = runBlocking<Unit> {
        conPermiso()
        sucursalSigue(true)
        assertEquals(ResultadoGps.Siguiendo, control().iniciar())
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        sucursalSigue(null)
        assertEquals(ResultadoGps.Siguiendo, control().iniciar())
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test fun `H-A1 - si un latido lo apaga con el turno abierto, el siguiente reanudar para el servicio`() = runBlocking<Unit> {
        conPermiso()
        abrirTurno()
        assertEquals(ResultadoGps.Siguiendo, reanudarSiHayTurno(control(), db.piezaDao(), quienSoy, AHORA))
        assertTrue(arrancarServicio())
        sucursalSigue(false)
        assertEquals(ResultadoGps.NoLoUsaLaTienda, reanudarSiHayTurno(control(), db.piezaDao(), quienSoy, AHORA))
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
        assertEquals(ResultadoGps.NoLoUsaLaTienda, estado.estado.value)
    }

    /** Turno en v1 (Centro) y sesión en v2 (Norte), cada una con su ajuste: lo que deja un `entrar` con PIN a otra sucursal. */
    private fun turnoEnUnaYSesionEnOtra(delTurno: Boolean, deLaSesion: Boolean) = quienSoy.guardarEntrada(
        DatosDeEntrada(Sesion("a2", "r2", "v2"), listOf(Sucursal("v1", "Centro", null, delTurno), Sucursal("v2", "Norte", null, deLaSesion))),
    )

    @Test fun `G - decide la sucursal del TURNO, no la de la sesion (r3 4)`() = runBlocking<Unit> {
        conPermiso()
        abrirTurno() // en v1
        turnoEnUnaYSesionEnOtra(delTurno = true, deLaSesion = false)
        assertEquals(ResultadoGps.Siguiendo, control().iniciar())
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        turnoEnUnaYSesionEnOtra(delTurno = false, deLaSesion = true)
        assertEquals(ResultadoGps.NoLoUsaLaTienda, control().iniciar())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `G - la sucursal del turno que ya no es suya no se sigue, aunque la de la sesion si (r4 2)`() = runBlocking<Unit> {
        conPermiso()
        abrirTurno() // en v1, que el supervisor le quitó: el latido deja sólo v2
        for (deLaSesion in listOf(false, true)) {
            quienSoy.guardarEntrada(DatosDeEntrada(Sesion("a2", "r2", "v2"), listOf(Sucursal("v2", "Norte", null, deLaSesion))))
            assertEquals(ResultadoGps.NoLoUsaLaTienda, control().iniciar())
            assertNull(shadowOf(app).nextStartedService)
            pasos.clear()
            puerta.detenerPedido = false // el reinicio de Android: nadie pidió detener, decide `debeSeguir`
            assertFalse(arrancarServicio()) // ni el reinicio de Android
            assertEquals(listOf("primerPlano", "quitar"), pasos)
            assertFalse(seguimiento().tomarUno()) // ni la lectura de cada 30 min
            assertTrue(db.pingDao().todos().isEmpty())
        }
    }

    @Test fun `G - el servicio que Android reinicia solo con la sucursal del turno apagada se detiene sin aviso fijo (r3 3)`() = runBlocking<Unit> {
        conPermiso()
        abrirTurno()
        // Un latido la apagó y Android mató el proceso: el reinicio START_STICKY entra directo a onStartCommand, sin iniciar().
        turnoEnUnaYSesionEnOtra(delTurno = false, deLaSesion = true)
        assertFalse(arrancarServicio())
        assertEquals(listOf("primerPlano", "quitar"), pasos) // el aviso fijo se quita
        assertFalse(puerta.enPrimerPlano)
        assertEquals(ResultadoGps.NoLoUsaLaTienda, estado.estado.value)
        // Con la del turno prendida (aunque la de la sesión no), sí sigue.
        pasos.clear()
        turnoEnUnaYSesionEnOtra(delTurno = true, deLaSesion = false)
        assertTrue(arrancarServicio())
        assertEquals(listOf("primerPlano"), pasos)
    }

    @Test fun `G - con la sucursal del turno apagada, la siguiente lectura detiene el servicio sin guardar el ping`() = runBlocking<Unit> {
        abrirTurno()
        turnoEnUnaYSesionEnOtra(delTurno = false, deLaSesion = true)
        assertFalse(seguimiento().tomarUno())
        assertTrue(db.pingDao().todos().isEmpty())
        assertEquals(ResultadoGps.NoLoUsaLaTienda, estado.estado.value)
        // La del turno prendida y la de la sesión apagada: el ping es de la del turno.
        turnoEnUnaYSesionEnOtra(delTurno = true, deLaSesion = false)
        assertTrue(seguimiento().tomarUno())
        assertEquals("v1", db.pingDao().todos().single().venueId)
    }

    @Test fun `G - revisar para el servicio vivo si la sucursal del turno se apago, y si sigue no arranca nada`() = runBlocking<Unit> {
        conPermiso()
        abrirTurno()
        val c = control()
        assertEquals(ResultadoGps.Siguiendo, c.iniciar())
        shadowOf(app).nextStartedService
        assertTrue(arrancarServicio())
        turnoEnUnaYSesionEnOtra(delTurno = true, deLaSesion = false) // la de la sesión no manda
        c.revisar()
        assertNull(shadowOf(app).nextStoppedService)
        assertNull(shadowOf(app).nextStartedService)
        turnoEnUnaYSesionEnOtra(delTurno = false, deLaSesion = true) // lo que guarda el latido
        c.revisar()
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
        assertEquals(ResultadoGps.NoLoUsaLaTienda, estado.estado.value)
        // La prenden otra vez: revisar no arranca nada; lo hace el siguiente reanudar (con la app a la vista).
        turnoEnUnaYSesionEnOtra(delTurno = true, deLaSesion = false)
        c.revisar()
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(ResultadoGps.Siguiendo, reanudarSiHayTurno(c, db.piezaDao(), quienSoy, AHORA))
    }

    @Test fun `G - cada cambio de sucursales guardado (latido, entrar) hace revisar el servicio`() = runBlocking<Unit> {
        var revisiones = 0
        val falso = object : ControlDeGps {
            override suspend fun iniciar(): ResultadoGps = ResultadoGps.Siguiendo
            override fun detener() = Unit
            override suspend fun revisar() { revisiones++ }
        }
        val vigilante = vigilarElSeguimiento(quienSoy, falso)
        withTimeout(5_000) { while (revisiones < 1) delay(10) } // al arrancar la app
        sucursalSigue(false)
        withTimeout(5_000) { while (revisiones < 2) delay(10) }
        sucursalSigue(false) // las mismas sucursales: nada que revisar
        turnoEnUnaYSesionEnOtra(delTurno = true, deLaSesion = false)
        withTimeout(5_000) { while (revisiones < 3) delay(10) }
        vigilante.cancel()
        assertEquals(3, revisiones)
    }

    @Test fun `el servicio con un startForeground que truena se detiene y deja NoSigue`() = runBlocking<Unit> {
        estado.publicar(ResultadoGps.Siguiendo) // lo que dijo iniciar() antes de que el servicio corriera
        assertFalse(arrancarServicio { throw SecurityException("FOREGROUND_SERVICE_LOCATION") })
        assertTrue(estado.estado.value is ResultadoGps.NoSigue)
        assertFalse(arrancarServicio { throw IllegalStateException("no permitido") })
        assertEquals(emptyList<String>(), pasos) // nunca llegó a primer plano: nada que quitar
        assertFalse(puerta.enPrimerPlano)
    }

    @Test fun `el servicio sin permiso intenta el primer plano, lo quita y se detiene con NoSigue`() = runBlocking<Unit> {
        assertFalse(arrancarServicio(tienePermiso = false))
        assertEquals(listOf("primerPlano", "quitar"), pasos)
        assertEquals(ResultadoGps.NoSigue("no diste permiso de ubicación"), estado.estado.value)
        assertFalse(puerta.enPrimerPlano)
    }

    @Test fun `detener antes de que corra el servicio no hace stopService y el servicio se quita solo`() = runBlocking<Unit> {
        conPermiso()
        control().iniciar()
        shadowOf(app).nextStartedService
        control().detener()
        assertNull(shadowOf(app).nextStoppedService) // stopService aquí cerraría la app (fgRequired)
        assertFalse(arrancarServicio())
        assertEquals(listOf("primerPlano", "quitar"), pasos)
        assertEquals(ResultadoGps.Siguiendo, estado.estado.value) // parar a propósito no es un problema
    }

    @Test fun `detener con el servicio en primer plano lo para`() = runBlocking<Unit> {
        conPermiso()
        control().iniciar()
        assertTrue(arrancarServicio())
        assertTrue(puerta.enPrimerPlano)
        control().detener()
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
        // un iniciar() nuevo borra el pedido de detener
        control().iniciar()
        assertTrue(arrancarServicio())
    }

    @Test fun `con turno abierto guarda un ping de su sucursal`() = runBlocking<Unit> {
        abrirTurno()
        assertTrue(seguimiento(ahora = AHORA + 5_000L).tomarUno())
        val p = db.pingDao().todos().single()
        assertEquals("v1", p.venueId)
        assertEquals(19.43, p.lat, 0.0)
        assertEquals(12f, p.precision)
        assertEquals(AHORA + 5_000L, p.capturadoAt)
    }

    @Test fun `sin turno o con el turno de otra persona el servicio se detiene sin guardar`() = runBlocking<Unit> {
        assertFalse(seguimiento().tomarUno())
        abrirTurno(staffId = "otra")
        assertFalse(seguimiento().tomarUno())
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `I2 - durante el borrado o tras otra activacion no se escribe el ping`() = runBlocking<Unit> {
        abrirTurno()
        quienSoy.marcarLimpiando(true)
        seguimiento().tomarUno()
        assertTrue(db.pingDao().todos().isEmpty())

        quienSoy.marcarLimpiando(false)
        coEvery { ubicacion.leer(any()) } coAnswers { quienSoy.empezarLimpiezaSi(quienSoy.generacion); quienSoy.marcarLimpiando(false); Ubicacion.Lectura(1.0, 2.0, 3f, false) }
        seguimiento().tomarUno() // la generación cambió mientras se leía
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `al abrir la app con turno abierto se vuelve a intentar`() = runBlocking<Unit> {
        var intentos = 0
        val falso = object : ControlDeGps {
            override suspend fun iniciar(): ResultadoGps { intentos++; return ResultadoGps.Siguiendo }
            override fun detener() = Unit
            override suspend fun revisar() = Unit
        }
        assertNull(reanudarSiHayTurno(falso, db.piezaDao(), quienSoy, AHORA))
        abrirTurno()
        assertEquals(ResultadoGps.Siguiendo, reanudarSiHayTurno(falso, db.piezaDao(), quienSoy, AHORA))
        assertEquals(1, intentos)
    }

    /** D3 en el GPS (hz:nube0000:na1c5a48): un turno de un día ya terminado («salida olvidada») ya no se sigue. */
    @Test fun `D3 - con el turno olvidado de otro dia la lectura no guarda ping y detiene el servicio`() = runBlocking<Unit> {
        abrirTurno(haceMs = DOS_DIAS)
        assertFalse(seguimiento().tomarUno())
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `D3 - el turno se vuelve olvidado con el servicio corriendo - la siguiente lectura lo para`() = runBlocking<Unit> {
        abrirTurno()
        assertTrue(seguimiento().tomarUno()) // hoy: sigue
        assertFalse(seguimiento(ahora = AHORA + DOS_DIAS).tomarUno()) // dos días después, sin salida ni entrada nueva
        assertEquals(1, db.pingDao().todos().size)
    }

    @Test fun `D3 - una lectura que cruza el limite del turno no guarda el ping (Codex r1 2)`() = runBlocking<Unit> {
        abrirTurno()
        val limite = limiteEnElCelular(AHORA - 60_000L)
        var reloj = limite - 1_000L // la lectura empieza un segundo antes del límite…
        coEvery { ubicacion.leer(any()) } coAnswers {
            reloj = limite + 1_000L // …y el GPS contesta un segundo después
            Ubicacion.Lectura(19.43, -99.13, 12f, falsa = false)
        }
        val s = SeguimientoDelTurno(puerta, estado, ubicacion, db.pingDao(), db.piezaDao(), quienSoy) { reloj }
        assertFalse(s.tomarUno())
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `D3 - sin posicion y con el limite cruzado mientras buscaba, se para sin esperar otra vuelta (Codex r2 1)`() = runBlocking<Unit> {
        abrirTurno()
        val limite = limiteEnElCelular(AHORA - 60_000L)
        var reloj = limite - 1_000L
        coEvery { ubicacion.leer(any()) } coAnswers { reloj = limite + 1_000L; null }
        val s = SeguimientoDelTurno(puerta, estado, ubicacion, db.pingDao(), db.piezaDao(), quienSoy) { reloj }
        assertFalse(s.tomarUno())
        // Sin posición dentro del turno vigente sigue como antes: otra vuelta.
        abrirTurno()
        reloj = AHORA
        coEvery { ubicacion.leer(any()) } returns null
        assertTrue(s.tomarUno())
    }

    @Test fun `D3 - una entrada nueva marcada mientras se leia no para el servicio ni se lleva esa lectura (Codex r2 2, r4 3)`() = runBlocking<Unit> {
        abrirTurno()
        val limite = limiteEnElCelular(AHORA - 60_000L)
        var reloj = limite - 1_000L
        coEvery { ubicacion.leer(any()) } coAnswers {
            reloj = limite + 1_000L
            // El promotor marcó su entrada de hoy mientras el GPS buscaba señal: la fila del turno ya es otra, y la posición
            // que contestó el GPS puede ser de antes del toque (de otra sucursal).
            db.piezaDao().guardarTurno(TurnoLocal(staffId = "s1", llaveEntrada = "e2", timeEntryId = null, venueId = "v1", abiertoAt = limite + 500L))
            Ubicacion.Lectura(19.43, -99.13, 12f, falsa = false)
        }
        val s = SeguimientoDelTurno(puerta, estado, ubicacion, db.pingDao(), db.piezaDao(), quienSoy) { reloj }
        assertTrue(s.tomarUno()) // el servicio sigue: ahora es de la entrada nueva
        assertTrue(db.pingDao().todos().isEmpty())
        coEvery { ubicacion.leer(any()) } returns Ubicacion.Lectura(19.43, -99.13, 12f, falsa = false)
        assertTrue(s.tomarUno()) // la siguiente (que el arranque de la entrada pide de inmediato) sí es suya
        assertEquals(limite + 1_000L, db.pingDao().todos().single().capturadoAt)
    }

    @Test fun `D3 - con la hora del celular corregida hacia atras, la misma entrada sigue guardando sus pings (Codex r4 2)`() = runBlocking<Unit> {
        abrirTurno(haceMs = -3_600_000L) // la entrada quedó guardada una hora «en el futuro» (el reloj iba adelantado)
        assertTrue(seguimiento().tomarUno())
        assertEquals(1, db.pingDao().todos().size)
    }

    @Test fun `D3 - un arranque viejo que termina despues de uno nuevo no quita el aviso (Codex r4 1)`() = runBlocking<Unit> {
        abrirTurno(haceMs = DOS_DIAS) // la fila que leyó el arranque viejo
        val r = seguimiento().arrancar(true, primerPlano = { pasos += "primerPlano" }, esElUltimo = { false }) { pasos += "quitar" }
        assertTrue(r) // decide el bucle, con la fila de ahora
        assertEquals(listOf("primerPlano"), pasos)
        assertTrue(puerta.enPrimerPlano)
    }

    @Test fun `D3 - un error del GPS al cruzar el limite no deja el aviso otra vuelta (Codex r3 1)`() = runBlocking<Unit> {
        abrirTurno()
        val limite = limiteEnElCelular(AHORA - 60_000L)
        var reloj = limite - 1_000L
        coEvery { ubicacion.leer(any()) } coAnswers { reloj = limite + 1_000L; throw SecurityException("permiso quitado") }
        val s = SeguimientoDelTurno(puerta, estado, ubicacion, db.pingDao(), db.piezaDao(), quienSoy) { reloj }
        assertFalse(s.tomarUno())
        // Dentro del turno, un error sigue como antes: otra vuelta, sin ping.
        reloj = AHORA
        coEvery { ubicacion.leer(any()) } throws IllegalStateException("proveedor")
        assertTrue(s.tomarUno())
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `bucle - un arranque nuevo mientras la lectura decidia parar no para el servicio (Codex r3 2)`() = runBlocking<Unit> {
        var arranques = 0
        val respuestas = ArrayDeque(listOf(false, true, false)) // la vieja dice «para», la de la entrada nueva «sigue», luego «para»
        var esperas = 0
        correrBucle(
            tomarUno = { respuestas.removeFirst().also { if (respuestas.size == 2) arranques++ } }, // el arranque llega en la 1ª
            arranques = { arranques },
        ) { esperas++ }
        assertTrue(respuestas.isEmpty()) // decidió otra vez con la fila nueva en vez de parar
        assertEquals(1, esperas)
    }

    @Test fun `bucle - con un arranque nuevo durante la lectura, la siguiente es ya, no en 30 min (Codex r3 3)`() = runBlocking<Unit> {
        var arranques = 0
        val respuestas = ArrayDeque(listOf(true, true, false))
        var esperas = 0
        correrBucle(
            tomarUno = { respuestas.removeFirst().also { if (respuestas.size == 2) arranques++ } },
            arranques = { arranques },
        ) { esperas++ }
        assertEquals(1, esperas) // sólo después de la 2ª (la del arranque nuevo no esperó)
    }

    @Test fun `bucle - sin arranques nuevos para cuando ya no hay turno y un error cuenta como seguir`() = runBlocking<Unit> {
        val respuestas = ArrayDeque<() -> Boolean>(listOf({ throw IllegalStateException("x") }, { true }, { false }))
        var esperas = 0
        correrBucle(tomarUno = { respuestas.removeFirst()() }, arranques = { 0 }) { esperas++ }
        assertTrue(respuestas.isEmpty())
        assertEquals(2, esperas)
    }

    @Test fun `D3 - la misma entrada ya subida (con timeEntryId) sigue guardando su ping`() = runBlocking<Unit> {
        abrirTurno()
        coEvery { ubicacion.leer(any()) } coAnswers {
            db.piezaDao().guardarTurno(TurnoLocal(staffId = "s1", llaveEntrada = "e1", timeEntryId = "te1", venueId = "v1", abiertoAt = AHORA - 60_000L))
            Ubicacion.Lectura(19.43, -99.13, 12f, falsa = false)
        }
        assertTrue(seguimiento().tomarUno())
        assertEquals(1, db.pingDao().todos().size)
    }

    @Test fun `D3 - al volver al frente con el turno olvidado no arranca el GPS y para el servicio vivo`() = runBlocking<Unit> {
        conPermiso()
        abrirTurno()
        val c = control()
        assertEquals(ResultadoGps.Siguiendo, c.iniciar())
        shadowOf(app).nextStartedService
        assertTrue(arrancarServicio())
        // Dos días después la persona abre la app sin haber marcado salida.
        assertNull(reanudarSiHayTurno(control(ahora = AHORA + DOS_DIAS), db.piezaDao(), quienSoy, AHORA + DOS_DIAS))
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
    }

    @Test fun `D3 - el turno olvidado no se reanuda nunca, ni con un control falso`() = runBlocking<Unit> {
        var intentos = 0
        var detenidos = 0
        val falso = object : ControlDeGps {
            override suspend fun iniciar(): ResultadoGps { intentos++; return ResultadoGps.Siguiendo }
            override fun detener() { detenidos++ }
            override suspend fun revisar() = Unit
        }
        abrirTurno(haceMs = DOS_DIAS)
        assertNull(reanudarSiHayTurno(falso, db.piezaDao(), quienSoy, AHORA))
        assertEquals(0, intentos)
        assertEquals(1, detenidos)
        // El de otra persona (aunque sea viejo) no se toca: no es de esta sesión.
        abrirTurno(staffId = "otra", haceMs = DOS_DIAS)
        assertNull(reanudarSiHayTurno(falso, db.piezaDao(), quienSoy, AHORA))
        assertEquals(1, detenidos)
    }

    @Test fun `D3 - el reinicio de Android con el turno olvidado se para en la primera lectura`() = runBlocking<Unit> {
        conPermiso()
        abrirTurno(haceMs = DOS_DIAS)
        // START_STICKY: Android revive el servicio sin pasar por iniciar(). Cumple el primer plano y se quita.
        assertFalse(arrancarServicio())
        assertEquals(listOf("primerPlano", "quitar"), pasos)
        assertFalse(puerta.enPrimerPlano)
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `D3 - la sucursal de un turno olvidado ya no decide el seguimiento`() {
        val yo = quienSoy.instantanea()
        val viejo = TurnoLocal(staffId = "s1", llaveEntrada = "e1", timeEntryId = null, venueId = "v9", abiertoAt = AHORA - DOS_DIAS)
        // v9 no es suya: con el turno vivo no se sigue; olvidado, decide la sesión (v1, que sí sigue).
        assertFalse(debeSeguir(yo, viejo.copy(abiertoAt = AHORA - 60_000L), AHORA))
        assertTrue(debeSeguir(yo, viejo, AHORA))
    }

    /** Lo que `descartarConHijos` le hace al turno al quitar su ENTRADA (rechazada, o descartada por el SERVIDOR). */
    private suspend fun quitarLaEntrada() = db.piezaDao().run { guardarTurno(turnoAhora()!!.copy(llaveEntrada = null, timeEntryId = null)) }

    @Test fun `D1 - quitar la entrada despierta la espera y la siguiente lectura para el servicio`() = runBlocking<Unit> {
        abrirTurno()
        val s = seguimiento()
        assertTrue(s.tomarUno())
        val espera = async { s.esperar(30 * 60_000L) }
        delay(200)
        assertTrue(espera.isActive) // con el turno igual, duerme
        quitarLaEntrada()
        withTimeout(5_000) { espera.await() } // antes: hasta 30 min con el aviso «Turno activo» puesto
        assertFalse(s.tomarUno())
    }

    @Test fun `D1 - una entrada nueva despierta la espera sin esperar 30 min`() = runBlocking<Unit> {
        abrirTurno()
        val s = seguimiento()
        assertTrue(s.tomarUno())
        val espera = async { s.esperar(30 * 60_000L) }
        delay(200)
        db.piezaDao().guardarTurno(TurnoLocal(staffId = "s1", llaveEntrada = "e2", timeEntryId = null, venueId = "v1", abiertoAt = AHORA - 1_000L))
        withTimeout(5_000) { espera.await() }
    }

    @Test fun `D1 - la misma entrada que sube (su timeEntryId) no despierta la espera`() = runBlocking<Unit> {
        abrirTurno()
        val s = seguimiento()
        assertTrue(s.tomarUno())
        val espera = async { s.esperar(30 * 60_000L) }
        delay(200)
        db.piezaDao().run { guardarTurno(turnoAhora()!!.copy(timeEntryId = "te1")) }
        delay(500)
        assertTrue(espera.isActive) // no pide una lectura de más
        espera.cancel()
    }

    @Test fun `D1 - si la entrada se quito entre la lectura y la espera, la espera no duerme`() = runBlocking<Unit> {
        abrirTurno()
        val s = seguimiento()
        assertTrue(s.tomarUno())
        quitarLaEntrada()
        withTimeout(5_000) { s.esperar(30 * 60_000L) }
    }

    @Test fun `D1 - sin cambios la espera dura lo que se le pide`() = runBlocking<Unit> {
        abrirTurno()
        val s = seguimiento()
        assertTrue(s.tomarUno())
        val inicio = System.currentTimeMillis()
        s.esperar(300L)
        assertTrue(System.currentTimeMillis() - inicio >= 290L)
    }

    @Test fun `bateria - sin la pantalla directa abre la lista de ajustes, y sin ninguna no hay boton`() {
        assertTrue(Bateria.estaOptimizada(app))
        assertNull(Bateria.intentParaArreglar(app))
        val ajustes = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.settings"; name = "Lista" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), ajustes)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, Bateria.intentParaArreglar(app)!!.action)
    }

    private companion object {
        /** Un martes cualquiera a mediodía (UTC); las pruebas no dependen del reloj de la máquina. */
        const val AHORA = 1_763_985_600_000L
        const val DOS_DIAS = 2 * 24 * 3_600_000L
    }
}
