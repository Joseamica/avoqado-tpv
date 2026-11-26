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
    // ── D2 (25-nov): la revisión saltada por un cobro ya no espera una hora ────────────────────

    private val hora = 60L * 60L * 1000L

    @Test
    fun `D2 tras saltarse por un cobro en curso vuelve a mirar a los 2 minutos`() = runTest {
        every { critical.isAnyCriticalOperationInProgress() } returns true
        every { secureStorage.getLastBlumonInitTimestamp() } returns aLongTimeAgo()

        scheduler.runCheck()

        assertThat(scheduler.nextCheckDelayMs()).isEqualTo(SdkTokenRefreshScheduler.RETRY_AFTER_SKIP_MS)
    }

    @Test
    fun `D2 si un cobro empieza a media revision tambien vuelve a mirar a los 2 minutos`() = runTest {
        every { critical.isAnyCriticalOperationInProgress() } returnsMany listOf(false, true)
        every { secureStorage.getLastBlumonInitTimestamp() } returns aLongTimeAgo()

        scheduler.runCheck()

        assertThat(scheduler.lastRefreshOutcome.value).isEqualTo(SdkTokenRefreshScheduler.RefreshOutcome.SkippedRaceLost)
        assertThat(scheduler.nextCheckDelayMs()).isEqualTo(SdkTokenRefreshScheduler.RETRY_AFTER_SKIP_MS)
    }

    @Test
    fun `D2 la primera revision cae justo a las 23 h, no hasta una hora despues`() = runTest {
        val ahora = 1_000_000_000_000L
        every { secureStorage.getLastBlumonInitTimestamp() } returns ahora - (22 * hora + 40 * 60_000L) // 22 h 40 min

        assertThat(scheduler.nextCheckDelayMs(now = ahora)).isEqualTo(20 * 60_000L)
    }

    @Test
    fun `D2 con la llave recien hecha la revision sigue siendo cada hora`() = runTest {
        val ahora = 1_000_000_000_000L
        every { secureStorage.getLastBlumonInitTimestamp() } returns ahora - hora

        assertThat(scheduler.nextCheckDelayMs(now = ahora)).isEqualTo(SdkTokenRefreshScheduler.CHECK_INTERVAL_MS)
    }

    @Test
    fun `D2 con la bandera ya abajo o recien invalidada no hace falta mirar seguido`() = runTest {
        every { secureStorage.getLastBlumonInitTimestamp() } returns aLongTimeAgo()
        scheduler.runCheck() // invalida
        assertThat(scheduler.nextCheckDelayMs()).isEqualTo(SdkTokenRefreshScheduler.CHECK_INTERVAL_MS)

        isInitializedFlow.value = false
        scheduler.runCheck()
        assertThat(scheduler.lastRefreshOutcome.value).isEqualTo(SdkTokenRefreshScheduler.RefreshOutcome.SkippedFlagAlreadyFalse)
        assertThat(scheduler.nextCheckDelayMs()).isEqualTo(SdkTokenRefreshScheduler.CHECK_INTERVAL_MS)
    }

    @Test
    fun `D2 sin sello o tras un error con la llave vieja nunca espera de mas`() = runTest {
        every { secureStorage.getLastBlumonInitTimestamp() } returns null
        assertThat(scheduler.nextCheckDelayMs()).isEqualTo(SdkTokenRefreshScheduler.CHECK_INTERVAL_MS)

        every { secureStorage.getLastBlumonInitTimestamp() } returns aLongTimeAgo()
        every { critical.isAnyCriticalOperationInProgress() } throws IllegalStateException("x")
        scheduler.runCheck()
        assertThat(scheduler.nextCheckDelayMs()).isEqualTo(SdkTokenRefreshScheduler.RETRY_AFTER_SKIP_MS)
    }
}

