package com.jaac.avoqado_campo.gps

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_campo.checada.Ubicacion
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.TurnoLocal
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
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
    private val estado = EstadoDelGps(AlmacenEnMemoria())
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen).also {
        it.guardarActivacion(DatosDeActivacion("c1", "c".repeat(64), "s1", "Ana", Sesion("a", "r", "v1"), listOf(Sucursal("v1", "Centro", null))))
    }
    private lateinit var db: BaseDeCampo
    private val ubicacion = mockk<Ubicacion>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, BaseDeCampo::class.java).allowMainThreadQueries().build()
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        coEvery { ubicacion.leer(any()) } returns Ubicacion.Lectura(19.43, -99.13, 12f, falsa = false)
    }

    @After fun tearDown() = db.close()

    private fun conPermiso() = shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun control(arrancar: ((Intent) -> Unit)? = null) =
        if (arrancar == null) ControlDeGpsReal(app, estado) else ControlDeGpsReal(app, estado, arrancar)
    private fun seguimiento(ahora: Long = 1_000L) =
        SeguimientoDelTurno(estado, ubicacion, db.pingDao(), db.piezaDao(), quienSoy) { ahora }
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

    @Test fun `el servicio con un startForeground que truena se detiene y deja NoSigue`() {
        estado.publicar(ResultadoGps.Siguiendo) // lo que dijo iniciar() antes de que el servicio corriera
        val sigue = seguimiento().arrancar(tienePermiso = true) { throw SecurityException("FOREGROUND_SERVICE_LOCATION") }
        assertFalse(sigue)
        assertTrue(estado.estado.value is ResultadoGps.NoSigue)
        assertFalse(seguimiento().arrancar(tienePermiso = true) { throw IllegalStateException("no permitido") })
    }

    @Test fun `el servicio sin permiso no se pone en primer plano`() {
        var enPrimerPlano = false
        assertFalse(seguimiento().arrancar(tienePermiso = false) { enPrimerPlano = true })
        assertFalse(enPrimerPlano)
        assertEquals(ResultadoGps.NoSigue("no diste permiso de ubicación"), estado.estado.value)
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

    @Test fun `bateria - sin la pantalla directa abre la lista de ajustes`() {
        assertTrue(Bateria.estaOptimizada(app))
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, Bateria.intentParaArreglar(app).action)
    }
}
