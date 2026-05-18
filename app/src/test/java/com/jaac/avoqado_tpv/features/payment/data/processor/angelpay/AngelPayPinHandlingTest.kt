package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.network.RedactingLoggingInterceptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for spec §4.5b PIN handling enforcement (Task 35).
 *
 * Verifies that the AngelPay PIN cannot accidentally leak through:
 *  - OkHttp response body logs (RedactingLoggingInterceptor.redactJsonFields)
 *  - Firebase Crashlytics custom keys (CrashlyticsAsserter)
 *
 * The TerminalConfigRepository in-memory cache (AtomicReference<TerminalConfigData>)
 * is intentionally NOT covered here: it has no disk-persistence path, so §4.5b is
 * automatically satisfied. See TerminalConfigRepositoryImpl.kt for the design comment.
 */
class AngelPayPinHandlingTest {

    // ────────────────────────────────────────────────────────────────────────
    // RedactingLoggingInterceptor
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `RedactingLoggingInterceptor redacts pin field in JSON body`() {
        val interceptor = RedactingLoggingInterceptor()
        val body =
            """{"merchantAccounts":[],"angelpayAuth":{"email":"a@b.co","pin":"123456","environment":"QA"}}"""

        val redacted = interceptor.redactJsonFields(body)

        assertTrue("expected pin to be redacted", redacted.contains(""""pin":"***""""))
        assertFalse("expected actual PIN value to be gone", redacted.contains("123456"))
        assertTrue("email should still be visible", redacted.contains("a@b.co"))
    }

    @Test
    fun `RedactingLoggingInterceptor redacts password field`() {
        val interceptor = RedactingLoggingInterceptor()
        val body = """{"user":"alice","password":"hunter2","other":"data"}"""

        val redacted = interceptor.redactJsonFields(body)

        assertTrue(redacted.contains(""""password":"***""""))
        assertFalse(redacted.contains("hunter2"))
        assertTrue("non-sensitive fields remain visible", redacted.contains(""""other":"data""""))
    }

    // ────────────────────────────────────────────────────────────────────────
    // CrashlyticsAsserter
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `CrashlyticsAsserter throws on 6-digit numeric in debug builds`() {
        if (!BuildConfig.DEBUG) return // assertion is debug-only

        assertThrows(IllegalArgumentException::class.java) {
            CrashlyticsAsserter.setCustomKey("test_key", "123456")
        }
    }

    @Test
    fun `CrashlyticsAsserter accepts non-PIN values`() {
        // We can't easily mock the static FirebaseCrashlytics.getInstance() call without
        // significant setup, so we just verify the assertion DOESN'T fire on safe inputs.
        // Any failure mode other than IllegalArgumentException is acceptable here —
        // FirebaseCrashlytics may itself throw in unit tests because Firebase isn't
        // initialised, which is fine: we're proving the PIN guard didn't trip.
        val safeValues = listOf(
            "abc123",     // letters mixed
            "12345",      // 5 digits — too short for a PIN
            "1234567",    // 7 digits — too long for a PIN
            "12-34-56",   // separators
        )
        for (value in safeValues) {
            try {
                CrashlyticsAsserter.setCustomKey("test_key", value)
            } catch (e: IllegalArgumentException) {
                fail("CrashlyticsAsserter should NOT throw on non-PIN value '$value': ${e.message}")
            } catch (e: Throwable) {
                // Acceptable — Firebase isn't initialised in unit tests. The PIN guard
                // didn't fire, which is what this test verifies.
            }
        }
    }
}
