package com.jaac.avoqado_tpv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §17.5 packaging + §6.4 Pattern A safeguards.
 *
 * Multi-merchant migration Task 36.
 *
 * These tests are intentionally NOT `@HiltAndroidTest` — the project's
 * Hilt+Robolectric unit test infrastructure is not set up under
 * `app/src/test/`. (Hilt instrumentation tests do live under
 * `app/src/androidTest/`, but those require a connected device/emulator
 * and are deferred to Task 39's manual PAX A910S QA checklist.)
 *
 * Instead we verify the §17.5 packaging guardrails and the §6.4 Pattern A
 * PAX startup safety by reading the source `AvoqadoTPVApplication.kt`
 * at JVM time and asserting the structural patterns hold.
 *
 * Failure modes caught:
 *   - Someone removes `Provider<AngelPaySdkGateway>` and switches to
 *     eager `@Inject lateinit var` direct injection — that would cause
 *     Hilt to eagerly resolve the AngelPay graph at Application creation
 *     on PAX (sandbox/production) flavors, where AngelPay classes are
 *     not on the classpath, producing a `ClassNotFoundException` at
 *     boot.
 *   - Someone removes the `BuildConfig.SUPPORTED_PROCESSOR == "ANGELPAY"`
 *     (or equivalent negated `!= "BLUMON"`) guard — that would invoke
 *     AngelPay SDK init on PAX flavors and either crash or balloon the
 *     APK.
 *
 * If either assertion fails, do NOT silence the test — it is signalling
 * a real regression risk. Restore the `Provider<>` indirection and/or
 * the BuildConfig guard before merging.
 */
class NexgoFlavorHiltGraphTest {

    // Test runs with working dir at app/ (Gradle module). Path is relative to that.
    private val appFile: File = File("src/main/java/com/jaac/avoqado_tpv/AvoqadoTPVApplication.kt")

    @Test
    fun `AvoqadoTPVApplication uses Provider for AngelPaySdkGateway (D4 Pattern A)`() {
        assertTrue(
            "AvoqadoTPVApplication.kt not found at ${appFile.absolutePath} — adjust test working dir",
            appFile.exists(),
        )
        val source = appFile.readText()
        assertTrue(
            "AvoqadoTPVApplication must use Provider<AngelPaySdkGateway> to avoid eager Hilt " +
                "resolution on PAX flavors — switching to direct `@Inject lateinit var " +
                "angelPaySdkGateway: AngelPaySdkGateway` would crash PAX startup with " +
                "ClassNotFoundException because AngelPay classes are not on the PAX classpath.",
            source.contains("Provider<AngelPaySdkGateway>"),
        )
    }

    @Test
    fun `AvoqadoTPVApplication gates AngelPay init behind SUPPORTED_PROCESSOR check`() {
        val source = appFile.readText()
        // Accept either the positive ("ANGELPAY") or the negated ("!= BLUMON" / "!= ANGELPAY") form.
        val hasPositiveGuard =
            Regex("""SUPPORTED_PROCESSOR\s*==\s*"ANGELPAY"""").containsMatchIn(source)
        val hasNegatedGuard =
            Regex("""SUPPORTED_PROCESSOR\s*!=\s*"ANGELPAY"""").containsMatchIn(source) ||
                Regex("""SUPPORTED_PROCESSOR\s*!=\s*"BLUMON"""").containsMatchIn(source)
        assertTrue(
            "AvoqadoTPVApplication must guard AngelPay initialization behind " +
                "BuildConfig.SUPPORTED_PROCESSOR (== \"ANGELPAY\" or != \"ANGELPAY\"/\"BLUMON\"). " +
                "Without this guard, AngelPay SDK init runs on PAX flavors and either crashes " +
                "or violates the §17.5 packaging contract.",
            hasPositiveGuard || hasNegatedGuard,
        )
    }
}
