package com.jaac.avoqado_tpv.features.onlineorders.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result as AppResult
import com.jaac.avoqado_tpv.features.onlineorders.domain.ConteoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.PaginaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
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
import kotlinx.coroutines.CoroutineScope
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

    private fun folio(id: String) = Folio(
        orderId = id, onlineOrderId = null, folio = "W$id", estado = "PENDING_ACCEPTANCE", nombre = "Ana", telefono = null, nota = null,
        creadoEn = null, venceEn = null, total = BigDecimal("10.00"), falta = BigDecimal("10.00"), estadoDePago = "PENDING",
        necesitaImprimir = false, productos = emptyList(),
    )

    private fun pagina(
        showTile: Boolean = true,
        poll: Int? = 30,
        porPagar: List<Folio> = listOf(folio("a")),
        porEntregar: List<Folio> = emptyList(),
        siguientes: Map<SeccionDeFolios, String> = emptyMap(),
        serverTime: Instant? = null,
    ) = PaginaDeFolios(serverTime, showTile, poll, ConteoDeFolios(porPagar.size, porEntregar.size), porPagar, porEntregar, siguientes)

    private val cabeceras = mutableListOf<Boolean>()
    private var respuesta: () -> Result<PaginaDeFolios> = { Result.success(pagina()) }

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
        coEvery { folios.listar("v1", any()) } answers {
            cabeceras += secondArg<Boolean>()
            respuesta()
        }
    }

    /** El vigía con el scope de la prueba; se cancela al final (memoria de la TPV, líneas 33-34). */
    private suspend fun kotlinx.coroutines.test.TestScope.conVigia(bloque: suspend (VigiaDeFolios) -> Unit) {
        preparar()
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler) + job)
        val vigia = VigiaDeFolios(folios, shifts, asistencia, ajustes, storage, { hora })
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
            coVerify(exactly = 1) { folios.listar("v1", any()) }
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
            coVerify(exactly = 0) { folios.listar(any(), any()) }
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
            coVerify(exactly = 1) { folios.listar("v1", any()) }
            advanceTimeBy(2_000)
            coVerify(exactly = 2) { folios.listar("v1", any()) }
        }
    }

    @Test
    fun `con el recuadro prendido espera 30 s`() = runTest {
        conVigia {
            advanceTimeBy(29_000)
            coVerify(exactly = 1) { folios.listar("v1", any()) }
            advanceTimeBy(2_000)
            coVerify(exactly = 2) { folios.listar("v1", any()) }
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
}
