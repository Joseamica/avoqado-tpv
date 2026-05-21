package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val RESOLVER_LOG_TAG = "AngelPayResolver"

/**
 * Resolves AngelPay credentials at runtime — single source-of-truth.
 *
 * **D4 dual-source — spec §6.5, §18.3.**
 *
 * Priority order:
 *   1. **Backend `angelpayAuth`** payload (from the most recent
 *      `TerminalConfigRepository.fetchConfig()` call — populated by Task 13's
 *      `/tpv/terminals/:serial/config` endpoint when terminal.brand == "NEXGO"
 *      and the venue has an ACTIVE `AngelPayUserAccount`). Preferred path —
 *      no Crashlytics warning.
 *   2. **BuildConfig fallback** — legacy `BuildConfig.ANGELPAY_QA_EMAIL` +
 *      `ANGELPAY_QA_PASSWORD` (kept as empty strings post-Task-21; only
 *      populated during the 30-day grace transition). Records a Crashlytics
 *      warning so Operations can monitor stragglers still relying on
 *      hardcoded creds. The BuildConfig path is only attempted on AngelPay
 *      flavors (`SUPPORTED_PROCESSOR == "ANGELPAY"`) so that PAX flavors
 *      stay completely independent of any AngelPay class loading.
 *   3. **Neither available** → `Result.failure(MissingAngelPayCredsError)`.
 *
 * **PIN handling (§4.5b):** the resolved `AngelPayCreds.pin` is in-memory only
 * — never logged, persisted to SecureStorage, or sent to analytics.
 *
 * **Guardrails:** NEVER called from PAX flavors (sandbox/production). Call sites
 * upstream check `BuildConfig.SUPPORTED_PROCESSOR == "ANGELPAY"` before invoking
 * the resolver — see `AvoqadoTPVApplication.initializeAngelPaySdkIfEnabled()`.
 *
 * **Testability:** `legacyCredsLoader` is constructor-injected so unit tests can
 * substitute a fake without having to mock the generated `BuildConfig` class
 * (which behaves inconsistently under MockK across JVM agent variants). The
 * default loader reads `BuildConfig.ANGELPAY_QA_*` directly and is correct in
 * production.
 */
@Singleton
class AngelPayCredentialResolver @Inject constructor(
    private val terminalConfigRepository: TerminalConfigRepository,
    private val crashlytics: FirebaseCrashlytics,
    private val legacyCredsLoader: () -> LegacyAngelPayCreds? = ::defaultLegacyCredsLoader,
) {
    /**
     * Resolve creds for a SPECIFIC AngelPay account by its server-side ID.
     *
     * Multi-AngelPay accounts per venue (2026-05-18). Looks up in the cached
     * `angelpayAccounts` list from terminal config and returns the matching
     * entry's creds. Use this for multi-account flows where the operator
     * switched merchants and we need to swap the SDK session.
     *
     * Falls back to legacy single-account `angelpayAuth` when the cached list
     * is empty (e.g., backend didn't populate angelpayAccounts yet — old TPV /
     * old backend) — only succeeds if the singular `angelpayAuth.accountId`
     * matches the requested [accountId].
     *
     * @param accountId Avoqado `AngelPayUserAccount` CUID
     * @return Success with creds (source="backend") or failure when no match.
     */
    suspend fun resolveByAccountId(accountId: String): Result<AngelPayCreds> {
        Timber.tag(RESOLVER_LOG_TAG).i("resolveByAccountId($accountId) — searching cached angelpayAccounts list")
        val accounts = terminalConfigRepository.getCachedAngelPayAccounts()
        val match = accounts.firstOrNull { it.accountId == accountId }
        if (match != null) {
            Timber.tag(RESOLVER_LOG_TAG).i("✓ resolveByAccountId match in list: email=${match.email}, env=${match.environment}")
            return Result.success(
                AngelPayCreds(
                    email = match.email,
                    pin = match.pin,
                    environment = match.environment,
                    source = "backend",
                    accountId = match.accountId,
                ),
            )
        }
        // Legacy fallback — old backend that only emits the singular `angelpayAuth`.
        val singular = terminalConfigRepository.getCachedAngelPayAuth()
        if (singular != null && singular.accountId == accountId) {
            Timber.tag(RESOLVER_LOG_TAG).i("✓ resolveByAccountId match via legacy singular angelpayAuth: email=${singular.email}")
            return Result.success(
                AngelPayCreds(
                    email = singular.email,
                    pin = singular.pin,
                    environment = singular.environment,
                    source = "backend",
                    accountId = singular.accountId,
                ),
            )
        }
        Timber.tag(RESOLVER_LOG_TAG).w("✗ resolveByAccountId($accountId) — not found in cached list (size=${accounts.size}) or legacy singular auth")
        return Result.failure(
            MissingAngelPayCredsError,
        )
    }

    fun resolve(): Result<AngelPayCreds> {
        Timber.tag(RESOLVER_LOG_TAG).i("resolve() — checking backend angelpayAuth in cached terminal config")
        val backendAuth = terminalConfigRepository.getCachedAngelPayAuth()
        if (backendAuth != null) {
            Timber.tag(RESOLVER_LOG_TAG).i("✓ BACKEND creds resolved: accountId=${backendAuth.accountId}, email=${backendAuth.email}, env=${backendAuth.environment}")
            return Result.success(
                AngelPayCreds(
                    email = backendAuth.email,
                    pin = backendAuth.pin,
                    environment = backendAuth.environment,
                    source = "backend",
                    accountId = backendAuth.accountId,
                ),
            )
        }

        Timber.tag(RESOLVER_LOG_TAG).w("Backend cached config has NO angelpayAuth — TPV probably hasn't fetched a fresh config since the venue's AngelPayUserAccount was activated, OR the config endpoint didn't include angelpayAuth (check: terminal.brand === 'NEXGO' AND venue has ACTIVE AngelPayUserAccount). Trying BuildConfig fallback...")
        val legacy = legacyCredsLoader()
        if (legacy != null) {
            Timber.tag(RESOLVER_LOG_TAG).w("✓ BuildConfig fallback used (deprecated): email=${legacy.email}, env=${legacy.environment}")
            crashlytics.log("AngelPay: using deprecated BuildConfig credentials (remove in v3)")
            crashlytics.recordException(DeprecatedBuildConfigCredsWarning)
            return Result.success(
                AngelPayCreds(
                    email = legacy.email,
                    pin = legacy.pin,
                    environment = legacy.environment,
                    source = "buildconfig-fallback",
                    accountId = null,
                ),
            )
        }

        Timber.tag(RESOLVER_LOG_TAG).e("✗ NO credentials available from either source. Backend angelpayAuth=null AND BuildConfig fields empty/missing. " +
            "Fix: (a) verify dashboard shows AngelPay account ACTIVE for this venue, (b) force-stop the app and reopen so it fetches fresh config, (c) check backend logs for /tpv/terminals/:serial/config response shape.")
        return Result.failure(MissingAngelPayCredsError)
    }
}

/**
 * Resolved AngelPay credentials. NEVER persisted to disk, logs, or analytics.
 * See spec §4.5b PIN handling rules.
 *
 * @property source      `"backend"` | `"buildconfig-fallback"`
 * @property accountId   Avoqado `AngelPayUserAccount` CUID — null when
 *                       `source == "buildconfig-fallback"` (no backend account
 *                       backing the legacy creds).
 */
data class AngelPayCreds(
    val email: String,
    val pin: String,
    val environment: String,
    val source: String,
    val accountId: String?,
)

/**
 * In-memory bundle returned by `legacyCredsLoader`. Mirrors the
 * `AngelPayCreds` shape minus the `source`/`accountId` fields (those are filled
 * in by the resolver). Tests construct this directly; production uses
 * [defaultLegacyCredsLoader] which reads `BuildConfig`.
 */
data class LegacyAngelPayCreds(
    val email: String,
    val pin: String,
    val environment: String,
)

/**
 * Marker exception — recorded to Crashlytics (not thrown). Surfaces in
 * analytics so Operations can monitor BuildConfig-fallback usage during
 * the 30-day grace period.
 */
object DeprecatedBuildConfigCredsWarning :
    RuntimeException("AngelPay using deprecated hardcoded credentials — remove in v3")

/**
 * Returned in `Result.failure` when neither backend nor BuildConfig sources
 * have credentials.
 */
object MissingAngelPayCredsError :
    RuntimeException("AngelPay credentials missing from both backend config and BuildConfig")

/**
 * Default `legacyCredsLoader` — reads `BuildConfig.ANGELPAY_QA_*`.
 *
 * - Returns `null` when both `ANGELPAY_QA_EMAIL` and `ANGELPAY_QA_PASSWORD`
 *   are blank (the expected state post-Task-21 on AngelPay flavors).
 * - Only inspects `BuildConfig.ANGELPAY_ENV` on AngelPay flavors
 *   (`SUPPORTED_PROCESSOR == "ANGELPAY"`) since that BuildConfig field exists
 *   only in `nexgo`/`nexgoProd` per `app/build.gradle.kts`. Reading it from a
 *   shared accessor on a PAX flavor would fail to compile; this guard ensures
 *   the resolver class is compilable on every flavor even though only AngelPay
 *   flavors will actually invoke it.
 */
internal fun defaultLegacyCredsLoader(): LegacyAngelPayCreds? {
    if (BuildConfig.SUPPORTED_PROCESSOR != "ANGELPAY") return null

    val email = BuildConfig.ANGELPAY_QA_EMAIL.takeIf { it.isNotBlank() } ?: return null
    val pin = BuildConfig.ANGELPAY_QA_PASSWORD.takeIf { it.isNotBlank() } ?: return null
    // `ANGELPAY_ENV` only exists on AngelPay flavors — guarded above.
    val env = BuildConfig.ANGELPAY_ENV
    return LegacyAngelPayCreds(email = email, pin = pin, environment = env)
}
