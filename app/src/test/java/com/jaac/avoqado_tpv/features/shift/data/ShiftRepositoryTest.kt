package com.jaac.avoqado_tpv.features.shift.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.shift.data.dto.CashReconciliationDto
import com.jaac.avoqado_tpv.features.shift.data.dto.CloseShiftRequest
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
    private lateinit var repository: ShiftRepository

    @Before
    fun setUp() {
        clearMocks(apiService, secureStorage, cachedShiftDao)
        every { secureStorage.isShiftSystemEnabled() } returns true
        repository = ShiftRepository(apiService, secureStorage, cachedShiftDao)
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

    private fun httpError(code: Int): Response<ShiftResponse> = Response.error(
        code,
        "{}".toResponseBody("application/json".toMediaTypeOrNull())
    )
}
