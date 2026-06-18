package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.MySaleItem
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.MySalesData
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.MySalesResponse
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.SalesToReviewData
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.SalesToReviewResponse
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
 *   - Verification status enum mapping (PENDING/COMPLETED/FAILED/REJECTED/null)
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

    private fun stubSalesToReview(items: List<MySaleItem>) {
        val body = SalesToReviewResponse(
            success = true,
            data = SalesToReviewData(totalToReview = items.size, sales = items)
        )
        coEvery { apiService.getSalesToReview() } returns Response.success(body)
    }

    private fun createViewModel(): MySalesViewModel = MySalesViewModel(apiService, socketManager)

    @Test
    fun `parseVerificationStatus maps backend strings to enum`() {
        assertThat(MySalesViewModel.parseVerificationStatus(null)).isEqualTo(VerificationReviewStatus.NONE)
        assertThat(MySalesViewModel.parseVerificationStatus("PENDING")).isEqualTo(VerificationReviewStatus.PENDING)
        assertThat(MySalesViewModel.parseVerificationStatus("PROCESSING")).isEqualTo(VerificationReviewStatus.PENDING)
        assertThat(MySalesViewModel.parseVerificationStatus("COMPLETED")).isEqualTo(VerificationReviewStatus.COMPLETED)
        assertThat(MySalesViewModel.parseVerificationStatus("FAILED")).isEqualTo(VerificationReviewStatus.FAILED)
        assertThat(MySalesViewModel.parseVerificationStatus("REJECTED")).isEqualTo(VerificationReviewStatus.REJECTED)
        // Unknown values fall back to NONE rather than crash
        assertThat(MySalesViewModel.parseVerificationStatus("FUTURE_STATUS")).isEqualTo(VerificationReviewStatus.NONE)
    }

    @Test
    fun `REJECTED verification surfaces as terminal — no rejection reasons, note preserved`() = runTest {
        stubMySales(
            listOf(
                saleItem(
                    verificationStatus = "REJECTED",
                    rejectionReasons = null,
                    reviewNotes = "No se logró la portabilidad, cliente desistió"
                )
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        val sale = vm.uiState.value.salesByDay.values.flatten().first()
        assertThat(sale.verificationStatus).isEqualTo(VerificationReviewStatus.REJECTED)
        assertThat(sale.rejectionReasons).isEmpty()
        assertThat(sale.reviewNotes).isEqualTo("No se logró la portabilidad, cliente desistió")

        vm.viewModelScope.cancel()
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

    // ──────────────────────────────────────────────────────────────────────
    // Regression: the pinned "Por revisar" cluster (salesToReview) must not be
    // clobbered by loadSales(). It is a cross-month feed owned by
    // loadSalesToReview(); loadSales() rebuilding the whole UI state used to
    // reset it to emptyList(), so navigating months / refreshing the month
    // made the cluster (and its legend) silently disappear. (Isaac, 2026-06-16)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `navigateMonth keeps the Por revisar cluster — loadSales must not clobber salesToReview`() = runTest {
        stubMySales(listOf(saleItem(verificationStatus = "COMPLETED")))
        stubSalesToReview(listOf(saleItem(id = "rev-1", verificationStatus = "FAILED")))

        val vm = createViewModel()
        advanceUntilIdle()

        // Precondition: the cross-month cluster loaded during init.
        assertThat(vm.uiState.value.salesToReview).hasSize(1)

        // Navigating months reloads only the month list; the pinned cluster must survive.
        vm.navigateMonth(-1)
        advanceUntilIdle()

        assertThat(vm.uiState.value.salesToReview).hasSize(1)
        assertThat(vm.uiState.value.salesToReview.first().id).isEqualTo("rev-1")

        vm.viewModelScope.cancel()
    }

    @Test
    fun `navigateMonth re-syncs the Por revisar cluster from the server`() = runTest {
        stubMySales(listOf(saleItem(verificationStatus = "COMPLETED")))
        stubSalesToReview(listOf(saleItem(id = "rev-1", verificationStatus = "FAILED")))

        val vm = createViewModel()
        advanceUntilIdle()
        // Cluster fetched once during init.
        coVerify(exactly = 1) { apiService.getSalesToReview() }

        // Changing months must re-fetch the cross-month cluster so a stale snapshot
        // (a sale rejected while a socket event was missed) can't linger.
        vm.navigateMonth(-1)
        advanceUntilIdle()

        coVerify(atLeast = 2) { apiService.getSalesToReview() }

        vm.viewModelScope.cancel()
    }

    @Test
    fun `refreshing the current month preserves the Por revisar cluster`() = runTest {
        stubMySales(listOf(saleItem(verificationStatus = "COMPLETED")))
        stubSalesToReview(
            listOf(
                saleItem(id = "rev-1", verificationStatus = "FAILED"),
                saleItem(id = "rev-2", verificationStatus = "PENDING"),
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.salesToReview).hasSize(2)

        // A plain month reload (same month) must not wipe the cluster.
        vm.loadSales("2026-04")
        advanceUntilIdle()

        assertThat(vm.uiState.value.salesToReview).hasSize(2)

        vm.viewModelScope.cancel()
    }
}
