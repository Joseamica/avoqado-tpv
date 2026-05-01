package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.MySaleItem
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.MySalesData
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.MySalesResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Unit tests for back-office review status surfacing in `MySalesViewModel`.
 *
 * Covers:
 *   - Verification status enum mapping (PENDING/COMPLETED/FAILED/null)
 *   - Rejection reason parsing
 *   - Socket event triggers a refetch (real-time refresh from dashboard)
 *   - Backwards compat — null verification fields don't crash
 *   - Edge case: reasons in payload from a future enum value are silently dropped
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MySalesViewModelReviewTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var apiService: ApiService
    private lateinit var socketManager: SocketManager

    private val fakeSocketEvents = MutableSharedFlow<SocketEvent>(replay = 0)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        apiService = mockk(relaxed = true)
        socketManager = mockk(relaxed = true)
        every { socketManager.events } returns fakeSocketEvents
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun saleItem(
        id: String = "ord-1",
        verificationStatus: String? = null,
        rejectionReasons: List<String>? = null,
        reviewNotes: String? = null,
    ) = MySaleItem(
        id = id,
        orderNumber = "SN0001",
        serialNumber = "8952140000001234567",
        categoryName = "SIM Negra",
        price = 100.0,
        date = "2026-04-30T10:00:00.000Z",
        paymentStatus = "COMPLETED",
        isGift = false,
        verificationId = if (verificationStatus != null) "sv-$id" else null,
        verificationStatus = verificationStatus,
        reviewedAt = if (verificationStatus != null) "2026-04-30T11:00:00.000Z" else null,
        reviewNotes = reviewNotes,
        rejectionReasons = rejectionReasons,
        hasPhotos = verificationStatus != null
    )

    private fun stubMySales(items: List<MySaleItem>) {
        val body = MySalesResponse(
            success = true,
            data = MySalesData(month = "2026-04", totalSales = items.size, totalAmount = 0.0, sales = items)
        )
        coEvery { apiService.getMySalesHistory(any(), any(), any()) } returns Response.success(body)
    }

    private fun createViewModel(): MySalesViewModel = MySalesViewModel(apiService, socketManager)

    @Test
    fun `parseVerificationStatus maps backend strings to enum`() {
        assertThat(MySalesViewModel.parseVerificationStatus(null)).isEqualTo(VerificationReviewStatus.NONE)
        assertThat(MySalesViewModel.parseVerificationStatus("PENDING")).isEqualTo(VerificationReviewStatus.PENDING)
        assertThat(MySalesViewModel.parseVerificationStatus("PROCESSING")).isEqualTo(VerificationReviewStatus.PENDING)
        assertThat(MySalesViewModel.parseVerificationStatus("COMPLETED")).isEqualTo(VerificationReviewStatus.COMPLETED)
        assertThat(MySalesViewModel.parseVerificationStatus("FAILED")).isEqualTo(VerificationReviewStatus.FAILED)
        // Unknown values fall back to NONE rather than crash
        assertThat(MySalesViewModel.parseVerificationStatus("FUTURE_STATUS")).isEqualTo(VerificationReviewStatus.NONE)
    }

    @Test
    fun `RejectionReason parseList drops unknown values silently`() {
        val out = RejectionReason.parseList(listOf("REVIEW_PORTABILIDAD", "UNKNOWN_FUTURE", "OTHER"))
        assertThat(out).containsExactly(
            RejectionReason.REVIEW_PORTABILIDAD,
            RejectionReason.OTHER,
        ).inOrder()
    }

    @Test
    fun `legacy backend with null verificationStatus does not crash and exposes NONE`() = runTest {
        stubMySales(listOf(saleItem(verificationStatus = null)))

        val vm = createViewModel()
        advanceUntilIdle()

        val sale = vm.uiState.value.salesByDay.values.flatten().first()
        assertThat(sale.verificationStatus).isEqualTo(VerificationReviewStatus.NONE)
        assertThat(sale.rejectionReasons).isEmpty()
        assertThat(sale.reviewNotes).isNull()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `FAILED verification surfaces rejection reasons and notes`() = runTest {
        stubMySales(
            listOf(
                saleItem(
                    verificationStatus = "FAILED",
                    rejectionReasons = listOf("REVIEW_PORTABILIDAD", "REVIEW_DUPLICATE_VINCULACION"),
                    reviewNotes = "Revisar foto del IFE"
                )
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        val sale = vm.uiState.value.salesByDay.values.flatten().first()
        assertThat(sale.verificationStatus).isEqualTo(VerificationReviewStatus.FAILED)
        assertThat(sale.rejectionReasons).containsExactly(
            RejectionReason.REVIEW_PORTABILIDAD,
            RejectionReason.REVIEW_DUPLICATE_VINCULACION,
        ).inOrder()
        assertThat(sale.reviewNotes).isEqualTo("Revisar foto del IFE")

        vm.viewModelScope.cancel()
    }

    @Test
    fun `COMPLETED verification has no rejection reasons`() = runTest {
        stubMySales(listOf(saleItem(verificationStatus = "COMPLETED")))

        val vm = createViewModel()
        advanceUntilIdle()

        val sale = vm.uiState.value.salesByDay.values.flatten().first()
        assertThat(sale.verificationStatus).isEqualTo(VerificationReviewStatus.COMPLETED)
        assertThat(sale.rejectionReasons).isEmpty()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `socket event SaleVerificationReviewed triggers a refetch of current month`() = runTest {
        stubMySales(listOf(saleItem(verificationStatus = "PENDING")))

        val vm = createViewModel()
        advanceUntilIdle()

        // First load happened in init
        coVerify(exactly = 1) { apiService.getMySalesHistory(any(), any(), any()) }

        // Emit the socket event that the back-office reviewed the sale
        fakeSocketEvents.emit(
            SocketEvent.SaleVerificationReviewed(
                saleVerificationId = "sv-ord-1",
                paymentId = "pay-1",
                status = "COMPLETED",
                reviewedAt = "2026-04-30T12:00:00.000Z",
                reviewNotes = null,
                rejectionReasons = null,
                reviewedBy = "Ada Lovelace"
            )
        )
        advanceUntilIdle()

        // ViewModel should have refetched
        coVerify(atLeast = 2) { apiService.getMySalesHistory(any(), any(), any()) }

        vm.viewModelScope.cancel()
    }

    @Test
    fun `socket events of unrelated types do not trigger refetch`() = runTest {
        stubMySales(listOf(saleItem(verificationStatus = "PENDING")))

        val vm = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { apiService.getMySalesHistory(any(), any(), any()) }

        // Emit a SIM custody event — should be ignored
        fakeSocketEvents.emit(
            SocketEvent.SimCustodyAssignedToPromoter(targetStaffId = "staff-1", count = 5)
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { apiService.getMySalesHistory(any(), any(), any()) }

        vm.viewModelScope.cancel()
    }
}
