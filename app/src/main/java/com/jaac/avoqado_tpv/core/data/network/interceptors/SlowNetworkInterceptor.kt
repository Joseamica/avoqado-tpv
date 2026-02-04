package com.jaac.avoqado_tpv.core.data.network.interceptors

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Slow Network Interceptor
 *
 * Simulates slow network conditions for testing payment flows
 * under poor connectivity. Toggle from SuperAdmin screen.
 *
 * **Usage:**
 * ```kotlin
 * // Enable slow network simulation
 * SlowNetworkInterceptor.enabled = true
 * SlowNetworkInterceptor.delayMs = 5000L  // 5 seconds
 *
 * // Disable
 * SlowNetworkInterceptor.enabled = false
 * ```
 *
 * **Test scenarios:**
 * - Payment timeout handling
 * - Loading state persistence
 * - User experience under slow 3G
 * - Retry logic verification
 *
 * ⚠️ WARNING: Only enable for testing, NEVER in production.
 */
@Singleton
class SlowNetworkInterceptor @Inject constructor() : Interceptor {

    companion object {
        /**
         * Enable/disable slow network simulation.
         * Toggle from SuperAdmin screen.
         */
        @Volatile
        var enabled: Boolean = false

        /**
         * Delay in milliseconds to add to each request.
         * Default: 3000ms (3 seconds)
         *
         * Recommended values:
         * - 1000ms: Slightly slow (good connection)
         * - 3000ms: Slow 3G (common test case)
         * - 5000ms: Very slow (edge case)
         * - 10000ms: Near timeout (stress test)
         */
        @Volatile
        var delayMs: Long = 3000L

        /**
         * Presets for common network conditions
         */
        object Presets {
            const val FAST_3G = 1000L      // 1 second
            const val SLOW_3G = 3000L      // 3 seconds
            const val EDGE = 5000L         // 5 seconds
            const val VERY_SLOW = 8000L    // 8 seconds
            const val NEAR_TIMEOUT = 12000L // 12 seconds
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (enabled && delayMs > 0) {
            val url = request.url.encodedPath
            Timber.w("🐢 [SlowNetwork] Simulating ${delayMs}ms delay for: $url")

            try {
                Thread.sleep(delayMs)
            } catch (e: InterruptedException) {
                Timber.w("🐢 [SlowNetwork] Sleep interrupted for: $url")
                Thread.currentThread().interrupt()
            }

            Timber.d("🐢 [SlowNetwork] Delay complete, proceeding with request: $url")
        }

        return chain.proceed(request)
    }
}
