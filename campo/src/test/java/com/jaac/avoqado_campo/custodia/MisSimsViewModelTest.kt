package com.jaac.avoqado_campo.custodia

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.EvidenciaSueltaNueva
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.SimConVersion
import com.jaac.avoqado_campo.cola.TipoPieza
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
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MisSimsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val encolador = mockk<Encolador>()
    private val copia = mockk<RepositorioDeCopia>()
    private val dao = mockk<PiezaDao>()
    private val context = mockk<Context>(relaxed = true)
    private val quienSoy = QuienSoy(AlmacenEnMemoria())
    private val laCopia = MutableStateFlow<Copia?>(null)
    private val cola = MutableStateFlow<List<Pieza>>(emptyList())
    private lateinit var vm: MisSimsViewModel

    private fun sim(serie: String, estado: String = "PROMOTER_PENDING", version: Int = 3, verificacion: String? = null) =
        SimEnCopia(serie, estado, version, "Prepago", "150.00", null, verificacion, "ver-$serie", "pay-$serie", listOf("REVIEW_ILLEGIBLE_IMAGES"), "Se ve borrosa")

    private fun ponerCopia(vararg s: SimEnCopia) {
        laCopia.value = Copia(2, "v1", quienSoy.generacion, s.toList(), ConfigDeSucursal("SIM", "ICCID", false, false, false, false, false, false, true), 0L)
    }

    private fun custodia(tipo: String, cuerpo: String, estado: String = EstadoPieza.PENDIENTE, code: String? = null, id: Long = 1) =
        Pieza(id = id, tipo = tipo, llave = "k$id", staffId = "s1", venueId = "v1", cuerpoJson = cuerpo, estado = estado, rechazoCode = code, creadaAt = 0, resumen = "")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(Disparador)
        every { Disparador.ahora(any()) } just runs
        quienSoy.guardarActivacion(
            DatosDeActivacion("c1", "c".repeat(64), "s1", "Ana", Sesion("a", "r", "v1"), listOf(Sucursal("v1", "Centro", null))),
        )
        every { copia.copia } returns laCopia
        coEvery { copia.leer() } answers { laCopia.value }
        coEvery { copia.actualizar(any()) } returns false
        coEvery { copia.marcar(any(), any()) } just runs
        every { dao.pendientesDeVer() } returns cola
        coEvery { dao.actualizar(any()) } just runs
        coEvery { encolador.aceptarCustodia(any(), any(), any(), any()) } returns "k"
        coEvery { encolador.rechazarCustodia(any(), any(), any(), any()) } returns "k"
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        unmockkObject(Disparador)
        Dispatchers.resetMain()
    }

    private fun vm() = MisSimsViewModel(encolador, copia, dao, quienSoy, context).also { vm = it }

    @Test fun `aceptar 60 SIMs son dos piezas de 50 y 10 con su version de la copia`() {
        ponerCopia(*Array(60) { sim("S$it", version = it) })
        val lotes = mutableListOf<List<SimConVersion>>()
        coEvery { encolador.aceptarCustodia(quienSoy.generacion, capture(lotes), "v1", emptyList()) } returns "k"
        vm().aceptarTodas()
        assertEquals(listOf(50, 10), lotes.map { it.size })
        assertEquals(SimConVersion("S59", 59), lotes[1].last())
        coVerify(exactly = 60) { copia.marcar(any(), MarcaLocal.ACEPTADA_EN_EL_CELULAR) }
        io.mockk.verify(exactly = 1) { Disparador.ahora(any()) }
    }

    @Test fun `rechazar una ya por subir no hace nada`() {
        ponerCopia(sim("S1"))
        cola.value = listOf(custodia(TipoPieza.CUSTODIA_ACEPTAR, """{"sims":[{"serialNumber":"S1","custodyVersion":3}]}"""))
        val v = vm()
        assertEquals(EstadoFila.POR_SUBIR, v.estado.value.porAceptar.single().estado)
        v.rechazar("S1")
        coVerify(exactly = 0) { encolador.rechazarCustodia(any(), any(), any(), any()) }
    }

    @Test fun `rechazar manda su version y marca la copia`() {
        ponerCopia(sim("S1", version = 7))
        vm().rechazar("S1")
        coVerify { encolador.rechazarCustodia(quienSoy.generacion, SimConVersion("S1", 7), "v1", emptyList()) }
        coVerify { copia.marcar("S1", MarcaLocal.RECHAZADA_EN_EL_CELULAR) }
    }

    @Test fun `dos toques a aceptar son una pieza`() {
        ponerCopia(sim("S1"))
        val v = vm()
        coEvery { encolador.aceptarCustodia(any(), any(), any(), any()) } coAnswers {
            cola.value = listOf(custodia(TipoPieza.CUSTODIA_ACEPTAR, """{"sims":[{"serialNumber":"S1","custodyVersion":3}]}"""))
            "k"
        }
        v.aceptar("S1"); v.aceptar("S1")
        coVerify(exactly = 1) { encolador.aceptarCustodia(any(), any(), any(), any()) }
    }

    @Test fun `ASIGNACION_CAMBIO al rechazar espera la version nueva y luego descarta la vieja al decidir`() {
        ponerCopia(sim("S1", version = 3))
        val vieja = custodia(TipoPieza.CUSTODIA_RECHAZAR, """{"serialNumber":"S1","custodyVersion":3}""", EstadoPieza.RECHAZADA, "ASIGNACION_CAMBIO", id = 9)
        cola.value = listOf(vieja)
        val v = vm()
        assertEquals(EstadoFila.ACTUALIZANDO, v.estado.value.porAceptar.single().estado)
        coVerify(exactly = 2) { copia.actualizar("v1") } // al abrir y por la reasignación
        v.aceptar("S1")
        coVerify(exactly = 0) { encolador.aceptarCustodia(any(), any(), any(), any()) }

        ponerCopia(sim("S1", version = 4))
        val fila = v.estado.value.porAceptar.single()
        assertEquals(EstadoFila.POR_DECIDIR, fila.estado)
        assertEquals("Te volvieron a asignar esta SIM: vuelve a decidir", fila.nota)
        v.aceptar("S1")
        coVerify { encolador.aceptarCustodia(quienSoy.generacion, listOf(SimConVersion("S1", 4)), "v1", listOf(9L)) }
    }

    @Test fun `ASIGNACION_CAMBIO al aceptar llega como fila fallida de un AVISO`() {
        ponerCopia(sim("S1", version = 3), sim("S2", version = 3))
        cola.value = listOf(
            custodia(
                TipoPieza.CUSTODIA_ACEPTAR, """{"sims":[{"serialNumber":"S1","custodyVersion":3},{"serialNumber":"S2","custodyVersion":3}]}""",
                EstadoPieza.AVISO, "CUSTODIA_EN_PARTE", id = 4,
            ).copy(rechazoDatosJson = """{"fallidas":[{"serialNumber":"S1","status":"error","code":"ASIGNACION_CAMBIO"}]}"""),
        )
        val v = vm()
        assertEquals(listOf(EstadoFila.ACTUALIZANDO, EstadoFila.POR_DECIDIR), v.estado.value.porAceptar.map { it.estado })
        coVerify(exactly = 2) { copia.actualizar("v1") }
        ponerCopia(sim("S1", version = 5))
        assertEquals("Te volvieron a asignar esta SIM: vuelve a decidir", v.estado.value.porAceptar.single().nota)
        v.rechazar("S1")
        coVerify { encolador.rechazarCustodia(quienSoy.generacion, SimConVersion("S1", 5), "v1", emptyList()) } // un AVISO no se descarta
    }

    @Test fun `si el segundo lote falla dice cuantas se guardaron`() {
        ponerCopia(*Array(60) { sim("S$it") })
        coEvery { encolador.aceptarCustodia(any(), any(), any(), any()) } returns "k" andThenThrows IllegalStateException("disco lleno")
        val v = vm()
        v.aceptarTodas()
        assertEquals("Se guardaron 50 de 60; las demás no. No se guardó. disco lleno", v.estado.value.error)
    }

    @Test fun `vendidas FAILED se revisan con sus motivos y PENDING esta en revision`() {
        ponerCopia(sim("S1", "SOLD", verificacion = "FAILED"), sim("S2", "SOLD", verificacion = "PENDING"), sim("S3", "PROMOTER_HELD"))
        val e = vm().estado.value
        assertEquals(listOf("S3"), e.enCustodia.map { it.sim.serialNumber })
        val (revisar, revision) = e.vendidas
        assertEquals(EstadoFila.REVISAR, revisar.estado)
        assertTrue(revisar.nota!!.contains("Imágenes ilegibles"))
        assertTrue(revisar.nota!!.contains("Se ve borrosa"))
        assertEquals(EstadoFila.EN_REVISION, revision.estado)
    }

    @Test fun `corregir evidencia encola una evidencia suelta con su pago y su verificacion`() {
        ponerCopia(sim("S1", "SOLD", verificacion = "FAILED"))
        val ev = slot<EvidenciaSueltaNueva>()
        coEvery { encolador.evidenciaSuelta(quienSoy.generacion, capture(ev)) } returns "k"
        val v = vm()
        v.empezarCorreccion("S1", "Vinculacion")
        val foto = tmp.newFile("f.jpg").absolutePath
        v.alTomarFotoDeCorreccion(foto)
        v.alEscribirNumeroDeCorreccion("5512345678")
        v.enviarCorreccion()
        assertEquals(EvidenciaSueltaNueva("v1", "pay-S1", "ver-S1", "Vinculacion", "5512345678", foto, "Corregir evidencia de SIM S1"), ev.captured)
        assertFalse(v.estado.value.corrigiendo != null)
    }
}
