package com.jaac.avoqado_tpv.core.observability

import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Centralized Crashlytics custom-key injection.
 *
 * Every Timber.e() in release builds already lands in Crashlytics via
 * [CrashReportingTree]. This helper enriches those reports with the operational
 * context (which terminal, which venue, which payment) so Operations doesn't have
 * to ask "what device was that?" when triaging an issue.
 *
 * Pattern mirrors [com.jaac.avoqado_tpv.core.util.ConnectionStateManager]:
 * - Wrap every call in try/catch so a missing google-services.json (early startup,
 *   sandbox-only builds) never crashes the app.
 * - Use plain JVM types (String, Boolean, Int, Long) — Crashlytics rejects nulls
 *   and complex objects.
 *
 * Custom keys appear in the Firebase Console "Keys" tab on every issue. A naming
 * convention: prefix by domain so they stay grouped in the dashboard:
 * - `app_*` — static, set once at startup
 * - `session_*` — per logged-in session (venue, staff, role)
 * - `payment_*` — per in-flight payment attempt
 * - `network_*` — already set by ConnectionStateManager
 */
object CrashlyticsContext {

    // ── App-level (static, set once at startup) ───────────────────────

    /**
     * Set keys that don't change for the lifetime of the process.
     * Call from `Application.onCreate()` AFTER Firebase is initialized.
     */
    fun setAppContext(
        buildVariant: String,
        environment: String,
        terminalSerial: String?,
        appVersionName: String,
        appVersionCode: Int,
    ) {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("app_build_variant", buildVariant)
                setCustomKey("app_environment", environment)
                setCustomKey("app_version_name", appVersionName)
                setCustomKey("app_version_code", appVersionCode)
                if (!terminalSerial.isNullOrBlank()) {
                    setCustomKey("app_terminal_serial", terminalSerial)
                }
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to set app context — Firebase not ready?") }
    }

    // ── Session (set on login, cleared on logout) ─────────────────────

    fun setSessionContext(
        venueId: String?,
        staffId: String?,
        staffRole: String?,
    ) {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                if (!venueId.isNullOrBlank()) setCustomKey("session_venue_id", venueId)
                if (!staffId.isNullOrBlank()) {
                    setCustomKey("session_staff_id", staffId)
                    setUserId(staffId) // Also tag the user globally — Firebase Console filters by this
                }
                if (!staffRole.isNullOrBlank()) setCustomKey("session_staff_role", staffRole)
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to set session context") }
    }

    fun clearSessionContext() {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setUserId("")
                // Note: Crashlytics has no removeCustomKey() — overwrite with sentinel.
                setCustomKey("session_venue_id", "")
                setCustomKey("session_staff_id", "")
                setCustomKey("session_staff_role", "")
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to clear session context") }
    }

    // ── Payment (set before each payment attempt) ─────────────────────

    /**
     * Tag the in-flight payment so any crash during card / cash / crypto carries
     * the exact context: who, where, how much, and via which processor.
     *
     * Call from the ViewModel right before the SDK / API call. No need to clear —
     * the next payment overwrites these keys.
     */
    fun setPaymentContext(
        processor: String, // "BLUMON" / "ANGELPAY" / "B4BIT"
        method: String, // "CARD" / "CASH" / "CRYPTO"
        merchantId: String?,
        amount: String?,
        orderId: String?,
        attemptId: String?,
    ) {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("payment_processor", processor)
                setCustomKey("payment_method", method)
                if (!merchantId.isNullOrBlank()) setCustomKey("payment_merchant_id", merchantId)
                if (!amount.isNullOrBlank()) setCustomKey("payment_amount", amount)
                if (!orderId.isNullOrBlank()) setCustomKey("payment_order_id", orderId)
                if (!attemptId.isNullOrBlank()) setCustomKey("payment_attempt_id", attemptId)
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to set payment context") }
    }

    fun setPaymentEmvContext(
        stage: String,
        flowOrigin: String?,
        message: String?,
        appCount: Int? = null,
        selectedAppIndex: Int? = null,
        elapsedSeconds: Int? = null,
    ) {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("payment_emv_stage", stage)
                setCustomKey("payment_emv_flow_origin", flowOrigin.orEmpty())
                setCustomKey("payment_emv_message", message.orEmpty())
                setCustomKey("payment_emv_app_count", appCount ?: -1)
                setCustomKey("payment_emv_selected_app_index", selectedAppIndex ?: -1)
                setCustomKey("payment_emv_elapsed_seconds", elapsedSeconds ?: -1)
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to set EMV context") }
    }

    fun logPaymentBreadcrumb(message: String) {
        runCatching {
            FirebaseCrashlytics.getInstance().log("[Payment] $message")
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to write payment breadcrumb") }
    }

    fun recordPaymentEmvStall(
        flowOrigin: String?,
        message: String,
        elapsedSeconds: Int,
    ) {
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            setPaymentEmvContext(
                stage = "PROCESSING_CHIP_STALL",
                flowOrigin = flowOrigin,
                message = message,
                elapsedSeconds = elapsedSeconds,
            )
            crashlytics.log("[Payment/EMV] Processing state stuck for ${elapsedSeconds}s: $message")
            crashlytics.recordException(
                PaymentEmvStallException("Payment EMV chip processing stuck for ${elapsedSeconds}s")
            )
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to record EMV stall") }
    }

    private class PaymentEmvStallException(message: String) : Exception(message)
}
