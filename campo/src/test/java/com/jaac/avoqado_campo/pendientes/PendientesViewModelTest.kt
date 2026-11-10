package com.jaac.avoqado_campo.pendientes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.EvidenciaNueva
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.SecuenciaDeCaptura
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.cola.VentaNueva
import com.jaac.avoqado_campo.cola.rechazar
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.Identidad
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.red.Conectividad
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendientesViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    private lateinit var encolador: Encolador
    private val conectividad = mockk<Conectividad>()
    private var vm: PendientesViewModel? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(Disparador)
        every { Disparador.ahora(any()) } just runs
        every { conectividad.hayRed() } returns true
        every { conectividad.cambios } returns MutableStateFlow(true)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BaseDeCampo::class.java)
            .allowMainThreadQueries().build()
        dao = db.piezaDao()
        quienSoy.guardarActivacion(
            DatosDeActivacion(
                "c1", "c".repeat(64), "s1", "Ana", Sesion("a", "r", "v1"),
                listOf(Sucursal("v1", "Centro", null), Sucursal("v2", "Norte", null)),
            ),
        )
        val reloj = RelojAnclado(almacen, ahoraDelCelular = { 1_762_800_000_000 }, transcurrido = { 0L }, arranque = { 1 })
        encolador = Encolador(dao, quienSoy, reloj, SecuenciaDeCaptura(almacen), File(tmp.root, "cola"), env = "dev")
    }

    @After fun tearDown() {
        vm?.viewModelScope?.cancel()
        unmockkObject(Disparador)
        Dispatchers.resetMain()
        db.close()
    }

    private fun nuevoVm() = PendientesViewModel(
        dao, encolador, quienSoy, mockk<RepositorioDeCopia>(relaxed = true), conectividad,
        ApplicationProvider.getApplicationContext<Context>(),
    ).also { vm = it }

    private fun foto() = tmp.newFile("f${System.nanoTime()}.jpg").also { it.writeBytes(ByteArray(10) { 7 }) }.absolutePath

    private suspend fun ventaRechazada(code: String, datos: String?): Pieza {
        val llave = encolador.venta(
            VentaNueva("89521400001", BigDecimal("50"), false, "v1", listOf(EvidenciaNueva("Vinculacion", "5512345678", foto())), "SIM 89521400001"),
        )
        val v = dao.porLlave(llave)!!
        dao.rechazar(v, code, "El precio cambió: ahora es \$150.00.", datos)
        return dao.porId(v.id)!!
    }

    private suspend fun esperar(condicion: suspend () -> Boolean) = withTimeout(5_000) { while (!condicion()) delay(10) }

    private fun yo(estado: EstadoDelCelular = EstadoDelCelular.VINCULADO) =
        Identidad(estado, "s1", "Ana", listOf(Sucursal("v1", "Centro", null), Sucursal("v2", "Norte", null)), "v1", false, false, null)

    private fun pieza(tipo: String, estado: String, code: String? = null, mensaje: String? = null, datos: String? = null, venue: String = "v1") =
        Pieza(id = 7, tipo = tipo, llave = "k7", staffId = "s1", venueId = venue, cuerpoJson = "{}", estado = estado,
            rechazoCode = code, rechazoMensaje = mensaje, rechazoDatosJson = datos, creadaAt = 0, resumen = "Algo")

    private fun acciones(f: FilaPendiente) = f.botones.map { it.accion }

    @Test fun `precio cambio ofrece corregir a 150 y al tocarlo queda una venta nueva con ese precio y otra llave`() = runBlocking {
        val vieja = ventaRechazada("PRECIO_CAMBIO", """{"code":"PRECIO_CAMBIO","message":"x","precioActual":"150.00"}""")
        val m = nuevoVm()
        val fila = m.filas.first { l -> l.any { it.id == vieja.id } }.first { it.id == vieja.id }
        assertEquals(listOf("Corregir a \$150.00", "Quitar de mi celular"), fila.botones.map { it.texto })

        m.corregirPrecio(vieja.id)
        esperar { dao.porId(vieja.id)?.estado == EstadoPieza.DESCARTADA }
        val nueva = dao.siguientesParaSubir().single { it.tipo == TipoPieza.VENTA }
        assertNotEquals(vieja.llave, nueva.llave)
        assertEquals("150.00", JsonParser.parseString(nueva.cuerpoJson).asJsonObject["precioVisto"].asString)
        // Su evidencia ahora depende de la venta nueva.
        assertEquals(nueva.llave, dao.siguientesParaSubir().single { it.tipo == TipoPieza.EVIDENCIA }.dependeDe)
    }

    @Test fun `quitar descarta la venta y rechaza a sus evidencias`() = runBlocking {
        val v = ventaRechazada("SIM_YA_VENDIDA", null)
        val m = nuevoVm()
        m.quitar(v.id)
        esperar { dao.porId(v.id)?.estado == EstadoPieza.DESCARTADA }
        val ev = dao.hijosPorSubir(v.llave).single()
        assertEquals(EstadoPieza.RECHAZADA, ev.estado)
        assertEquals("SU_VENTA_SE_DESCARTO", ev.rechazoCode)
    }

    @Test fun `quitar no toca lo que todavia puede subir`() = runBlocking {
        val llave = encolador.venta(VentaNueva("89521400002", BigDecimal("50"), false, "v1", emptyList(), "SIM 2"))
        val id = dao.porLlave(llave)!!.id
        val m = nuevoVm()
        m.quitar(id)
        delay(200)
        assertEquals(EstadoPieza.PENDIENTE, dao.porId(id)!!.estado)
    }

    @Test fun `una retenida no ofrece quitar`() {
        val f = filaDe(pieza(TipoPieza.VENTA, EstadoPieza.RETENIDA, code = "DESVINCULADO_CON_VENTA"), yo(EstadoDelCelular.DESVINCULADO), true, emptySet())
        assertEquals("En revisión de tu supervisor", f.texto)
        assertTrue(f.botones.isEmpty())
    }

    @Test fun `toda rechazada ofrece quitar ademas de su accion propia`() {
        val casos = listOf(
            pieza(TipoPieza.EVIDENCIA, EstadoPieza.RECHAZADA, "SALE_REJECTED", "Esta venta fue rechazada") to
                ("Tu supervisor rechazó esta venta" to listOf(Accion.QUITAR)),
            pieza(TipoPieza.EVIDENCIA, EstadoPieza.RECHAZADA, "INVALID_LINKING_NUMBER", "El número lleva 9 o 10 dígitos") to
                ("El número lleva 9 o 10 dígitos" to listOf(Accion.CORREGIR_EVIDENCIA, Accion.QUITAR)),
            pieza(TipoPieza.ENTRADA, EstadoPieza.RECHAZADA, "YA_TIENE_ENTRADA", "Ya tienes una entrada", """{"timeEntryId":"te1","venueId":"v1"}""") to
                ("Ya tienes una entrada" to listOf(Accion.USAR_ENTRADA, Accion.QUITAR)),
            pieza(TipoPieza.ENTRADA, EstadoPieza.RECHAZADA, "YA_TIENE_ENTRADA", "Ya tienes una entrada", """{"timeEntryId":"te1","venueId":"v2"}""") to
                ("Tu entrada abierta es de Norte: marca la salida ahí" to listOf(Accion.QUITAR)),
            pieza(TipoPieza.SALIDA, EstadoPieza.RECHAZADA, "SALIDA_SE_ENCIMA", "Se encima") to
                ("Se encima con otro turno. Tu supervisor lo revisa" to listOf(Accion.QUITAR)),
            pieza(TipoPieza.CUSTODIA_ACEPTAR, EstadoPieza.RECHAZADA, "ASIGNACION_CAMBIO", "x") to
                ("Te volvieron a asignar esta SIM: vuelve a decidir" to listOf(Accion.VOLVER_A_DECIDIR, Accion.QUITAR)),
            pieza(TipoPieza.VENTA, EstadoPieza.RECHAZADA, "ALGO_NUEVO_DEL_SERVIDOR", null) to
                ("No se pudo subir. Pídele a tu supervisor que lo revise." to listOf(Accion.QUITAR)),
        )
        casos.forEach { (p, esperado) ->
            val f = filaDe(p, yo(), true, emptySet())
            assertEquals(p.rechazoCode, esperado.first, f.texto)
            assertEquals(p.rechazoCode, esperado.second, acciones(f))
        }
    }

    @Test fun `los avisos se entienden y lo pendiente dice que espera`() {
        assertEquals(listOf(Accion.ENTENDIDO), acciones(filaDe(pieza(TipoPieza.EVIDENCIA, EstadoPieza.AVISO, "SALE_ALREADY_APPROVED", "Ya aprobada"), yo(), true, emptySet())))
        assertEquals(
            listOf(Accion.VOLVER_A_DECIDIR, Accion.ENTENDIDO),
            acciones(filaDe(pieza(TipoPieza.CUSTODIA_ACEPTAR, EstadoPieza.AVISO, "CUSTODIA_EN_PARTE", "1 de 2"), yo(), true, emptySet())),
        )
        val pendiente = pieza(TipoPieza.VENTA, EstadoPieza.PENDIENTE)
        assertEquals("Espera señal", filaDe(pendiente, yo(), false, emptySet()).texto)
        assertEquals("Se sube cuando entres a Norte", filaDe(pendiente.copy(venueId = "v2"), yo(), true, emptySet()).texto)
        assertEquals("Espera a su venta", filaDe(pieza(TipoPieza.EVIDENCIA, EstadoPieza.PENDIENTE).copy(dependeDe = "kv"), yo(), true, setOf("kv")).texto)
        assertTrue(filaDe(pendiente, yo(), true, emptySet()).botones.isEmpty())
    }
}
