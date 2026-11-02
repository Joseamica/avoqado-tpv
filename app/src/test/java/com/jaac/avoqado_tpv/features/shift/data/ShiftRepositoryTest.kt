package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.shift.data.dto.*
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.toEntity
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationOutcome
import io.mockk.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ShiftRepositoryTest {
    private val apiService = mockk<ApiService>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val refundQueue = mockk<com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository>(relaxed = true)
    private lateinit var database: AvoqadoDatabase
    private lateinit var repository: ShiftRepository
    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        repository = task5RoomShiftRepository(database, secureStorage, apiService, "venue-1", "staff-1", true)
        kotlinx.coroutines.runBlocking {
            database.cachedShiftDao().cacheShift(com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity(
                "c000000000000000000000031", "venue-1", "OPEN", "staff-1", "", "2026-08-08T00:00:00Z", "0", 0, 0, 1))
        }
    }
    @After fun closeDatabase() { database.close() }

    // B2: el gesto público confirma el commit local; el coordinador único hace HTTP después.
    @Test fun `default close preserves null reconciliation fields for legacy and kiosk callers`() = runTest {
        val request = slot<CloseShiftRequest>()
        coEvery { apiService.closeShift("venue-1", "c000000000000000000000031", capture(request)) } returns Response.success(successfulClose())
        assertThat(repository.closeShift("venue-1", "c000000000000000000000031")).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 0) { apiService.closeShift(any(), any(), any()) }
        repository.sincronizarCierresLocales("venue-1")
        assertThat(request.captured.closeData).isNull()
        assertThat(request.captured.cashReconciliationAction).isNull()
        assertThat(request.captured.countedCash).isNull()
        assertThat(request.captured.operationId).isNotEmpty()
        coVerify(exactly = 1) { apiService.closeShift("venue-1", "c000000000000000000000031", any()) }
    }
    @Test fun `COUNTED close normalizes BigDecimal to two-place plain string without exponent`() = runTest {
        val request = slot<CloseShiftRequest>()
        coEvery { apiService.closeShift("venue-1", "c000000000000000000000031", capture(request)) } returns Response.success(successfulClose())
        repository.closeShift("venue-1", "c000000000000000000000031", CashReconciliationAction.COUNTED, BigDecimal("0E+7"))
        repository.sincronizarCierresLocales("venue-1")
        assertThat(request.captured.cashReconciliationAction).isEqualTo(CashReconciliationAction.COUNTED)
        assertThat(request.captured.countedCash).isEqualTo("0.00")
    }
    @Test fun `local projection preserves count without claiming remote reconciliation`() = runTest {
        val result = repository.closeShift("venue-1", "c000000000000000000000031", CashReconciliationAction.COUNTED, BigDecimal("6000.00"))
        val shift = result.getOrThrow()
        assertThat(shift.financialTotalsKnown).isFalse()
        assertThat(shift.reconciliation).isNull()
        assertThat(shift.cashDifference).isNull()
        assertThat(database.localShiftSessionDao().ultima("venue-1")!!.countedCash).isEqualTo("6000.00")
        coVerify(exactly = 0) { apiService.closeShift(any(), any(), any()) }
        val row = database.localShiftSessionDao().ultima("venue-1")!!
        val response = successfulClose(shiftDto(cashDeclared = "6000.00", cashDifference = "-25.50"),
            CashReconciliationDto(CashReconciliationOutcome.APPLIED, "6000.00", "-25.50")).copy(
                operation = ShiftOperationDto(row.closeOperationId, "CLOSE", "APPLIED", closedAtLocal = row.closedAtLocal, submittedCountedCash = row.countedCash))
        coEvery { apiService.closeShift("venue-1", "c000000000000000000000031", any()) } returns Response.success(response)
        repository.sincronizarCierresLocales("venue-1")
        val evidence = com.google.gson.Gson().fromJson(database.localShiftSessionDao().ultima("venue-1")!!.closeEvidenceJson, ShiftResponse::class.java)
        assertThat(evidence.reconciliation?.outcome).isEqualTo(CashReconciliationOutcome.APPLIED)
        assertThat(evidence.reconciliation?.countedCash).isEqualTo("6000.00")
        assertThat(evidence.reconciliation?.cashDifference).isEqualTo("-25.50")
        assertThat(evidence.data.cashDeclared).isEqualTo("6000.00")
        assertThat(evidence.data.cashDifference).isEqualTo("-25.50")
    }
    @Test fun `409 recovers exact operation without history guess or second close POST`() = runTest {
        coEvery { apiService.closeShift(any(), any(), any()) } returns httpError(409)
        repository.closeShift("venue-1", "c000000000000000000000031")
        repository.sincronizarCierresLocales("venue-1")
        val row = database.localShiftSessionDao().ultima("venue-1")!!
        coEvery { apiService.getShiftOperation("venue-1", row.closeOperationId!!) } returns Response.success(
            ShiftOperationResponse(true, ShiftOperationDto(operationId = row.closeOperationId, kind = "CLOSE", state = "APPLIED", closedAtLocal = row.closedAtLocal),
                ShiftOperationShiftDto(id = "c000000000000000000000031", status = "CLOSED")))
        repository.sincronizarCierresLocales("venue-1")
        assertThat(database.localShiftSessionDao().ultima("venue-1")!!.closeStatus).isEqualTo("CONFIRMED")
        coVerify(exactly = 1) { apiService.closeShift("venue-1", "c000000000000000000000031", any()) }
        coVerify(exactly = 1) { apiService.getShiftOperation("venue-1", row.closeOperationId!!) }
        coVerify(exactly = 0) { apiService.getShiftHistory(any(), any(), any()) }
    }
    @Test fun `409 unproven operation keeps original close pending`() = runTest {
        coEvery { apiService.closeShift(any(), any(), any()) } returns httpError(409)
        repository.closeShift("venue-1", "c000000000000000000000031", CashReconciliationAction.COUNTED, BigDecimal("61.25"))
        repository.sincronizarCierresLocales("venue-1")
        val row = database.localShiftSessionDao().ultima("venue-1")!!
        assertThat(row.closeStatus).isEqualTo("PENDING")
        assertThat(row.countedCash).isEqualTo("61.25")
        assertThat(row.closeEvidenceJson).isNull()
        coVerify(exactly = 1) { apiService.closeShift(any(), any(), any()) }
        coVerify(exactly = 0) { apiService.getShiftHistory(any(), any(), any()) }
    }
    @Test fun `400 remains visible review without inventing history reconciliation`() = runTest {
        coEvery { apiService.closeShift(any(), any(), any()) } returns httpError(400)
        repository.closeShift("venue-1", "c000000000000000000000031", CashReconciliationAction.COUNTED, BigDecimal("61.25"))
        repository.sincronizarCierresLocales("venue-1")
        val row = database.localShiftSessionDao().ultima("venue-1")!!
        assertThat(row.closeStatus).isEqualTo("REVIEW")
        assertThat(row.countedCash).isEqualTo("61.25")
        assertThat(row.closeOperationId).isNotEmpty()
        coVerify(exactly = 1) { apiService.closeShift(any(), any(), any()) }
        coVerify(exactly = 0) { apiService.getShiftHistory(any(), any(), any()) }
    }
    @Test fun `abrir el turno con exito avisa por shiftChanges`() = runTest {
        val avisos = mutableListOf<Unit>()
        backgroundScope.launch { repository.shiftChanges.collect { avisos += it } }; runCurrent()
        val result = repository.openShift("venue-1", "staff-1", 500.0); runCurrent()
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(avisos).hasSize(1)
        coVerify(exactly = 0) { apiService.openShift(any(), any()) }
    }
    @Test fun `si el commit de apertura falla no se avisa nada`() = runTest {
        val avisos = mutableListOf<Unit>()
        backgroundScope.launch { repository.shiftChanges.collect { avisos += it } }; runCurrent()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER task6_no_open BEFORE INSERT ON local_shift_sessions BEGIN SELECT RAISE(ABORT,'no local commit'); END")
        val result = repository.openShift("venue-1", "staff-1", 500.0); runCurrent()
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(avisos).isEmpty()
        assertThat(database.localShiftSessionDao().contar("venue-1")).isEqualTo(0)
    }
    @Test fun `cerrar el turno con exito avisa por shiftChanges`() = runTest {
        val avisos = mutableListOf<Unit>()
        backgroundScope.launch { repository.shiftChanges.collect { avisos += it } }; runCurrent()
        repository.closeShift("venue-1", "c000000000000000000000031"); runCurrent()
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
        id = "c000000000000000000000031",
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

    @Test fun `P1 devolucion sin registrar permite conteo durable pero impide POST del coordinador`() = runTest {
        database.pendingRefundDao().insertRow(reembolsoPendiente().toEntity())
        val result = repository.closeShift("venue-1", "c000000000000000000000031")
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(database.localShiftSessionDao().ultima("venue-1")!!.localStatus).isEqualTo("CLOSED")
        repository.sincronizarCierresLocales("venue-1")
        coVerify(exactly = 0) { apiService.closeShift(any(), any(), any()) }
    }

    @Test fun `P1 si Room no se puede leer no guarda ni envia un cierre a ciegas`() = runTest {
        database.close()
        val result = repository.closeShift("venue-1", "c000000000000000000000031")
        assertThat(result).isInstanceOf(Result.Error::class.java)
        coVerify(exactly = 0) { apiService.closeShift(any(), any(), any()) }
    }
}
