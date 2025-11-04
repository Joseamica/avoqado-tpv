package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject

/**
 * Tenant Interceptor
 *
 * Ensures venueId is included in all requests for multi-tenant isolation
 * Adds X-Venue-Id header to every request
 *
 * **CRITICAL for security:**
 * - Prevents data leakage between venues
 * - Backend validates venueId matches user's access
 * - All database queries filtered by venueId
 *
 * @param secureStorage Storage for venueId
 */
class TenantInterceptor @Inject constructor(
    private val secureStorage: SecureStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath

        // Skip tenant header for public endpoints (activation doesn't have venueId yet)
        val publicEndpoints = listOf("/api/v1/tpv/activate")
        val isPublicEndpoint = publicEndpoints.any { path.contains(it) }

        if (isPublicEndpoint) {
            Timber.d("Public endpoint detected - skipping X-Venue-Id header: $path")
            return chain.proceed(originalRequest)
        }

        // Get venueId from secure storage
        val venueId = secureStorage.getVenueId()

        if (venueId == null) {
            Timber.w("No venueId found - Request may fail with 403 Forbidden")
            return chain.proceed(originalRequest)
        }

        // Add X-Venue-Id header for tenant isolation
        val tenantRequest = originalRequest.newBuilder()
            .header("X-Venue-Id", venueId)
            .build()

        Timber.d("Added X-Venue-Id: $venueId to ${originalRequest.url}")

        return chain.proceed(tenantRequest)
    }
}
