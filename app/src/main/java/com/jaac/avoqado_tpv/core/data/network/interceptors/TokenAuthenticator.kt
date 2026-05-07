package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.observability.CrashlyticsContext
import com.jaac.avoqado_tpv.core.session.SessionManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import dagger.Lazy
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Token Authenticator - Handles 401 Unauthorized with Automatic Token Refresh
 *
 * **Why Authenticator instead of Interceptor?**
 * 1. Authenticator is specifically designed for 401 handling (OkHttp pattern)
 * 2. Runs AFTER response (not in the request chain)
 * 3. Avoids Hilt dependency cycles (uses Lazy<AuthRepository>)
 * 4. Automatically thread-safe (OkHttp guarantees single execution)
 *
 * **Flow:**
 * ```
 * Request → Interceptor (adds token) → Server → 401 Response
 *   ↓
 * Authenticator.authenticate() called
 *   ↓
 * Refresh Token → Retry Request with New Token
 * ```
 *
 * **Lazy Injection:**
 * Using `Lazy<AuthRepository>` breaks the Hilt dependency cycle:
 * ```
 * ApiService → Retrofit → OkHttpClient → Authenticator (LAZY) → AuthRepository → ApiService
 *                                            ↑__________________________|
 *                                           (broken by Lazy)
 * ```
 *
 * **Pattern:** Square Terminal, Stripe Terminal, Toast POS
 * Professional POS systems silently renew tokens without user intervention.
 *
 * @param secureStorage Storage for JWT token
 * @param authRepositoryLazy Lazy-injected AuthRepository (breaks dependency cycle)
 * @param sessionManager Session event manager for notifying UI of session expiration
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authRepositoryLazy: Lazy<AuthRepository>,  // ✅ Lazy breaks cycle
    private val sessionManager: SessionManager
) : Authenticator {

    // Access repository only when needed (lazy initialization)
    private val authRepository: AuthRepository by lazy { authRepositoryLazy.get() }

    // Synchronized lock to prevent multiple simultaneous token refreshes
    // (If 5 requests fail with 401 simultaneously, only 1 should refresh)
    private val refreshLock = Any()
    @Volatile
    private var isRefreshing = false

    companion object {
        /**
         * Hard cap on the refresh-token request. The "Verificando sesión..." overlay
         * is visible to the user while we wait, so this is the absolute worst-case
         * UX delay. OkHttp's callTimeout (25s) is the upstream cap; we tighten here
         * because the overlay is BLOCKING — user can't print receipt, send email,
         * share via WhatsApp until we resolve. 8s strikes the balance: long enough
         * for a slow-but-recovering network, short enough that the user doesn't
         * abandon the device.
         */
        private const val REFRESH_TIMEOUT_MS = 8_000L

        /**
         * Threshold above which a refresh duration is considered "slow" and gets
         * a Crashlytics non-fatal even on success. Lets us detect degradation
         * before it becomes a hang.
         */
        private const val REFRESH_SLOW_THRESHOLD_MS = 3_000L
    }

    /**
     * Endpoints that should NOT trigger token refresh on 401.
     * These endpoints use credentials (PIN, activation code) - NOT tokens.
     * A 401 here means "bad credentials" or "venue suspended", NOT "token expired".
     */
    private val authEndpoints = listOf(
        "/auth/login",
        "/auth/",
        "/login-pin",
        "/activate",
        "/refresh"  // Don't retry refresh if refresh itself fails
    )

    /**
     * Check if this request is an authentication endpoint.
     * Auth endpoints should not trigger token refresh on 401.
     */
    private fun isAuthEndpoint(request: Request): Boolean {
        val path = request.url.encodedPath
        return authEndpoints.any { path.contains(it, ignoreCase = true) }
    }

    /**
     * Called by OkHttp when a request receives 401 Unauthorized.
     *
     * **Return values:**
     * - Non-null Request: Retry with new auth header
     * - Null: Give up (token refresh failed, user must re-login)
     *
     * **Thread-safe:**
     * OkHttp calls this from multiple threads. We use synchronized block
     * to ensure only one refresh happens at a time.
     *
     * @param route The connection route (unused in our case)
     * @param response The 401 response
     * @return Updated request with new token, or null if refresh failed
     */
    override fun authenticate(route: Route?, response: Response): Request? {
        // ✅ CRITICAL: Skip token refresh for authentication endpoints
        // A 401 on login/activate means "bad credentials" or "venue suspended"
        // NOT "token expired" - let the calling code handle the error
        if (isAuthEndpoint(response.request)) {
            Timber.d("🔐 [Auth] 401 on auth endpoint (${response.request.url.encodedPath}) - skipping token refresh")
            return null  // Let the error propagate to LoginViewModel
        }

        Timber.w("⚠️ [Auth] Received 401 Unauthorized - Token expired, attempting refresh...")
        CrashlyticsContext.logNetworkBreadcrumb(
            "Auth 401 on ${response.request.url.encodedPath} — starting token refresh"
        )

        // 📺 Show loading overlay immediately so user knows something is happening
        sessionManager.notifySessionVerifying()
        val overlayShownAt = System.currentTimeMillis()

        // Prevent multiple simultaneous refreshes (thread-safe)
        synchronized(refreshLock) {
            // Get current token from storage
            val currentToken = secureStorage.getToken()

            // Check if token was already refreshed by another thread
            // (If request's token != current token, another thread already refreshed)
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (currentToken != null && currentToken != requestToken) {
                Timber.d("✅ [Auth] Token already refreshed by another thread, retrying request")
                sessionManager.resetSessionExpiringState()  // Hide loading overlay
                CrashlyticsContext.recordAuthRefresh(
                    elapsedMs = System.currentTimeMillis() - overlayShownAt,
                    outcome = "success_other_thread",
                )
                return buildRequestWithNewToken(response.request, currentToken)
            }

            // If already refreshing, wait for completion
            if (isRefreshing) {
                Timber.d("⏳ [Auth] Refresh already in progress, waiting...")
                val startTime = System.currentTimeMillis()
                while (isRefreshing && (System.currentTimeMillis() - startTime) < REFRESH_TIMEOUT_MS) {
                    Thread.sleep(100)
                }

                val waitElapsed = System.currentTimeMillis() - startTime

                // Check if refresh succeeded
                val refreshedToken = secureStorage.getToken()
                if (refreshedToken != null && refreshedToken != requestToken) {
                    Timber.d("✅ [Auth] Refresh completed, retrying request (waited ${waitElapsed}ms)")
                    sessionManager.resetSessionExpiringState()  // Hide loading overlay
                    CrashlyticsContext.recordAuthRefresh(
                        elapsedMs = waitElapsed,
                        outcome = "success_waited",
                    )
                    return buildRequestWithNewToken(response.request, refreshedToken)
                } else {
                    Timber.w("❌ [Auth] Refresh failed or timed out (waited ${waitElapsed}ms)")
                    // 🛡️ Always reset overlay so the UI doesn't get stuck on
                    // "Verificando sesión..." even when the primary thread fails.
                    // If session truly expired, the primary thread already called
                    // notifySessionExpired() → AppNavigation will navigate to Login.
                    sessionManager.resetSessionExpiringState()
                    CrashlyticsContext.recordAuthRefresh(
                        elapsedMs = waitElapsed,
                        outcome = "timeout",
                    )
                    return null // Give up
                }
            }

            // Mark refresh as in progress
            isRefreshing = true
            val refreshStartedAt = System.currentTimeMillis()

            try {
                // 🛡️ Attempt to refresh token with hard timeout. Without withTimeout,
                // a stuck HTTP/2 stream could keep the user on "Verificando sesión..."
                // for 25-30s (OkHttp callTimeout). We bound it to REFRESH_TIMEOUT_MS so
                // the user's UI unblocks fast and the next request can retry on a fresh
                // connection (pingInterval will have evicted the stale one by then).
                val refreshResult = runBlocking {
                    withTimeout(REFRESH_TIMEOUT_MS) {
                        authRepository.refreshAccessToken()
                    }
                }
                val refreshElapsed = System.currentTimeMillis() - refreshStartedAt

                if (refreshResult is com.jaac.avoqado_tpv.core.domain.models.Result.Success) {
                    Timber.i("✅ [Auth] Token refreshed successfully in ${refreshElapsed}ms, retrying original request")

                    // ✅ Hide loading overlay - refresh succeeded
                    sessionManager.resetSessionExpiringState()

                    // 🔄 Notify observers (Socket.IO) to reconnect with fresh token
                    sessionManager.notifyTokenRefreshed()

                    CrashlyticsContext.recordAuthRefresh(
                        elapsedMs = refreshElapsed,
                        outcome = if (refreshElapsed > REFRESH_SLOW_THRESHOLD_MS) "success_slow" else "success",
                    )

                    // Get new token and retry
                    val newToken = secureStorage.getToken()
                    if (newToken != null) {
                        return buildRequestWithNewToken(response.request, newToken)
                    } else {
                        Timber.e("❌ [Auth] Token refresh succeeded but new token not found in storage")
                        return null
                    }
                } else {
                    Timber.e("❌ [Auth] Token refresh failed in ${refreshElapsed}ms: $refreshResult")

                    // Refresh failed (refresh token expired) → Force logout
                    secureStorage.clearSession()
                    Timber.w("🚪 [Auth] Session cleared due to refresh failure - User must re-login")

                    // Notify UI that session has expired → triggers navigation to Login
                    sessionManager.notifySessionExpired()

                    CrashlyticsContext.recordAuthRefresh(
                        elapsedMs = refreshElapsed,
                        outcome = "fail",
                    )

                    return null // Give up (return null = OkHttp stops retrying)
                }
            } catch (e: TimeoutCancellationException) {
                val elapsed = System.currentTimeMillis() - refreshStartedAt
                Timber.w("⏱️ [Auth] Token refresh timed out after ${elapsed}ms — clearing overlay and giving up")
                // 🛡️ TIMEOUT path — most common cause is HTTP/2 stale stream after Doze.
                // We do NOT call notifySessionExpired() here (the token may still be valid;
                // the network just hung). Just clear overlay so user can retry the action.
                // The next request will trigger another refresh attempt with a fresh
                // connection (pingInterval evicted the stale one).
                sessionManager.resetSessionExpiringState()
                CrashlyticsContext.recordAuthRefresh(
                    elapsedMs = elapsed,
                    outcome = "timeout",
                    cause = e,
                )
                return null
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - refreshStartedAt
                Timber.e(e, "❌ [Auth] Exception during token refresh after ${elapsed}ms")
                secureStorage.clearSession()

                // Notify UI that session has expired → triggers navigation to Login
                sessionManager.notifySessionExpired()

                CrashlyticsContext.recordAuthRefresh(
                    elapsedMs = elapsed,
                    outcome = "fail",
                    cause = e,
                )

                return null
            } finally {
                isRefreshing = false
            }
        }
    }

    /**
     * Build a new request with updated Authorization header.
     *
     * @param originalRequest The failed request
     * @param newToken The new access token
     * @return Updated request with new token
     */
    private fun buildRequestWithNewToken(originalRequest: Request, newToken: String): Request {
        return originalRequest.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }
}
