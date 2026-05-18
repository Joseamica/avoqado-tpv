package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.AuthenticateSimpleResult
import com.angelpay.angelpaysdk.models.MerchantOption
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.core.data.network.dto.AngelPayAuthDto
import com.jaac.avoqado_tpv.core.data.network.dto.MerchantAccountDto
import com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigData
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
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
 * Unit tests for [AngelPayAuthRepository] — Task 30 of the AngelPay SDK 1.0.5
 * multi-merchant migration. Covers the §18.6 state machine, the §6.5 retry
 * policy, the §6.9/§18.4 config validation handoff, and the §18.6 auth-expiry
 * recovery hook.
 *
 * Uses [UnconfinedTestDispatcher] following the repo's established pattern for
 * ViewModel + StateFlow-heavy tests (memory note 2026-02-06).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AngelPayAuthRepositoryTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var sdkGateway: AngelPaySdkGateway
    private lateinit var credentialResolver: AngelPayCredentialResolver
    private lateinit var configValidator: AngelPayConfigValidator
    private lateinit var terminalConfigRepository: TerminalConfigRepository
    private lateinit var merchantRepository: AngelPayMerchantRepository
    private lateinit var crashlytics: FirebaseCrashlytics
    private lateinit var repo: AngelPayAuthRepository

    private val backendCreds = AngelPayCreds(
        email = "ops@venue.io",
        pin = "654321",
        environment = "QA",
        source = "backend",
        accountId = "acc-cuid-1",
    )

    private val merchantOption = MerchantOption(
        id = 11,
        name = "Main Restaurant",
        afiliationNumber = "1001",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        sdkGateway = mockk(relaxed = true)
        credentialResolver = mockk()
        configValidator = mockk()
        terminalConfigRepository = mockk()
        merchantRepository = mockk(relaxed = true)
        crashlytics = mockk(relaxed = true)

        // Sensible defaults — individual tests override.
        every { sdkGateway.isAuthenticated() } returns false
        every { sdkGateway.getSessionInfo() } returns null
        every { credentialResolver.resolve() } returns Result.success(backendCreds)
        every { terminalConfigRepository.getCachedConfig() } returns null

        repo = AngelPayAuthRepository(
            sdkGateway = sdkGateway,
            credentialResolver = credentialResolver,
            configValidator = configValidator,
            terminalConfigRepository = terminalConfigRepository,
            merchantRepository = merchantRepository,
            crashlytics = crashlytics,
            reportApi = null,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun configWithAngelPay(): TerminalConfigData = mockk {
        every { merchantAccounts } returns emptyList<MerchantAccountDto>()
        every { angelpayAuth } returns AngelPayAuthDto(
            accountId = "acc-cuid-1",
            email = "ops@venue.io",
            pin = "654321",
            environment = "QA",
        )
    }

    // ----------------------------------------------------------------------
    // 1. Idempotency
    // ----------------------------------------------------------------------
    @Test
    fun `ensureAuthenticated when SDK already authenticated emits Authenticated immediately`() =
        runTest(dispatcher) {
            every { sdkGateway.isAuthenticated() } returns true
            every { terminalConfigRepository.getCachedConfig() } returns null

            val result = repo.ensureAuthenticated()

            assertTrue(result.isSuccess)
            assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
            // No re-auth, no creds resolution.
            coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
            verify(exactly = 0) { credentialResolver.resolve() }
        }

    // ----------------------------------------------------------------------
    // 2. Happy path — Success + AllClear
    // ----------------------------------------------------------------------
    @Test
    fun `ensureAuthenticated with backend creds + Success transitions to Authenticated and runs config validation`() =
        runTest(dispatcher) {
            coEvery { sdkGateway.authenticateSimple("ops@venue.io", "654321") } returns
                Result.success(AuthenticateSimpleResult.Success)
            val config = configWithAngelPay()
            every { terminalConfigRepository.getCachedConfig() } returns config
            coEvery { configValidator.validate(config) } returns ValidationResult.AllClear

            val result = repo.ensureAuthenticated()

            assertTrue("expected success, got $result", result.isSuccess)
            assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
            coVerify(exactly = 1) { configValidator.validate(config) }
        }

    // ----------------------------------------------------------------------
    // 3. MerchantSelectionRequired branch
    // ----------------------------------------------------------------------
    @Test
    fun `ensureAuthenticated with MerchantSelectionRequired emits SelectingMerchant with merchants and token`() =
        runTest(dispatcher) {
            val selection = AuthenticateSimpleResult.MerchantSelectionRequired(
                merchants = listOf(merchantOption),
                temporaryToken = "temp-jwt-abc",
            )
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns Result.success(selection)

            val result = repo.ensureAuthenticated()

            assertTrue(result.isSuccess)
            val state = repo.state.value
            assertTrue("expected SelectingMerchant, got $state", state is AngelPayAuthState.SelectingMerchant)
            val selectingState = state as AngelPayAuthState.SelectingMerchant
            assertEquals(listOf(merchantOption), selectingState.merchants)
            assertEquals("temp-jwt-abc", selectingState.temporaryToken)
            // Config validation NOT run yet — that happens after selection completes.
            coVerify(exactly = 0) { configValidator.validate(any()) }
        }

    // ----------------------------------------------------------------------
    // 4. Failure path — invalid PIN, 3x retries, then AuthError
    // ----------------------------------------------------------------------
    @Test
    fun `ensureAuthenticated with invalid PIN emits AuthError after 3 retries`() =
        runTest(dispatcher) {
            val authErr = IllegalArgumentException("PIN invalido")
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns Result.failure(authErr)

            val result = repo.ensureAuthenticated()

            assertTrue(result.isFailure)
            val state = repo.state.value
            assertTrue("expected AuthError, got $state", state is AngelPayAuthState.AuthError)
            assertEquals("PIN invalido", (state as AngelPayAuthState.AuthError).message)
            // 3 attempts × authenticateSimple.
            coVerify(exactly = 3) { sdkGateway.authenticateSimple(any(), any()) }
            verify(atLeast = 1) { crashlytics.recordException(authErr) }
        }

    // ----------------------------------------------------------------------
    // 5. Exponential backoff timing — virtual time
    // ----------------------------------------------------------------------
    @Test
    fun `ensureAuthenticated retries with exponential backoff 500ms 1s 2s`() =
        runTest(dispatcher) {
            val netErr = AngelPayNetworkError()
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns Result.failure(netErr)

            val start = currentTime
            repo.ensureAuthenticated()
            val elapsed = currentTime - start

            // 500 + 1000 = 1500ms minimum across the two gaps between 3 attempts.
            // After the third attempt the repo gives up (no final delay).
            assertEquals(
                "expected 1500ms of cumulative backoff (500 + 1000), got ${elapsed}ms",
                1500L,
                elapsed,
            )
            coVerify(exactly = 3) { sdkGateway.authenticateSimple(any(), any()) }
        }

    // ----------------------------------------------------------------------
    // 6. completeMerchantSelection — success
    // ----------------------------------------------------------------------
    @Test
    fun `completeMerchantSelection on success transitions to Authenticated and runs config validation`() =
        runTest(dispatcher) {
            coEvery { merchantRepository.completeInitialSelection(11, "temp-jwt-abc") } returns
                Result.success(Unit)
            val config = configWithAngelPay()
            every { terminalConfigRepository.getCachedConfig() } returns config
            coEvery { configValidator.validate(config) } returns ValidationResult.AllClear

            val result = repo.completeMerchantSelection(11, "temp-jwt-abc")

            assertTrue(result.isSuccess)
            assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
            coVerify(exactly = 1) { configValidator.validate(config) }
        }

    // ----------------------------------------------------------------------
    // 7. completeMerchantSelection — failure
    // ----------------------------------------------------------------------
    @Test
    fun `completeMerchantSelection on failure transitions to AuthError`() =
        runTest(dispatcher) {
            coEvery { merchantRepository.completeInitialSelection(11, "temp-jwt-abc") } returns
                Result.failure(AngelPayNetworkError())

            val result = repo.completeMerchantSelection(11, "temp-jwt-abc")

            assertTrue(result.isFailure)
            val state = repo.state.value
            assertTrue("expected AuthError, got $state", state is AngelPayAuthState.AuthError)
            // Config validation NOT run on selection failure.
            coVerify(exactly = 0) { configValidator.validate(any()) }
        }

    // ----------------------------------------------------------------------
    // 8. handleAuthExpiry — logout + re-auth
    // ----------------------------------------------------------------------
    @Test
    fun `handleAuthExpiry forces logout then re-runs ensureAuthenticated`() =
        runTest(dispatcher) {
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
                Result.success(AuthenticateSimpleResult.Success)

            val result = repo.handleAuthExpiry()

            assertTrue(result.isSuccess)
            assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
            verify(exactly = 1) { sdkGateway.logout() }
            verify(exactly = 1) { merchantRepository.clearActive() }
            coVerify(exactly = 1) { sdkGateway.authenticateSimple(any(), any()) }
        }

    // ----------------------------------------------------------------------
    // 9. runConfigValidation — PartialOperable surfaces ConfigMismatchBanner
    // ----------------------------------------------------------------------
    @Test
    fun `runConfigValidation PartialOperable emits ConfigMismatchBanner state`() =
        runTest(dispatcher) {
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
                Result.success(AuthenticateSimpleResult.Success)
            val config = configWithAngelPay()
            every { terminalConfigRepository.getCachedConfig() } returns config
            coEvery { configValidator.validate(config) } returns ValidationResult.PartialOperable(
                operableIds = setOf(11),
                onlyInSdk = setOf(22),
                onlyInAvoqado = setOf(33),
            )

            repo.ensureAuthenticated()

            val state = repo.state.value
            assertTrue(
                "expected ConfigMismatchBanner, got $state",
                state is AngelPayAuthState.ConfigMismatchBanner,
            )
            val banner = state as AngelPayAuthState.ConfigMismatchBanner
            assertEquals(setOf(22), banner.onlyInSdk)
            assertEquals(setOf(33), banner.onlyInAvoqado)
        }

    // ----------------------------------------------------------------------
    // 10. logout — clears state + active merchant
    // ----------------------------------------------------------------------
    @Test
    fun `logout transitions to Unauthenticated and clears merchant active state`() =
        runTest(dispatcher) {
            // Move into Authenticated first.
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
                Result.success(AuthenticateSimpleResult.Success)
            repo.ensureAuthenticated()
            assertEquals(AngelPayAuthState.Authenticated, repo.state.value)

            repo.logout()

            assertEquals(AngelPayAuthState.Unauthenticated, repo.state.value)
            verify(exactly = 1) { sdkGateway.logout() }
            verify(exactly = 1) { merchantRepository.clearActive() }
        }

    @Test
    fun `runConfigValidation HardBlock transitions to AuthError`() =
        runTest(dispatcher) {
            // Bonus coverage — not one of the required 10, but cheap and important.
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
                Result.success(AuthenticateSimpleResult.Success)
            val config = configWithAngelPay()
            every { terminalConfigRepository.getCachedConfig() } returns config
            coEvery { configValidator.validate(config) } returns ValidationResult.HardBlock(
                message = "Sin merchants compartidos",
            )

            repo.ensureAuthenticated()

            val state = repo.state.value
            assertTrue("expected AuthError, got $state", state is AngelPayAuthState.AuthError)
            assertEquals("Sin merchants compartidos", (state as AngelPayAuthState.AuthError).message)
        }

    @Test
    fun `ensureAuthenticated when creds resolver fails emits AuthError without calling SDK`() =
        runTest(dispatcher) {
            // Bonus coverage — extra branch.
            every { credentialResolver.resolve() } returns Result.failure(MissingAngelPayCredsError)

            val result = repo.ensureAuthenticated()

            assertTrue(result.isFailure)
            val state = repo.state.value
            assertTrue("expected AuthError, got $state", state is AngelPayAuthState.AuthError)
            coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
        }

    @Test
    fun `state defaults to Unauthenticated`() {
        // Bonus sanity — make sure no observer-side init mutates state on construction.
        val freshRepo = AngelPayAuthRepository(
            sdkGateway = sdkGateway,
            credentialResolver = credentialResolver,
            configValidator = configValidator,
            terminalConfigRepository = terminalConfigRepository,
            merchantRepository = merchantRepository,
            crashlytics = crashlytics,
            reportApi = null,
        )
        assertEquals(AngelPayAuthState.Unauthenticated, freshRepo.state.value)
        assertNull("smoke check — type guard", freshRepo.state.value as? AngelPayAuthState.AuthError)
    }
}
