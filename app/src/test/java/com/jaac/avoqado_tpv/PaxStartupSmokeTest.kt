package com.jaac.avoqado_tpv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §6.4 Pattern A: PAX flavors must not load AngelPay symbols at app startup.
 *
 * Multi-merchant migration Task 36.
 *
 * As a unit-test-friendly proxy for the real "Application.onCreate
 * completes without ClassNotFoundException" instrumentation smoke test,
 * we verify the `BuildConfig.SUPPORTED_PROCESSOR` value for the
 * sandboxDebug variant (PAX flavor) is `"BLUMON"`. That field is the
 * single gate consulted by `AvoqadoTPVApplication.maybeInitAngelPay()`,
 * so as long as it remains `"BLUMON"` for PAX builds and the guard in
 * the application class is in place (see `NexgoFlavorHiltGraphTest`),
 * the AngelPay graph is never resolved on PAX startup.
 *
 * The real end-to-end PAX startup smoke test (cold boot of
 * `sandboxRelease`/`productionRelease` APK on a PAX A910S, asserting
 * no `ClassNotFoundException` for AngelPay classes in logcat) lives in
 * Task 39's manual QA checklist — instrumented Application bringup
 * tests are deferred to MVP+ when Robolectric / Hilt unit-test
 * infrastructure is added.
 */
class PaxStartupSmokeTest {

    @Test
    fun `sandboxDebug BuildConfig SUPPORTED_PROCESSOR is BLUMON`() {
        assertEquals(
            "PAX (sandboxDebug) flavor must not have ANGELPAY as SUPPORTED_PROCESSOR — that " +
                "would cause AngelPay init to run on PAX startup and either crash with " +
                "ClassNotFoundException or violate the §17.5 packaging contract. If this " +
                "test fails, check app/build.gradle.kts `buildConfigField(\"String\", " +
                "\"SUPPORTED_PROCESSOR\", ...)` for the sandbox flavor.",
            "BLUMON",
            BuildConfig.SUPPORTED_PROCESSOR,
        )
    }
}
