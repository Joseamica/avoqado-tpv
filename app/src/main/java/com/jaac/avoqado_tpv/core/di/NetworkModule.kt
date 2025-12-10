package com.jaac.avoqado_tpv.core.di

import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.interceptors.AuthInterceptor
import com.jaac.avoqado_tpv.core.data.network.interceptors.LoggingInterceptor
import com.jaac.avoqado_tpv.core.data.network.interceptors.TenantInterceptor
import com.jaac.avoqado_tpv.core.data.network.interceptors.TokenAuthenticator
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
     * ⚠️ PRODUCTION ONLY - Disabled in DEBUG to allow testing with dev servers
     *
     * **Security:** Prevents man-in-the-middle attacks by validating server certificates
     * Pattern used by Square POS, Toast POS, Stripe, etc.
     *
     * **Certificate Rotation:**
     * - Pin MULTIPLE certificates (current + backup) to avoid downtime during rotation
     * - Backend should rotate certificates 30 days before expiration
     * - App should be updated with new pins before old certificates expire
     *
     * **Current Pins (Example - MUST UPDATE BEFORE PRODUCTION):**
     * - sha256/AAAA... → Primary certificate (api.avoqado.io)
     * - sha256/BBBB... → Backup certificate (for rotation)
     *
     * @return CertificatePinner instance or null (DEBUG)
     */
    @Provides
    @Singleton
    fun provideCertificatePinner(): CertificatePinner? {
        // Skip certificate pinning in DEBUG builds
        if (BuildConfig.DEBUG) {
            return null
        }

        // ⚠️ TODO: UPDATE THESE PINS BEFORE PRODUCTION DEPLOYMENT
        // Get real pins using: openssl s_client -connect api.avoqado.io:443 | openssl x509 -pubkey -noout | ...
        return CertificatePinner.Builder()
            // Primary certificate (current)
            .add("api.avoqado.io", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") // ← PLACEHOLDER
            // Backup certificate (for rotation - prevents downtime)
            .add("api.avoqado.io", "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=") // ← PLACEHOLDER
            .build()
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
        certificatePinner: CertificatePinner?
    ): OkHttpClient {
        return OkHttpClient.Builder()
            // Interceptors (order matters!)
            .addInterceptor(authInterceptor)        // Add JWT token
            .addInterceptor(tenantInterceptor)      // Add venueId
            .addInterceptor(LoggingInterceptor.create())  // Log requests (DEBUG only)

            // Authenticator (handles 401 responses with token refresh)
            .authenticator(tokenAuthenticator)       // ✅ Refresh token on 401

            // Timeouts
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

            // Retry on connection failure
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
     * ApiService interface for making API calls
     */
    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
