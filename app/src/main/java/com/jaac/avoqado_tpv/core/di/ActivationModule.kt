package com.jaac.avoqado_tpv.core.di

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.repository.ActivationRepositoryImpl
import com.jaac.avoqado_tpv.core.domain.repository.ActivationRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Activation Dependency Injection Module
 *
 * Provides dependencies for terminal activation feature.
 * Follows Clean Architecture dependency rule:
 * - Domain layer (interface) ← Data layer (implementation)
 *
 * **Provided Dependencies:**
 * - ActivationRepository (interface bound to ActivationRepositoryImpl)
 *
 * **Usage:**
 * Dependencies are automatically injected by Hilt:
 * ```kotlin
 * @HiltViewModel
 * class ActivationViewModel @Inject constructor(
 *     private val activateTerminalUseCase: ActivateTerminalUseCase // Uses ActivationRepository
 * ) : ViewModel()
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
object ActivationModule {

    /**
     * Provide ActivationRepository implementation
     *
     * Binds domain layer interface to data layer implementation.
     * Singleton scope ensures single instance throughout app lifecycle.
     *
     * **Dependencies:**
     * - ApiService: For backend API calls
     * - SecureStorage: For storing venueId after successful activation
     *
     * @param apiService Retrofit service for API calls
     * @param secureStorage Encrypted storage for sensitive data
     * @return ActivationRepository implementation
     */
    @Provides
    @Singleton
    fun provideActivationRepository(
        apiService: ApiService,
        secureStorage: SecureStorage
    ): ActivationRepository {
        return ActivationRepositoryImpl(
            apiService = apiService,
            secureStorage = secureStorage
        )
    }
}
