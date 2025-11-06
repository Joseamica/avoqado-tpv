package com.jaac.avoqado_tpv.core.di

import com.example.clean_lib_services.shared_tools.api.server.CoreServer
import com.example.clean_lib_services.shared_tools.api.server.TokenServer
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.payment.data.BlumonAuthManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PaymentModule - Hilt DI module for Blumon OAuth authentication
 *
 * **Provides:**
 * - TokenServer: Handles OAuth token requests (sandbox-tokener.blumonpay.net)
 * - CoreServer: Handles RSA + DUKPT key requests (sandbox-core.blumonpay.net)
 * - BlumonAuthManager: Orchestrates complete OAuth flow (3 steps)
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
        coreServer: CoreServer
    ): BlumonAuthManager {
        return BlumonAuthManager(
            deviceInfoManager = deviceInfoManager,
            tokenServer = tokenServer,
            coreServer = coreServer
        )
    }
}
