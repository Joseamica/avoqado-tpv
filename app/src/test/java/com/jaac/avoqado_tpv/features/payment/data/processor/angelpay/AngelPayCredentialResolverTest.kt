package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.core.data.network.dto.AngelPayAuthDto
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AngelPayCredentialResolver (Task 25 — D4 dual-source, spec §6.5, §18.3).
 *
 * Verifies:
 *  1. Backend `angelpayAuth` is preferred when present (no Crashlytics warning).
 *  2. Falls back to BuildConfig credentials when backend is null and BuildConfig
 *     fields are populated, with a Crashlytics warning so we can monitor stragglers.
 *  3. Returns Result.failure(MissingAngelPayCredsError) when both sources are empty.
 *
 * NOTE: BuildConfig fields on the `sandboxDebug` test variant are empty strings
 * (set in app/build.gradle.kts), so the fallback branch returns failure by default —
 * which is the correct production behavior. To cover the "fallback succeeds" branch
 * without mocking BuildConfig (mockkObject on the generated BuildConfig class breaks
 * across JVM agent variants), we exercise the resolver via a constructor-injected
 * `legacyCredsLoader` lambda. In production this lambda reads BuildConfig directly;
 * tests substitute a fake.
 */
class AngelPayCredentialResolverTest {

    private lateinit var terminalConfigRepo: TerminalConfigRepository
    private lateinit var crashlytics: FirebaseCrashlytics

    @Before
    fun setup() {
        terminalConfigRepo = mockk(relaxed = true)
        crashlytics = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `prefers backend angelpayAuth when present`() {
        val backendAuth = AngelPayAuthDto(
            accountId = "acc-cuid-1",
            email = "ops@venue.io",
            pin = "654321",
            environment = "QA",
        )
        every { terminalConfigRepo.getCachedAngelPayAuth() } returns backendAuth

        val resolver = AngelPayCredentialResolver(
            terminalConfigRepository = terminalConfigRepo,
            crashlytics = crashlytics,
            legacyCredsLoader = {
                LegacyAngelPayCreds(email = "legacy@x.co", pin = "999999", environment = "QA")
            },
        )

        val result = resolver.resolve()

        assertTrue("expected success but got failure", result.isSuccess)
        val creds = result.getOrThrow()
        assertEquals("ops@venue.io", creds.email)
        assertEquals("654321", creds.pin)
        assertEquals("QA", creds.environment)
        assertEquals("backend", creds.source)
        assertEquals("acc-cuid-1", creds.accountId)
        verify(exactly = 0) { crashlytics.recordException(any()) }
    }

    @Test
    fun `falls back to BuildConfig with warning`() {
        every { terminalConfigRepo.getCachedAngelPayAuth() } returns null

        val resolver = AngelPayCredentialResolver(
            terminalConfigRepository = terminalConfigRepo,
            crashlytics = crashlytics,
            legacyCredsLoader = {
                LegacyAngelPayCreds(email = "legacy@x.co", pin = "999999", environment = "QA")
            },
        )

        val result = resolver.resolve()

        assertTrue("expected success", result.isSuccess)
        val creds = result.getOrThrow()
        assertEquals("legacy@x.co", creds.email)
        assertEquals("999999", creds.pin)
        assertEquals("QA", creds.environment)
        assertEquals("buildconfig-fallback", creds.source)
        assertNull("accountId should be null on fallback (no backend account)", creds.accountId)
        verify(atLeast = 1) {
            crashlytics.recordException(any<DeprecatedBuildConfigCredsWarning>())
        }
    }

    @Test
    fun `errors when both sources null`() {
        every { terminalConfigRepo.getCachedAngelPayAuth() } returns null

        val resolver = AngelPayCredentialResolver(
            terminalConfigRepository = terminalConfigRepo,
            crashlytics = crashlytics,
            legacyCredsLoader = { null },  // Production legacyCredsLoader returns null when ANGELPAY_QA_* are blank.
        )

        val result = resolver.resolve()

        assertTrue("expected failure", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            "expected MissingAngelPayCredsError, got ${err?.javaClass?.simpleName}",
            err is MissingAngelPayCredsError,
        )
    }
}
