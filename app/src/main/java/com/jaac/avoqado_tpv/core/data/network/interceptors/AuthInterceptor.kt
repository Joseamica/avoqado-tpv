package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth Interceptor
 *
 * Automatically adds JWT token to all requests.
 * For 401 handling and token refresh, see TokenAuthenticator.
 *
 * **Why separate Interceptor and Authenticator?**
 * - Interceptor: Adds Authorization header to all requests (no dependency cycle)
 * - Authenticator: Handles 401 responses and token refresh (uses Lazy<AuthRepository> to break cycle)
 *
 * **Pattern:** OkHttp best practice for authentication
 * - Interceptor runs BEFORE request (adds headers)
 * - Authenticator runs AFTER 401 response (refreshes token)
 *
 * @param secureStorage Storage for JWT token
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val secureStorage: SecureStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Get JWT token from secure storage
        val token = secureStorage.getToken()

        // If no token, proceed without auth header (e.g., login/activation endpoints)
        if (token == null) {
            Timber.d("No auth token found, proceeding without Authorization header")
            return chain.proceed(originalRequest)
        }

        // Add Authorization header
        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        Timber.d("🔐 [Auth] Added Authorization header to ${originalRequest.url}")

        // Execute request (Authenticator will handle 401 if token expired)
        return chain.proceed(authenticatedRequest)
    }
}
