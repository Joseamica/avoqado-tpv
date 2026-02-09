package com.jaac.avoqado_tpv.core.observability

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Timber Tree for production builds.
 *
 * Forwards Timber.w() and Timber.e() to Firebase Crashlytics as:
 * - **Breadcrumbs** (log): all W/E messages for debugging context
 * - **Non-fatal exceptions** (recordException): all E messages for dashboard visibility
 *
 * This means every existing `Timber.e("❌ Payment failed")` in the codebase
 * automatically appears in Firebase Crashlytics Console — no code changes needed.
 *
 * Pattern used by Square, Toast, and most production Android apps.
 */
class CrashReportingTree : timber.log.Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        return priority >= Log.WARN
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val crashlytics = try {
            FirebaseCrashlytics.getInstance()
        } catch (e: Exception) {
            return // Crashlytics not available (e.g., missing google-services.json)
        }

        // Add as breadcrumb (helps trace events leading to a crash/error)
        crashlytics.log("${priorityLabel(priority)}/$tag: $message")

        // Record non-fatal exception for ERROR level
        if (priority >= Log.ERROR) {
            val exception = t ?: NonFatalException("[$tag] $message")
            crashlytics.recordException(exception)
        }
    }

    private fun priorityLabel(priority: Int): String = when (priority) {
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }

    /**
     * Wrapper exception for Timber.e() calls without a Throwable.
     * Groups by tag+message in Crashlytics dashboard.
     */
    private class NonFatalException(message: String) : Exception(message)
}
