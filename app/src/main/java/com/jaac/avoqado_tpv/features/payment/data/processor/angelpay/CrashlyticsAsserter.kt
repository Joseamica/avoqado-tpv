package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.BuildConfig

/**
 * Wrapper around `FirebaseCrashlytics.setCustomKey()` that throws on 6-digit
 * numeric strings in debug builds — defensive guard against accidentally
 * leaking the AngelPay PIN to Firebase Crashlytics analytics (spec §4.5b).
 *
 * Use this wrapper instead of `FirebaseCrashlytics.getInstance().setCustomKey()`
 * for any AngelPay-related telemetry. Production builds skip the assertion to
 * avoid hard crashes on legitimate 6-digit values (e.g., merchant IDs that
 * happen to be 6 digits) — the assertion exists to catch developer mistakes
 * during testing, not to police production data.
 */
object CrashlyticsAsserter {

    private val PIN_PATTERN = Regex("""^\d{6}$""")

    fun setCustomKey(key: String, value: String) {
        if (BuildConfig.DEBUG && PIN_PATTERN.matches(value)) {
            throw IllegalArgumentException(
                "Crashlytics key '$key' has 6-digit value that looks like a PIN: REJECTED",
            )
        }
        FirebaseCrashlytics.getInstance().setCustomKey(key, value)
    }
}
