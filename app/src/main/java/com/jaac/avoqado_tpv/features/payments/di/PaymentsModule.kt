package com.jaac.avoqado_tpv.features.payments.di

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.payments.data.repository.PaymentRepositoryImpl
import com.jaac.avoqado_tpv.features.payments.domain.repository.PaymentRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Payments Module
 *
 * Provides dependency injection bindings for payment history feature.
 *
 * **Bindings:**
 * - PaymentRepository → PaymentRepositoryImpl (singleton)
 */
@Module
@InstallIn(SingletonComponent::class)
object PaymentsModule {

    /**
     * Provide Payment Repository
     *
     * @param apiService Retrofit API service (provided by NetworkModule)
     * @return Payment repository implementation
     */
    @Provides
    @Singleton
    fun providePaymentRepository(
        apiService: ApiService
    ): PaymentRepository {
        return PaymentRepositoryImpl(apiService)
    }
}
