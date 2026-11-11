package com.jaac.avoqado_campo.venta

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.cola.CelularBorrandose
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.SimYaEnCola
import com.jaac.avoqado_campo.cola.VentaNueva
import com.jaac.avoqado_campo.copia.ConfigDeSucursal
import com.jaac.avoqado_campo.copia.Copia
import com.jaac.avoqado_campo.copia.MarcaLocal
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class VentaViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val encolador = mockk<Encolador>()
    private val copia = mockk<RepositorioDeCopia>()
    private val dao = mockk<PiezaDao>()
    private val context = mockk<Context>(relaxed = true)
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    private lateinit var vm: VentaViewModel
    private val venta = slot<VentaNueva>()

    private val serie = "8952020012345678901F"

    private fun sim(estado: String = "PROMOTER_HELD", precio: String? = "150.00", localmente: String? = null) =
        SimEnCopia(serie, estado, 1, "Prepago", precio, localmente, null, null, null, emptyList(), null)

    private fun config(portabilidad: Boolean = false, numero: Boolean = false) =
        ConfigDeSucursal("SIM", "ICCID", portabilidad, numero, false, false, false, false, modulo = true)

    private fun copiaCon(vararg s: SimEnCopia, config: ConfigDeSucursal = config()) =
        Copia(2, "v1", quienSoy.generacion, s.toList(), config, 0L)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(Disparador)
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Disparador.ahora(any()) } just runs
        quienSoy.guardarActivacion(
            DatosDeActivacion("cel", "c".repeat(64), "s1", "Ana", Sesion("a", "1".repeat(64), "v1"), listOf(Sucursal("v1", "Centro", null))),
        )
        coEvery { dao.ventaConSerieEnCola(any()) } returns null
        coEvery { copia.actualizar(any()) } returns false
        coEvery { copia.marcar(any(), any(), any()) } just runs
        coEvery { encolador.venta(quienSoy.generacion, capture(venta)) } returns "llave"
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        unmockkObject(Disparador)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun vm(c: Copia?) = VentaViewModel(encolador, copia, dao, quienSoy, context).also {
        coEvery { copia.leer() } returns c
        vm = it
    }

    private fun VentaViewModel.listaParaRegistrar() = apply { alTomarFoto(Slot.VINCULACION, "/tmp/v.jpg") }

    @Test fun `el ICCID con espacios y minusculas se busca canonico`() {
        val v = vm(copiaCon(sim()))
        v.alEscanear("8952 0200 1234 5678 901f")
        assertEquals(serie, v.estado.value.sim?.serialNumber)
        assertNull(v.estado.value.error)
        coVerify { dao.ventaConSerieEnCola(serie) }
    }

    @Test fun `una SIM ajena refresca la copia una vez y dice que no es tuya`() {
        val v = vm(copiaCon())
        v.alEscanear(serie)
        coVerify(exactly = 1) { copia.actualizar("v1") }
        assertNull(v.estado.value.sim)
        assertEquals("Esta SIM no está en tu custodia.", v.estado.value.error)
    }

    @Test fun `con senal la copia refrescada si la trae`() {
        val v = vm(copiaCon())
        coEvery { copia.actualizar("v1") } returns true
        coEvery { copia.leer() } returnsMany listOf(copiaCon(), copiaCon(sim()))
        v.alEscanear(serie)
        assertEquals(serie, v.estado.value.sim?.serialNumber)
    }

    @Test fun `pendiente sin aceptar pide aceptarla y con la aceptacion encolada se deja`() {
        val v = vm(copiaCon(sim(estado = "PROMOTER_PENDING")))
        v.alEscanear(serie)
        assertEquals("Primero acepta esta SIM en Mis SIMs.", v.estado.value.error)
        coEvery { copia.leer() } returns copiaCon(sim(estado = "PROMOTER_PENDING", localmente = MarcaLocal.ACEPTADA_EN_EL_CELULAR))
        v.alEscanear(serie)
        assertEquals(serie, v.estado.value.sim?.serialNumber)
    }

    @Test fun `vendida, marcada o con VENTA en la cola no se deja`() {
        val v = vm(copiaCon(sim(estado = "SOLD")))
        v.alEscanear(serie)
        assertEquals("Esta SIM ya está vendida (o por subir).", v.estado.value.error)
        coEvery { copia.leer() } returns copiaCon(sim(localmente = MarcaLocal.VENDIDA_EN_EL_CELULAR))
        v.alEscanear(serie)
        assertEquals("Esta SIM ya está vendida (o por subir).", v.estado.value.error)
        coEvery { copia.leer() } returns copiaCon(sim())
        coEvery { dao.ventaConSerieEnCola(serie) } returns mockk<Pieza>()
        v.alEscanear(serie)
        assertNull(v.estado.value.sim)
        assertEquals("Esta SIM ya está vendida (o por subir).", v.estado.value.error)
    }

    @Test fun `precio 0 es de caja y se encola en 0`() {
        val v = vm(copiaCon(sim(precio = "0")))
        v.alEscanear(serie)
        assertEquals(TipoDePrecio.DE_CAJA, v.estado.value.tipoDePrecio)
        v.listaParaRegistrar().registrar()
        assertEquals(0, venta.captured.precio.compareTo(BigDecimal.ZERO))
    }

    @Test fun `precio 150 es del promotor y no se edita`() {
        val v = vm(copiaCon(sim(precio = "150")))
        v.alEscanear(serie)
        v.alEscribirPrecio("99")
        assertEquals(TipoDePrecio.DE_PROMOTOR, v.estado.value.tipoDePrecio)
        v.listaParaRegistrar().registrar()
        assertEquals(0, venta.captured.precio.compareTo(BigDecimal("150")))
    }

    @Test fun `precio null es libre y no registra sin un importe valido`() {
        val v = vm(copiaCon(sim(precio = null)))
        v.alEscanear(serie)
        assertEquals(TipoDePrecio.LIBRE, v.estado.value.tipoDePrecio)
        v.listaParaRegistrar().registrar()
        listOf("0", "1.234", "abc").forEach { v.alEscribirPrecio(it); v.registrar() }
        coVerify(exactly = 0) { encolador.venta(any(), any()) }
        v.alEscribirPrecio("120.50")
        v.registrar()
        assertEquals(0, venta.captured.precio.compareTo(BigDecimal("120.50")))
    }

    @Test fun `sin la foto de vinculacion no registra`() {
        val v = vm(copiaCon(sim()))
        v.alEscanear(serie)
        v.registrar()
        coVerify(exactly = 0) { encolador.venta(any(), any()) }
        assertEquals("Falta la foto de vinculación.", v.estado.value.error)
    }

    @Test fun `con numero de vinculacion pide 9 o 10 digitos`() {
        val v = vm(copiaCon(sim(), config = config(numero = true)))
        v.alEscanear(serie)
        v.listaParaRegistrar()
        v.alEscribirNumero(Slot.VINCULACION, "12345678")
        v.registrar()
        coVerify(exactly = 0) { encolador.venta(any(), any()) }
        v.alEscribirNumero(Slot.VINCULACION, "55 1234 5678")
        v.registrar()
        assertEquals("5512345678", venta.captured.evidencias.single().numero)
    }

    @Test fun `con portabilidad pide su foto y el numero portado`() {
        val v = vm(copiaCon(sim(), config = config(portabilidad = true)))
        v.alEscanear(serie)
        v.alCambiarPortabilidad(true)
        assertEquals(listOf(Slot.VINCULACION, Slot.PORTABILIDAD), v.estado.value.slots)
        v.listaParaRegistrar().registrar()
        v.alTomarFoto(Slot.PORTABILIDAD, "/tmp/p.jpg")
        v.registrar()
        coVerify(exactly = 0) { encolador.venta(any(), any()) }
        v.alEscribirNumero(Slot.PORTABILIDAD, "551234567")
        v.registrar()
        assertTrue(venta.captured.isPortabilidad)
        val p = venta.captured.evidencias.single { it.photoLabel == "Portabilidad" }
        assertEquals("551234567", p.numero)
        assertEquals("/tmp/p.jpg", p.archivoFoto)
    }

    @Test fun `sin portabilidad en la sucursal no se puede prender`() {
        val v = vm(copiaCon(sim()))
        v.alEscanear(serie)
        v.alCambiarPortabilidad(true)
        assertEquals(listOf(Slot.VINCULACION), v.estado.value.slots)
    }

    @Test fun `dos registrar seguidos son una sola venta`() {
        val v = vm(copiaCon(sim()))
        val espera = CompletableDeferred<String>()
        coEvery { encolador.venta(any(), any()) } coAnswers { espera.await() }
        v.alEscanear(serie)
        v.listaParaRegistrar()
        v.registrar()
        v.registrar()
        assertTrue(v.estado.value.registrando)
        espera.complete("llave")
        v.registrar()
        coVerify(exactly = 1) { encolador.venta(any(), any()) }
    }

    @Test fun `al guardar marca la SIM, despierta al subidor y confirma`() {
        val v = vm(copiaCon(sim()))
        v.alEscanear(serie)
        v.listaParaRegistrar().registrar()
        coVerify { copia.marcar(any(), serie, MarcaLocal.VENDIDA_EN_EL_CELULAR) }
        verify { Disparador.ahora(context) }
        assertTrue(v.estado.value.guardada)
        assertFalse(v.estado.value.registrando)
        assertEquals("v1", venta.captured.venueId)
        assertEquals("Vinculacion", venta.captured.evidencias.single().photoLabel)
    }

    @Test fun `durante el borrado dice espera un momento y no marca nada`() {
        val v = vm(copiaCon(sim()))
        coEvery { encolador.venta(any(), any()) } throws CelularBorrandose()
        v.alEscanear(serie)
        v.listaParaRegistrar().registrar()
        assertEquals("Espera un momento.", v.estado.value.error)
        assertFalse(v.estado.value.guardada)
        coVerify(exactly = 0) { copia.marcar(any(), any(), any()) }
    }

    @Test fun `si falla la marca despues de encolar sigue guardada y no se vende otra vez`() {
        val v = vm(copiaCon(sim()))
        coEvery { copia.marcar(any(), any(), any()) } throws java.io.IOException("disco lleno")
        every { Disparador.ahora(any()) } throws IllegalStateException("WorkManager")
        v.alEscanear(serie)
        v.listaParaRegistrar().registrar()
        assertTrue(v.estado.value.guardada)
        assertNull(v.estado.value.error)
        v.registrar()
        coVerify(exactly = 1) { encolador.venta(any(), any()) }
    }

    @Test fun `la misma SIM ya en la cola al registrar dice que ya esta vendida`() {
        val v = vm(copiaCon(sim()))
        coEvery { encolador.venta(any(), any()) } throws SimYaEnCola()
        v.alEscanear(serie)
        v.listaParaRegistrar().registrar()
        assertEquals("Esta SIM ya está vendida (o por subir).", v.estado.value.error)
        assertFalse(v.estado.value.guardada)
    }

    @Test fun `la foto reemplazada y las de una venta sin registrar se borran`() {
        val v = vm(copiaCon(sim()))
        v.alEscanear(serie)
        val primera = tmp.newFile("1.jpg")
        val segunda = tmp.newFile("2.jpg")
        v.alTomarFoto(Slot.VINCULACION, primera.path)
        v.alTomarFoto(Slot.VINCULACION, segunda.path)
        assertFalse(primera.exists())
        assertTrue(segunda.exists())
        v.alEscanear(serie) // otra búsqueda: la venta a medias se descarta
        assertFalse(segunda.exists())
    }

    @Test fun `portabilidad prendida, su foto, apagada y registrar - esa foto se borra y la de vinculacion no (H5)`() {
        val v = vm(copiaCon(sim(), config = config(portabilidad = true)))
        v.alEscanear(serie)
        val vinculacion = tmp.newFile("v.jpg")
        val portabilidad = tmp.newFile("p.jpg")
        v.alCambiarPortabilidad(true)
        v.alTomarFoto(Slot.VINCULACION, vinculacion.path)
        v.alTomarFoto(Slot.PORTABILIDAD, portabilidad.path)
        v.alCambiarPortabilidad(false)
        v.registrar()
        assertTrue(v.estado.value.guardada)
        assertEquals(listOf(vinculacion.path), venta.captured.evidencias.map { it.archivoFoto })
        assertFalse(portabilidad.exists()) // nadie la encoló: no se queda en el celular
        assertTrue(vinculacion.exists()) // es del Encolador (aquí, un mock que no la mueve)
        v.venderOtra()
        assertTrue(vinculacion.exists())
    }

    @Test fun `la foto de una venta registrada no la borra la pantalla`() {
        val v = vm(copiaCon(sim()))
        v.alEscanear(serie)
        val f = tmp.newFile("v.jpg")
        v.alTomarFoto(Slot.VINCULACION, f.path)
        v.registrar()
        v.venderOtra()
        assertTrue(f.exists()) // es del Encolador (aquí, un mock que no la mueve)
    }

    @Test fun `registrar lee la generacion antes que la sesion - otra persona entre las dos no recibe la venta (H2)`() {
        val v = vm(copiaCon(sim()))
        // Como el Encolador (EncoladorTest H2): con una generación que ya no es la vigente no escribe nada.
        coEvery { encolador.venta(any(), any()) } coAnswers {
            if (firstArg<String>() != quienSoy.generacion) throw CelularBorrandose()
            "llave"
        }
        v.alEscanear(serie)
        almacen.despuesDeLeer("sesion") { // la sesión se lee, y justo después borran el celular y activan a Beto
            quienSoy.borrarTodo()
            quienSoy.guardarActivacion(
                DatosDeActivacion("c2", "d".repeat(64), "s2", "Beto", Sesion("b", "r2", "v2"), listOf(Sucursal("v2", "Norte", null))),
            )
        }
        v.listaParaRegistrar().registrar()
        assertEquals("Espera un momento.", v.estado.value.error)
        assertFalse(v.estado.value.guardada)
        coVerify(exactly = 0) { copia.marcar(any(), any(), any()) }
    }
}
