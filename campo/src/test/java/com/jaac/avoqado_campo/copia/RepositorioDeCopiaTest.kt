package com.jaac.avoqado_campo.copia

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_campo.BuildConfig
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.DatosDeEntrada
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositorioDeCopiaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    private val server = MockWebServer()
    private lateinit var api: CampoApi
    private val archivo get() = File(context.filesDir, "copia.json")
    private val pedidos = mutableListOf<RecordedRequest>()
    /** Se corre al contestar `mis-sims` (para cambiar la generación a media descarga). */
    @Volatile private var alBajar: () -> Unit = {}
    @Volatile private var modulos = MODULOS_CON_TODO

    @Before fun setUp() {
        archivo.delete()
        db = Room.inMemoryDatabaseBuilder(context, BaseDeCampo::class.java).allowMainThreadQueries().build()
        dao = db.piezaDao()
        activar("s1")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pedidos += request
                return when {
                    request.path!!.endsWith("/mis-sims") -> { alBajar(); ok(MIS_SIMS) }
                    request.path!!.endsWith("/tpv/modules") -> ok(modulos)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        api = RedModule.crearRetrofit(server.url("/").toString(), RedModule.proveerCliente()).create(CampoApi::class.java)
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
        archivo.delete()
    }

    private fun activar(staffId: String) = quienSoy.guardarActivacion(
        DatosDeActivacion("cel", "c".repeat(64), staffId, "Ana", Sesion("acc1", "1".repeat(64), "v1"), listOf(Sucursal("v1", "Centro", null))),
    )

    private fun repo() = RepositorioDeCopia(context, api, GuardiaDeSesion(api, quienSoy, MutexDeSesion()), dao, quienSoy)
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun copiaEnArchivo(versionApp: Int = BuildConfig.VERSION_CODE, generacion: String = quienSoy.generacion, venueId: String = "v1") =
        archivo.writeText(
            """{"versionApp":$versionApp,"venueId":"$venueId","generacion":"$generacion","sims":[],""" +
                """"config":{"etiquetaArticulo":"SIM","etiquetaCodigo":"ICCID","portabilidad":false,"numeroDeVinculacion":false,""" +
                """"fotoEntrada":false,"gpsEntrada":false,"fotoSalida":false,"gpsSalida":false,"modulo":true},"bajadaAt":1}""",
        )

    private suspend fun ventaEnCola(serie: String, estado: String = EstadoPieza.PENDIENTE): Pieza {
        val id = dao.insertar(
            Pieza(
                tipo = TipoPieza.VENTA, llave = UUID.randomUUID().toString(), staffId = "s1", venueId = "v1",
                cuerpoJson = """{"llave":"x","serialNumber":"$serie","precioVisto":50.00}""", estado = estado, creadaAt = 1, resumen = "r",
            ),
        )
        return dao.porId(id)!!
    }

    // ---------- la copia que ya no sirve se tira ----------

    @Test fun `una copia de versionApp vieja se tira al leer`() = runBlocking {
        copiaEnArchivo(versionApp = BuildConfig.VERSION_CODE - 1)
        val r = repo()
        assertNull(r.leer())
        assertNull(r.copia.value)
        assertFalse(archivo.exists())
    }

    @Test fun `una copia de otra generacion se tira al leer (R7-1)`() = runBlocking {
        copiaEnArchivo(generacion = "de-otra-activacion")
        val r = repo()
        assertNull(r.leer())
        assertFalse(archivo.exists())
    }

    @Test fun `una copia de otra sucursal se tira al leer`() = runBlocking {
        copiaEnArchivo(venueId = "v9")
        assertNull(repo().leer())
        assertFalse(archivo.exists())
    }

    @Test fun `una copia vigente se lee al arrancar`() = runBlocking {
        copiaEnArchivo()
        val r = repo()
        assertEquals("v1", r.copia.value?.venueId)
        assertEquals("v1", r.leer()?.venueId)
    }

    // ---------- descargas tardías (R7-1, R8-1, I2) ----------

    @Test fun `una descarga que termina despues de un cambio de generacion no escribe ni publica`() = runBlocking {
        val r = repo()
        alBajar = { quienSoy.borrarTodo(); activar("s2") }
        assertFalse(r.actualizar("v1"))
        assertNull(r.copia.value)
        assertFalse(archivo.exists())
    }

    @Test fun `una actualizacion que empieza con limpiando no publica nada (I2)`() = runBlocking {
        val r = repo()
        quienSoy.marcarLimpiando(true)
        assertFalse(r.actualizar("v1"))
        assertNull(r.copia.value)
        assertFalse(archivo.exists())
    }

    @Test fun `limpiando a media descarga tampoco escribe (I2)`() = runBlocking {
        val r = repo()
        alBajar = { quienSoy.marcarLimpiando(true) }
        assertFalse(r.actualizar("v1"))
        assertNull(r.copia.value)
        assertFalse(archivo.exists())
    }

    @Test fun `un entrar a otra sucursal a media descarga no escribe ni publica la de antes`() = runBlocking {
        val r = repo()
        alBajar = { quienSoy.guardarEntrada(DatosDeEntrada(Sesion("acc2", "2".repeat(64), "v2"), listOf(Sucursal("v1", "Centro", null), Sucursal("v2", "Norte", null)))) }
        assertFalse(r.actualizar("v1"))
        assertNull(r.copia.value)
        assertFalse(archivo.exists())
    }

    @Test fun `marcar no toca una copia de otra sucursal`() = runBlocking {
        val r = repo()
        assertTrue(r.actualizar("v1"))
        val antes = archivo.readText()
        quienSoy.guardarEntrada(DatosDeEntrada(Sesion("acc2", "2".repeat(64), "v2"), listOf(Sucursal("v2", "Norte", null))))
        r.marcar(quienSoy.generacion, "8952140000001", MarcaLocal.VENDIDA_EN_EL_CELULAR)
        assertNull(r.copia.value!!.sims.single { it.serialNumber == "8952140000001" }.localmente)
        assertEquals(antes, archivo.readText())
    }

    @Test fun `borrar quita el archivo y publica null`() = runBlocking {
        val r = repo()
        assertTrue(r.actualizar("v1"))
        assertTrue(archivo.exists())
        r.borrar()
        assertNull(r.copia.value)
        assertFalse(archivo.exists())
    }

    // ---------- lo que baja ----------

    @Test fun `actualizar baja mis SIMs y la configuracion de la sucursal`() = runBlocking {
        val r = repo()
        assertTrue(r.actualizar("v1"))
        val c = r.copia.value!!
        assertEquals(BuildConfig.VERSION_CODE, c.versionApp)
        assertEquals(quienSoy.generacion, c.generacion)
        assertEquals(
            ConfigDeSucursal(
                etiquetaArticulo = "Chip", etiquetaCodigo = "Código", portabilidad = true, numeroDeVinculacion = true,
                fotoEntrada = true, gpsEntrada = false, fotoSalida = false, gpsSalida = true, modulo = true,
            ),
            c.config,
        )
        val fallida = c.sims.single { it.serialNumber == "8952140000002" }
        assertEquals(
            SimEnCopia(
                "8952140000002", "SOLD", 4, "Prepago", "50.00", null,
                "FAILED", "sv2", "pay2", listOf("FOTO_BORROSA", "NUMERO_NO_COINCIDE"), "Toma otra foto",
            ),
            fallida,
        )
        val enMano = c.sims.single { it.serialNumber == "8952140000001" }
        assertNull(enMano.precio); assertEquals(emptyList<String>(), enMano.rejectionReasons)
        // Sin token y con la sucursal en `X-Venue-Id` (B2); mis-sims con la sesión.
        val mod = pedidos.single { it.path!!.endsWith("/tpv/modules") }
        assertNull(mod.getHeader("Authorization")); assertEquals("v1", mod.getHeader("X-Venue-Id"))
        assertEquals("Bearer acc1", pedidos.single { it.path!!.endsWith("/mis-sims") }.getHeader("Authorization"))
        // Sobrevive a cerrar la app.
        assertEquals(c, repo().leer())
    }

    @Test fun `cada bandera de attendance va a su campo`() = runBlocking {
        // Junto con la prueba de arriba (sí-no-no-sí), cualquier cruce de dos banderas sale.
        modulos = """{"modules":[{"code":"SERIALIZED_INVENTORY","config":{"attendance":
            {"requireClockInPhoto":true,"requireClockInGps":true,"requireClockOutPhoto":false,"requireClockOutGps":false}}}]}"""
        val r = repo()
        assertTrue(r.actualizar("v1"))
        val c = r.copia.value!!.config
        assertEquals(listOf(true, true, false, false), listOf(c.fotoEntrada, c.gpsEntrada, c.fotoSalida, c.gpsSalida))
    }

    @Test fun `sin el modulo de SIMs la configuracion lo dice`() = runBlocking {
        modulos = """{"modules":[{"code":"ATTENDANCE","config":{}}]}"""
        val r = repo()
        assertTrue(r.actualizar("v1"))
        val c = r.copia.value!!.config
        assertFalse(c.modulo)
        assertEquals("SIM", c.etiquetaArticulo); assertEquals("ICCID", c.etiquetaCodigo)
        assertFalse(c.portabilidad); assertFalse(c.numeroDeVinculacion)
    }

    @Test fun `sin sesion de esa sucursal no baja nada`() = runBlocking {
        val r = repo()
        assertFalse(r.actualizar("v2"))
        assertNull(r.copia.value)
        assertTrue(pedidos.isEmpty())
    }

    // ---------- las marcas locales ----------

    @Test fun `conserva VENDIDA_EN_EL_CELULAR mientras la venta siga en la cola y la quita cuando ya esta HECHA`() = runBlocking {
        val r = repo()
        val v = ventaEnCola("8952140000001")
        assertTrue(r.actualizar("v1"))
        assertEquals(MarcaLocal.VENDIDA_EN_EL_CELULAR, r.copia.value!!.sims.single { it.serialNumber == "8952140000001" }.localmente)
        assertNull(r.copia.value!!.sims.single { it.serialNumber == "8952140000002" }.localmente)

        dao.actualizar(v.copy(estado = EstadoPieza.HECHA))
        assertTrue(r.actualizar("v1"))
        assertNull(r.copia.value!!.sims.single { it.serialNumber == "8952140000001" }.localmente)
    }

    @Test fun `la aceptacion y el rechazo en la cola marcan sus SIMs`() = runBlocking {
        val r = repo()
        dao.insertar(
            Pieza(
                tipo = TipoPieza.CUSTODIA_ACEPTAR, llave = "a", staffId = "s1", venueId = "v1",
                cuerpoJson = """{"sims":[{"serialNumber":"8952140000003","custodyVersion":2}]}""", creadaAt = 1, resumen = "r",
            ),
        )
        dao.insertar(
            Pieza(
                tipo = TipoPieza.CUSTODIA_RECHAZAR, llave = "b", staffId = "s1", venueId = "v1",
                cuerpoJson = """{"serialNumber":"8952140000004","custodyVersion":1}""", estado = EstadoPieza.RECHAZADA, creadaAt = 1, resumen = "r",
            ),
        )
        assertTrue(r.actualizar("v1"))
        val sims = r.copia.value!!.sims.associateBy { it.serialNumber }
        assertEquals(MarcaLocal.ACEPTADA_EN_EL_CELULAR, sims["8952140000003"]!!.localmente)
        assertEquals(MarcaLocal.RECHAZADA_EN_EL_CELULAR, sims["8952140000004"]!!.localmente)
    }

    @Test fun `marcar con la generacion de antes de un borrado y otra activacion no toca la copia de la persona nueva (r3)`() = runBlocking {
        val r = repo()
        val deAna = quienSoy.generacion
        quienSoy.borrarTodo(); activar("s2") // otra persona, misma sucursal
        assertTrue(r.actualizar("v1"))
        val antes = archivo.readText()
        r.marcar(deAna, "8952140000001", MarcaLocal.VENDIDA_EN_EL_CELULAR) // la venta de Ana, encolada antes del borrado
        assertNull(r.copia.value!!.sims.single { it.serialNumber == "8952140000001" }.localmente)
        assertEquals(antes, archivo.readText())
    }

    @Test fun `marcar deja la marca en la copia y en el archivo`() = runBlocking {
        val r = repo()
        assertTrue(r.actualizar("v1"))
        r.marcar(quienSoy.generacion, "8952 1400 0000 1", MarcaLocal.VENDIDA_EN_EL_CELULAR)
        assertEquals(MarcaLocal.VENDIDA_EN_EL_CELULAR, r.copia.value!!.sims.single { it.serialNumber == "8952140000001" }.localmente)
        assertEquals(MarcaLocal.VENDIDA_EN_EL_CELULAR, repo().leer()!!.sims.single { it.serialNumber == "8952140000001" }.localmente)
    }

    @Test fun `marcar durante el borrado no escribe (I2)`() = runBlocking {
        val r = repo()
        assertTrue(r.actualizar("v1"))
        quienSoy.marcarLimpiando(true)
        r.marcar(quienSoy.generacion, "8952140000001", MarcaLocal.VENDIDA_EN_EL_CELULAR)
        assertNull(r.copia.value!!.sims.single { it.serialNumber == "8952140000001" }.localmente)
    }

    private companion object {
        val MIS_SIMS = """{"items":[
            {"id":"i1","serialNumber":"8952140000001","custodyState":"PROMOTER_HELD","custodyVersion":3,
             "category":{"id":"c1","name":"E-SIM de promotor","suggestedPrice":null},
             "verificationStatus":null,"verificationId":null,"paymentId":null,"rejectionReasons":null,"reviewNotes":null},
            {"id":"i2","serialNumber":"8952140000002","custodyState":"SOLD","custodyVersion":4,
             "category":{"id":"c2","name":"Prepago","suggestedPrice":"50.00"},
             "verificationStatus":"FAILED","verificationId":"sv2","paymentId":"pay2",
             "rejectionReasons":["FOTO_BORROSA","NUMERO_NO_COINCIDE"],"reviewNotes":"Toma otra foto"},
            {"id":"i3","serialNumber":"8952140000003","custodyState":"PROMOTER_PENDING","custodyVersion":2,
             "category":{"id":"c2","name":"Prepago","suggestedPrice":"50.00"}},
            {"id":"i4","serialNumber":"8952140000004","custodyState":"PROMOTER_PENDING","custodyVersion":1,
             "category":{"id":"c2","name":"Prepago","suggestedPrice":"50.00"}}
        ]}"""
        val MODULOS_CON_TODO = """{"modules":[{"code":"SERIALIZED_INVENTORY","config":{
            "labels":{"item":"Chip","barcode":"Código"},
            "features":{"enablePortabilidad":true,"linkingNumber":true},
            "attendance":{"requireClockInPhoto":true,"requireClockInGps":false,"requireClockOutPhoto":false,"requireClockOutGps":true}
        }}]}"""
    }
}
