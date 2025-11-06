package com.jaac.avoqado_tpv.core.di

import com.jaac.avoqado_tpv.features.payment.data.MerchantRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * RepositoryModule - Hilt DI module for repository bindings
 *
 * **Purpose:**
 * Binds repository interfaces to their implementations.
 * Follows Clean Architecture: domain layer defines contracts (interfaces),
 * data layer provides implementations.
 *
 * **Why Separate Module:**
 * Dagger/Hilt requires @Binds methods in abstract classes,
 * while @Provides methods are in object/class modules.
 * Splitting avoids compilation errors.
 *
 * **Bindings:**
 * - MerchantRepository → MerchantRepositoryImpl
 *   (Provides merchant account management for multi-merchant payment routing)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Bind MerchantRepository to its implementation
     *
     * **Usage:**
     * ```kotlin
     * @HiltViewModel
     * class PaymentViewModel @Inject constructor(
     *     private val merchantRepository: MerchantRepository
     * ) : ViewModel() {
     *     // Hilt automatically provides MerchantRepositoryImpl
     * }
     * ```
     *
     * @param impl MerchantRepositoryImpl instance (Hilt creates automatically)
     * @return MerchantRepository interface
     */
    @Binds
    @Singleton
    abstract fun bindMerchantRepository(
        impl: MerchantRepositoryImpl
    ): MerchantRepository
}
