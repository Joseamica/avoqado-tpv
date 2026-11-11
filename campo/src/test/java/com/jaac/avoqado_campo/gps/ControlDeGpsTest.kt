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
import com.jaac.avoqado_campo.identidad.DatosDeLatido
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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
    private fun control(arrancar: ((Intent) -> Unit)? = null) =
        if (arrancar == null) ControlDeGpsReal(app, estado, puerta, quienSoy) else ControlDeGpsReal(app, estado, puerta, quienSoy, arrancar)
    /** Lo que trae un latido: la sucursal v1 con el ajuste de seguimiento que diga el SERVIDOR. */
    private fun sucursalSigue(sigue: Boolean?) =
        quienSoy.guardarLatido(DatosDeLatido("VINCULADO", null, null, listOf(Sucursal("v1", "Centro", null, sigue))))
    private fun seguimiento(ahora: Long = 1_000L) =
        SeguimientoDelTurno(puerta, estado, ubicacion, db.pingDao(), db.piezaDao(), quienSoy) { ahora }

    /** Lo que hizo el servicio en `onStartCommand`, en orden. */
    private val pasos = mutableListOf<String>()
    private fun arrancarServicio(tienePermiso: Boolean = true, primerPlano: () -> Unit = { pasos += "primerPlano" }) =
        seguimiento().arrancar(tienePermiso, primerPlano) { pasos += "quitar" }
    private suspend fun abrirTurno(staffId: String = "s1") =
        db.piezaDao().guardarTurno(TurnoLocal(staffId = staffId, llaveEntrada = "e1", timeEntryId = null, venueId = "v1", abiertoAt = 1L))

    @Test fun `sin permiso no arranca nada y dice por que`() {
        val r = control().iniciar()
        assertEquals(ResultadoGps.NoSigue("no diste permiso de ubicación"), r)
        assertEquals(r, estado.estado.value)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `con permiso y la ubicacion apagada no sigue`() {
        conPermiso()
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        val r = control().iniciar()
        assertEquals(ResultadoGps.NoSigue("la ubicación del teléfono está apagada"), r)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `si Android no deja arrancar el servicio no sigue y no truena`() {
        conPermiso()
        val r = control { throw SecurityException("Android 14") }.iniciar()
        assertTrue(r is ResultadoGps.NoSigue)
        assertEquals(r, estado.estado.value)
        val r2 = control { throw IllegalStateException("ForegroundServiceStartNotAllowedException") }.iniciar()
        assertTrue(r2 is ResultadoGps.NoSigue)
    }

    @Test fun `con permiso y ubicacion arranca el servicio de ubicacion`() {
        conPermiso()
        assertEquals(ResultadoGps.Siguiendo, control().iniciar())
        assertEquals(ResultadoGps.Siguiendo, estado.estado.value)
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test fun `H-A1 - la tienda no usa el seguimiento - no arranca el servicio y el estado no es un aviso`() {
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

    @Test fun `H-A1 - con true o sin el dato (SERVIDOR viejo) arranca como hoy`() {
        conPermiso()
        sucursalSigue(true)
        assertEquals(ResultadoGps.Siguiendo, control().iniciar())
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        sucursalSigue(null)
        assertEquals(ResultadoGps.Siguiendo, control().iniciar())
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test fun `H-A1 - si un latido lo apaga con el turno abierto, el siguiente reanudar para el servicio`() = runBlocking {
        conPermiso()
        abrirTurno()
        assertEquals(ResultadoGps.Siguiendo, reanudarSiHayTurno(control(), db.piezaDao(), quienSoy))
        assertTrue(arrancarServicio())
        sucursalSigue(false)
        assertEquals(ResultadoGps.NoLoUsaLaTienda, reanudarSiHayTurno(control(), db.piezaDao(), quienSoy))
        assertEquals(ServicioDeUbicacion::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
        assertEquals(ResultadoGps.NoLoUsaLaTienda, estado.estado.value)
    }

    @Test fun `el servicio con un startForeground que truena se detiene y deja NoSigue`() {
        estado.publicar(ResultadoGps.Siguiendo) // lo que dijo iniciar() antes de que el servicio corriera
        assertFalse(arrancarServicio { throw SecurityException("FOREGROUND_SERVICE_LOCATION") })
        assertTrue(estado.estado.value is ResultadoGps.NoSigue)
        assertFalse(arrancarServicio { throw IllegalStateException("no permitido") })
        assertEquals(emptyList<String>(), pasos) // nunca llegó a primer plano: nada que quitar
        assertFalse(puerta.enPrimerPlano)
    }

    @Test fun `el servicio sin permiso intenta el primer plano, lo quita y se detiene con NoSigue`() {
        assertFalse(arrancarServicio(tienePermiso = false))
        assertEquals(listOf("primerPlano", "quitar"), pasos)
        assertEquals(ResultadoGps.NoSigue("no diste permiso de ubicación"), estado.estado.value)
        assertFalse(puerta.enPrimerPlano)
    }

    @Test fun `detener antes de que corra el servicio no hace stopService y el servicio se quita solo`() {
        conPermiso()
        control().iniciar()
        shadowOf(app).nextStartedService
        control().detener()
        assertNull(shadowOf(app).nextStoppedService) // stopService aquí cerraría la app (fgRequired)
        assertFalse(arrancarServicio())
        assertEquals(listOf("primerPlano", "quitar"), pasos)
        assertEquals(ResultadoGps.Siguiendo, estado.estado.value) // parar a propósito no es un problema
    }

    @Test fun `detener con el servicio en primer plano lo para`() {
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

    @Test fun `con turno abierto guarda un ping de su sucursal`() = runBlocking {
        abrirTurno()
        assertTrue(seguimiento(ahora = 5_000L).tomarUno())
        val p = db.pingDao().todos().single()
        assertEquals("v1", p.venueId)
        assertEquals(19.43, p.lat, 0.0)
        assertEquals(12f, p.precision)
        assertEquals(5_000L, p.capturadoAt)
    }

    @Test fun `sin turno o con el turno de otra persona el servicio se detiene sin guardar`() = runBlocking {
        assertFalse(seguimiento().tomarUno())
        abrirTurno(staffId = "otra")
        assertFalse(seguimiento().tomarUno())
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `I2 - durante el borrado o tras otra activacion no se escribe el ping`() = runBlocking {
        abrirTurno()
        quienSoy.marcarLimpiando(true)
        seguimiento().tomarUno()
        assertTrue(db.pingDao().todos().isEmpty())

        quienSoy.marcarLimpiando(false)
        coEvery { ubicacion.leer(any()) } coAnswers { quienSoy.empezarLimpieza(); quienSoy.marcarLimpiando(false); Ubicacion.Lectura(1.0, 2.0, 3f, false) }
        seguimiento().tomarUno() // la generación cambió mientras se leía
        assertTrue(db.pingDao().todos().isEmpty())
    }

    @Test fun `al abrir la app con turno abierto se vuelve a intentar`() = runBlocking {
        var intentos = 0
        val falso = object : ControlDeGps {
            override fun iniciar(): ResultadoGps { intentos++; return ResultadoGps.Siguiendo }
            override fun detener() = Unit
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
