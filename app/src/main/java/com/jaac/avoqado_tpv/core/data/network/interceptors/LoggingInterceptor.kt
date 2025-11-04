package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.jaac.avoqado_tpv.BuildConfig
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber

/**
 * Logging Interceptor Factory
 *
 * Creates OkHttp logging interceptor that logs to Timber
 * Only enabled in DEBUG builds for security
 *
 * Logs:
 * - Request method, URL, headers
 * - Request body (JSON)
 * - Response status code
 * - Response body (JSON)
 * - Request duration
 */
object LoggingInterceptor {

    /**
     * Create logging interceptor
     *
     * @return HttpLoggingInterceptor configured for Timber
     */
    fun create(): HttpLoggingInterceptor {
        val logger = HttpLoggingInterceptor.Logger { message ->
            Timber.tag("OkHttp").d(message)
        }

        return HttpLoggingInterceptor(logger).apply {
            level = if (BuildConfig.DEBUG) {
                // Log full request/response in DEBUG
                HttpLoggingInterceptor.Level.BODY
            } else {
                // No logging in RELEASE for security
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }
}
