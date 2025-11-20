package com.jaac.avoqado_tpv.core.di

import com.jaac.avoqado_tpv.core.data.repository.TerminalConfigRepositoryImpl
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.features.payment.data.MerchantRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.reports.data.repository.ReportsRepositoryImpl
import com.jaac.avoqado_tpv.features.reports.domain.repository.ReportsRepository
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
 * - TerminalConfigRepository → TerminalConfigRepositoryImpl
 *   (Fetches terminal configuration from backend on app startup)
 * - ReportsRepository → ReportsRepositoryImpl
 *   (Aggregates shift data into sales reports and analytics)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Bind TerminalConfigRepository to its implementation
     *
     * **Usage:**
     * ```kotlin
     * class SplashViewModel @Inject constructor(
     *     private val terminalConfigRepository: TerminalConfigRepository
     * ) : ViewModel() {
     *     // Fetch terminal config on app startup
     *     terminalConfigRepository.fetchConfig(serialNumber)
     * }
     * ```
     *
     * @param impl TerminalConfigRepositoryImpl instance (Hilt creates automatically)
     * @return TerminalConfigRepository interface
     */
    @Binds
    @Singleton
    abstract fun bindTerminalConfigRepository(
        impl: TerminalConfigRepositoryImpl
    ): TerminalConfigRepository

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

    /**
     * Bind ReportsRepository to its implementation
     *
     * **Usage:**
     * ```kotlin
     * @HiltViewModel
     * class ReportsViewModel @Inject constructor(
     *     private val reportsRepository: ReportsRepository
     * ) : ViewModel() {
     *     // Hilt automatically provides ReportsRepositoryImpl
     * }
     * ```
     *
     * @param impl ReportsRepositoryImpl instance (Hilt creates automatically)
     * @return ReportsRepository interface
     */
    @Binds
    @Singleton
    abstract fun bindReportsRepository(
        impl: ReportsRepositoryImpl
    ): ReportsRepository
}
