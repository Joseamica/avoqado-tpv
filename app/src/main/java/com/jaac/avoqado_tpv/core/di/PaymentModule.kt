package com.jaac.avoqado_tpv.core.di

import com.example.clean_lib_services.shared_tools.api.server.CoreServer
import com.example.clean_lib_services.shared_tools.api.server.TokenServer
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.payment.data.BlumonAuthManager
// ⭐ NEW: Backend payment recording dependencies
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * PaymentModule - Hilt DI module for Blumon OAuth authentication
 *
 * ⭐ PRODUCTION MIGRATION (2025-11-19): Now supports Sandbox + Production via build variants
 *
 * **Provides:**
 * - TokenServer: Handles OAuth token requests
 *   - Sandbox: sandbox-tokener.blumonpay.net (via sandbox SDK)
 *   - Production: tokener.blumonpay.net (via production SDK)
 * - CoreServer: Handles RSA + DUKPT key requests
 *   - Sandbox: sandbox-core.blumonpay.net (via sandbox SDK)
 *   - Production: core.blumonpay.net (via production SDK)
 * - BlumonAuthManager: Orchestrates complete OAuth flow (3 steps)
 *
 * **Endpoints configured by SDK variant:**
 * - sandboxDebug/sandboxRelease: Uses blumon_sdk-debug.aar + lib-services-BP-SAND_1601.aar
 * - productionDebug/productionRelease: Uses blumon_sdk-prod.aar + lib_services-1.2.0.0-PROD.aar
 *
 * **Singleton Scope**: All dependencies are application-scoped
 */
@Module
@InstallIn(SingletonComponent::class)
object PaymentModule {

    /**
     * Provides TokenServer for OAuth token requests
     *
     * **Endpoint**: https://sandbox-tokener.blumonpay.net/oauth/token
     * **Purpose**: Step 1 of OAuth flow (get access_token)
     */
    @Provides
    @Singleton
    fun provideTokenServer(): TokenServer {
        return TokenServer()
    }

    /**
     * Provides CoreServer for RSA + DUKPT key requests
     *
     * **Endpoints**:
     * - POST /device/getKey (RSA keys)
     * - POST /device/initDukptKeys (DUKPT keys)
     * **Purpose**: Steps 2-3 of OAuth flow (get encryption keys)
     */
    @Provides
    @Singleton
    fun provideCoreServer(): CoreServer {
        return CoreServer()
    }

    /**
     * Provides BlumonAuthManager with all dependencies
     *
     * **Complete OAuth Flow:**
     * 1. Calculate SHA256 password
     * 2. Get access_token from TokenServer
     * 3. Get RSA keys from CoreServer
     * 4. Get DUKPT keys from CoreServer
     *
     * @param deviceInfoManager Provides device brand/model for password calculation
     * @param tokenServer Handles token authentication
     * @param coreServer Handles key retrieval
     */
    @Provides
    @Singleton
    fun provideBlumonAuthManager(
        deviceInfoManager: DeviceInfoManager,
        tokenServer: TokenServer,
        coreServer: CoreServer,
        apiService: com.jaac.avoqado_tpv.core.data.network.ApiService
    ): BlumonAuthManager {
        return BlumonAuthManager(
            deviceInfoManager = deviceInfoManager,
            tokenServer = tokenServer,
            coreServer = coreServer,
            apiService = apiService
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ⭐ NEW: BACKEND PAYMENT RECORDING (Retrofit API)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Provides PaymentApiService for backend payment recording.
     *
     * **Endpoints:**
     * - POST /tpv/venues/{venueId}/fast
     * - POST /tpv/venues/{venueId}/orders/{orderId}
     *
     * **Authentication:** Uses AuthInterceptor (adds Bearer token to all requests)
     *
     * @param retrofit Retrofit instance configured with base URL and interceptors
     */
    @Provides
    @Singleton
    fun providePaymentApiService(@PaymentClient retrofit: Retrofit): PaymentApiService {
        return retrofit.create(PaymentApiService::class.java)
    }

    /**
     * Provides FastPaymentRecorder for recording fast payments.
     *
     * **Usage:** POST /tpv/venues/{venueId}/fast
     *
     * @param apiService PaymentApiService for Retrofit calls
     */
    @Provides
    @Singleton
    fun provideFastPaymentRecorder(
        apiService: PaymentApiService
    ): FastPaymentRecorder {
        return FastPaymentRecorder(apiService)
    }

    /**
     * Provides OrderPaymentRecorder for recording order payments.
     *
     * **Usage:** POST /tpv/venues/{venueId}/orders/{orderId}
     *
     * **Note:** This is NOT used yet (no create order feature implemented).
     * Ready for when the feature is built.
     *
     * @param apiService PaymentApiService for Retrofit calls
     */
    @Provides
    @Singleton
    fun provideOrderPaymentRecorder(
        apiService: PaymentApiService
    ): OrderPaymentRecorder {
        return OrderPaymentRecorder(apiService)
    }

    /**
     * Provides RecordPaymentUseCase for orchestrating payment recording.
     *
     * **Strategy Pattern:**
     * This use case selects the appropriate recorder (Fast vs Order)
     * based on PaymentContext type.
     *
     * @param fastPaymentRecorder FastPaymentRecorder singleton
     * @param orderPaymentRecorder OrderPaymentRecorder singleton
     */
    @Provides
    @Singleton
    fun provideRecordPaymentUseCase(
        fastPaymentRecorder: FastPaymentRecorder,
        orderPaymentRecorder: OrderPaymentRecorder
    ): RecordPaymentUseCase {
        return RecordPaymentUseCase(
            fastPaymentRecorder = fastPaymentRecorder,
            orderPaymentRecorder = orderPaymentRecorder
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ⭐ NEW: OFFLINE PAYMENT QUEUE (Room Database)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Provides PaymentQueueRepository for offline payment queue.
     *
     * **Purpose:** Queue failed payment recordings for retry
     *
     * **Implementation:** PaymentQueueRepositoryImpl (Room database persistence)
     *
     * **Injected Into:**
     * - PaymentViewModel: Enqueue payments when backend recording fails
     * - PaymentSyncWorker: Fetch pending payments for retry
     *
     * @param pendingPaymentDao DAO for Room database operations
     */
    @Provides
    @Singleton
    fun providePaymentQueueRepository(
        pendingPaymentDao: PendingPaymentDao
    ): PaymentQueueRepository {
        return PaymentQueueRepositoryImpl(
            pendingPaymentDao = pendingPaymentDao
        )
    }
}
