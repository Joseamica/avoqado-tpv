package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject

/**
 * Auth Interceptor
 *
 * Automatically adds JWT token to all requests
 * Handles 401 Unauthorized responses (session expired)
 *
 * @param secureStorage Storage for JWT token
 */
class AuthInterceptor @Inject constructor(
    private val secureStorage: SecureStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Get JWT token from secure storage
        val token = secureStorage.getToken()

        // If no token, proceed without auth header
        if (token == null) {
            Timber.d("No auth token found, proceeding without Authorization header")
            return chain.proceed(originalRequest)
        }

        // Add Authorization header
        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        Timber.d("Added Authorization header to ${originalRequest.url}")

        // Execute request
        val response = chain.proceed(authenticatedRequest)

        // Handle 401 Unauthorized (session expired)
        if (response.code == 401) {
            Timber.w("Received 401 Unauthorized - Session expired")
            // Clear expired session
            secureStorage.clearSession()
            // TODO: Emit event to force logout in UI
            // EventBus.emit(SessionExpiredEvent)
        }

        return response
    }
}
