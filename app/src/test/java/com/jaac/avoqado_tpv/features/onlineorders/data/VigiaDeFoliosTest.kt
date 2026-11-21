package com.jaac.avoqado_tpv.features.onlineorders.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result as AppResult
import com.jaac.avoqado_tpv.core.presentation.screens.recuadroPorPagar
import com.jaac.avoqado_tpv.core.printer.ComandaNoEmpezada
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaPorImprimir
import com.jaac.avoqado_tpv.features.onlineorders.domain.ConteoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioProducto
import com.jaac.avoqado_tpv.features.onlineorders.domain.PaginaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.TarjetaPorAbrir
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import com.jaac.avoqado_tpv.features.timeclock.domain.repository.TimeEntryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class VigiaDeFoliosTest {

    private val folios = mockk<FoliosRepository>()
    private val shifts = mockk<ShiftRepository>()
    private val asistencia = mockk<TimeEntryRepository>()
    private val ajustes = mockk<TpvSettingsRepository>()
    private val storage = mockk<SecureStorage>()

    private var sesion = true
    private var sistemaDeTurnos = true
    private var checadaObligatoria = false
    private var turnoAbierto = true
    private var entradaRegistrada = true
    private var hora = 1_000_000L

    private fun shift(status: ShiftStatus) = Shift(
        id = "s1", venueId = "v1", staffId = "st1", staffName = "Ana", startTime = "2026-11-06T09:00:00Z", endTime = null,
        status = status, startingCash = BigDecimal.ZERO, endingCash = null, totalSales = BigDecimal.ZERO, totalTips = BigDecimal.ZERO,
        totalOrders = 0, totalCashPayments = BigDecimal.ZERO, totalCardPayments = BigDecimal.ZERO, totalVoucherPayments = BigDecimal.ZERO,
        totalOtherPayments = BigDecimal.ZERO, totalProductsSold = 0, durationMinutes = null,
    )

    private fun entrada() = TimeEntry(
        id = "e1", staffId = "st1", staffName = "Ana", venueId = "v1", clockInTime = java.time.LocalDateTime.of(2026, 11, 6, 9, 0),
        clockOutTime = null, jobRole = null, totalHours = null, breakMinutes = 0, status = TimeEntryStatus.CLOCKED_IN,
        checkInPhotoUrl = null, breaks = emptyList(),
    )

    private fun folio(id: String, falta: String = "10.00") = Folio(
        orderId = id, onlineOrderId = null, folio = "W$id", estado = "PENDING_ACCEPTANCE", nombre = "Ana", telefono = null, nota = null,
        creadoEn = null, venceEn = null, total = BigDecimal("10.00"), falta = BigDecimal(falta), estadoDePago = "PENDING",
        necesitaImprimir = false, productos = emptyList(),
    )

    private fun pagina(
        showTile: Boolean = true,
        poll: Int? = 30,
        porPagar: List<Folio> = listOf(folio("a")),
        porEntregar: List<Folio> = emptyList(),
        siguientes: Map<SeccionDeFolios, String> = emptyMap(),
        serverTime: Instant? = null,
        tarjetas: List<TarjetaPorAbrir> = emptyList(),
        comandas: List<ComandaPorImprimir> = emptyList(),
    ) = PaginaDeFolios(
        serverTime, showTile, poll, ConteoDeFolios(porPagar.size, porEntregar.size), porPagar, porEntregar, siguientes, tarjetas, comandas,
    )

    private val cabeceras = mutableListOf<Boolean>()
    /** D661 (Ruling 45): lo que el vigía mandó en `x-online-orders-card-alert` en cada sondeo. */
    private val avisos = mutableListOf<Boolean>()
    private var respuesta: suspend () -> Result<PaginaDeFolios> = { Result.success(pagina()) }
    private var primerPlano = true
    private var timbres = 0
    /** D661 (Ruling 50): el reloj monótono de la comanda (la edad de su reclamo). */
    private var monotonico = 0L
    // Sin impresora por defecto (relaxed: isPrinterAvailable = false): ninguna prueba vieja imprime ni lee pedidos.
    private val impresora = mockk<PrinterManager>(relaxed = true)

    private fun preparar() {
        every { storage.getToken() } answers { if (sesion) "tok" else null }
        every { storage.getVenueId() } answers { if (sesion) "v1" else null }
        every { storage.getStaffId() } returns "st1"
        every { storage.isShiftSystemEnabled() } answers { sistemaDeTurnos }
        every { ajustes.getCurrentSettings() } answers { TpvSettings(requireClockInToLogin = checadaObligatoria) }
        coEvery { shifts.getCurrentShift("v1") } answers {
            AppResult.Success(if (turnoAbierto) shift(ShiftStatus.OPEN) else null)
        }
        coEvery { shifts.getCachedOpenShift("v1") } returns null
        coEvery { asistencia.findActiveEntryForStaff("v1", "st1") } answers {
            kotlin.Result.success(if (entradaRegistrada) entrada() else null)
        }
        coEvery { folios.listar("v1", any(), any()) } coAnswers {
            cabeceras += secondArg<Boolean>()
            avisos += thirdArg<Boolean>()
            respuesta()
        }
    }

    /** El vigía con el scope de la prueba; se cancela al final (memoria de la TPV, líneas 33-34). */
    private suspend fun kotlinx.coroutines.test.TestScope.conVigia(bloque: suspend (VigiaDeFolios) -> Unit) {
        preparar()
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler) + job)
        val vigia = VigiaDeFolios(
            folios, shifts, asistencia, ajustes, storage, { hora }, { timbres++ }, { primerPlano }, ComandaDeApoyo(folios, impresora) { monotonico },
        )
        try {
            vigia.iniciar(scope)
            bloque(vigia)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `con una sesion guardada consulta sin que nadie llame a onLoginSuccess`() = runTest {
        conVigia { vigia ->
            coVerify(exactly = 1) { folios.listar("v1", any(), any()) }
            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a")
            assertThat(vigia.estado.value.counts.porPagar).isEqualTo(1)
            assertThat(vigia.estado.value.sinConexion).isFalse()
        }
    }

    @Test
    fun `sin sesion no consulta`() = runTest {
        sesion = false
        conVigia { vigia ->
            advanceTimeBy(60_000)
            coVerify(exactly = 0) { folios.listar(any(), any(), any()) }
            assertThat(vigia.estado.value.porPagar).isEmpty()
        }
    }

    @Test
    fun `manda la cabecera solo con turno abierto y entrada registrada`() = runTest {
        checadaObligatoria = true

        conVigia { vigia ->
            assertThat(cabeceras.last()).isTrue()

            turnoAbierto = false
            vigia.sondearYa()
            assertThat(cabeceras.last()).isFalse()

            turnoAbierto = true
            entradaRegistrada = false
            vigia.sondearYa()
            assertThat(cabeceras.last()).isFalse()

            entradaRegistrada = true
            vigia.sondearYa()
            assertThat(cabeceras.last()).isTrue()
        }
    }

    @Test
    fun `con el sistema de turnos apagado o la checada no obligatoria manda la cabecera igual que Cobrar se habilita`() = runTest {
        // sin turno, pero el sistema de turnos apagado: «Cobrar» se habilita, así que la cabecera va
        sistemaDeTurnos = false
        turnoAbierto = false
        conVigia { vigia ->
            assertThat(cabeceras.last()).isTrue()
            // con turnos prendidos y sin turno: no
            sistemaDeTurnos = true
            vigia.sondearYa()
            assertThat(cabeceras.last()).isFalse()
        }
    }

    @Test
    fun `sin entrada registrada pero con la checada no obligatoria la cabecera va`() = runTest {
        checadaObligatoria = false
        entradaRegistrada = false
        conVigia {
            assertThat(cabeceras.last()).isTrue()
            // ni siquiera pregunta por la entrada si no es obligatoria
            coVerify(exactly = 0) { asistencia.findActiveEntryForStaff(any(), any()) }
        }
    }

    @Test
    fun `cerrar la caja en el repositorio sin volver al inicio quita la cabecera en la vuelta siguiente`() = runTest {
        conVigia { vigia ->
            assertThat(cabeceras.last()).isTrue()

            // la caja se cierra en el repositorio; ninguna pantalla se entera
            coEvery { shifts.getCurrentShift("v1") } returns AppResult.Success(shift(ShiftStatus.CLOSED))
            advanceTimeBy(30_001)

            assertThat(cabeceras.last()).isFalse()
            assertThat(vigia.estado.value.porPagar).isNotEmpty() // la lista sigue; solo deja de latir
        }
    }

    @Test
    fun `con showTile false espera 600 s`() = runTest {
        respuesta = { Result.success(pagina(showTile = false, poll = 600)) }
        conVigia { vigia ->
            assertThat(vigia.estado.value.showTile).isFalse()
            advanceTimeBy(599_000)
            coVerify(exactly = 1) { folios.listar("v1", any(), any()) }
            advanceTimeBy(2_000)
            coVerify(exactly = 2) { folios.listar("v1", any(), any()) }
        }
    }

    @Test
    fun `con el recuadro prendido espera 30 s`() = runTest {
        conVigia {
            advanceTimeBy(29_000)
            coVerify(exactly = 1) { folios.listar("v1", any(), any()) }
            advanceTimeBy(2_000)
            coVerify(exactly = 2) { folios.listar("v1", any(), any()) }
        }
    }

    @Test
    fun `sin red conserva la ultima lista y marca sinConexion`() = runTest {
        conVigia { vigia ->
            hora = 5_000_000L
            respuesta = { Result.failure(IOException("sin red")) }
            vigia.sondearYa()

            val e = vigia.estado.value
            assertThat(e.sinConexion).isTrue()
            assertThat(e.porPagar.map { it.orderId }).containsExactly("a")
            assertThat(e.showTile).isTrue()
            assertThat(e.actualizadoEnMs).isEqualTo(1_000_000L) // de cuándo es la lista, para «(de hace N min)»

            respuesta = { Result.success(pagina(porPagar = listOf(folio("b")))) }
            vigia.sondearYa()
            assertThat(vigia.estado.value.sinConexion).isFalse()
            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("b")
        }
    }

    @Test
    fun `cargarMas agrega la pagina siguiente sin repetir y guarda el cursor nuevo`() = runTest {
        respuesta = { Result.success(pagina(porEntregar = listOf(folio("x")), siguientes = mapOf(SeccionDeFolios.POR_ENTREGAR to "c1"))) }
        conVigia { vigia ->
            val cursor = slot<String>()
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_ENTREGAR, capture(cursor)) } returns Result.success(
                pagina(porPagar = emptyList(), porEntregar = listOf(folio("x"), folio("y")), siguientes = emptyMap()),
            )

            vigia.cargarMas(SeccionDeFolios.POR_ENTREGAR)

            assertThat(cursor.captured).isEqualTo("c1")
            assertThat(vigia.estado.value.porEntregar.map { it.orderId }).containsExactly("x", "y").inOrder()
            assertThat(vigia.estado.value.nextCursors).isEmpty()
        }
    }

    @Test
    fun `al cerrar la sesion la lista se vacia`() = runTest {
        conVigia { vigia ->
            assertThat(vigia.estado.value.porPagar).isNotEmpty()
            sesion = false
            vigia.sondearYa()
            assertThat(vigia.estado.value.porPagar).isEmpty()
            assertThat(vigia.estado.value.showTile).isFalse()
        }
    }

    @Test
    fun `lo cargado con cargarMas sigue tras la vuelta y si cambia la pagina 1 se relee con su cursor nuevo`() = runTest {
        respuesta = { Result.success(pagina(porEntregar = listOf(folio("x")), siguientes = mapOf(SeccionDeFolios.POR_ENTREGAR to "c1"))) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_ENTREGAR, "c1") } returns Result.success(
                pagina(porPagar = emptyList(), porEntregar = listOf(folio("y")), siguientes = mapOf(SeccionDeFolios.POR_ENTREGAR to "c2")),
            )
            assertThat(vigia.cargarMas(SeccionDeFolios.POR_ENTREGAR)).isTrue()
            assertThat(vigia.estado.value.porEntregar.map { it.orderId }).containsExactly("x", "y").inOrder()
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_ENTREGAR]).isEqualTo("c2")

            // otra vuelta con la MISMA página 1: las extras y su cursor siguen
            vigia.sondearYa()
            assertThat(vigia.estado.value.porEntregar.map { it.orderId }).containsExactly("x", "y").inOrder()
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_ENTREGAR]).isEqualTo("c2")

            // la página 1 cambia (sale x, entra z): la página extra se relee desde el cursor nuevo; ya no regresa a la página 1
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_ENTREGAR, "c9") } returns Result.success(
                pagina(porPagar = emptyList(), porEntregar = listOf(folio("w")), siguientes = mapOf(SeccionDeFolios.POR_ENTREGAR to "c3")),
            )
            respuesta = { Result.success(pagina(porEntregar = listOf(folio("z")), siguientes = mapOf(SeccionDeFolios.POR_ENTREGAR to "c9"))) }
            vigia.sondearYa()
            assertThat(vigia.estado.value.porEntregar.map { it.orderId }).containsExactly("z", "w").inOrder()
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_ENTREGAR]).isEqualTo("c3")
        }
    }

    @Test
    fun `un folio que pasa de una seccion a otra nunca aparece en las dos`() = runTest {
        respuesta = { Result.success(pagina(porPagar = listOf(folio("a")), siguientes = mapOf(SeccionDeFolios.POR_PAGAR to "c1"))) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(pagina(porPagar = listOf(folio("b"))))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            // b se cobró: ahora está en la página 1 de «por entregar»; la página 1 de «por pagar» no cambió
            respuesta = { Result.success(pagina(porPagar = listOf(folio("a")), porEntregar = listOf(folio("b")), siguientes = mapOf(SeccionDeFolios.POR_PAGAR to "c1"))) }
            vigia.sondearYa()

            val e = vigia.estado.value
            assertThat((e.porPagar + e.porEntregar).map { it.orderId }).containsExactly("a", "b")
        }
    }

    @Test
    fun `un 4xx deja la lista pero la marca desactualizada y la siguiente buena lo quita`() = runTest {
        conVigia { vigia ->
            respuesta = { Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(403, "sin permiso")) }
            vigia.sondearYa()
            assertThat(vigia.estado.value.desactualizado).isTrue()
            assertThat(vigia.estado.value.sinConexion).isFalse()
            assertThat(vigia.estado.value.porPagar).isNotEmpty()

            respuesta = { Result.success(pagina()) }
            vigia.sondearYa()
            assertThat(vigia.estado.value.desactualizado).isFalse()
        }
    }

    @Test
    fun `si cargarMas falla lo dice y el siguiente intento lo limpia`() = runTest {
        respuesta = { Result.success(pagina(porEntregar = listOf(folio("x")), siguientes = mapOf(SeccionDeFolios.POR_ENTREGAR to "c1"))) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_ENTREGAR, "c1") } returns Result.failure(IOException("sin red"))
            assertThat(vigia.cargarMas(SeccionDeFolios.POR_ENTREGAR)).isFalse()
            assertThat(vigia.estado.value.errorAlCargarMas).isTrue()
            assertThat(vigia.estado.value.porEntregar.map { it.orderId }).containsExactly("x")

            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_ENTREGAR, "c1") } returns Result.success(pagina(porPagar = emptyList(), porEntregar = listOf(folio("y"))))
            assertThat(vigia.cargarMas(SeccionDeFolios.POR_ENTREGAR)).isTrue()
            assertThat(vigia.estado.value.errorAlCargarMas).isFalse()
        }
    }

    @Test
    fun `un folio de las extras que se cobra sale en la relectura aunque la pagina 1 sea igual`() = runTest {
        // hay 2 por pagar en el servidor; la página 1 sólo trae a «a»
        respuesta = {
            Result.success(
                pagina(porPagar = listOf(folio("a")), siguientes = mapOf(SeccionDeFolios.POR_PAGAR to "c1"))
                    .copy(conteo = ConteoDeFolios(porPagar = 2, porEntregar = 0)),
            )
        }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(pagina(porPagar = listOf(folio("b"))))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)
            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b")

            // b se cobró: la página 1 es idéntica, pero ya sólo hay 1 por pagar (y 1 por entregar, fuera de la página).
            // La relectura de la página extra ya no lo trae (y no hay más).
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(pagina(porPagar = emptyList()))
            respuesta = {
                Result.success(
                    pagina(porPagar = listOf(folio("a")), siguientes = mapOf(SeccionDeFolios.POR_PAGAR to "c1"))
                        .copy(conteo = ConteoDeFolios(porPagar = 1, porEntregar = 1)),
                )
            }
            vigia.sondearYa()

            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a")
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_PAGAR]).isNull() // la página 2 ya está leída y no hay más
            assertThat(vigia.estado.value.counts.porPagar).isEqualTo(1)
        }
    }

    @Test
    fun `cargarMas con pagina vacia y sin cursor no vuelve a pedir la pagina 2`() = runTest {
        respuesta = { Result.success(pagina(porEntregar = listOf(folio("x")), siguientes = mapOf(SeccionDeFolios.POR_ENTREGAR to "c1"))) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_ENTREGAR, "c1") } returns Result.success(pagina(porPagar = emptyList(), porEntregar = emptyList()))
            assertThat(vigia.cargarMas(SeccionDeFolios.POR_ENTREGAR)).isTrue()
            assertThat(vigia.estado.value.nextCursors).isEmpty()

            vigia.sondearYa() // misma página 1: sigue sin cursor
            assertThat(vigia.estado.value.nextCursors).isEmpty()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R79 #6: en cada sondeo se RELEEN las páginas extra que la cajera cargó (las lecciones del panel: R82 #4, R85 #3, Dc1-m1)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun conCursor(porPagar: List<Folio>, cursor: String?, conteo: Int = porPagar.size) =
        pagina(porPagar = porPagar, siguientes = cursor?.let { mapOf(SeccionDeFolios.POR_PAGAR to it) } ?: emptyMap())
            .copy(conteo = ConteoDeFolios(porPagar = conteo, porEntregar = 0))

    @Test
    fun `R79 cada sondeo relee las paginas extra que se cargaron y ninguna mas`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 3)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), "c2"))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            // otra caja abonó a «b» (está en la página 2): la página 1 no cambia, pero la relectura lo trae
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns
                Result.success(conCursor(listOf(folio("b", falta = "4.00")), "c2"))
            vigia.sondearYa()

            coVerify(exactly = 2) { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } // la que cargó + la relectura
            coVerify(exactly = 0) { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c2") } // nunca más de lo que cargó
            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b").inOrder()
            assertThat(vigia.estado.value.porPagar.last().falta).isEqualTo(BigDecimal("4.00"))
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_PAGAR]).isEqualTo("c2")
        }
    }

    @Test
    fun `R79 una relectura lenta y un sondeo nuevo - se aplica, no se descarta, y nunca corren dos`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 2)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), null))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            val compuertas = listOf(kotlinx.coroutines.CompletableDeferred<Unit>(), kotlinx.coroutines.CompletableDeferred<Unit>())
            var relecturas = 0
            var enVuelo = 0
            var maximo = 0
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } coAnswers {
                val n = relecturas++
                enVuelo++; maximo = maxOf(maximo, enVuelo)
                compuertas[n].await()
                enVuelo--
                Result.success(conCursor(listOf(folio("b", falta = if (n == 0) "7.00" else "3.00")), null))
            }

            vigia.sondearYa() // arranca la relectura 1 y se queda esperando
            respuesta = { Result.success(conCursor(listOf(folio("a"), folio("c")), "c1", conteo = 3)) }
            vigia.sondearYa() // sondeo con la relectura en vuelo: la página 1 sí se aplica…
            vigia.sondearYa() // …y dos sondeos dejan UNA relectura pendiente, no dos
            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "c", "b").inOrder()
            assertThat(relecturas).isEqualTo(1)

            compuertas[0].complete(Unit) // la lenta termina: se aplica (no se descarta) y arranca la pendiente
            assertThat(vigia.estado.value.porPagar.last().falta).isEqualTo(BigDecimal("7.00"))
            assertThat(relecturas).isEqualTo(2)

            compuertas[1].complete(Unit)
            assertThat(vigia.estado.value.porPagar.last().falta).isEqualTo(BigDecimal("3.00"))
            assertThat(relecturas).isEqualTo(2)
            assertThat(maximo).isEqualTo(1)
        }
    }

    @Test
    fun `R79 la relectura se aplica pagina por pagina`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 3)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), "c2"))
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c2") } returns Result.success(conCursor(listOf(folio("d")), null))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)
            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b", "d").inOrder()

            val compuerta = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns
                Result.success(conCursor(listOf(folio("b", falta = "4.00")), "c2"))
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c2") } coAnswers {
                compuerta.await(); Result.success(conCursor(listOf(folio("d", falta = "6.00")), null))
            }
            vigia.sondearYa()

            // la página 2 ya llegó y se ve; la 3 sigue en camino
            val enCamino = vigia.estado.value.porPagar.associate { it.orderId to it.falta }
            assertThat(enCamino["b"]).isEqualTo(BigDecimal("4.00"))
            assertThat(enCamino["d"]).isEqualTo(BigDecimal("10.00"))

            compuerta.complete(Unit)
            assertThat(vigia.estado.value.porPagar.associate { it.orderId to it.falta }["d"]).isEqualTo(BigDecimal("6.00"))
        }
    }

    @Test
    fun `R79 un folio que cambio de pagina sale una sola vez`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 4)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b"), folio("c")), "c2"))
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c2") } returns Result.success(conCursor(listOf(folio("d")), null))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            // las páginas se recorrieron: «b» subió a la página 1 y «c» aparece en dos páginas extra
            respuesta = { Result.success(conCursor(listOf(folio("a"), folio("b")), "c1", conteo = 4)) }
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b"), folio("c")), "c2"))
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c2") } returns Result.success(conCursor(listOf(folio("c"), folio("d")), null))
            vigia.sondearYa()

            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b", "c", "d").inOrder()
        }
    }

    @Test
    fun `R79 un sondeo sin cursor suelta todas las paginas extra`() = runTest {
        respuesta = { Result.success(pagina(porEntregar = listOf(folio("x")), siguientes = mapOf(SeccionDeFolios.POR_ENTREGAR to "c1"))) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_ENTREGAR, "c1") } returns Result.success(
                pagina(porPagar = emptyList(), porEntregar = listOf(folio("y").copy(estado = "ACCEPTED")), siguientes = mapOf(SeccionDeFolios.POR_ENTREGAR to "c2")),
            )
            vigia.cargarMas(SeccionDeFolios.POR_ENTREGAR)
            assertThat(vigia.estado.value.porEntregar.map { it.orderId }).containsExactly("x", "y")

            // otra caja entregó «y»: la página 1 (la misma) ya no trae cursor ⇒ no hay camino a las extras y se sueltan
            respuesta = { Result.success(pagina(porEntregar = listOf(folio("x")))) }
            vigia.sondearYa()

            assertThat(vigia.estado.value.porEntregar.map { it.orderId }).containsExactly("x")
            assertThat(vigia.estado.value.nextCursors).isEmpty()
            coVerify(exactly = 1) { folios.cargarMas(any(), any(), any()) } // sólo la que cargó la cajera
        }
    }

    @Test
    fun `R79 la relectura suelta las paginas que ya no alcanza`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 3)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), "c2"))
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c2") } returns Result.success(conCursor(listOf(folio("d")), null))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            // se cobró lo de la página 3: la página 2 ya no tiene siguiente
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), null))
            respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 2)) }
            vigia.sondearYa()

            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b").inOrder()
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_PAGAR]).isNull()
            coVerify(exactly = 1) { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c2") } // sólo la de la cajera
        }
    }

    @Test
    fun `R79 el conteo de cada seccion sale del servidor y no del largo de la lista`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 9)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns
                Result.success(conCursor(listOf(folio("a"), folio("b")), "c2", conteo = 2))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)
            vigia.sondearYa()

            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b").inOrder()
            assertThat(vigia.estado.value.counts.porPagar).isEqualTo(9)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R92 (revisión de P5): M1, M3 y M4
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `R92 M1 una relectura vieja no suelta la pagina que la cajera agrego mientras tanto`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 3)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), "c2"))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            // La relectura de la página 2 sale antes de que existiera un folio más nuevo: contestará «no hay más».
            val compuerta = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } coAnswers {
                compuerta.await(); Result.success(conCursor(listOf(folio("b")), null))
            }
            vigia.sondearYa()
            // Mientras tanto la cajera toca «Cargar más» y llega la página 3 (con más detrás).
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c2") } returns Result.success(conCursor(listOf(folio("d")), "c3"))
            assertThat(vigia.cargarMas(SeccionDeFolios.POR_PAGAR)).isTrue()

            compuerta.complete(Unit)

            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b", "d").inOrder()
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_PAGAR]).isEqualTo("c3")
        }
    }

    @Test
    fun `R92 M3 al salir de la pantalla se sueltan las paginas extra y ya no se releen`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 2)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), null))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            vigia.soltarExtras(vigia.abrirLista())
            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a")
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_PAGAR]).isEqualTo("c1")
            assertThat(vigia.estado.value.counts.porPagar).isEqualTo(2) // el recuadro sigue con el número del servidor

            vigia.sondearYa() // la página 1 sigue para el recuadro, pero ya no se relee nada
            coVerify(exactly = 1) { folios.cargarMas(any(), any(), any()) }
            coVerify(exactly = 2) { folios.listar("v1", any(), any()) }
        }
    }

    @Test
    fun `R92 M4 una relectura que falla sin red deja las paginas extra como estaban`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 3)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), "c2"))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.failure(IOException("sin red"))
            vigia.sondearYa()

            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b").inOrder()
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_PAGAR]).isEqualTo("c2")
            assertThat(vigia.estado.value.errorAlCargarMas).isFalse() // la relectura no es un «Cargar más» de la cajera
        }
    }

    @Test
    fun `R92 M4 Cargar mas durante una relectura no pierde ni repite nada`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 4)) }
        conVigia { vigia ->
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), "c2"))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            val compuerta = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } coAnswers {
                compuerta.await(); Result.success(conCursor(listOf(folio("b"), folio("d")), "c2")) // «d» se recorrió a la página 2
            }
            vigia.sondearYa()
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c2") } returns Result.success(conCursor(listOf(folio("d"), folio("e")), "c3"))
            assertThat(vigia.cargarMas(SeccionDeFolios.POR_PAGAR)).isTrue()
            compuerta.complete(Unit)

            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b", "d", "e").inOrder()
            assertThat(vigia.estado.value.nextCursors[SeccionDeFolios.POR_PAGAR]).isEqualTo("c3")
        }
    }

    @Test
    fun `R97 la lista vieja que se cierra despues de abrir otra no suelta lo que cargo la nueva`() = runTest {
        respuesta = { Result.success(conCursor(listOf(folio("a")), "c1", conteo = 2)) }
        conVigia { vigia ->
            val vieja = vigia.abrirLista()
            val nueva = vigia.abrirLista() // la pantalla nueva nace antes de que la vieja se limpie
            coEvery { folios.cargarMas("v1", SeccionDeFolios.POR_PAGAR, "c1") } returns Result.success(conCursor(listOf(folio("b")), null))
            vigia.cargarMas(SeccionDeFolios.POR_PAGAR)

            vigia.soltarExtras(vieja)
            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a", "b").inOrder()

            vigia.soltarExtras(nueva)
            assertThat(vigia.estado.value.porPagar.map { it.orderId }).containsExactly("a")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D661 (Task 12): pagados con tarjeta en línea en «No, entran solos». La terminal suena mientras haya uno por abrir, abrirlo lo
    // cobra el SERVIDOR (nunca el lector de esta terminal) y la comanda sale de una lectura fresca, aquí en el vigía.
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tarjeta(id: String, cobrando: Boolean = false) =
        TarjetaPorAbrir(id, "W$id", "Ana", BigDecimal("250.50"), Instant.parse("2026-11-20T18:00:00Z"), cobrando)

    private val latte = FolioProducto("Latte", 1, null, emptyList())

    /** Un pedido cobrado en línea con su comanda pendiente, como lo trae «Por entregar» o la lectura de UN pedido. */
    private fun cobrado(id: String, estado: String = "ACCEPTED", productos: List<FolioProducto> = listOf(latte)) =
        folio(id, falta = "0.00").copy(estado = estado, estadoDePago = "PAID", necesitaImprimir = true, productos = productos)

    private fun conImpresora() {
        every { impresora.isPrinterAvailable() } returns true
        every { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
    }

    @Test
    fun `suena cada 4 s mientras haya pagados por abrir`() = runTest {
        respuesta = { Result.success(pagina(porPagar = emptyList(), tarjetas = listOf(tarjeta("t1")))) }
        conVigia { vigia ->
            advanceTimeBy(4_001)
            assertThat(timbres).isEqualTo(1)
            advanceTimeBy(8_000)
            assertThat(timbres).isEqualTo(3)

            // Otra caja lo está cobrando: ya no suena aquí.
            respuesta = { Result.success(pagina(porPagar = emptyList(), tarjetas = listOf(tarjeta("t1", cobrando = true)))) }
            vigia.sondearYa()
            advanceTimeBy(8_000)
            assertThat(timbres).isEqualTo(3)

            // Llega otro: vuelve a sonar cada 4 s.
            respuesta = { Result.success(pagina(porPagar = emptyList(), tarjetas = listOf(tarjeta("t1", cobrando = true), tarjeta("t2")))) }
            vigia.sondearYa()
            advanceTimeBy(8_000)
            assertThat(timbres).isEqualTo(5)

            // Ya se abrieron: deja de sonar.
            respuesta = { Result.success(pagina(porPagar = emptyList())) }
            vigia.sondearYa()
            advanceTimeBy(12_000)
            assertThat(timbres).isEqualTo(5)
        }
    }

    @Test
    fun `una copia vieja no suena`() = runTest {
        respuesta = { Result.success(pagina(porPagar = emptyList(), tarjetas = listOf(tarjeta("t1")))) }
        conVigia { vigia ->
            advanceTimeBy(4_001)
            assertThat(timbres).isEqualTo(1)

            // Sin red: la lista se queda (la pantalla la enseña) pero ya no suena: pudo abrirse en otra caja.
            respuesta = { Result.failure(IOException("sin red")) }
            vigia.sondearYa()
            assertThat(vigia.estado.value.tarjetasPorAbrir.map { it.orderId }).containsExactly("t1")
            advanceTimeBy(8_000)
            assertThat(timbres).isEqualTo(1)

            // Vuelve la red: una lectura en vivo, suena.
            respuesta = { Result.success(pagina(porPagar = emptyList(), tarjetas = listOf(tarjeta("t1")))) }
            vigia.sondearYa()
            advanceTimeBy(4_000)
            assertThat(timbres).isEqualTo(2)

            // Un 4xx es respuesta, pero la lista ya no es fiable: no suena.
            respuesta = { Result.failure(BackendHttpException(403, "sin permiso")) }
            vigia.sondearYa()
            advanceTimeBy(8_000)
            assertThat(timbres).isEqualTo(2)

            // Una lectura que se queda colgada (o la terminal que se durmió): la copia envejece más de 90 s y deja de sonar.
            respuesta = { Result.success(pagina(porPagar = emptyList(), tarjetas = listOf(tarjeta("t1")))) }
            vigia.sondearYa()
            advanceTimeBy(4_000)
            assertThat(timbres).isEqualTo(3)
            val colgada = CompletableDeferred<Result<PaginaDeFolios>>()
            respuesta = { colgada.await() }
            vigia.sondearYa()
            hora += 91_000
            advanceTimeBy(8_000)
            assertThat(timbres).isEqualTo(3)
        }
    }

    @Test
    fun `un folio pagado en caja no suena`() = runTest {
        // Un folio de la tienda pagado EN CAJA está en «Por entregar», nunca en «tarjetasPorAbrir»: no suena.
        respuesta = { Result.success(pagina(porPagar = listOf(folio("p")), porEntregar = listOf(cobrado("e")))) }
        conVigia {
            advanceTimeBy(60_001)
            assertThat(timbres).isEqualTo(0)
        }
    }

    @Test
    fun `el recuadro sigue visible y el sondeo rapido mientras haya tarjetas por abrir aunque la tienda cambie de modo o se apague`() = runTest {
        // La tienda se apagó (o pasó a «Sí, alguien del personal lo acepta»): el SERVIDOR ya no pide el recuadro ni el sondeo rápido,
        // pero queda un pagado en línea por abrir: el recuadro sigue y la terminal sigue preguntando cada 30 s.
        respuesta = { Result.success(pagina(showTile = false, poll = 600, porPagar = emptyList(), tarjetas = listOf(tarjeta("t1")))) }
        conVigia { vigia ->
            assertThat(recuadroPorPagar(vigia.estado.value, canOperate = true, canWork = true, motivoSinTurno = null, ahoraMs = hora))
                .isNotNull()
            advanceTimeBy(29_000)
            coVerify(exactly = 1) { folios.listar("v1", any(), any()) }
            advanceTimeBy(2_000)
            coVerify(exactly = 2) { folios.listar("v1", any(), any()) }

            // Ya se abrió: vuelven el recuadro apagado y la espera larga.
            respuesta = { Result.success(pagina(showTile = false, poll = 600, porPagar = emptyList())) }
            vigia.sondearYa()
            assertThat(recuadroPorPagar(vigia.estado.value, canOperate = true, canWork = true, motivoSinTurno = null, ahoraMs = hora))
                .isNull()
            advanceTimeBy(599_000)
            coVerify(exactly = 3) { folios.listar("v1", any(), any()) }
            advanceTimeBy(2_000)
            coVerify(exactly = 4) { folios.listar("v1", any(), any()) }
        }
    }

    @Test
    fun `Ruling 45 la cabecera de aviso de tarjetas sale solo en el sondeo del vigia y solo con la app al frente`() = runTest {
        conVigia { vigia ->
            assertThat(avisos.last()).isTrue()

            // La app se fue al fondo: sigue leyendo, pero ya no dice que puede avisar.
            primerPlano = false
            vigia.sondearYa()
            assertThat(avisos.last()).isFalse()
            assertThat(cabeceras.last()).isTrue() // el latido de «Por pagar» no cambia

            primerPlano = true
            vigia.sondearYa()
            assertThat(avisos.last()).isTrue()
            // (Paginar nunca la manda: «Cargar más» no tiene esa cabecera; lo prueba FoliosRepositoryTest.)
        }
    }

    // El cuerpo va en una función de nombre corto: con el nombre completo, la clase que Kotlin genera para la corrutina de `runTest`
    // pasa de 255 bytes y el compilador no la puede escribir («error while writing …$1.class»).
    @Test
    fun `un pedido de tarjeta cobrado con needsPrint imprime su comanda de apoyo una vez desde una lectura fresca del pedido completo, aunque se haya perdido la respuesta de abrir, tras reconectar o reiniciar, con paginacion o despues del cierre del dia`() =
        comandaUnaVezDeUnaLecturaFresca()

    private fun comandaUnaVezDeUnaLecturaFresca() = runTest {
        conImpresora()
        // «x»: el cierre del día ya lo pasó a COMPLETED (o quedó fuera de la página 1): no está en «Por entregar»; se lee por su camino.
        // «y»: está en la página 1 de «Por entregar»: sus renglones salen de esa fila de la MISMA lectura.
        val impresas = mutableSetOf<String>()
        fun lectura() = pagina(
            porPagar = emptyList(),
            porEntregar = listOf(cobrado("y").copy(necesitaImprimir = "y" !in impresas)),
            comandas = listOf(ComandaPorImprimir("x", "Wx"), ComandaPorImprimir("y", "Wy")).filter { it.orderId !in impresas },
        )
        coEvery { folios.leerPedido("v1", "x") } answers {
            Result.success(cobrado("x", estado = "COMPLETED").copy(necesitaImprimir = "x" !in impresas))
        }
        coEvery { folios.reclamarImpresion("v1", any()) } returns Result.success(ReclamoDeComanda(true))
        coEvery { folios.confirmarImpresion("v1", any()) } coAnswers {
            impresas += secondArg<String>()
            Result.success(true)
        }

        // 1) Se perdió la respuesta de «abrir» y la terminal se quedó sin red: no se lee ni se imprime nada. La app se reinicia.
        respuesta = { Result.failure(IOException("sin red")) }
        conVigia {
            advanceTimeBy(31_000)
            coVerify(exactly = 0) { folios.leerPedido(any(), any()) }
            verify(exactly = 0) { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

        // 2) La app nueva (sin nada en memoria) ya tiene red: la lectura fresca trae lo que falta y cada comanda sale una vez.
        respuesta = { Result.success(lectura()) }
        conVigia { vigia ->
            verify(exactly = 1) { impresora.printKitchenTicket("Wx", any(), any(), any(), any(), any(), true, any(), any()) }
            verify(exactly = 1) { impresora.printKitchenTicket("Wy", any(), any(), any(), any(), any(), true, any(), any()) }
            coVerify(exactly = 1) { folios.leerPedido("v1", "x") }
            coVerify(exactly = 0) { folios.leerPedido("v1", "y") }
            assertThat(impresas).containsExactly("x", "y")

            // 3) Las lecturas siguientes ya no las piden: ninguna sale dos veces.
            vigia.sondearYa()
            advanceTimeBy(61_000)
            verify(exactly = 2) { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { folios.leerPedido("v1", "x") }
        }
    }

    @Test
    fun `sin impresora no se lee ningun pedido para su comanda`() = runTest {
        // Un modelo sin impresora (relaxed: isPrinterAvailable = false) no lee nada para imprimir.
        coEvery { folios.leerPedido(any(), any()) } returns Result.success(cobrado("x"))
        coEvery { folios.reclamarImpresion(any(), any()) } returns Result.success(ReclamoDeComanda(true))
        respuesta = { Result.success(pagina(porPagar = emptyList(), comandas = listOf(ComandaPorImprimir("x", "Wx")))) }
        conVigia {
            advanceTimeBy(61_000)
            coVerify(exactly = 0) { folios.leerPedido(any(), any()) }
            coVerify(exactly = 0) { folios.reclamarImpresion(any(), any()) }
        }
    }

    @Test
    fun `una comanda atorada no frena a las demas`() = runTest {
        conImpresora()
        val impresas = mutableSetOf<String>()
        respuesta = {
            Result.success(
                pagina(
                    porPagar = emptyList(),
                    porEntregar = listOf(cobrado("x"), cobrado("y").copy(necesitaImprimir = "y" !in impresas)),
                    comandas = listOf(ComandaPorImprimir("x", "Wx"), ComandaPorImprimir("y", "Wy")).filter { it.orderId !in impresas },
                ),
            )
        }
        // El papel de «x» nunca termina (una impresora que no contesta).
        coEvery { folios.reclamarImpresion("v1", "x") } coAnswers { awaitCancellation() }
        coEvery { folios.reclamarImpresion("v1", "y") } returns Result.success(ReclamoDeComanda(true))
        coEvery { folios.confirmarImpresion("v1", "y") } coAnswers {
            impresas += "y"
            Result.success(true)
        }
        conVigia {
            verify(exactly = 0) { impresora.printKitchenTicket("Wy", any(), any(), any(), any(), any(), any(), any(), any()) }
            advanceTimeBy(20_001)
            verify(exactly = 1) { impresora.printKitchenTicket("Wy", any(), any(), any(), any(), any(), any(), any(), any()) }

            // Las siguientes lecturas no vuelven a intentar «x» mientras su trabajo siga vivo.
            advanceTimeBy(60_000)
            coVerify(exactly = 1) { folios.reclamarImpresion("v1", "x") }
        }
    }

    @Test
    fun `una lista que llega mientras se imprime no se pierde`() = runTest {
        conImpresora()
        var comandas = listOf(ComandaPorImprimir("x", "Wx"))
        val impresas = mutableSetOf<String>()
        respuesta = {
            Result.success(
                pagina(
                    porPagar = emptyList(),
                    porEntregar = listOf(cobrado("x"), cobrado("z")),
                    comandas = comandas.filter { it.orderId !in impresas },
                ),
            )
        }
        // El papel de «x» tarda 25 s.
        coEvery { folios.reclamarImpresion("v1", "x") } coAnswers {
            delay(25_000)
            Result.success(ReclamoDeComanda(false))
        }
        coEvery { folios.reclamarImpresion("v1", "z") } returns Result.success(ReclamoDeComanda(true))
        coEvery { folios.confirmarImpresion("v1", "z") } coAnswers {
            impresas += "z"
            Result.success(true)
        }
        conVigia { vigia ->
            // A los 5 s, con la vuelta de «x» viva, llega una lectura que ya trae «z».
            advanceTimeBy(5_000)
            comandas = listOf(ComandaPorImprimir("x", "Wx"), ComandaPorImprimir("z", "Wz"))
            vigia.sondearYa()
            verify(exactly = 0) { impresora.printKitchenTicket("Wz", any(), any(), any(), any(), any(), any(), any(), any()) }

            // La vuelta deja de esperar a «x» a los 20 s y recorre la lista que llegó mientras tanto (antes del siguiente sondeo, a los 35 s).
            advanceTimeBy(16_000)
            verify(exactly = 1) { impresora.printKitchenTicket("Wz", any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `un pedido cuya lectura falla o llega sin renglones descansa 3 min`() = runTest {
        conImpresora()
        respuesta = {
            Result.success(pagina(porPagar = emptyList(), comandas = listOf(ComandaPorImprimir("x", "Wx"), ComandaPorImprimir("w", "Ww"))))
        }
        coEvery { folios.leerPedido("v1", "x") } returns Result.failure(IOException("sin red"))
        coEvery { folios.leerPedido("v1", "w") } returns Result.success(cobrado("w", productos = emptyList()))
        conVigia {
            coVerify(exactly = 1) { folios.leerPedido("v1", "x") }
            coVerify(exactly = 1) { folios.leerPedido("v1", "w") }

            // Durante 3 min no se vuelven a leer en cada sondeo (cada 30 s).
            hora += 150_000
            advanceTimeBy(150_000)
            coVerify(exactly = 1) { folios.leerPedido("v1", "x") }
            coVerify(exactly = 1) { folios.leerPedido("v1", "w") }

            hora += 60_000
            advanceTimeBy(60_000)
            coVerify(exactly = 2) { folios.leerPedido("v1", "x") }
            coVerify(exactly = 2) { folios.leerPedido("v1", "w") }
            verify(exactly = 0) { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `Ruling 50 una comanda cuyo reclamo ya tiene 75 s al soltarse la impresora no sale y el siguiente sondeo la reclama otra vez - una mas joven sale como hoy`() = runTest {
        conImpresora()
        // La impresora queda libre `espera` ms después del reclamo y, ya libre, pregunta si todavía toca (como el PrinterManager real).
        var espera = 80_000L
        every { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            monotonico += espera
            if (arg<(() -> Boolean)?>(8)?.invoke() == false) Result.failure(ComandaNoEmpezada()) else Result.success(Unit)
        }
        val impresas = mutableSetOf<String>()
        var comandas = listOf(ComandaPorImprimir("v", "Wv"))
        respuesta = {
            Result.success(
                pagina(porPagar = emptyList(), porEntregar = listOf(cobrado("v"), cobrado("j")), comandas = comandas.filter { it.orderId !in impresas }),
            )
        }
        coEvery { folios.reclamarImpresion("v1", any()) } returns Result.success(ReclamoDeComanda(true))
        coEvery { folios.confirmarImpresion("v1", any()) } coAnswers {
            impresas += secondArg<String>()
            Result.success(true)
        }
        conVigia { vigia ->
            // Esperó 80 s a la impresora: no sale, no se confirma y no descansa.
            coVerify(exactly = 1) { folios.reclamarImpresion("v1", "v") }
            coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }

            // El siguiente sondeo la reclama otra vez y, con la impresora libre, sale una vez.
            espera = 0
            vigia.sondearYa()
            coVerify(exactly = 2) { folios.reclamarImpresion("v1", "v") }
            coVerify(exactly = 1) { folios.confirmarImpresion("v1", "v") }

            // Una que esperó menos de 75 s sale como hoy, al primer intento.
            espera = 74_999
            comandas = listOf(ComandaPorImprimir("j", "Wj"))
            vigia.sondearYa()
            coVerify(exactly = 1) { folios.reclamarImpresion("v1", "j") }
            coVerify(exactly = 1) { folios.confirmarImpresion("v1", "j") }
        }
    }
}
