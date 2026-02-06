package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.session.SessionManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import dagger.Lazy
import kotlinx.coroutines.runBlocking
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

        // 📺 Show loading overlay immediately so user knows something is happening
        sessionManager.notifySessionVerifying()

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
                return buildRequestWithNewToken(response.request, currentToken)
            }

            // If already refreshing, wait for completion
            if (isRefreshing) {
                Timber.d("⏳ [Auth] Refresh already in progress, waiting...")
                val startTime = System.currentTimeMillis()
                while (isRefreshing && (System.currentTimeMillis() - startTime) < 5000) {
                    Thread.sleep(100)
                }

                // Check if refresh succeeded
                val refreshedToken = secureStorage.getToken()
                if (refreshedToken != null && refreshedToken != requestToken) {
                    Timber.d("✅ [Auth] Refresh completed, retrying request")
                    sessionManager.resetSessionExpiringState()  // Hide loading overlay
                    return buildRequestWithNewToken(response.request, refreshedToken)
                } else {
                    Timber.w("❌ [Auth] Refresh failed or timed out")
                    // Keep isSessionExpiring = true - will be reset after Login navigation
                    return null // Give up
                }
            }

            // Mark refresh as in progress
            isRefreshing = true

            try {
                // Attempt to refresh token (blocking call)
                val refreshResult = runBlocking {
                    authRepository.refreshAccessToken()
                }

                if (refreshResult is com.jaac.avoqado_tpv.core.domain.models.Result.Success) {
                    Timber.i("✅ [Auth] Token refreshed successfully, retrying original request")

                    // ✅ Hide loading overlay - refresh succeeded, no need to show "verifying" anymore
                    sessionManager.resetSessionExpiringState()

                    // 🔄 Notify observers (Socket.IO) to reconnect with fresh token
                    sessionManager.notifyTokenRefreshed()

                    // Get new token and retry
                    val newToken = secureStorage.getToken()
                    if (newToken != null) {
                        return buildRequestWithNewToken(response.request, newToken)
                    } else {
                        Timber.e("❌ [Auth] Token refresh succeeded but new token not found in storage")
                        return null
                    }
                } else {
                    Timber.e("❌ [Auth] Token refresh failed: $refreshResult")

                    // Refresh failed (refresh token expired) → Force logout
                    secureStorage.clearSession()
                    Timber.w("🚪 [Auth] Session cleared due to refresh failure - User must re-login")

                    // Notify UI that session has expired → triggers navigation to Login
                    sessionManager.notifySessionExpired()

                    return null // Give up (return null = OkHttp stops retrying)
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [Auth] Exception during token refresh")
                secureStorage.clearSession()

                // Notify UI that session has expired → triggers navigation to Login
                sessionManager.notifySessionExpired()

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
