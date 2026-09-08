package com.jaac.avoqado_tpv.features.shift.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.shift.data.dto.CashReconciliationDto
import com.jaac.avoqado_tpv.features.shift.data.dto.CloseShiftRequest
import com.jaac.avoqado_tpv.features.shift.data.dto.OpenShiftRequest
import com.jaac.avoqado_tpv.features.shift.data.dto.PaginationMeta
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftHistoryResponse
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftResponse
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationOutcome
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.math.BigDecimal

class ShiftRepositoryTest {

    private val apiService = mockk<ApiService>()
    private val secureStorage = mockk<SecureStorage>()
    private val cachedShiftDao = mockk<CachedShiftDao>(relaxed = true)
    private val refundQueue = mockk<com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository>(relaxed = true)
    private lateinit var repository: ShiftRepository

    @Before
    fun setUp() {
        clearMocks(apiService, secureStorage, cachedShiftDao)
        every { secureStorage.isShiftSystemEnabled() } returns true
        clearMocks(refundQueue)
        coEvery { refundQueue.blockingForVenue(any()) } returns emptyList()
        repository = ShiftRepository(apiService, secureStorage, cachedShiftDao, refundQueue)
    }

    @Test
    fun `default close preserves null reconciliation fields for legacy and kiosk callers`() = runTest {
        val request = slot<CloseShiftRequest>()
        coEvery { apiService.closeShift("venue-1", "shift-1", capture(request)) } returns
            Response.success(successfulClose())

        val result = repository.closeShift("venue-1", "shift-1")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(request.captured.closeData).isNull()
        assertThat(request.captured.cashReconciliationAction).isNull()
        assertThat(request.captured.countedCash).isNull()
        coVerify(exactly = 1) { apiService.closeShift("venue-1", "shift-1", any()) }
    }

    @Test
    fun `COUNTED close normalizes BigDecimal to two-place plain string without exponent`() = runTest {
        val request = slot<CloseShiftRequest>()
        coEvery { apiService.closeShift("venue-1", "shift-1", capture(request)) } returns
            Response.success(successfulClose())

        repository.closeShift(
            venueId = "venue-1",
            shiftId = "shift-1",
            action = CashReconciliationAction.COUNTED,
            countedCash = BigDecimal("0E+7")
        )

        assertThat(request.captured.cashReconciliationAction).isEqualTo(CashReconciliationAction.COUNTED)
        assertThat(request.captured.countedCash).isEqualTo("0.00")
    }

    @Test
    fun `root reconciliation is request-scoped on the returned shift`() = runTest {
        coEvery { apiService.closeShift(any(), any(), any()) } returns Response.success(
            successfulClose(
                shift = shiftDto(cashDeclared = "6000.00", cashDifference = "-25.50"),
                reconciliation = CashReconciliationDto(
                    outcome = CashReconciliationOutcome.APPLIED,
                    countedCash = "6000.00",
                    cashDifference = "-25.50"
                )
            )
        )

        val result = repository.closeShift(
            venueId = "venue-1",
            shiftId = "shift-1",
            action = CashReconciliationAction.COUNTED,
            countedCash = BigDecimal("6000.00")
        )

        val shift = (result as Result.Success).data
        assertThat(shift.cashDeclared).isEqualTo(BigDecimal("6000.00"))
        assertThat(shift.cashDifference).isEqualTo(BigDecimal("-25.50"))
        assertThat(shift.reconciliation?.outcome).isEqualTo(CashReconciliationOutcome.APPLIED)
        assertThat(shift.reconciliation?.cashDeclared).isEqualTo(BigDecimal("6000.00"))
        assertThat(shift.reconciliation?.cashDifference).isEqualTo(BigDecimal("-25.50"))
    }

    @Test
    fun `409 performs one bounded history GET and never repeats the close POST`() = runTest {
        coEvery { apiService.closeShift(any(), any(), any()) } returns httpError(409)
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 10, pageNumber = 1) } returns
            Response.success(
                ShiftHistoryResponse(
                    success = true,
                    data = listOf(shiftDto(status = "CLOSED")),
                    meta = PaginationMeta(1, 1, 1, 10)
                )
            )

        val result = repository.closeShift("venue-1", "shift-1")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.id).isEqualTo("shift-1")
        coVerify(exactly = 1) { apiService.closeShift("venue-1", "shift-1", any()) }
        coVerify(exactly = 1) { apiService.getShiftHistory("venue-1", pageSize = 10, pageNumber = 1) }
    }

    @Test
    fun `409 remains an error when the bounded GET cannot find the closed shift`() = runTest {
        coEvery { apiService.closeShift(any(), any(), any()) } returns httpError(409)
        coEvery { apiService.getShiftHistory(any(), any(), any()) } returns Response.success(
            ShiftHistoryResponse(
                success = true,
                data = emptyList(),
                meta = PaginationMeta(0, 0, 1, 10)
            )
        )

        val result = repository.closeShift("venue-1", "shift-1")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(((result as Result.Error).exception as ApiException.HttpError).code).isEqualTo(409)
        coVerify(exactly = 1) { apiService.closeShift(any(), any(), any()) }
        coVerify(exactly = 1) { apiService.getShiftHistory(any(), any(), any()) }
    }

    @Test
    fun `400 already-closed response performs one bounded GET and never repeats POST`() = runTest {
        coEvery { apiService.closeShift(any(), any(), any()) } returns httpError(400)
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 10, pageNumber = 1) } returns
            Response.success(
                ShiftHistoryResponse(
                    success = true,
                    data = listOf(shiftDto(status = "CLOSED")),
                    meta = PaginationMeta(1, 1, 1, 10)
                )
            )

        val result = repository.closeShift("venue-1", "shift-1")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 1) { apiService.closeShift("venue-1", "shift-1", any()) }
        coVerify(exactly = 1) { apiService.getShiftHistory("venue-1", pageSize = 10, pageNumber = 1) }
    }

    // ─── 🔔 Abrir o cerrar el turno AVISA a las demás pantallas (QA Nexgo N86, 7-sep-2026) ────
    //
    // Cada pantalla tiene su propio ShiftViewModel; sin este pulso, «Turnos de caja» abría la caja
    // e Inicio seguía en «Sin turno de caja» bloqueando Cobrar hasta reiniciar la app.

    @Test
    fun `abrir el turno con exito avisa por shiftChanges`() = runTest {
        val avisos = mutableListOf<Unit>()
        backgroundScope.launch { repository.shiftChanges.collect { avisos += it } }
        runCurrent()
        coEvery { apiService.openShift("venue-1", any<OpenShiftRequest>()) } returns
            Response.success(ShiftResponse(success = true, data = shiftDto(status = "OPEN"), reconciliation = null))

        val result = repository.openShift("venue-1", "staff-1", 500.0)
        runCurrent()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(avisos).hasSize(1)
    }

    @Test
    fun `si abrir el turno falla no se avisa nada`() = runTest {
        val avisos = mutableListOf<Unit>()
        backgroundScope.launch { repository.shiftChanges.collect { avisos += it } }
        runCurrent()
        coEvery { apiService.openShift("venue-1", any<OpenShiftRequest>()) } returns
            Response.error(500, "".toResponseBody("application/json".toMediaTypeOrNull()))

        val result = repository.openShift("venue-1", "staff-1", 500.0)
        runCurrent()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(avisos).isEmpty()
    }

    @Test
    fun `cerrar el turno con exito avisa por shiftChanges`() = runTest {
        val avisos = mutableListOf<Unit>()
        backgroundScope.launch { repository.shiftChanges.collect { avisos += it } }
        runCurrent()
        coEvery { apiService.closeShift("venue-1", "shift-1", any()) } returns Response.success(successfulClose())

        repository.closeShift("venue-1", "shift-1")
        runCurrent()

        assertThat(avisos).hasSize(1)
    }

    private fun successfulClose(
        shift: ShiftDto = shiftDto(),
        reconciliation: CashReconciliationDto? = null
    ) = ShiftResponse(
        success = true,
        data = shift,
        reconciliation = reconciliation
    )

    private fun shiftDto(
        status: String = "CLOSED",
        cashDeclared: String? = null,
        cashDifference: String? = null
    ) = ShiftDto(
        id = "shift-1",
        venueId = "venue-1",
        staffId = "staff-1",
        startTime = "2026-08-08T00:00:00Z",
        endTime = "2026-08-08T01:00:00Z",
        status = status,
        startingCash = "500.00",
        endingCash = "6000.00",
        totalSales = "5600.00",
        totalTips = "0.00",
        totalOrders = 10,
        totalCashPayments = "5525.50",
        totalCardPayments = "74.50",
        totalVoucherPayments = "0.00",
        totalOtherPayments = "0.00",
        totalProductsSold = 10,
        staff = null,
        cashDeclared = cashDeclared,
        cashDifference = cashDifference
    )

    // ─── 📋 La historia de turnos PAGINA (revisión 5-sep-2026) ────────────────
    //
    // 🔴 El servidor recorta el `pageSize` a 50 (`TOPE_DE_TURNOS_POR_PAGINA`) y lo dice en `meta`,
    // que nadie leía. Los reportes pedían 200 turnos, recibían 50, y comparaban periodos con una
    // tercera parte de los datos sin avisar de nada — un total más chico que el real se lee como
    // una caída de ventas, no como un error.

    /** Un turno CERRADO con id y fecha propios, para poder contar y cortar por fecha. */
    private fun turno(id: String, inicio: String) = shiftDto().copy(id = id, startTime = inicio)

    private fun pagina(
        turnos: List<ShiftDto>,
        hasNext: Boolean?,
        currentPage: Int = 1,
        totalPages: Int = 3
    ) = Response.success(
        ShiftHistoryResponse(
            success = true,
            data = turnos,
            meta = PaginationMeta(
                totalRecords = 0,
                totalPages = totalPages,
                currentPage = currentPage,
                pageSize = 50,
                hasNextPage = hasNext,
                totalCount = 120
            )
        )
    )

    private fun turnos(n: Int, prefijo: String, inicio: String = "2026-08-08T00:00:00Z") =
        (1..n).map { turno("$prefijo-$it", inicio) }

    @Test
    fun `P1 pide 50 en 50 y sigue mientras el servidor diga que hay mas - 50+50+20 = 120`() = runTest {
        // Antes esto devolvía 50 de 120 y el reporte comparaba periodos con eso, en silencio.
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 1) } returns
            pagina(turnos(50, "p1"), hasNext = true, currentPage = 1)
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 2) } returns
            pagina(turnos(50, "p2"), hasNext = true, currentPage = 2)
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 3) } returns
            pagina(turnos(20, "p3"), hasNext = false, currentPage = 3)

        val result = repository.getShiftHistory("venue-1", limit = 200)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data).hasSize(120)
        coVerify(exactly = 1) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 1) }
        coVerify(exactly = 1) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 2) }
        coVerify(exactly = 1) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 3) }
    }

    @Test
    fun `P1 con desde que cae en la 2a pagina NO se pide la 3a`() = runTest {
        // Seguir paginando por turnos que el filtro de periodo va a descartar es gastar red en una
        // terminal de 1 GB. En cuanto llega uno anterior al corte, ya no hacen falta más páginas.
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 1) } returns
            pagina(turnos(50, "p1", "2026-08-20T00:00:00Z"), hasNext = true, currentPage = 1)
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 2) } returns
            pagina(turnos(50, "p2", "2026-07-01T00:00:00Z"), hasNext = true, currentPage = 2)

        val result = repository.getShiftHistory(
            "venue-1",
            limit = 200,
            desde = java.time.Instant.parse("2026-08-01T00:00:00Z")
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data).hasSize(100)
        coVerify(exactly = 1) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 2) }
        coVerify(exactly = 0) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 3) }
    }

    @Test
    fun `P1 con meta nulo - servidor viejo - se queda en UNA sola llamada`() = runTest {
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = any()) } returns
            Response.success(ShiftHistoryResponse(success = true, data = turnos(50, "p1"), meta = null))

        val result = repository.getShiftHistory("venue-1", limit = 200)

        assertThat((result as Result.Success).data).hasSize(50)
        coVerify(exactly = 1) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 1) }
        coVerify(exactly = 0) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 2) }
    }

    @Test
    fun `P1 sin hasNextPage el paginador lo deduce de currentPage y totalPages`() = runTest {
        // 🔴 Gson no distingue «false» de «ausente»: por eso `hasNextPage` es nullable y hay un
        // respaldo. Un servidor que mande `meta` pero no la bandera igual tiene que paginar.
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 1) } returns
            pagina(turnos(50, "p1"), hasNext = null, currentPage = 1, totalPages = 2)
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 2) } returns
            pagina(turnos(30, "p2"), hasNext = null, currentPage = 2, totalPages = 2)

        val result = repository.getShiftHistory("venue-1", limit = 200)

        assertThat((result as Result.Success).data).hasSize(80)
        coVerify(exactly = 1) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 2) }
        coVerify(exactly = 0) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 3) }
    }

    @Test
    fun `P1 al llegar al limite pedido deja de paginar y devuelve exactamente ese limite`() = runTest {
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 10, pageNumber = 1) } returns
            pagina(turnos(10, "p1"), hasNext = true, currentPage = 1)

        val result = repository.getShiftHistory("venue-1", limit = 10)

        assertThat((result as Result.Success).data).hasSize(10)
        coVerify(exactly = 0) { apiService.getShiftHistory("venue-1", pageSize = any(), pageNumber = 2) }
    }

    @Test
    fun `P1 la pantalla de Turnos - limit 10 - NO se trae 50 renglones a una terminal de 1 GB`() = runTest {
        // El `pageSize` va acotado por el `limit`: subirlo a 50 fijo quintuplicaría el payload de la
        // pantalla más usada para enseñar los mismos 10 renglones.
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 10, pageNumber = 1) } returns
            pagina(turnos(10, "p1"), hasNext = false, currentPage = 1, totalPages = 1)

        repository.getShiftHistory("venue-1", limit = 10)

        coVerify(exactly = 1) { apiService.getShiftHistory("venue-1", pageSize = 10, pageNumber = 1) }
        coVerify(exactly = 0) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = any()) }
    }

    @Test
    fun `P1 un fallo a media paginacion es ERROR, nunca la lista parcial como si fuera buena`() = runTest {
        // Devolver lo que se alcanzó a traer reproduce el defecto: totales más chicos que la
        // realidad, presentados como buenos. El reporte prefiere no salir a salir mintiendo.
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 1) } returns
            pagina(turnos(50, "p1"), hasNext = true, currentPage = 1)
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = 2) } returns
            Response.error(500, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

        val result = repository.getShiftHistory("venue-1", limit = 200)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(((result as Result.Error).exception as ApiException.HttpError).code).isEqualTo(500)
    }

    @Test
    fun `P1 el tope duro de paginas corta un meta mentiroso en vez de hacer bucle infinito`() = runTest {
        // Un servidor que SIEMPRE diga «hay más» no puede volverse un bucle de red en una terminal.
        coEvery { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = any()) } returns
            pagina(turnos(50, "px"), hasNext = true, currentPage = 1, totalPages = 9999)

        val result = repository.getShiftHistory("venue-1", limit = 10_000)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 10) { apiService.getShiftHistory("venue-1", pageSize = 50, pageNumber = any()) }
    }

    private fun httpError(code: Int): Response<ShiftResponse> = Response.error(
        code,
        "{}".toResponseBody("application/json".toMediaTypeOrNull())
    )

    // ─── 💸 Barrera de reembolsos por debajo de todas las pantallas (auditoría de Codex F8) ───

    private fun reembolsoPendiente() = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund(
        idempotencyKey = "k-1", venueId = "venue-1", staffId = "s1",
        processor = com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.BLUMON,
        originalPaymentId = "pay-orig", originalOrderId = null, amount = java.math.BigDecimal("50.00"),
        originalTotalAmount = java.math.BigDecimal("100.00"), tipRefundCents = null, isPartialRefund = true,
        refundReason = com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason.CUSTOMER_REQUEST,
        merchantAccountId = "m1", blumonSerialNumber = "SER1", originalOperationNumber = 1,
        authorizationNumber = "502511", referenceNumber = "000000188231", maskedPan = null, cardBrand = null,
        entryMode = "CHIP", createdAt = 1_000L,
    )

    @Test
    fun `P1 con una devolucion sin registrar el repositorio NO cierra ni llama al API - cubre al kiosco`() = runTest {
        coEvery { refundQueue.blockingForVenue("venue-1") } returns listOf(reembolsoPendiente())

        val result = repository.closeShift("venue-1", "shift-1")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception.message).contains("devolución")
        coVerify(exactly = 0) { apiService.closeShift(any(), any(), any()) }
    }

    @Test
    fun `P1 si la cola no se puede leer tampoco cierra - nunca a ciegas`() = runTest {
        coEvery { refundQueue.blockingForVenue("venue-1") } throws IllegalStateException("db cerrada")

        val result = repository.closeShift("venue-1", "shift-1")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        coVerify(exactly = 0) { apiService.closeShift(any(), any(), any()) }
    }
}
