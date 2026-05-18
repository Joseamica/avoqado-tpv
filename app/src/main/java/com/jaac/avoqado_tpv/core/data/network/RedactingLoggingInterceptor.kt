package com.jaac.avoqado_tpv.core.data.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber

/**
 * OkHttp interceptor that redacts sensitive JSON fields from response body logs.
 *
 * Specifically strips `"pin"` and `"password"` values per spec §4.5b PIN handling.
 * The AngelPay PIN MUST NEVER appear in OkHttp logs, Crashlytics breadcrumbs,
 * or any persisted log file.
 *
 * Wrap this around the existing `HttpLoggingInterceptor` configuration in
 * `NetworkModule` if/when AngelPay PIN-bearing payloads are routed through the
 * standard logging pipeline. Today, `LoggingInterceptor` only logs in DEBUG
 * builds; this class hardens the same path against accidental PIN leakage.
 *
 * The static `redactJsonFields()` helper is intentionally side-effect free so
 * it can be reused by other loggers (file logger, Crashlytics breadcrumbs).
 */
class RedactingLoggingInterceptor(
    private val logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger { msg ->
        Timber.tag("OkHttp").d(msg)
    },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        // peekBody() does not consume the response body, so downstream code can still read it.
        val body = response.peekBody(Long.MAX_VALUE).string()
        logger.log(redactJsonFields(body))
        return response
    }

    /**
     * Strip sensitive JSON string field values. Matches both single-line and
     * pretty-printed JSON. Numeric pin values (rare) are not currently
     * matched — the AngelPay backend always serialises the PIN as a string.
     */
    fun redactJsonFields(body: String): String =
        body
            .replace(Regex(""""pin"\s*:\s*"[^"]*""""), """"pin":"***"""")
            .replace(Regex(""""password"\s*:\s*"[^"]*""""), """"password":"***"""")
}
