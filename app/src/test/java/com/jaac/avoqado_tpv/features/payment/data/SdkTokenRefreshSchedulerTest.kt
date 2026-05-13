package com.jaac.avoqado_tpv.features.payment.data

import com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardParams
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.util.CriticalNetworkOperationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Tests every decision branch of [SdkTokenRefreshScheduler.runCheck].
 *
 * The scheduler MUST only invalidate the SDK init flag when ALL of these are true:
 *  - The flag is currently true.
 *  - No critical network operation is in progress.
 *  - A last-init timestamp exists.
 *  - The timestamp is older than 23h.
 *
 * Any other outcome must be a no-op for the flag and observable through
 * [SdkTokenRefreshScheduler.lastRefreshOutcome] for diagnostics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkTokenRefreshSchedulerTest {

    private lateinit var initManager: InitializationManager
    private lateinit var stopDetectCard: StopDetectCardUseCase
    private lateinit var secureStorage: SecureStorage
    private lateinit var critical: CriticalNetworkOperationManager
    private lateinit var scheduler: SdkTokenRefreshScheduler

    // Backing flow for InitializationManager.isInitialized (StateFlow is read-only externally).
    private lateinit var isInitializedFlow: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        isInitializedFlow = MutableStateFlow(true)
        initManager = mockk(relaxed = true)
        every { initManager.isInitialized } returns isInitializedFlow

        stopDetectCard = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        critical = mockk(relaxed = true)
        every { critical.isAnyCriticalOperationInProgress() } returns false

        scheduler = SdkTokenRefreshScheduler(initManager, stopDetectCard, secureStorage, critical)
    }

    private fun aLongTimeAgo() = System.currentTimeMillis() -
        (SdkTokenRefreshScheduler.STALE_THRESHOLD_HOURS + 1) * 60 * 60 * 1000L

    private fun recentlyAgo() = System.currentTimeMillis() -
        (SdkTokenRefreshScheduler.STALE_THRESHOLD_HOURS - 1) * 60 * 60 * 1000L

    // ── HAPPY PATH ──────────────────────────────────────────────────────────────────────

    @Test
    fun `idle and stale invalidates flag and calls stop card`() = runTest {
        every { secureStorage.getLastBlumonInitTimestamp() } returns aLongTimeAgo()

        scheduler.runCheck()

        assertThat(scheduler.lastRefreshOutcome.value)
            .isInstanceOf(SdkTokenRefreshScheduler.RefreshOutcome.Invalidated::class.java)
        coVerify(exactly = 1) { stopDetectCard.runInfallible(any<StopDetectCardParams>()) }
        verify(exactly = 1) { initManager.invalidateForRefresh() }
    }

    // ── ALL SKIP BRANCHES (no flag mutation, no stop card) ──────────────────────────────

    @Test
    fun `skips when flag is already false`() = runTest {
        isInitializedFlow.value = false
        every { secureStorage.getLastBlumonInitTimestamp() } returns aLongTimeAgo()

        scheduler.runCheck()

        assertThat(scheduler.lastRefreshOutcome.value)
            .isEqualTo(SdkTokenRefreshScheduler.RefreshOutcome.SkippedFlagAlreadyFalse)
        verify(exactly = 0) { initManager.invalidateForRefresh() }
        coVerify(exactly = 0) { stopDetectCard.runInfallible(any<StopDetectCardParams>()) }
    }

    @Test
    fun `skips when payment in progress`() = runTest {
        every { critical.isAnyCriticalOperationInProgress() } returns true
        every { secureStorage.getLastBlumonInitTimestamp() } returns aLongTimeAgo()

        scheduler.runCheck()

        assertThat(scheduler.lastRefreshOutcome.value)
            .isEqualTo(SdkTokenRefreshScheduler.RefreshOutcome.SkippedCriticalInProgress)
        verify(exactly = 0) { initManager.invalidateForRefresh() }
        coVerify(exactly = 0) { stopDetectCard.runInfallible(any<StopDetectCardParams>()) }
    }

    @Test
    fun `skips when no init timestamp recorded`() = runTest {
        every { secureStorage.getLastBlumonInitTimestamp() } returns null

        scheduler.runCheck()

        assertThat(scheduler.lastRefreshOutcome.value)
            .isEqualTo(SdkTokenRefreshScheduler.RefreshOutcome.SkippedNoTimestamp)
        verify(exactly = 0) { initManager.invalidateForRefresh() }
        coVerify(exactly = 0) { stopDetectCard.runInfallible(any<StopDetectCardParams>()) }
    }

    @Test
    fun `skips when init is still fresh under 23h`() = runTest {
        every { secureStorage.getLastBlumonInitTimestamp() } returns recentlyAgo()

        scheduler.runCheck()

        assertThat(scheduler.lastRefreshOutcome.value)
            .isInstanceOf(SdkTokenRefreshScheduler.RefreshOutcome.SkippedFresh::class.java)
        verify(exactly = 0) { initManager.invalidateForRefresh() }
        coVerify(exactly = 0) { stopDetectCard.runInfallible(any<StopDetectCardParams>()) }
    }

    // ── RACE WINDOW ─────────────────────────────────────────────────────────────────────

    @Test
    fun `aborts before invalidation when a critical op starts mid-evaluation`() = runTest {
        every { secureStorage.getLastBlumonInitTimestamp() } returns aLongTimeAgo()
        // First call (before the stale-check) returns false; second call (the re-check
        // right before mutation) returns true to simulate a payment starting in between.
        every { critical.isAnyCriticalOperationInProgress() } returnsMany listOf(false, true)

        scheduler.runCheck()

        assertThat(scheduler.lastRefreshOutcome.value)
            .isEqualTo(SdkTokenRefreshScheduler.RefreshOutcome.SkippedRaceLost)
        verify(exactly = 0) { initManager.invalidateForRefresh() }
        coVerify(exactly = 0) { stopDetectCard.runInfallible(any<StopDetectCardParams>()) }
    }

    // ── RESILIENCE ──────────────────────────────────────────────────────────────────────

    @Test
    fun `still invalidates when stop card throws`() = runTest {
        every { secureStorage.getLastBlumonInitTimestamp() } returns aLongTimeAgo()
        coEvery { stopDetectCard.runInfallible(any<StopDetectCardParams>()) } throws RuntimeException("kernel busy")

        scheduler.runCheck()

        assertThat(scheduler.lastRefreshOutcome.value)
            .isInstanceOf(SdkTokenRefreshScheduler.RefreshOutcome.Invalidated::class.java)
        verify(exactly = 1) { initManager.invalidateForRefresh() }
    }

    @Test
    fun `catches unexpected throwable so loop does not die`() = runTest {
        every { secureStorage.getLastBlumonInitTimestamp() } throws IllegalStateException("disk corrupt")

        scheduler.runCheck()

        assertThat(scheduler.lastRefreshOutcome.value)
            .isInstanceOf(SdkTokenRefreshScheduler.RefreshOutcome.Error::class.java)
        verify(exactly = 0) { initManager.invalidateForRefresh() }
    }
}

