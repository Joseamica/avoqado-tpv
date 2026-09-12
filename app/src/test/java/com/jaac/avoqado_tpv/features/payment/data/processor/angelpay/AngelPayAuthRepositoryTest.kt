package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.AuthenticateSimpleResult
import com.angelpay.angelpaysdk.models.MerchantOption
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.core.data.network.dto.AngelPayAuthDto
import com.jaac.avoqado_tpv.core.data.network.dto.MerchantAccountDto
import com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigData
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.data.repository.TerminalConfigUnreachableException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    private lateinit var deviceInfoManager: com.jaac.avoqado_tpv.core.util.DeviceInfoManager
    private lateinit var merchantRepository: AngelPayMerchantRepository
    private lateinit var paymentStateProvider: PaymentStateProvider
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
        deviceInfoManager = mockk(relaxed = true)
        merchantRepository = mockk(relaxed = true)
        paymentStateProvider = mockk()
        every { paymentStateProvider.isCharging() } returns false
        every { paymentStateProvider.isChargeAttemptActive() } returns false
        crashlytics = mockk(relaxed = true)

        // Sensible defaults — individual tests override.
        every { sdkGateway.isAuthenticated() } returns false
        every { sdkGateway.getSessionInfo() } returns null
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(emptyList())
        every { credentialResolver.resolve() } returns Result.success(backendCreds)
        every { terminalConfigRepository.getCachedConfig() } returns null
        coEvery { terminalConfigRepository.fetchConfig(any()) } returns Result.failure(RuntimeException("test-stub"))

        repo = AngelPayAuthRepository(
            sdkGateway = sdkGateway,
            credentialResolver = credentialResolver,
            configValidator = configValidator,
            terminalConfigRepository = terminalConfigRepository,
            deviceInfoManager = deviceInfoManager,
            merchantRepository = merchantRepository,
            paymentStateProvider = paymentStateProvider,
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
            // Return a non-empty merchant list so the stale-session guard does NOT
            // force a re-auth. Production code: if getUserMerchants() is null/empty
            // it treats the session as stale and falls through to authenticateSimple.
            coEvery { sdkGateway.getUserMerchants() } returns Result.success(
                listOf(mockk(relaxed = true) { every { id } returns 11 })
            )
            every { terminalConfigRepository.getCachedConfig() } returns null

            val result = repo.ensureAuthenticated()

            assertTrue(result.isSuccess)
            assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
            // No re-auth call — the SDK session was reused.
            coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
            // Note: credentialResolver.resolve() MAY be called once in the short-circuit
            // path to populate currentAngelPayAccountId (fallback when SDK merchant IDs
            // don't match any config entry). That's not a re-auth — just an account-ID
            // lookup. What must NOT happen is authenticateSimple().
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
            coEvery { configValidator.validate(config, any()) } returns ValidationResult.AllClear

            val result = repo.ensureAuthenticated()

            assertTrue("expected success, got $result", result.isSuccess)
            assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
            coVerify(exactly = 1) { configValidator.validate(config, any()) }
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
            // 5 attempts × authenticateSimple (maxAttempts changed from 3 → 5, 2026-05-19).
            coVerify(exactly = 5) { sdkGateway.authenticateSimple(any(), any()) }
            // T26: se reporta TIPADA (AngelPayAuthFailure) con la causa real adentro.
            verify(atLeast = 1) {
                crashlytics.recordException(match { it is AngelPayAuthFailure && it.cause === authErr })
            }
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

            // 1000 + 3000 + 5000 + 10000 = 19000ms across the four gaps between 5 attempts.
            // Backoff schedule changed from 500/1000/2000 (3 attempts) to
            // 1000/3000/5000/10000/20000 (5 attempts) on 2026-05-19 for AngelPay QA load.
            // After the fifth attempt the repo gives up (no final delay).
            assertEquals(
                "expected 19000ms of cumulative backoff (1000+3000+5000+10000), got ${elapsed}ms",
                19000L,
                elapsed,
            )
            coVerify(exactly = 5) { sdkGateway.authenticateSimple(any(), any()) }
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
            coEvery { configValidator.validate(config, any()) } returns ValidationResult.AllClear

            val result = repo.completeMerchantSelection(11, "temp-jwt-abc")

            assertTrue(result.isSuccess)
            assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
            coVerify(exactly = 1) { configValidator.validate(config, any()) }
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
            coEvery { configValidator.validate(config, any()) } returns ValidationResult.PartialOperable(
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
            coEvery { configValidator.validate(config, any()) } returns ValidationResult.HardBlock(
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
            deviceInfoManager = deviceInfoManager,
            merchantRepository = merchantRepository,
            paymentStateProvider = paymentStateProvider,
            crashlytics = crashlytics,
            reportApi = null,
        )
        assertEquals(AngelPayAuthState.Unauthenticated, freshRepo.state.value)
        assertNull("smoke check — type guard", freshRepo.state.value as? AngelPayAuthState.AuthError)
    }

    // ----------------------------------------------------------------------
    // D2 charging gate on switchAccount (P1 fix 2026-07-09)
    // ----------------------------------------------------------------------
    @Test
    fun `switchAccount is rejected while a payment is charging`() = runTest(dispatcher) {
        every { paymentStateProvider.isCharging() } returns true

        val result = repo.switchAccount("acc-cuid-2")

        assertTrue(result.isFailure)
        // The dangerous side effects must never run mid-charge:
        verify(exactly = 0) { sdkGateway.logout() }
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
        verify(exactly = 0) { merchantRepository.clearActive() }
    }

    @Test
    fun `switchAccount proceeds when no payment is charging`() = runTest(dispatcher) {
        every { paymentStateProvider.isCharging() } returns false
        // Unknown accountId + failing config refetch → resolver failure path,
        // which proves the guard let the call through to credential resolution.
        coEvery { credentialResolver.resolveByAccountId("acc-cuid-2") } returns
            Result.failure(RuntimeException("account not in cached config"))

        val result = repo.switchAccount("acc-cuid-2")

        assertTrue(result.isFailure)
        coVerify(atLeast = 1) { credentialResolver.resolveByAccountId("acc-cuid-2") }
    }

    // ----------------------------------------------------------------------
    // Incidente Amaena (2026-07-29): la re-auth debe volver a la cuenta ACTIVA,
    // no a la primaria del venue (angelpayAccounts[0] = la más antigua). El
    // swap silencioso a la primaria cobraba la afiliación A mientras el
    // registro llevaba la B.
    // ----------------------------------------------------------------------

    private val secondAccountCreds = AngelPayCreds(
        email = "ventas@venue.io",
        pin = "222222",
        environment = "QA",
        source = "backend",
        accountId = "acc-cuid-2",
    )

    @Test
    fun `handleAuthExpiry re-authenticates the account that was active, not the primary`() =
        runTest(dispatcher) {
            coEvery { credentialResolver.resolveByAccountId("acc-cuid-2") } returns
                Result.success(secondAccountCreds)
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
                Result.success(AuthenticateSimpleResult.Success)
            assertTrue(repo.switchAccount("acc-cuid-2").isSuccess)
            assertEquals("acc-cuid-2", repo.getCurrentAngelPayAccountId())

            val result = repo.handleAuthExpiry()

            assertTrue(result.isSuccess)
            // switch (1) + re-auth post-expiry (2) — SIEMPRE con la cuenta que estaba activa…
            coVerify(exactly = 2) { sdkGateway.authenticateSimple("ventas@venue.io", any()) }
            // …y NUNCA con la primaria: ese swap silencioso es el bug de Amaena.
            coVerify(exactly = 0) { sdkGateway.authenticateSimple("ops@venue.io", any()) }
            assertEquals("acc-cuid-2", repo.getCurrentAngelPayAccountId())
        }

    @Test
    fun `ensureAuthenticatedAs authenticates the target account directly when the session is dead`() =
        runTest(dispatcher) {
            coEvery { credentialResolver.resolveByAccountId("acc-cuid-2") } returns
                Result.success(secondAccountCreds)
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
                Result.success(AuthenticateSimpleResult.Success)

            val result = repo.ensureAuthenticatedAs("acc-cuid-2")

            assertTrue(result.isSuccess)
            coVerify(exactly = 1) { sdkGateway.authenticateSimple("ventas@venue.io", any()) }
            coVerify(exactly = 0) { sdkGateway.authenticateSimple("ops@venue.io", any()) }
            assertEquals("acc-cuid-2", repo.getCurrentAngelPayAccountId())
        }

    @Test
    fun `ensureAuthenticatedAs falls back to the venue primary when the target account no longer resolves`() =
        runTest(dispatcher) {
            coEvery { credentialResolver.resolveByAccountId("acc-gone") } returns
                Result.failure(MissingAngelPayCredsError)
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
                Result.success(AuthenticateSimpleResult.Success)

            val result = repo.ensureAuthenticatedAs("acc-gone")

            assertTrue(result.isSuccess)
            // Se INTENTÓ resolver la cuenta objetivo (incluido el retry post self-heal)…
            coVerify(atLeast = 1) { credentialResolver.resolveByAccountId("acc-gone") }
            // …y solo entonces cayó a la primaria (fallback explícito > terminal muerta).
            coVerify(exactly = 1) { sdkGateway.authenticateSimple("ops@venue.io", any()) }
        }

    @Test
    fun `ensureAuthenticatedAs short-circuits when already authenticated as the target account`() =
        runTest(dispatcher) {
            every { sdkGateway.isAuthenticated() } returns true
            coEvery { sdkGateway.getUserMerchants() } returns Result.success(
                listOf(mockk(relaxed = true) { every { id } returns 11 }),
            )
            val config = mockk<TerminalConfigData>(relaxed = true) {
                every { merchantAccounts } returns listOf(
                    mockk<MerchantAccountDto>(relaxed = true) {
                        every { providerCode } returns "ANGELPAY"
                        every { externalMerchantId } returns "11"
                        every { angelpayUserAccountId } returns "acc-cuid-2"
                    },
                )
            }
            every { terminalConfigRepository.getCachedConfig() } returns config
            coEvery { configValidator.validate(any(), any()) } returns ValidationResult.AllClear

            val result = repo.ensureAuthenticatedAs("acc-cuid-2")

            assertTrue(result.isSuccess)
            assertEquals("acc-cuid-2", repo.getCurrentAngelPayAccountId())
            coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
            verify(exactly = 0) { sdkGateway.logout() }
        }

    @Test
    fun `ensureAuthenticatedAs re-authenticates when the live session belongs to a different account`() =
        runTest(dispatcher) {
            every { sdkGateway.isAuthenticated() } returns true
            coEvery { sdkGateway.getUserMerchants() } returns Result.success(
                listOf(mockk(relaxed = true) { every { id } returns 11 }),
            )
            // El merchant 11 de la sesión viva pertenece a la cuenta 1 (la primaria)…
            val config = mockk<TerminalConfigData>(relaxed = true) {
                every { merchantAccounts } returns listOf(
                    mockk<MerchantAccountDto>(relaxed = true) {
                        every { providerCode } returns "ANGELPAY"
                        every { externalMerchantId } returns "11"
                        every { angelpayUserAccountId } returns "acc-cuid-1"
                    },
                )
            }
            every { terminalConfigRepository.getCachedConfig() } returns config
            coEvery { configValidator.validate(any(), any()) } returns ValidationResult.AllClear
            // …y el flujo de pago exige la cuenta 2.
            coEvery { credentialResolver.resolveByAccountId("acc-cuid-2") } returns
                Result.success(secondAccountCreds)
            coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
                Result.success(AuthenticateSimpleResult.Success)

            val result = repo.ensureAuthenticatedAs("acc-cuid-2")

            assertTrue(result.isSuccess)
            verify(atLeast = 1) { sdkGateway.logout() }
            coVerify(exactly = 1) { sdkGateway.authenticateSimple("ventas@venue.io", any()) }
            assertEquals("acc-cuid-2", repo.getCurrentAngelPayAccountId())
        }

    // ----------------------------------------------------------------------
    // T26 (Testarudo, 2026-09-11): la N86 arrancó sin red y quedó ~4 h sin poder
    // cobrar. El error de red salía como «credentials missing» y, peor, sin red
    // ensureAuthenticatedAs caía a la cuenta PRIMARIA del venue.
    // ----------------------------------------------------------------------

    private fun sinRed(): Result<Nothing> = Result.failure(
        TerminalConfigUnreachableException("Sin conexión a internet.", java.net.UnknownHostException("api.avoqado.io")),
    )

    @Test
    fun `P1 sin red ensureAuthenticatedAs NO cae a la cuenta primaria`() = runTest(dispatcher) {
        coEvery { credentialResolver.resolveByAccountId("acc-cuid-2") } returns Result.failure(MissingAngelPayCredsError)
        coEvery { terminalConfigRepository.fetchConfig(any()) } returns sinRed()
        coEvery { sdkGateway.authenticateSimple(any(), any()) } returns Result.success(AuthenticateSimpleResult.Success)

        val result = repo.ensureAuthenticatedAs("acc-cuid-2")

        assertTrue(result.isFailure)
        // Sin red no se puede afirmar que la cuenta ya no existe: jamás a la primaria.
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
        val state = repo.state.value as AngelPayAuthState.AuthError
        assertEquals(AuthErrorKind.SIN_RED, state.kind)
    }

    @Test
    fun `P1 sin red al arrancar el error es SIN_RED y no dice que faltan credenciales`() = runTest(dispatcher) {
        every { credentialResolver.resolve() } returns Result.failure(MissingAngelPayCredsError)
        coEvery { terminalConfigRepository.fetchConfig(any()) } returns sinRed()

        val result = repo.ensureAuthenticated()

        assertTrue(result.isFailure)
        val state = repo.state.value as AngelPayAuthState.AuthError
        assertEquals(AuthErrorKind.SIN_RED, state.kind)
        assertFalse(state.message.contains("credentials missing"))
    }

    @Test
    fun `config fresca sin credenciales es SIN_CREDENCIALES y conserva MissingAngelPayCredsError`() = runTest(dispatcher) {
        every { credentialResolver.resolve() } returns Result.failure(MissingAngelPayCredsError)
        coEvery { terminalConfigRepository.fetchConfig(any()) } returns Result.success(mockk(relaxed = true))

        val result = repo.ensureAuthenticated()

        assertSame(MissingAngelPayCredsError, result.exceptionOrNull())
        val state = repo.state.value as AngelPayAuthState.AuthError
        assertEquals(AuthErrorKind.SIN_CREDENCIALES, state.kind)
    }

    @Test
    fun `switchAccount con la cuenta ausente de la config fresca es CUENTA_NO_EN_CONFIG`() = runTest(dispatcher) {
        coEvery { credentialResolver.resolveByAccountId("acc-x") } returns Result.failure(MissingAngelPayCredsError)
        coEvery { terminalConfigRepository.fetchConfig(any()) } returns Result.success(mockk(relaxed = true))

        val result = repo.switchAccount("acc-x")

        assertTrue(result.isFailure)
        val state = repo.state.value as AngelPayAuthState.AuthError
        assertEquals(AuthErrorKind.CUENTA_NO_EN_CONFIG, state.kind)
    }

    @Test
    fun `switchAccount sin red es SIN_RED`() = runTest(dispatcher) {
        coEvery { credentialResolver.resolveByAccountId("acc-x") } returns Result.failure(MissingAngelPayCredsError)
        coEvery { terminalConfigRepository.fetchConfig(any()) } returns sinRed()

        repo.switchAccount("acc-x")

        assertEquals(AuthErrorKind.SIN_RED, (repo.state.value as AngelPayAuthState.AuthError).kind)
    }

    @Test
    fun `falla de transporte de authenticateSimple es SIN_RED`() = runTest(dispatcher) {
        coEvery { sdkGateway.authenticateSimple(any(), any()) } returns Result.failure(AngelPayNetworkError())

        repo.ensureAuthenticated()

        assertEquals(AuthErrorKind.SIN_RED, (repo.state.value as AngelPayAuthState.AuthError).kind)
    }

    @Test
    fun `DNS caido dentro de la cadena de causas tambien es SIN_RED`() = runTest(dispatcher) {
        // mapSdkError convierte "Unable to resolve host auth..." en AuthExpired por la palabra "auth".
        coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
            Result.failure(AngelPayAuthExpiredError(cause = java.net.UnknownHostException("auth.angelpay.mx")))

        repo.ensureAuthenticated()

        assertEquals(AuthErrorKind.SIN_RED, (repo.state.value as AngelPayAuthState.AuthError).kind)
    }

    @Test
    fun `reporta a Crashlytics una excepcion tipada con su causa y la llave angelpay_auth_state`() = runTest(dispatcher) {
        every { credentialResolver.resolve() } returns Result.failure(MissingAngelPayCredsError)
        coEvery { terminalConfigRepository.fetchConfig(any()) } returns sinRed()

        repo.ensureAuthenticated()

        verify(exactly = 1) {
            crashlytics.recordException(
                match {
                    it is AngelPayAuthFailure && it.kind == AuthErrorKind.SIN_RED &&
                        it.cause is TerminalConfigUnreachableException
                },
            )
        }
        verify(exactly = 1) {
            crashlytics.setCustomKey("angelpay_auth_state", match<String> { it.startsWith("SIN_RED desde=") })
        }
    }

    @Test
    fun `dos autenticaciones simultaneas nunca corren a la vez`() = runTest(dispatcher) {
        val puerta = CompletableDeferred<Unit>()
        var enVuelo = 0
        var maximo = 0
        var sesion = false
        every { sdkGateway.isAuthenticated() } answers { sesion }
        coEvery { sdkGateway.getUserMerchants() } answers {
            if (sesion) Result.success(listOf(mockk(relaxed = true) { every { id } returns 11 }))
            else Result.success(emptyList())
        }
        coEvery { credentialResolver.resolveByAccountId("acc-cuid-2") } returns Result.success(
            backendCreds.copy(email = "ventas@venue.io", accountId = "acc-cuid-2"),
        )
        coEvery { sdkGateway.authenticateSimple(any(), any()) } coAnswers {
            enVuelo++
            maximo = maxOf(maximo, enVuelo)
            puerta.await()
            enVuelo--
            sesion = true
            Result.success(AuthenticateSimpleResult.Success)
        }

        val arranque = async { repo.ensureAuthenticated() }
        val cobro = async { repo.ensureAuthenticatedAs("acc-cuid-2") }
        runCurrent()
        puerta.complete(Unit)
        arranque.await()
        cobro.await()

        assertEquals("dos authenticateSimple a la vez pisan la sesión del SDK", 1, maximo)
    }

    // ----------------------------------------------------------------------
    // T26, segunda indicación: FETCH_ANGELPAY_MERCHANTS respeta al dueño de la sesión.
    // switchAccount hace logout: en la ventana «auth del cobro → lanzamiento» dejaba la
    // sesión en otra cuenta (incidente de Amaena).
    // ----------------------------------------------------------------------

    @Test
    fun `P1 FETCH_ANGELPAY_MERCHANTS no toca la sesion si un cobro es duenio`() = runTest(dispatcher) {
        assertTrue(repo.candadoDeSesion.tryLock()) // el cobro

        val result = repo.refrescarComerciosParaElPanel("acc-cuid-2")

        assertTrue(result.isFailure)
        verify(exactly = 0) { sdkGateway.logout() }
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
        repo.candadoDeSesion.unlock()
    }

    @Test
    fun `P1 FETCH_ANGELPAY_MERCHANTS no entra con la pantalla de cobro trabajando`() = runTest(dispatcher) {
        every { paymentStateProvider.isChargeAttemptActive() } returns true

        val result = repo.refrescarComerciosParaElPanel("acc-cuid-2")

        assertTrue(result.isFailure)
        verify(exactly = 0) { sdkGateway.logout() }
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
        assertFalse(repo.candadoDeSesion.isLocked)
    }

    @Test
    fun `FETCH_ANGELPAY_MERCHANTS con la sesion libre cambia a la cuenta pedida y suelta el candado`() = runTest(dispatcher) {
        var sesion = false
        every { sdkGateway.isAuthenticated() } answers { sesion }
        coEvery { sdkGateway.getUserMerchants() } answers {
            if (sesion) Result.success(listOf(mockk(relaxed = true) { every { id } returns 22 }))
            else Result.success(emptyList())
        }
        coEvery { credentialResolver.resolveByAccountId("acc-cuid-2") } returns Result.success(secondAccountCreds)
        coEvery { sdkGateway.authenticateSimple(any(), any()) } answers {
            sesion = true
            Result.success(AuthenticateSimpleResult.Success)
        }

        val result = repo.refrescarComerciosParaElPanel("acc-cuid-2")

        assertTrue("esperaba éxito, fue $result", result.isSuccess)
        coVerify(exactly = 1) { sdkGateway.authenticateSimple("ventas@venue.io", any()) }
        coVerify(exactly = 0) { sdkGateway.authenticateSimple("ops@venue.io", any()) }
        assertFalse(repo.candadoDeSesion.isLocked)
    }
}
