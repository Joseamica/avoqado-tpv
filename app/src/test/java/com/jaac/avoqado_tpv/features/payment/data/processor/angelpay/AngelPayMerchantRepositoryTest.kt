package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.MerchantSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AngelPayMerchantRepository] — Task 28 of AngelPay SDK 1.0.5
 * multi-merchant migration. Covers D2 race protection (mutex + charging guard +
 * timeout + multi-tap cancel) and D6 freshness (fetch/refresh wrappers).
 *
 * Uses [UnconfinedTestDispatcher] per repo memory note — repo's StateFlow +
 * `withTimeoutOrNull` combination doesn't play well with the standard scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AngelPayMerchantRepositoryTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var sdkGateway: AngelPaySdkGateway
    private lateinit var cacheDao: AngelPayMerchantCacheDao
    private lateinit var paymentStateProvider: PaymentStateProvider
    private lateinit var repo: AngelPayMerchantRepository

    private val merchantA = MerchantSummary(id = 1, name = "A", affiliationNumber = "1001", isActive = true)
    private val merchantB = MerchantSummary(id = 2, name = "B", affiliationNumber = "1002", isActive = false)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        sdkGateway = mockk(relaxed = true)
        cacheDao = mockk(relaxed = true)
        paymentStateProvider = mockk()
        every { paymentStateProvider.isCharging() } returns false
        // Default: SDK reports no merchants, so the single-merchant short-circuit
        // in switchActiveMerchant (previousActive == null) falls through to the
        // normal switchMerchant path. Tests that exercise the short-circuit
        // override this with a non-empty active list.
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(emptyList())
        repo = AngelPayMerchantRepository(sdkGateway, cacheDao, paymentStateProvider)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `completeInitialSelection on success updates activeAngelPayMerchantId and cache`() = runTest(dispatcher) {
        coEvery { sdkGateway.selectMerchant(merchantA.id, "temp-token") } returns Result.success(Unit)

        val result = repo.completeInitialSelection(merchantA.id, "temp-token")

        assertTrue(result.isSuccess)
        assertEquals(merchantA.id, repo.activeAngelPayMerchantId.value)
        coVerify { cacheDao.markActive(merchantA.id) }
    }

    @Test
    fun `switchActiveMerchant short-circuits when SDK already on target via single-merchant auth`() = runTest(dispatcher) {
        // Single-merchant account: SDK auto-selected merchantA during authenticateSimple
        // Success, bypassing this repo, so _activeAngelPayMerchantId is null. The SDK
        // reports merchantA as the active one. Picking merchantA must NOT call
        // switchMerchant (AngelPay rejects switching to the merchant you're already on).
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(listOf(merchantA))

        val result = repo.switchActiveMerchant(merchantA.id)

        assertTrue(result.isSuccess)
        assertEquals(merchantA.id, repo.activeAngelPayMerchantId.value)
        coVerify { cacheDao.markActive(merchantA.id) }
        coVerify(exactly = 0) { sdkGateway.switchMerchant(any()) }
    }

    @Test
    fun `switchActiveMerchant still switches when SDK active differs from target`() = runTest(dispatcher) {
        // SDK is on merchantA, but cashier picks merchantB → real switch required.
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(listOf(merchantA, merchantB))
        coEvery { sdkGateway.switchMerchant(merchantB.id) } returns Result.success(Unit)

        val result = repo.switchActiveMerchant(merchantB.id)

        assertTrue(result.isSuccess)
        assertEquals(merchantB.id, repo.activeAngelPayMerchantId.value)
        coVerify(exactly = 1) { sdkGateway.switchMerchant(merchantB.id) }
    }

    @Test
    fun `switchActiveMerchant rejected when payment is charging`() = runTest(dispatcher) {
        every { paymentStateProvider.isCharging() } returns true

        val result = repo.switchActiveMerchant(merchantB.id)

        assertTrue(result.isFailure)
        assertEquals(SwitchBlockedDuringChargeError, result.exceptionOrNull())
        coVerify(exactly = 0) { sdkGateway.switchMerchant(any()) }
    }

    @Test
    fun `switchActiveMerchant times out at 8 seconds`() = runTest(dispatcher) {
        coEvery { sdkGateway.switchMerchant(merchantB.id) } coAnswers {
            delay(10_000)
            Result.success(Unit)
        }

        val result = repo.switchActiveMerchant(merchantB.id)

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("expected SwitchTimeoutError, got $err", err is SwitchTimeoutError)
        assertEquals(merchantB.id, (err as SwitchTimeoutError).targetMerchantId)
    }

    @Test
    fun `switchActiveMerchant failure keeps previous activeId`() = runTest(dispatcher) {
        coEvery { sdkGateway.selectMerchant(merchantA.id, any()) } returns Result.success(Unit)
        repo.completeInitialSelection(merchantA.id, "init-token")
        assertEquals(merchantA.id, repo.activeAngelPayMerchantId.value)

        coEvery { sdkGateway.switchMerchant(merchantB.id) } returns
            Result.failure(AngelPayNetworkError())

        val result = repo.switchActiveMerchant(merchantB.id)

        assertTrue(result.isFailure)
        assertEquals(
            "active merchant should remain A after failed switch to B",
            merchantA.id,
            repo.activeAngelPayMerchantId.value,
        )
        coVerify { cacheDao.markActive(merchantA.id) }
    }

    @Test
    fun `multi-tap cancels in-flight switch and starts fresh`() = runTest(dispatcher) {
        coEvery { sdkGateway.switchMerchant(merchantB.id) } coAnswers {
            delay(5_000)
            Result.success(Unit)
        }
        coEvery { sdkGateway.switchMerchant(merchantA.id) } returns Result.success(Unit)

        val firstSwitch = async { repo.switchActiveMerchant(merchantB.id) }
        // Let B start and grab the mutex
        dispatcher.scheduler.advanceUntilIdle()
        val secondSwitch = async { repo.switchActiveMerchant(merchantA.id) }
        dispatcher.scheduler.advanceUntilIdle()

        val secondResult = secondSwitch.await()
        assertTrue("second switch to A should succeed", secondResult.isSuccess)
        assertEquals(merchantA.id, repo.activeAngelPayMerchantId.value)
        // First switch was cancelled — clean up
        firstSwitch.cancel()
    }

    @Test
    fun `fetchAndCacheMerchants populates cache and syncs active marker`() = runTest(dispatcher) {
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(listOf(merchantA, merchantB))

        val result = repo.fetchAndCacheMerchants()

        assertTrue(result.isSuccess)
        assertEquals(merchantA.id, repo.activeAngelPayMerchantId.value)
        coVerify { cacheDao.replaceAll(match { it.size == 2 }) }
    }

    @Test
    fun `refreshBeforeSelector delegates to fetchAndCacheMerchants`() = runTest(dispatcher) {
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(listOf(merchantA))

        val result = repo.refreshBeforeSelector()

        assertTrue(result.isSuccess)
        coVerify { sdkGateway.getUserMerchants() }
    }

    @Test
    fun `clearActive resets the active merchant id to null`() = runTest(dispatcher) {
        coEvery { sdkGateway.selectMerchant(merchantA.id, any()) } returns Result.success(Unit)
        repo.completeInitialSelection(merchantA.id, "init")
        assertEquals(merchantA.id, repo.activeAngelPayMerchantId.value)

        repo.clearActive()

        assertNull(repo.activeAngelPayMerchantId.value)
    }

    // --- seedActiveMerchantFromSession ---

    @Test
    fun `seedActiveMerchantFromSession sets activeId from the active merchant`() = runTest(dispatcher) {
        repo.seedActiveMerchantFromSession(listOf(merchantA, merchantB)) // merchantA isActive=true

        assertEquals(merchantA.id, repo.activeAngelPayMerchantId.value)
    }

    @Test
    fun `seedActiveMerchantFromSession seeds the lone merchant even if isActive false`() = runTest(dispatcher) {
        val lone = MerchantSummary(id = 9, name = "Solo", affiliationNumber = "999", isActive = false)

        repo.seedActiveMerchantFromSession(listOf(lone))

        assertEquals(lone.id, repo.activeAngelPayMerchantId.value)
    }

    @Test
    fun `seedActiveMerchantFromSession is a no-op on empty list`() = runTest(dispatcher) {
        repo.seedActiveMerchantFromSession(emptyList())

        assertNull(repo.activeAngelPayMerchantId.value)
    }

    @Test
    fun `seedActiveMerchantFromSession is a no-op when multiple merchants and none active`() = runTest(dispatcher) {
        val b1 = MerchantSummary(id = 1, name = "A", affiliationNumber = "1", isActive = false)
        val b2 = MerchantSummary(id = 2, name = "B", affiliationNumber = "2", isActive = false)

        repo.seedActiveMerchantFromSession(listOf(b1, b2))

        assertNull(repo.activeAngelPayMerchantId.value)
    }
}
