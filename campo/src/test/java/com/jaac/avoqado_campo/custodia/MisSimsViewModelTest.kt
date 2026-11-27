package com.jaac.avoqado_campo.custodia

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.cola.CelularBorrandose
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MisSimsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val encolador = mockk<Encolador>()
    private val copia = mockk<RepositorioDeCopia>()
    private val dao = mockk<PiezaDao>()
    private val context = mockk<Context>(relaxed = true)
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    private val laCopia = MutableStateFlow<Copia?>(null)
    private val cola = MutableStateFlow<List<Pieza>>(emptyList())
    private lateinit var vm: MisSimsViewModel

    private fun sim(serie: String, estado: String = "PROMOTER_PENDING", version: Int = 3, verificacion: String? = null) =
        SimEnCopia(serie, estado, version, "Prepago", "150.00", null, verificacion, "ver-$serie", "pay-$serie", listOf("REVIEW_ILLEGIBLE_IMAGES"), "Se ve borrosa")

    private fun ponerCopia(vararg s: SimEnCopia, yaLlegadas: List<Long>? = null) {
        laCopia.value = Copia(2, "v1", quienSoy.generacion, s.toList(), ConfigDeSucursal("SIM", "ICCID", false, false, false, false, false, false, true), 0L, yaLlegadas)
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
        coEvery { copia.marcar(any(), any(), any()) } just runs
        // Como Room: «pendientes de ver» sin lo terminado; «con SIMs» (D1) las ventas y custodias en todos sus estados.
        every { dao.pendientesDeVer() } returns cola.map { l -> l.filter { it.estado != EstadoPieza.HECHA && it.estado != EstadoPieza.DESCARTADA } }
        every { dao.conSims() } returns cola.map { l -> l.filter { it.tipo == TipoPieza.VENTA || it.tipo.startsWith("CUSTODIA_") } }
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
        coVerify(exactly = 60) { copia.marcar(any(), any(), MarcaLocal.ACEPTADA_EN_EL_CELULAR) }
        io.mockk.verify(exactly = 1) { Disparador.ahora(any()) }
    }

    @Test fun `Actualizar que no baja nada dice que no se pudo y al lograrlo se quita`() {
        ponerCopia(sim("S1"))
        val v = vm()
        assertEquals(null, v.estado.value.error) // al abrir sin red no se avisa
        v.actualizar()
        assertEquals("No se pudo actualizar, revisa tu señal.", v.estado.value.error)
        assertFalse(v.estado.value.actualizando)
        coEvery { copia.actualizar(any()) } returns true
        v.actualizar()
        assertEquals(null, v.estado.value.error)
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
        coVerify { copia.marcar(any(), "S1", MarcaLocal.RECHAZADA_EN_EL_CELULAR) }
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

    // Isaac (PlayTelecom, 20-nov): «Mis ventas» aparte de «Mis SIMs», por estado. La rechazada salía «Vendida» en verde.
    @Test fun `mis ventas separa por revisar, exitosas y rechazadas`() {
        ponerCopia(
            sim("S1", "SOLD", verificacion = "FAILED"), sim("S2", "SOLD", verificacion = "COMPLETED"), sim("S3", "SOLD", verificacion = "PENDING"),
            sim("S4", "SOLD"), sim("S5", "SOLD", verificacion = "REJECTED"), sim("S6", "PROMOTER_HELD"),
        )
        val e = vm().estado.value
        assertEquals(listOf("S1"), e.porRevisar.map { it.sim.serialNumber })
        assertEquals(listOf("S2", "S3", "S4"), e.exitosas.map { it.sim.serialNumber })
        assertEquals(listOf("S5"), e.rechazadas.map { it.sim.serialNumber })
        assertEquals(EstadoFila.RECHAZADA, e.rechazadas.single().estado)
        assertTrue(e.rechazadas.single().nota!!.contains("Se ve borrosa"))
        assertEquals(listOf("S6"), e.enCustodia.map { it.sim.serialNumber })
    }

    // 0.6.0: «Mis ventas» por mes. La del SERVIDOR trae `soldAt`; la del celular que la lista aún no trae, la hora de la venta.
    // Codex r3 #8: `saleAt` (la hora real de la venta) manda; si no viene, `soldAt` (la de subida); si no, la hora local.
    @Test fun `cada venta trae su fecha - saleAt, si no soldAt, si no la hora local de la venta`() {
        val local = 1_795_000_000_000L
        cola.value = listOf(
            venta("S2", EstadoPieza.HECHA, 5).copy(resultadoJson = conPago, creadaAt = local),
            venta("S4", EstadoPieza.HECHA, 6).copy(resultadoJson = conPago, creadaAt = local + 1),
        )
        ponerCopia(
            sim("S1", "SOLD").copy(soldAt = "2026-12-01T00:05:00.000Z", saleAt = "2026-11-30T23:58:00.000Z"),
            sim("S2", "PROMOTER_HELD"), sim("S3", "SOLD"), sim("S4", "SOLD"),
            sim("S5", "SOLD").copy(soldAt = "2026-11-20T18:00:00.000Z"),
            yaLlegadas = listOf(6),
        )
        val e = vm().estado.value
        fun fecha(s: String) = e.exitosas.single { it.sim.serialNumber == s }.fecha
        assertEquals("saleAt gana a soldAt", java.time.Instant.parse("2026-11-30T23:58:00Z").toEpochMilli(), fecha("S1"))
        assertEquals("vendida en el celular, la lista aún no la trae", local, fecha("S2"))
        assertEquals("sin saleAt ni soldAt (SERVIDOR viejo) ni venta del celular: sin fecha", null, fecha("S3"))
        assertEquals("sin saleAt ni soldAt: la hora local de su venta", local + 1, fecha("S4"))
        assertEquals(java.time.Instant.parse("2026-11-20T18:00:00Z").toEpochMilli(), fecha("S5"))
    }

    // Codex r3 #7: mientras la foto de la galería se copia, «Enviar» no sale con la foto anterior.
    @Test fun `mientras se copia la foto de la galeria no se envia la correccion`() {
        ponerCopia(sim("S1", "SOLD", verificacion = "FAILED"))
        coEvery { encolador.evidenciaSuelta(any(), any()) } returns "k"
        val v = vm()
        v.empezarCorreccion("S1", "Vinculacion")
        v.alTomarFotoDeCorreccion(tmp.newFile("vieja.jpg").path)
        v.alCopiarFotoDeCorreccion(true)
        v.enviarCorreccion()
        coVerify(exactly = 0) { encolador.evidenciaSuelta(any(), any()) }
        assertEquals("Espera a que se guarde la foto.", v.estado.value.error)
        val nueva = tmp.newFile("nueva.jpg").path
        v.alTomarFotoDeCorreccion(nueva)
        v.alCopiarFotoDeCorreccion(false)
        val enviada = slot<EvidenciaSueltaNueva>()
        coEvery { encolador.evidenciaSuelta(any(), capture(enviada)) } returns "k"
        v.enviarCorreccion()
        assertEquals(nueva, enviada.captured.archivoFoto)
    }

    private fun venta(serie: String, estado: String, id: Long) = Pieza(
        id = id, tipo = TipoPieza.VENTA, llave = "kv$id", staffId = "s1", venueId = "v1",
        cuerpoJson = """{"llave":"kv$id","serialNumber":"$serie","precioVisto":0}""", estado = estado, creadaAt = 0, resumen = "",
    )

    private fun aceptada(serie: String, estado: String, id: Long) =
        custodia(TipoPieza.CUSTODIA_ACEPTAR, """{"sims":[{"serialNumber":"$serie","custodyVersion":3}]}""", estado, id = id)
            .copy(resultadoJson = """{"summary":{"total":1,"succeeded":1,"failed":0}}""".takeIf { estado == EstadoPieza.HECHA })

    private val conPago = """{"paymentId":"pay-1"}"""

    // ---------- D1 (Codex r3): «Mis ventas» acierta sin señal con la lista atrasada ----------

    // Caso 1: la aceptación ya llegó y la lista sigue en PROMOTER_PENDING; «Quitar» descarta la venta rechazada.
    @Test fun `quitar la venta de una SIM cuya aceptacion ya llego la deja en custodia y no por aceptar`() {
        cola.value = listOf(aceptada("S1", EstadoPieza.HECHA, 1), venta("S1", EstadoPieza.DESCARTADA, 2))
        ponerCopia(sim("S1", "PROMOTER_PENDING").copy(localmente = MarcaLocal.VENDIDA_EN_EL_CELULAR), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertEquals(listOf("S1"), e.enCustodia.map { it.sim.serialNumber })
        assertTrue(e.porAceptar.isEmpty())
        assertTrue(e.vendidas.isEmpty())
    }

    // Caso 2: la venta quedó DESCARTADA sin respuesta del SERVIDOR (gracia descartada, o la app murió entre «Quitar» y la marca).
    @Test fun `una venta descartada no sale en exitosas aunque la copia siga marcada`() {
        cola.value = listOf(venta("S1", EstadoPieza.DESCARTADA, 1))
        ponerCopia(sim("S1", "PROMOTER_HELD").copy(localmente = MarcaLocal.VENDIDA_EN_EL_CELULAR), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertTrue(e.exitosas.isEmpty())
        assertEquals(listOf("S1"), e.enCustodia.map { it.sim.serialNumber })
    }

    // Caso 3: la app murió entre guardar la venta y marcar la copia; la venta subió (HECHA) después de bajar la lista.
    @Test fun `una venta que subio despues de bajar la lista sale vendida aunque la copia no tenga marca`() {
        cola.value = listOf(venta("S1", EstadoPieza.HECHA, 5).copy(resultadoJson = conPago))
        ponerCopia(sim("S1", "PROMOTER_HELD"), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertEquals(listOf("S1"), e.exitosas.map { it.sim.serialNumber })
        assertTrue(e.enCustodia.isEmpty())
    }

    // Caso 4: el supervisor reasignó la SIM; el SERVIDOR rechazó la venta y la lista nueva ya no la trae.
    @Test fun `una venta rechazada de una SIM que ya no esta en la lista sigue en mis ventas`() {
        cola.value = listOf(venta("S9", EstadoPieza.RECHAZADA, 7))
        ponerCopia(sim("S1", "PROMOTER_HELD"), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertEquals(listOf("S9"), e.ventasPorSubir.map { it.sim.serialNumber })
        assertTrue(e.ventasPorSubir.single().nota!!.contains("No se pudo subir"))
        assertEquals(listOf("S1"), e.enCustodia.map { it.sim.serialNumber })
    }

    @Test fun `lo que ya habia llegado al pedir la lista lo dice la lista`() {
        cola.value = listOf(venta("S1", EstadoPieza.HECHA, 5).copy(resultadoJson = conPago), aceptada("S2", EstadoPieza.HECHA, 6))
        ponerCopia(sim("S1", "PROMOTER_HELD"), sim("S2", "PROMOTER_PENDING"), yaLlegadas = listOf(5L, 6L))
        val e = vm().estado.value
        assertEquals(listOf("S1"), e.enCustodia.map { it.sim.serialNumber })
        assertEquals(listOf(EstadoFila.POR_DECIDIR), e.porAceptar.map { it.estado })
        assertTrue(e.vendidas.isEmpty())
    }

    @Test fun `un rechazo de custodia que ya llego saca la SIM de por aceptar`() {
        cola.value = listOf(
            custodia(TipoPieza.CUSTODIA_RECHAZAR, """{"serialNumber":"S1","custodyVersion":3}""", EstadoPieza.HECHA, id = 3)
                .copy(resultadoJson = """{"custodyState":"PROMOTER_REJECTED"}"""),
        )
        ponerCopia(sim("S1", "PROMOTER_PENDING").copy(localmente = MarcaLocal.RECHAZADA_EN_EL_CELULAR), sim("S2", "PROMOTER_PENDING"), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertEquals(listOf("S2"), e.porAceptar.map { it.sim.serialNumber })
        assertTrue(e.enCustodia.isEmpty())
    }

    @Test fun `corregir precio deja la venta nueva por subir aunque la vieja este descartada`() {
        cola.value = listOf(venta("S1", EstadoPieza.DESCARTADA, 1), venta("S1", EstadoPieza.PENDIENTE, 2))
        ponerCopia(sim("S1", "PROMOTER_HELD"), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertEquals(listOf("S1"), e.ventasPorSubir.map { it.sim.serialNumber })
        assertEquals("Por subir", e.ventasPorSubir.single().nota)
    }

    // Codex misventas r1 #2: una aceptación vieja no pisa la asignación NUEVA que ya trae la lista, ni una copia de antes de D1.
    @Test fun `una aceptacion que ya llego no pisa otra asignacion de la SIM ni una copia sin yaLlegadas`() {
        cola.value = listOf(aceptada("S1", EstadoPieza.HECHA, 1), aceptada("S2", EstadoPieza.HECHA, 2))
        ponerCopia(sim("S1", "PROMOTER_PENDING", version = 5), sim("S2", "PROMOTER_PENDING"), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertEquals(listOf("S1"), e.porAceptar.map { it.sim.serialNumber }) // la recogieron y se la volvieron a dar
        assertEquals(listOf("S2"), e.enCustodia.map { it.sim.serialNumber })
        ponerCopia(sim("S1", "PROMOTER_PENDING"), sim("S2", "PROMOTER_PENDING")) // copia de antes de D1: manda la lista
        assertEquals(listOf("S1", "S2"), vm.estado.value.porAceptar.map { it.sim.serialNumber })
    }

    // Codex misventas r1 #1: un AVISO «en parte» sin saber cuáles fallaron (la gracia lo guardaba así) no acepta a ninguna.
    @Test fun `un aviso en parte sin la lista de fallidas no da ninguna por aceptada`() {
        cola.value = listOf(
            custodia(
                TipoPieza.CUSTODIA_ACEPTAR, """{"sims":[{"serialNumber":"S1","custodyVersion":3},{"serialNumber":"S2","custodyVersion":3}]}""",
                EstadoPieza.AVISO, "CUSTODIA_EN_PARTE", id = 4,
            ).copy(rechazoDatosJson = """{"fallidas":[]}"""),
        )
        ponerCopia(sim("S1", "PROMOTER_PENDING"), sim("S2", "PROMOTER_PENDING"), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertEquals(listOf(EstadoFila.POR_SUBIR, EstadoFila.POR_SUBIR), e.porAceptar.map { it.estado })
        assertTrue(e.enCustodia.isEmpty())
    }

    // Codex misventas r2 #4: el AVISO guardado con `fallidas: []` (antes del arreglo de Desenlace) las lee de la respuesta.
    @Test fun `un aviso en parte guardado sin fallidas las toma de la respuesta del SERVIDOR`() {
        cola.value = listOf(
            custodia(
                TipoPieza.CUSTODIA_ACEPTAR, """{"sims":[{"serialNumber":"S1","custodyVersion":3},{"serialNumber":"S2","custodyVersion":3}]}""",
                EstadoPieza.AVISO, "CUSTODIA_EN_PARTE", id = 4,
            ).copy(
                rechazoDatosJson = """{"fallidas":[]}""",
                resultadoJson = """{"summary":{"total":2,"succeeded":1,"failed":1},"fallidas":[{"serialNumber":"S1","status":"error","code":"X"}]}""",
            ),
        )
        ponerCopia(sim("S1", "PROMOTER_PENDING"), sim("S2", "PROMOTER_PENDING"), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertEquals(listOf(EstadoFila.POR_DECIDIR), e.porAceptar.map { it.estado })
        assertEquals(listOf("S2"), e.enCustodia.map { it.sim.serialNumber })
    }

    // Codex misventas r2 #2: con una copia de antes de D1 (mismo versionCode), la marca sigue mandando como en la 0.5.0.
    @Test fun `con una copia de antes de D1 la venta que ya subio sigue vendida por su marca`() {
        cola.value = listOf(venta("S1", EstadoPieza.HECHA, 5).copy(resultadoJson = conPago))
        ponerCopia(sim("S1", "PROMOTER_HELD").copy(localmente = MarcaLocal.VENDIDA_EN_EL_CELULAR))
        val e = vm().estado.value
        assertEquals(listOf("S1"), e.exitosas.map { it.sim.serialNumber })
        assertTrue(e.enCustodia.isEmpty())
    }

    @Test fun `entendido de un aviso de aceptacion sigue contando como aceptada`() {
        cola.value = listOf(
            custodia(
                TipoPieza.CUSTODIA_ACEPTAR, """{"sims":[{"serialNumber":"S1","custodyVersion":3},{"serialNumber":"S2","custodyVersion":3}]}""",
                EstadoPieza.DESCARTADA, "CUSTODIA_EN_PARTE", id = 4,
            ).copy(
                resultadoJson = """{"summary":{"total":2,"succeeded":1,"failed":1}}""",
                rechazoDatosJson = """{"fallidas":[{"serialNumber":"S1","status":"error","code":"OTRO"}]}""",
            ),
        )
        ponerCopia(sim("S1", "PROMOTER_PENDING"), sim("S2", "PROMOTER_PENDING"), yaLlegadas = emptyList())
        val e = vm().estado.value
        assertEquals(listOf("S2"), e.enCustodia.map { it.sim.serialNumber })
        assertEquals(listOf("S1"), e.porAceptar.map { it.sim.serialNumber }) // a S1 no se la aceptaron
    }

    // Codex r1 + r2: la venta del celular se acomoda por la COLA, no por la marca de la copia (que se queda atrás).
    @Test fun `las ventas del celular se acomodan por la cola y no por la marca de la copia`() {
        cola.value = listOf(
            venta("S1", EstadoPieza.PENDIENTE, 11), // sin marca: la app murió entre guardar y marcar
            venta("S3", EstadoPieza.RECHAZADA, 13), // el SERVIDOR la rechazó (p. ej. PRECIO_CAMBIO)
            venta("S4", EstadoPieza.AVISO, 14), // ya llegó (2xx), la copia aún no lo sabe
        )
        val vendidaEnCelular = MarcaLocal.VENDIDA_EN_EL_CELULAR
        ponerCopia(
            sim("S1", "PROMOTER_HELD"), sim("S2", "PROMOTER_HELD").copy(localmente = vendidaEnCelular), // S2: ya subió
            sim("S3", "PROMOTER_HELD").copy(localmente = vendidaEnCelular), sim("S4", "PROMOTER_HELD").copy(localmente = vendidaEnCelular),
            sim("S5", "SOLD", verificacion = "COMPLETED"),
            yaLlegadas = emptyList(), // la lista se bajó antes de que llegara S4
        )
        val e = vm().estado.value
        assertEquals(listOf("S1", "S3"), e.ventasPorSubir.map { it.sim.serialNumber })
        assertEquals("Por subir", e.ventasPorSubir[0].nota)
        assertTrue(e.ventasPorSubir[1].nota!!.contains("No se pudo subir"))
        assertEquals(listOf("S2", "S4", "S5"), e.exitosas.map { it.sim.serialNumber })
        assertTrue(e.enCustodia.isEmpty())
        assertEquals(0L, e.bajadaAt) // la hora de la lista viaja a la pantalla («La revisión de tu supervisor es de las…»)
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

    @Test fun `salir con Atras mientras se envia una correccion no borra su foto y el envio la guarda (R2-1)`() {
        ponerCopia(sim("S1", "SOLD", verificacion = "FAILED"))
        val espera = CompletableDeferred<Unit>()
        var fotoAlGuardar: Boolean? = null
        coEvery { encolador.evidenciaSuelta(any(), any()) } coAnswers {
            espera.await()
            fotoAlGuardar = File(secondArg<EvidenciaSueltaNueva>().archivoFoto).isFile // aquí el Encolador la copia a cola/
            "k"
        }
        val v = vm()
        v.empezarCorreccion("S1", "Vinculacion")
        val primera = tmp.newFile("a.jpg")
        val segunda = tmp.newFile("b.jpg")
        v.alTomarFotoDeCorreccion(primera.path)
        v.alTomarFotoDeCorreccion(segunda.path)
        assertFalse(primera.exists()) // la reemplazada no se queda en el celular
        v.enviarCorreccion()
        assertTrue(v.estado.value.ocupado)

        ViewModelStore().apply { put("misSims", v) }.clear() // Atrás con el envío en vuelo
        assertTrue(segunda.exists())
        espera.complete(Unit)
        assertEquals(true, fotoAlGuardar)
        coVerify(exactly = 1) { encolador.evidenciaSuelta(quienSoy.generacion, any()) }
    }

    @Test fun `salir con Atras sin enviar la correccion borra su foto (R2-1)`() {
        ponerCopia(sim("S1", "SOLD", verificacion = "FAILED"))
        val v = vm()
        v.empezarCorreccion("S1", "Vinculacion")
        val foto = tmp.newFile("a.jpg")
        v.alTomarFotoDeCorreccion(foto.path)
        ViewModelStore().apply { put("misSims", v) }.clear()
        assertFalse(foto.exists())
    }

    /** H2: la sesión se lee, y justo después borran el celular y activan a Beto (en otra sucursal). */
    private fun otraPersonaAlLeerLaSesion() = almacen.despuesDeLeer("sesion") {
        quienSoy.borrarTodo()
        quienSoy.guardarActivacion(
            DatosDeActivacion("c2", "d".repeat(64), "s2", "Beto", Sesion("b", "r2", "v2"), listOf(Sucursal("v2", "Norte", null))),
        )
    }

    /** Como el `Encolador` (EncoladorTest H2): con una generación que ya no es la vigente no escribe nada. */
    private fun comoElEncolador(generacion: String) {
        if (generacion != quienSoy.generacion) throw CelularBorrandose()
    }

    @Test fun `decidir lee la generacion antes que la sesion - otra persona entre las dos no recibe la decision (H2)`() {
        ponerCopia(sim("S1"))
        coEvery { encolador.aceptarCustodia(any(), any(), any(), any()) } coAnswers { comoElEncolador(firstArg()); "k" }
        val v = vm()
        otraPersonaAlLeerLaSesion()
        v.aceptar("S1")
        assertEquals("Espera un momento.", v.estado.value.error)
        coVerify(exactly = 0) { copia.marcar(any(), any(), any()) }
    }

    @Test fun `corregir lee la generacion antes que la sesion - otra persona entre las dos no recibe la correccion (H2)`() {
        ponerCopia(sim("S1", "SOLD", verificacion = "FAILED"))
        coEvery { encolador.evidenciaSuelta(any(), any()) } coAnswers { comoElEncolador(firstArg()); "k" }
        val v = vm()
        v.empezarCorreccion("S1", "Vinculacion")
        v.alTomarFotoDeCorreccion(tmp.newFile("a.jpg").path)
        otraPersonaAlLeerLaSesion()
        v.enviarCorreccion()
        assertEquals("Espera un momento.", v.estado.value.error)
        io.mockk.verify(exactly = 0) { Disparador.ahora(any()) }
    }
}
