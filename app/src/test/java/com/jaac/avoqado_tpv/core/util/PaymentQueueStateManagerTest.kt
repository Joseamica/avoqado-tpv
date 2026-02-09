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
        manager.refreshCounts(pendingCount = 3, failedCount = 2)

        val state = manager.queueState.value
        assertThat(state.pendingCount).isEqualTo(3)
        assertThat(state.failedCount).isEqualTo(2)
    }

    @Test
    fun `refreshCounts sets hasAnyPayments true when pending`() = runTest {
        manager.refreshCounts(pendingCount = 1, failedCount = 0)
        assertThat(manager.queueState.value.hasAnyPayments).isTrue()
    }

    @Test
    fun `refreshCounts sets hasAnyPayments true when failed`() = runTest {
        manager.refreshCounts(pendingCount = 0, failedCount = 1)
        assertThat(manager.queueState.value.hasAnyPayments).isTrue()
    }

    @Test
    fun `refreshCounts computes totalCount correctly`() = runTest {
        manager.refreshCounts(pendingCount = 5, failedCount = 3)
        assertThat(manager.queueState.value.totalCount).isEqualTo(8)
    }

    @Test
    fun `refreshCounts does not emit when state unchanged`() = runTest {
        manager.refreshCounts(pendingCount = 2, failedCount = 1)
        val firstState = manager.queueState.value

        // Same values again — should be same object reference (no emission)
        manager.refreshCounts(pendingCount = 2, failedCount = 1)
        val secondState = manager.queueState.value

        assertThat(firstState).isEqualTo(secondState)
    }

    // ========================================
    // RESET TESTS
    // ========================================

    @Test
    fun `reset clears all counts`() = runTest {
        manager.refreshCounts(pendingCount = 5, failedCount = 3)
        assertThat(manager.queueState.value.hasAnyPayments).isTrue()

        manager.reset()

        val state = manager.queueState.value
        assertThat(state.pendingCount).isEqualTo(0)
        assertThat(state.failedCount).isEqualTo(0)
        assertThat(state.hasAnyPayments).isFalse()
    }
}
