package com.jaac.avoqado_tpv.core.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentQueueStateManagerTest {

    private lateinit var manager: PaymentQueueStateManager

    @Before
    fun setup() {
        manager = PaymentQueueStateManager()
    }

    // ========================================
    // INITIAL STATE TESTS
    // ========================================

    @Test
    fun `initial state has zero counts`() {
        val state = manager.queueState.value
        assertThat(state.pendingCount).isEqualTo(0)
        assertThat(state.failedCount).isEqualTo(0)
    }

    @Test
    fun `initial state hasAnyPayments is false`() {
        assertThat(manager.queueState.value.hasAnyPayments).isFalse()
    }

    @Test
    fun `initial state totalCount is zero`() {
        assertThat(manager.queueState.value.totalCount).isEqualTo(0)
    }

    // ========================================
    // REFRESH COUNTS TESTS
    // ========================================

    @Test
    fun `refreshCounts updates pending and failed counts`() = runTest {
        manager.refreshPaymentCounts(pendingCount = 3, failedCount = 2)

        val state = manager.queueState.value
        assertThat(state.pendingCount).isEqualTo(3)
        assertThat(state.failedCount).isEqualTo(2)
    }

    @Test
    fun `refreshCounts sets hasAnyPayments true when pending`() = runTest {
        manager.refreshPaymentCounts(pendingCount = 1, failedCount = 0)
        assertThat(manager.queueState.value.hasAnyPayments).isTrue()
    }

    @Test
    fun `refreshCounts sets hasAnyPayments true when failed`() = runTest {
        manager.refreshPaymentCounts(pendingCount = 0, failedCount = 1)
        assertThat(manager.queueState.value.hasAnyPayments).isTrue()
    }

    @Test
    fun `refreshCounts computes totalCount correctly`() = runTest {
        manager.refreshPaymentCounts(pendingCount = 5, failedCount = 3)
        assertThat(manager.queueState.value.totalCount).isEqualTo(8)
    }

    @Test
    fun `refreshCounts does not emit when state unchanged`() = runTest {
        manager.refreshPaymentCounts(pendingCount = 2, failedCount = 1)
        val firstState = manager.queueState.value

        // Same values again — should be same object reference (no emission)
        manager.refreshPaymentCounts(pendingCount = 2, failedCount = 1)
        val secondState = manager.queueState.value

        assertThat(firstState).isEqualTo(secondState)
    }

    // ========================================
    // RESET TESTS
    // ========================================

    @Test
    fun `reset clears all counts`() = runTest {
        manager.refreshPaymentCounts(pendingCount = 5, failedCount = 3)
        assertThat(manager.queueState.value.hasAnyPayments).isTrue()

        manager.reset()

        val state = manager.queueState.value
        assertThat(state.pendingCount).isEqualTo(0)
        assertThat(state.failedCount).isEqualTo(0)
        assertThat(state.hasAnyPayments).isFalse()
    }
    // ========================================
    // REFRESCO PARCIAL — QA Nexgo N86 (7-sep-2026)
    // Inicio y Salud del aparato sólo cuentan PAGOS; antes su refresco ponía en cero
    // las devoluciones que el worker acababa de informar.
    // ========================================

    @Test
    fun `refreshPaymentCounts conserva los conteos de devoluciones`() = runTest {
        manager.refreshCounts(pendingCount = 1, failedCount = 1, pendingRefundCount = 3, failedRefundCount = 2)

        manager.refreshPaymentCounts(pendingCount = 5, failedCount = 0)

        val state = manager.queueState.value
        assertThat(state.pendingCount).isEqualTo(5)
        assertThat(state.failedCount).isEqualTo(0)
        assertThat(state.pendingRefundCount).isEqualTo(3)
        assertThat(state.failedRefundCount).isEqualTo(2)
    }

    @Test
    fun `refreshRefundCounts conserva los conteos de pagos`() = runTest {
        manager.refreshCounts(pendingCount = 4, failedCount = 1, pendingRefundCount = 0, failedRefundCount = 0)

        manager.refreshRefundCounts(pendingRefundCount = 1, failedRefundCount = 1)

        val state = manager.queueState.value
        assertThat(state.pendingCount).isEqualTo(4)
        assertThat(state.failedCount).isEqualTo(1)
        assertThat(state.pendingRefundCount).isEqualTo(1)
        assertThat(state.failedRefundCount).isEqualTo(1)
        assertThat(state.totalCount).isEqualTo(7)
    }
}
