package com.jaac.avoqado_tpv.features.payments.presentation

import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.payments.domain.models.PaginatedPayments
import com.jaac.avoqado_tpv.features.payments.domain.models.Payment
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentStatus
import com.jaac.avoqado_tpv.features.payments.domain.repository.PaymentRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsViewModelHistoryTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val repository = mockk<PaymentRepository>()
    private val storage = mockk<SecureStorage>()
    private val permissions = mockk<PermissionsRepository>()

    private fun model(canRefund: Boolean = false): PaymentsViewModel {
        coEvery { storage.getVenueId() } returns "venue-history"
        coEvery { storage.getSerialNumber() } returns "TEST-HISTORY"
        coEvery { permissions.hasPermission(any()) } returns canRefund
        return PaymentsViewModel(repository, storage, permissions, mockk<PrinterManager>(relaxed = true),
            clock = java.time.Clock.fixed(Instant.parse("2026-10-13T14:00:00Z"), java.time.ZoneOffset.UTC))
    }

    private fun payment(id: String, amount: String, order: String = "order-shared") = Payment(
        id = id, orderId = order, orderNumber = "120", venueId = "venue-history",
        amount = BigDecimal(amount), tipAmount = BigDecimal.ZERO, totalAmount = BigDecimal(amount),
        method = PaymentMethod.CASH, processedBy = null, createdAt = Instant.parse("2026-10-13T13:08:00Z"),
        status = PaymentStatus.COMPLETED, tableName = null,
    )

    @Test fun `partial payments retain distinct identities when a refund shares their order`() = runTest {
        val first = payment("partial-1", "50").copy(refundedAmount = BigDecimal("20"))
        val second = payment("partial-2", "70").copy(refundedAmount = BigDecimal.ZERO)
        val refund = payment("refund-1", "-20")
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } returns
            Result.Success(PaginatedPayments(listOf(first, second, refund), 3, 1, 20))
        val vm = model()
        advanceUntilIdle()
        val rows = (vm.state.value as PaymentsState.Success).payments
        assertEquals(listOf("partial-1", "partial-2"), rows.map { it.id })
        assertEquals(listOf(BigDecimal("50"), BigDecimal("70")), rows.map { it.amount })
        assertEquals(listOf(BigDecimal("20"), BigDecimal.ZERO), rows.map { it.refundedAmount })
    }

    @Test fun `overlapping pages display a payment only once`() = runTest {
        val first = payment("first", "50", "order-1")
        val second = payment("second", "70", "order-2")
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.Success(PaginatedPayments(listOf(first), 40, 1, 20)),
            Result.Success(PaginatedPayments(listOf(first, second), 40, 2, 20)),
        )
        val vm = model()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val rows = (vm.state.value as PaymentsState.Success).payments
        assertEquals(listOf("first", "second"), rows.map { it.id })
    }

    @Test fun `failed next page can be retried without skipping payments`() = runTest {
        val pages = mutableListOf<Int>()
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } coAnswers {
            val page = secondArg<Int>()
            pages += page
            when (pages.size) {
                1 -> Result.Success(PaginatedPayments(listOf(payment("first", "50")), 60, 1, 20))
                2 -> Result.Error(com.jaac.avoqado_tpv.core.domain.models.ApiException.NetworkError(IllegalStateException("connection lost")))
                else -> Result.Success(PaginatedPayments(listOf(payment("second", "70", "order-2")), 60, page, 20))
            }
        }
        val vm = model()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentsState.Success)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 2), pages)
        assertEquals(2, (vm.state.value as PaymentsState.Success).currentPage)
    }

    @Test fun `ambiguous legacy refund stays separate from partial payments`() = runTest {
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } returns
            Result.Success(PaginatedPayments(listOf(payment("one", "50"), payment("two", "70"), payment("refund", "-20")), 3, 1, 20))
        val vm = model(); advanceUntilIdle()
        val rows = (vm.state.value as PaymentsState.Success).payments
        assertEquals(listOf("one", "two", "refund"), rows.map { it.id })
        assertTrue(rows.take(2).all { it.refundedAmount == null && !it.isFullyRefunded })
    }

    @Test fun `refunds on later pages accumulate from original evidence`() = runTest {
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.Success(PaginatedPayments(listOf(payment("one", "100"), payment("refund-1", "-20")), 40, 1, 20)),
            Result.Success(PaginatedPayments(listOf(payment("refund-2", "-30")), 40, 2, 20)),
        )
        val vm = model(); advanceUntilIdle(); vm.loadMore(); advanceUntilIdle()
        assertEquals(BigDecimal("50"), (vm.state.value as PaymentsState.Success).payments.single().refundedAmount)
    }

    @Test fun `overlapping page replaces stale refund evidence`() = runTest {
        val first = payment("one", "100").copy(refundedAmount = BigDecimal("20"))
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.Success(PaginatedPayments(listOf(first), 40, 1, 20)),
            Result.Success(PaginatedPayments(listOf(first.copy(refundedAmount = BigDecimal.ZERO)), 40, 2, 20)),
        )
        val vm = model(); advanceUntilIdle(); vm.loadMore(); advanceUntilIdle()
        assertEquals(BigDecimal.ZERO, (vm.state.value as PaymentsState.Success).payments.single().refundedAmount)
    }

    @Test fun `late next page does not overwrite refreshed history`() = runTest {
        val delayed = CompletableDeferred<Result<PaginatedPayments>>()
        var calls = 0
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } coAnswers {
            calls++
            when(calls) {
                1 -> Result.Success(PaginatedPayments(listOf(payment("old", "50")), 40, 1, 20))
                2 -> delayed.await()
                else -> Result.Success(PaginatedPayments(listOf(payment("fresh", "70")), 1, 1, 20))
            }
        }
        val vm = model(); advanceUntilIdle(); vm.loadMore(); runCurrent(); vm.refresh(); advanceUntilIdle()
        delayed.complete(Result.Success(PaginatedPayments(listOf(payment("old-page2", "80")), 40, 2, 20)))
        advanceUntilIdle()
        assertEquals(listOf("fresh"), (vm.state.value as PaymentsState.Success).payments.map { it.id })
        assertEquals(1, (vm.state.value as PaymentsState.Success).currentPage)
    }

    @Test fun `pending refund remains visible and blocks a second bank execution`() = runTest {
        val pax = com.jaac.avoqado_tpv.BuildConfig.ENABLE_PAX_SDK
        val original = payment("bank", "50").copy(method = PaymentMethod.CARD,
            refundedAmount = BigDecimal.ZERO, merchantAccountId = "merchant", blumonOperationNumber = if(pax) 123 else null,
            processor = if(pax) "BLUMON" else "ANGELPAY")
        val pending = payment("refund", "-50").copy(status = PaymentStatus.PENDING)
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } returns
            Result.Success(PaginatedPayments(listOf(original, pending), 2, 1, 20))
        val vm = model(canRefund = true); advanceUntilIdle()
        val rows = (vm.state.value as PaymentsState.Success).payments
        assertEquals(listOf("bank", "refund"), rows.map { it.id })
        assertEquals(BigDecimal.ZERO, rows.first().refundedAmount)
        assertTrue(!rows.first().isFullyRefunded)
        assertTrue(!vm.getRefundAvailability(rows.first()).canRefund)
        if(!pax) assertTrue(vm.getRefundAvailability(rows.first(), setOf("bank")).canRefund)
    }

    @Test fun `new filter clears a superseded refresh spinner`() = runTest {
        val delayed = CompletableDeferred<Result<PaginatedPayments>>()
        var calls = 0
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } coAnswers {
            calls++
            if(calls == 2) delayed.await() else Result.Success(PaginatedPayments(listOf(payment("one", "50")), 40, 1, 20))
        }
        val vm = model(); advanceUntilIdle(); vm.refresh(); runCurrent(); vm.loadPayments(); advanceUntilIdle()
        delayed.complete(Result.Success(PaginatedPayments(emptyList(), 0, 1, 20))); advanceUntilIdle()
        assertTrue(!vm.isRefreshing.value)
        assertTrue(vm.state.value is PaymentsState.Success)
    }

    @Test fun `failed refresh restores a reattemptable state during next page load`() = runTest {
        val delayed = CompletableDeferred<Result<PaginatedPayments>>()
        var calls = 0
        coEvery { repository.getPaymentHistory(any(), any(), any(), any(), any(), any()) } coAnswers {
            calls++
            when(calls) {
                1 -> Result.Success(PaginatedPayments(listOf(payment("one", "50")), 40, 1, 20))
                2 -> delayed.await()
                else -> Result.Error(com.jaac.avoqado_tpv.core.domain.models.ApiException.NetworkError(IllegalStateException("refresh failed")))
            }
        }
        val vm = model(); advanceUntilIdle(); vm.loadMore(); runCurrent(); vm.refresh(); advanceUntilIdle()
        delayed.complete(Result.Success(PaginatedPayments(emptyList(), 40, 2, 20))); advanceUntilIdle()
        assertTrue(vm.state.value is PaymentsState.Success)
        assertTrue((vm.state.value as PaymentsState.Success).hasMore)
    }
}
