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
    private fun control(arrancar: ((Intent) -> Unit)? = null) = if (arrancar == null) ControlDeGpsReal(app, estado, puerta, quienSoy, db.piezaDao())
        else ControlDeGpsReal(app, estado, puerta, quienSoy, db.piezaDao(), arrancar)
    /** Lo que trae un latido: la sucursal v1 con el ajuste de seguimiento que diga el SERVIDOR. */
    private fun sucursalSigue(sigue: Boolean?) =
        quienSoy.guardarLatido(DatosDeLatido("VINCULADO", null, null, listOf(Sucursal("v1", "Centro", null, sigue))))
    private fun seguimiento(ahora: Long = 1_000L) =
        SeguimientoDelTurno(puerta, estado, ubicacion, db.pingDao(), db.piezaDao(), quienSoy) { ahora }

    /** Lo que hizo el servicio en `onStartCommand`, en orden. */
    private val pasos = mutableListOf<String>()
    private suspend fun arrancarServicio(tienePermiso: Boolean = true, primerPlano: () -> Unit = { pasos += "primerPlano" }) =
        seguimiento().arrancar(tienePermiso, primerPlano) { pasos += "quitar" }
    private suspend fun abrirTurno(staffId: String = "s1") =
        db.piezaDao().guardarTurno(TurnoLocal(staffId = staffId, llaveEntrada = "e1", timeEntryId = null, venueId = "v1", abiertoAt = 1L))

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
        val r = control { throw SecurityException("Android 14") }.iniciar()
        assertTrue(r is ResultadoGps.NoSigue)
        assertEquals(r, estado.estado.value)
        val r2 = control { throw IllegalStateException("ForegroundServiceStartNotAllowedException") }.iniciar()
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
        assertEquals(ResultadoGps.Siguiendo, reanudarSiHayTurno(control(), db.piezaDao(), quienSoy))
        assertTrue(arrancarServicio())
        sucursalSigue(false)
        assertEquals(ResultadoGps.NoLoUsaLaTienda, reanudarSiHayTurno(control(), db.piezaDao(), quienSoy))
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
        assertEquals(ResultadoGps.Siguiendo, reanudarSiHayTurno(c, db.piezaDao(), quienSoy))
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
        assertTrue(seguimiento(ahora = 5_000L).tomarUno())
        val p = db.pingDao().todos().single()
        assertEquals("v1", p.venueId)
        assertEquals(19.43, p.lat, 0.0)
        assertEquals(12f, p.precision)
        assertEquals(5_000L, p.capturadoAt)
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
        assertNull(reanudarSiHayTurno(falso, db.piezaDao(), quienSoy))
        abrirTurno()
        assertEquals(ResultadoGps.Siguiendo, reanudarSiHayTurno(falso, db.piezaDao(), quienSoy))
        assertEquals(1, intentos)
    }

    @Test fun `bateria - sin la pantalla directa abre la lista de ajustes, y sin ninguna no hay boton`() {
        assertTrue(Bateria.estaOptimizada(app))
        assertNull(Bateria.intentParaArreglar(app))
        val ajustes = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.settings"; name = "Lista" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), ajustes)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, Bateria.intentParaArreglar(app)!!.action)
    }
}
