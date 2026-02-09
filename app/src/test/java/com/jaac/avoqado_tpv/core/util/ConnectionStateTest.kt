package com.jaac.avoqado_tpv.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for ConnectionState data class computed properties
 * and ConnectionStateManager hysteresis logic.
 *
 * NOTE: ConnectionStateManager skips the first measurement (cold TCP+TLS is always slow).
 * Tests that exercise hysteresis must call `warmUp(manager)` first so the slow measurement
 * being tested is treated as the second (warm) measurement.
 */
class ConnectionStateTest {

    /** Send a fast warmup measurement so subsequent calls are treated as "warm" */
    private fun warmUp(manager: ConnectionStateManager) {
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 200)
    }

    // ========================================
    // ConnectionState DATA CLASS TESTS
    // ========================================

    @Test
    fun `isFullyConnected when both internet and server connected`() {
        val state = ConnectionState(hasInternet = true, hasServer = true)
        assertThat(state.isFullyConnected).isTrue()
    }

    @Test
    fun `isFullyConnected false when no internet`() {
        val state = ConnectionState(hasInternet = false, hasServer = true)
        assertThat(state.isFullyConnected).isFalse()
    }

    @Test
    fun `isFullyConnected false when no server`() {
        val state = ConnectionState(hasInternet = true, hasServer = false)
        assertThat(state.isFullyConnected).isFalse()
    }

    @Test
    fun `hasAnyIssue false when fully connected`() {
        val state = ConnectionState(hasInternet = true, hasServer = true)
        assertThat(state.hasAnyIssue).isFalse()
    }

    @Test
    fun `hasAnyIssue true when no internet`() {
        val state = ConnectionState(hasInternet = false, hasServer = true)
        assertThat(state.hasAnyIssue).isTrue()
    }

    @Test
    fun `default state is connected with no latency and not slow`() {
        val state = ConnectionState()
        assertThat(state.hasInternet).isTrue()
        assertThat(state.hasServer).isTrue()
        assertThat(state.latencyMs).isNull()
        assertThat(state.isSlowConnection).isFalse()
    }

    @Test
    fun `isSlowConnection reflects explicit field value`() {
        val slow = ConnectionState(hasInternet = true, hasServer = true, latencyMs = 500, isSlowConnection = true)
        assertThat(slow.isSlowConnection).isTrue()

        val fast = ConnectionState(hasInternet = true, hasServer = true, latencyMs = 5000, isSlowConnection = false)
        assertThat(fast.isSlowConnection).isFalse()
    }

    // ========================================
    // ConnectionStateManager HYSTERESIS TESTS
    // ========================================

    @Test
    fun `first measurement is ignored for slow detection`() {
        val manager = ConnectionStateManager()
        // First measurement even at 10s should NOT trigger slow (cold start)
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 10_000)
        assertThat(manager.connectionState.value.isSlowConnection).isFalse()
    }

    @Test
    fun `manager sets slow when warm latency exceeds threshold`() {
        val manager = ConnectionStateManager()
        warmUp(manager)
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 5000)
        assertThat(manager.connectionState.value.isSlowConnection).isTrue()
    }

    @Test
    fun `manager does not set slow when latency below threshold`() {
        val manager = ConnectionStateManager()
        warmUp(manager)
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 4999)
        assertThat(manager.connectionState.value.isSlowConnection).isFalse()
    }

    @Test
    fun `manager keeps slow during cooldown after spike`() {
        val manager = ConnectionStateManager()
        warmUp(manager)

        // Slow measurement
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 6000)
        assertThat(manager.connectionState.value.isSlowConnection).isTrue()

        // Fast measurement (but within cooldown window)
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 500)
        assertThat(manager.connectionState.value.isSlowConnection).isTrue()
    }

    @Test
    fun `manager keeps slow for multiple fast updates during cooldown`() {
        val manager = ConnectionStateManager()
        warmUp(manager)

        // Slow spike
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 7000)
        assertThat(manager.connectionState.value.isSlowConnection).isTrue()

        // Multiple fast updates — still within cooldown
        repeat(5) {
            manager.updateState(hasInternet = true, hasServer = true, latencyMs = 300)
        }
        assertThat(manager.connectionState.value.isSlowConnection).isTrue()
    }

    @Test
    fun `manager stores latency correctly`() {
        val manager = ConnectionStateManager()
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 3743)
        assertThat(manager.connectionState.value.latencyMs).isEqualTo(3743)
    }

    @Test
    fun `resetToConnected clears slow state`() {
        val manager = ConnectionStateManager()
        warmUp(manager)

        // Trigger slow
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 6000)
        assertThat(manager.connectionState.value.isSlowConnection).isTrue()

        // Reset
        manager.resetToConnected()
        assertThat(manager.connectionState.value.isSlowConnection).isFalse()
        assertThat(manager.connectionState.value.latencyMs).isNull()
    }

    @Test
    fun `no slow after reset even with fast measurement`() {
        val manager = ConnectionStateManager()
        warmUp(manager)

        // Trigger slow then reset
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 6000)
        manager.resetToConnected()

        // After reset, counter resets — next is "first" measurement again, so ignored
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 400)
        assertThat(manager.connectionState.value.isSlowConnection).isFalse()
    }

    @Test
    fun `boundary test at exactly threshold`() {
        val manager = ConnectionStateManager()
        warmUp(manager)

        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 4999)
        assertThat(manager.connectionState.value.isSlowConnection).isFalse()

        manager.resetToConnected()
        warmUp(manager)

        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 5000)
        assertThat(manager.connectionState.value.isSlowConnection).isTrue()
    }

    @Test
    fun `null latency does not trigger slow`() {
        val manager = ConnectionStateManager()
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = null)
        assertThat(manager.connectionState.value.isSlowConnection).isFalse()
    }

    @Test
    fun `null latency after slow spike keeps slow during cooldown`() {
        val manager = ConnectionStateManager()
        warmUp(manager)

        // Slow spike
        manager.updateState(hasInternet = true, hasServer = true, latencyMs = 6000)
        assertThat(manager.connectionState.value.isSlowConnection).isTrue()

        // Null latency update (e.g., error) — still in cooldown
        manager.updateState(hasInternet = true, hasServer = false, latencyMs = null)
        assertThat(manager.connectionState.value.isSlowConnection).isTrue()
    }
}
