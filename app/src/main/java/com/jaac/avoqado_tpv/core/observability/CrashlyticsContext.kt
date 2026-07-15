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

    /**
     * Snapshot SDK-staleness signals at the moment a Blumon SaleIcc/SaleCtls failure happens.
     *
     * Why: investigating MomentumFailure recurring after long-lived processes (Doña Simona case,
     * 2026-05-12). Blumon/Edgardo confirmed (2026-05-12) the OAuth token has a server-side TTL
     * of 24h with no automatic refresh in the SDK — the integrating app is responsible for
     * re-running `InitializerUseCase`. We need data from real prod failures to validate the
     * hypothesis before changing the init flow.
     *
     * Pure observability — does not change any control flow. **MUST be called BEFORE the
     * `Timber.e(...)` that triggers `recordException` in `CrashReportingTree`**, so the custom
     * keys are attached to the non-fatal that gets reported.
     *
     * All signals consolidated into a single Crashlytics custom key `sdk_state_snapshot` to
     * stay well within the 64-key Firebase limit (current count: ~53). Keys are space-separated
     * `name=value` pairs for easy parsing on the dashboard.
     *
     * Fields captured:
     *  - failure=<class>             SaleIccFailure subclass or EXCEPTION:<Throwable>
     *  - initFlag=true|false         `_isInitialized.value` (in-memory)
     *  - ageHours=<long>             Hours since last successful init, -1 if never initialized
     *  - posIdEffective=<string>     🔑 AUTORITATIVO: posId REAL de la tabla Init del SDK
     *                                (`InitializationManager.readEffectivePosId()`), o `no-leido`.
     *                                Es el único que dice a qué afiliación pega el dinero.
     *  - posIdInit=<string>          ⚠️ CREENCIA: last init's posId (from disk)
     *  - posIdCurrent=<string>       ⚠️ CREENCIA: currently selected merchant's posId
     *  - serialInit=<string>         Last init's serial (from disk)
     *  - serialCurrent=<string>      TerminalConfig.serialNumber (runtime)
     *  - merchantMismatch=true|false posIdEffective ≠ posIdCurrent (si hay lectura autoritativa),
     *                                si no cae a posIdInit ≠ posIdCurrent OR serialInit ≠ serialCurrent
     *  - mismatchVerificado=true|f   true = merchantMismatch se calculó contra la tabla Init real.
     *                                false = se calculó comparando dos creencias de la app; NO es prueba.
     *  - fallback=true|false         `MerchantRepository.isUsingFallback()`
     *  - uptimeMin=<long>            SystemClock.elapsedRealtime / 60_000
     *  - env=PROD|SAND               BuildConfig.BLUMON_ENV
     */
    fun recordSdkStalenessSnapshot(
        failureClass: String,
        initFlagInMemory: Boolean,
        lastInitTimestampMs: Long?,
        lastInitPosId: String?,
        currentMerchantPosId: String?,
        lastInitSerial: String?,
        currentSerial: String?,
        merchantIsFallback: Boolean,
        processUptimeMs: Long,
        blumonEnv: String,
        effectivePosId: String? = null,
    ) {
        runCatching {
            val nowMs = System.currentTimeMillis()
            val ageHours: Long = if (lastInitTimestampMs != null && lastInitTimestampMs > 0) {
                (nowMs - lastInitTimestampMs) / 3_600_000L
            } else {
                -1L
            }
            val uptimeMinutes = processUptimeMs / 60_000L
            val posIdInit = lastInitPosId.orEmpty()
            val posIdCurrent = currentMerchantPosId.orEmpty()
            val serialInit = lastInitSerial.orEmpty()
            val serialCurrent = currentSerial.orEmpty()
            // ⚠️ posIdInit y posIdCurrent son AMBOS CREENCIAS de la app: posIdInit sale de disco
            // (con qué posId la app CREE que inicializó) y posIdCurrent es la SELECCIÓN de merchant.
            // Ninguno lee la tabla Init del SDK, que es lo único que decide a qué afiliación pega
            // el dinero. Un log que compara creencia contra creencia puede reportar
            // merchantMismatch=false mientras el SDK cobra a otro comercio — exactamente el error
            // que hizo perder 2 días al diagnóstico del TX_024.
            // Por eso `posIdEffective` (de InitializationManager.readEffectivePosId(), la fila real
            // de la tabla Init) es la señal AUTORITATIVA: cuando está disponible, es la que manda.
            val posIdEffective = effectivePosId.orEmpty()
            // 🔑 UNA sola fuente de verdad para "¿este veredicto se verificó?". Debe ser
            // EXACTAMENTE la condición de la rama autoritativa de abajo: si se calcula por separado,
            // las dos se desincronizan y el snapshot acaba jurando "verificado" sobre un veredicto
            // que en realidad se supuso — el pecado exacto que esta clave existe para matar.
            val mismatchVerificado = posIdEffective.isNotEmpty() && posIdCurrent.isNotEmpty()
            val merchantMismatch = if (mismatchVerificado) {
                // Verdad de fierro: lo que el SDK REALMENTE tiene vs lo que se seleccionó.
                posIdEffective != posIdCurrent ||
                    (serialInit.isNotEmpty() && serialCurrent.isNotEmpty() && serialInit != serialCurrent)
            } else {
                // Sin lectura autoritativa: se cae al criterio viejo (creencia vs creencia). Es
                // mejor que nada, pero NO es prueba — leer junto a posIdEffective= para saber si
                // este valor se pudo verificar o solo se supuso.
                (posIdInit.isNotEmpty() && posIdCurrent.isNotEmpty() && posIdInit != posIdCurrent) ||
                    (serialInit.isNotEmpty() && serialCurrent.isNotEmpty() && serialInit != serialCurrent)
            }

            val snapshot = buildString {
                append("failure=").append(failureClass).append(' ')
                append("initFlag=").append(initFlagInMemory).append(' ')
                append("ageHours=").append(ageHours).append(' ')
                append("posIdEffective=").append(posIdEffective.ifEmpty { "no-leido" }).append(' ')
                append("posIdInit=").append(posIdInit).append(' ')
                append("posIdCurrent=").append(posIdCurrent).append(' ')
                append("serialInit=").append(serialInit).append(' ')
                append("serialCurrent=").append(serialCurrent).append(' ')
                append("merchantMismatch=").append(merchantMismatch).append(' ')
                append("mismatchVerificado=").append(mismatchVerificado).append(' ')
                append("fallback=").append(merchantIsFallback).append(' ')
                append("uptimeMin=").append(uptimeMinutes).append(' ')
                append("env=").append(blumonEnv)
            }
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("sdk_state_snapshot", snapshot)
                log("[SDK/Staleness] $snapshot")
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to record SDK staleness snapshot") }
    }

    // ── Network / Connection observability (added 2026-05-06) ─────────
    //
    // Added in response to repeat reports at Doña Simona where multiple loading states
    // ("Verificando sesión...", "Procesando chip...", "Sin conexión al servidor", etc.)
    // got stuck for 30+ seconds after the device woke from Doze/sleep. Root cause was
    // stale HTTP/2 connections in OkHttp's pool — we had no observability for it.
    //
    // These helpers cover three distinct visibility gaps:
    //   1) Pool eviction events (how many zombie conns were purged on resume)
    //   2) Auth refresh duration (how long was the user blocked on "Verificando sesión...")
    //   3) Backend reachability transitions (when did `hasServer` flip false→true→false)
    //
    // Every call is wrapped in runCatching to keep observability from breaking the app.

    /**
     * Tag the in-flight loading screen.
     *
     * Call from any ViewModel right BEFORE entering a long-running suspend block
     * that the user can see (e.g. fetching merchants, refreshing settings, posting
     * a payment). Pair with [clearLoadingState] in finally / on success.
     *
     * Future crashes/non-fatals during the load will carry these keys, so triage
     * can see "user was blocked on `merchant_fetch` for 18s when X happened".
     */
    fun setLoadingState(
        screen: String,
        stage: String,
        startedAtMs: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("loading_active", true)
                setCustomKey("loading_screen", screen)
                setCustomKey("loading_stage", stage)
                setCustomKey("loading_started_at_ms", startedAtMs)
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to set loading state") }
    }

    fun clearLoadingState() {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("loading_active", false)
                setCustomKey("loading_screen", "")
                setCustomKey("loading_stage", "")
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to clear loading state") }
    }

    /**
     * Record a non-fatal when a loading state runs longer than expected.
     *
     * Use as the "tripwire" pattern: every screen/feature decides what's "too long"
     * for its user-visible operation, calls this once, and we get aggregate metrics
     * in Firebase Console grouped by `screen` and `stage`.
     *
     * Example call sites: TokenAuthenticator (>5s on refresh), MerchantRepository
     * (>5s on getMerchants), HeartbeatRepository (>8s on heartbeat).
     */
    fun recordLoadingStateStall(
        screen: String,
        stage: String,
        elapsedMs: Long,
        cause: Throwable? = null,
    ) {
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("loading_screen", screen)
            crashlytics.setCustomKey("loading_stage", stage)
            crashlytics.setCustomKey("loading_elapsed_ms", elapsedMs)
            crashlytics.log("[Loading/Stall] $screen.$stage stuck for ${elapsedMs}ms${if (cause != null) " — ${cause.javaClass.simpleName}: ${cause.message}" else ""}")
            crashlytics.recordException(
                LoadingStateStallException(
                    "[$screen.$stage] stuck for ${elapsedMs}ms",
                    cause,
                )
            )
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to record loading stall") }
    }

    /**
     * Record the OkHttp connection pool state.
     *
     * Called from MainActivity onResume after evicting stale connections, and from
     * heartbeat probes when they detect a slow response. Lets us correlate "we
     * evicted N stale conns at 13:15:42" with "next probe succeeded at 13:15:43".
     */
    fun setNetworkPoolContext(
        idleConnections: Int,
        totalConnections: Int,
        evictedAt: Long? = null,
    ) {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("network_pool_idle", idleConnections)
                setCustomKey("network_pool_total", totalConnections)
                if (evictedAt != null) setCustomKey("network_pool_last_evicted_at_ms", evictedAt)
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to set network pool context") }
    }

    fun logNetworkBreadcrumb(message: String) {
        runCatching {
            FirebaseCrashlytics.getInstance().log("[Network] $message")
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to write network breadcrumb") }
    }

    /**
     * Record a non-fatal when the connection banner first turns on.
     *
     * Fires the first time `hasServer` flips to false in a session (or after at
     * least N seconds of being healthy). Includes how long since the last
     * successful heartbeat and the failure cause if known. Single-shot per
     * incident — does NOT fire repeatedly while the banner stays up.
     */
    fun recordBackendUnreachable(
        gapSinceLastSuccessMs: Long,
        cause: Throwable? = null,
        source: String = "heartbeat",
    ) {
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("network_unreachable_source", source)
            crashlytics.setCustomKey("network_unreachable_gap_ms", gapSinceLastSuccessMs)
            crashlytics.log("[Network/Banner] hasServer=false (source=$source, gap=${gapSinceLastSuccessMs}ms)")
            crashlytics.recordException(
                BackendUnreachableException(
                    "Backend unreachable from $source after ${gapSinceLastSuccessMs}ms",
                    cause,
                )
            )
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to record backend unreachable") }
    }

    /**
     * Record auth-refresh timing — the "Verificando sesión..." overlay duration.
     *
     * Called from TokenAuthenticator on both success and failure paths.
     */
    fun recordAuthRefresh(
        elapsedMs: Long,
        outcome: String, // "success" / "fail" / "timeout"
        cause: Throwable? = null,
    ) {
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("auth_refresh_outcome", outcome)
            crashlytics.setCustomKey("auth_refresh_elapsed_ms", elapsedMs)
            crashlytics.log("[Auth] refresh $outcome in ${elapsedMs}ms${if (cause != null) " — ${cause.javaClass.simpleName}: ${cause.message}" else ""}")
            // Only record non-fatal when the user actually saw a stuck overlay
            if (outcome == "timeout" || (outcome == "fail" && elapsedMs > 5_000)) {
                crashlytics.recordException(
                    AuthRefreshSlowException(
                        "Auth refresh $outcome after ${elapsedMs}ms",
                        cause,
                    )
                )
            }
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to record auth refresh") }
    }

    /**
     * Record a non-fatal when Socket.IO can't authenticate after multiple retries.
     *
     * Specifically targets the "Token has expired" loop documented in production
     * (Crashlytics issue de5c4a0a, 550 events spanning v1.13.1-1.13.5). Includes
     * retry count, last error message, and whether an HTTP refresh succeeded —
     * lets us tell apart "refresh worked but socket still rejected" (server bug)
     * from "refresh itself failed" (client/network bug).
     */
    fun recordSocketAuthFailure(
        retryCount: Int,
        lastError: String,
        refreshOutcome: String, // "skipped" / "success_same_token" / "success_new_token" / "timeout" / "fail_401" / "fail_other"
    ) {
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("socket_auth_retry_count", retryCount)
            crashlytics.setCustomKey("socket_auth_last_error", lastError.take(200))
            crashlytics.setCustomKey("socket_auth_refresh_outcome", refreshOutcome)
            crashlytics.log("[Socket/Auth] retry=$retryCount refresh=$refreshOutcome — $lastError")
            crashlytics.recordException(
                SocketAuthFailureException(
                    "Socket auth failed (retry=$retryCount, refresh=$refreshOutcome): $lastError"
                )
            )
        }.onFailure { Timber.w(it, "🛡️ [Crashlytics] Failed to record socket auth failure") }
    }

    private class PaymentEmvStallException(message: String) : Exception(message)
    private class LoadingStateStallException(message: String, cause: Throwable?) : Exception(message, cause)
    private class BackendUnreachableException(message: String, cause: Throwable?) : Exception(message, cause)
    private class AuthRefreshSlowException(message: String, cause: Throwable?) : Exception(message, cause)
    private class SocketAuthFailureException(message: String) : Exception(message)
}
