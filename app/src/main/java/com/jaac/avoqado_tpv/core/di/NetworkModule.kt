package com.jaac.avoqado_tpv.core.di

import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.interceptors.AuthInterceptor
import com.jaac.avoqado_tpv.core.data.network.interceptors.LoggingInterceptor
import com.jaac.avoqado_tpv.core.data.network.interceptors.TenantInterceptor
import com.jaac.avoqado_tpv.core.data.network.interceptors.TokenAuthenticator
import com.jaac.avoqado_tpv.core.data.network.interceptors.VersionGateInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Network Module
 *
 * Provides Retrofit, OkHttp, and ApiService instances
 * Configured with:
 * - Authentication interceptor (JWT token)
 * - Tenant interceptor (venueId header)
 * - Logging interceptor (DEBUG only)
 * - Certificate pinning (PRODUCTION ONLY)
 * - Connection timeout (30s)
 * - Read/Write timeout (30s)
 *
 * **Certificate Pinning:**
 * Protects against man-in-the-middle (MITM) attacks by validating server certificates.
 * Pins are only applied in RELEASE builds to avoid blocking development/staging servers.
 *
 * **How to Get Certificate Pins:**
 * 1. Run: `openssl s_client -connect api.avoqado.io:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64`
 * 2. Or use CertificatePinner logging in DEBUG: `.certificatePinner(CertificatePinner.Builder().build())`
 * 3. Connect to server → Check Logcat for "Certificate pinning failure!" → Copy SHA256 hash
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Base URL for API
     *
     * Uses PROD URL for production flavor, DEV URL for sandbox flavor.
     * This allows productionDebug to use the real API while sandboxDebug uses ngrok.
     */
    @Provides
    @Singleton
    fun provideBaseUrl(): String {
        return if (BuildConfig.BLUMON_ENV == "PROD") {
            BuildConfig.API_BASE_URL  // Production: api.avoqado.io
        } else {
            BuildConfig.API_BASE_URL_DEV  // Sandbox: ngrok dev server
        }
    }

    /**
     * Certificate Pinner for SSL pinning
     *
     * **DISABLED (2025-12-26):**
     * Certificate pinning is disabled for all builds. HTTPS provides sufficient
     * security for a POS app communicating with its own backend.
     *
     * **Why disabled:**
     * - Let's Encrypt certificates rotate every 90 days
     * - Requires app updates to maintain pins (operational burden)
     * - Risk of bricking deployed terminals if pins expire
     * - HTTPS already prevents MITM attacks for trusted CAs
     *
     * **If you need to re-enable pinning:**
     * 1. Get current pin: openssl s_client -connect api.avoqado.io:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
     * 2. Add BOTH current AND backup pins (root CA)
     * 3. Set up monitoring for certificate expiration
     * 4. Have a process to update pins before expiration
     *
     * @return null (pinning disabled)
     */
    @Provides
    @Singleton
    fun provideCertificatePinner(): CertificatePinner? {
        // Certificate pinning disabled - HTTPS is sufficient for our use case
        // This prevents app failures when Render rotates certificates
        return null
    }

    /**
     * OkHttpClient with interceptors, authenticator, and timeouts
     *
     * **Interceptor vs Authenticator:**
     * - Interceptor: Runs BEFORE request (adds headers)
     * - Authenticator: Runs AFTER 401 response (refreshes token)
     *
     * **Lazy Injection:**
     * TokenAuthenticator uses Lazy<AuthRepository> to break dependency cycle.
     * This is safe because Authenticator is only called AFTER OkHttpClient is fully constructed.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tenantInterceptor: TenantInterceptor,
        tokenAuthenticator: TokenAuthenticator,  // ✅ Handles 401 with token refresh
        versionGateInterceptor: VersionGateInterceptor,  // 🚨 Handles 426 Upgrade Required
        certificatePinner: CertificatePinner?,
        slowNetworkInterceptor: com.jaac.avoqado_tpv.core.data.network.interceptors.SlowNetworkInterceptor  // 🐢 DEBUG: Simulate slow network
    ): OkHttpClient {
        return OkHttpClient.Builder()
            // Interceptors (order matters!)
            .addInterceptor(slowNetworkInterceptor) // 🐢 DEBUG: Slow network simulation (first, before auth)
            .addInterceptor(authInterceptor)        // Add JWT token + version headers
            .addInterceptor(tenantInterceptor)      // Add venueId
            .addInterceptor(versionGateInterceptor) // 🚨 Handle 426 Upgrade Required
            .addInterceptor(LoggingInterceptor.create())  // Log requests (DEBUG only)

            // Authenticator (handles 401 responses with token refresh)
            .authenticator(tokenAuthenticator)       // ✅ Refresh token on 401

            // Per-request timeouts
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

            // 🛡️ HTTP/2 keep-alive — kills stale connections after Doze/wake within 15s.
            // Without this, the OS or NAT can silently kill an idle TCP connection while OkHttp
            // keeps it in the pool. Next request reuses the zombie connection and hangs until
            // readTimeout (30s). This was the root cause of "Sin conexión al servidor",
            // "Verificando sesión...", "Procesando chip..." stalls reported at Doña Simona
            // and other PAX terminals after long idle periods. See OkHttp docs:
            // https://square.github.io/okhttp/3.x/okhttp/okhttp3/OkHttpClient.Builder.html#pingInterval-long-java.util.concurrent.TimeUnit-
            .pingInterval(15, TimeUnit.SECONDS)

            // 🛡️ Total call budget — even if connect/read/write each cap at 30s, callTimeout
            // bounds the entire call (DNS + TCP + TLS + headers + body + retry). This caps the
            // worst-case "loading state" duration at 25s instead of 30s+ on bad networks.
            .callTimeout(25, TimeUnit.SECONDS)

            // Retry on connection failure (helps when pingInterval evicts a dead conn mid-call)
            .retryOnConnectionFailure(true)

            // Certificate pinning (PRODUCTION ONLY)
            .apply {
                certificatePinner?.let { pinner ->
                    certificatePinner(pinner)
                }
            }

            .build()
    }

    /**
     * Payment-specific OkHttpClient with shorter timeouts.
     *
     * This client is used ONLY for backend payment recording calls
     * (`recordFastPayment` / `recordOrderPayment`) so offline queue fallback
     * activates faster when backend is slow/unreachable.
     *
     * Important: This does NOT affect Blumon SDK networking.
     */
    @Provides
    @Singleton
    @PaymentClient
    fun providePaymentOkHttpClient(
        okHttpClient: OkHttpClient
    ): OkHttpClient {
        return okHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // 🛡️ Total call budget for payment recording — fail-fast so offline queue activates
            // sooner if backend is unreachable. Inherits pingInterval(15s) from the parent client.
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Retrofit instance with Gson converter
     */
    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        baseUrl: String
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Payment-specific Retrofit with shorter timeout client.
     */
    @Provides
    @Singleton
    @PaymentClient
    fun providePaymentRetrofit(
        @PaymentClient okHttpClient: OkHttpClient,
        baseUrl: String
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * ApiService interface for making API calls
     */
    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
