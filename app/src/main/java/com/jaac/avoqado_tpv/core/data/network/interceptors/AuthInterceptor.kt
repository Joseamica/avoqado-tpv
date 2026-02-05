package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth Interceptor
 *
 * Automatically adds JWT token and app version headers to all requests.
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
 * **Version Headers (Square/Toast pattern):**
 * - X-App-Version-Code: Integer version code (e.g., 16)
 * - X-App-Version-Name: Version string (e.g., "1.4.2-sandbox")
 * Backend uses these for version gating (HTTP 426 if outdated)
 *
 * @param secureStorage Storage for JWT token
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val secureStorage: SecureStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Start building new request with version headers (always added)
        val requestBuilder = originalRequest.newBuilder()
            .header("X-App-Version-Code", BuildConfig.VERSION_CODE.toString())
            .header("X-App-Version-Name", BuildConfig.VERSION_NAME)

        // Get JWT token from secure storage
        val token = secureStorage.getToken()

        // If token exists, add Authorization header
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer $token")
            Timber.d("🔐 [Auth] Added Authorization + Version headers to ${originalRequest.url}")
        } else {
            Timber.d("📦 [Auth] Added Version headers (no auth) to ${originalRequest.url}")
        }

        // Execute request
        return chain.proceed(requestBuilder.build())
    }
}
