package com.jaac.avoqado_tpv.core.di

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.ordering.data.api.CustomerApiService
import com.jaac.avoqado_tpv.features.ordering.data.api.DiscountApiService
import com.jaac.avoqado_tpv.features.ordering.data.api.FloorElementApiService
import com.jaac.avoqado_tpv.features.ordering.data.api.OrderApiService
import com.jaac.avoqado_tpv.features.ordering.data.api.TableApiService
import com.jaac.avoqado_tpv.features.ordering.data.repository.CustomerRepositoryImpl
import com.jaac.avoqado_tpv.features.ordering.data.repository.DiscountRepositoryImpl
import com.jaac.avoqado_tpv.features.ordering.data.repository.FloorElementRepositoryImpl
import com.jaac.avoqado_tpv.features.ordering.data.repository.OrderRepositoryImpl
import com.jaac.avoqado_tpv.features.ordering.data.repository.ProductRepositoryImpl
import com.jaac.avoqado_tpv.features.ordering.data.repository.TableRepositoryImpl
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElementRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.ordering.domain.TableRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Ordering Module
 *
 * Provides dependencies for the ordering system:
 * - TableApiService: Retrofit service for table management
 * - TableRepository: Repository for table operations
 * - FloorElementApiService: Retrofit service for floor element management
 * - FloorElementRepository: Repository for floor element operations
 * - ProductRepository: Repository for menu/products (uses shared ApiService)
 * - OrderApiService: Retrofit service for order management
 * - OrderRepository: Repository for order operations
 */
@Module
@InstallIn(SingletonComponent::class)
object OrderingModule {

    /**
     * Provide TableApiService
     *
     * Creates Retrofit service for table management endpoints.
     * Uses shared Retrofit instance from NetworkModule.
     */
    @Provides
    @Singleton
    fun provideTableApiService(retrofit: Retrofit): TableApiService {
        return retrofit.create(TableApiService::class.java)
    }

    /**
     * Provide TableRepository
     *
     * Binds TableRepositoryImpl to TableRepository interface.
     * Repository handles data operations and error mapping.
     */
    @Provides
    @Singleton
    fun provideTableRepository(
        apiService: TableApiService
    ): TableRepository {
        return TableRepositoryImpl(apiService)
    }

    /**
     * Provide FloorElementApiService
     *
     * Creates Retrofit service for floor element management endpoints.
     * Handles decorative elements (walls, bars, service areas, labels, doors).
     * Uses shared Retrofit instance from NetworkModule.
     */
    @Provides
    @Singleton
    fun provideFloorElementApiService(retrofit: Retrofit): FloorElementApiService {
        return retrofit.create(FloorElementApiService::class.java)
    }

    /**
     * Provide FloorElementRepository
     *
     * Binds FloorElementRepositoryImpl to FloorElementRepository interface.
     * Repository handles floor element CRUD operations and error mapping.
     */
    @Provides
    @Singleton
    fun provideFloorElementRepository(
        apiService: FloorElementApiService
    ): FloorElementRepository {
        return FloorElementRepositoryImpl(apiService)
    }

    /**
     * Provide ProductRepository
     *
     * Binds ProductRepositoryImpl to ProductRepository interface.
     * Repository handles menu/product operations and error mapping.
     * Uses shared ApiService (not dedicated ProductApiService) for now.
     *
     * Backend endpoint: GET /api/v1/venues/{venueId}/products
     */
    @Provides
    @Singleton
    fun provideProductRepository(
        apiService: ApiService
    ): ProductRepository {
        return ProductRepositoryImpl(apiService)
    }

    /**
     * Provide OrderApiService
     *
     * Creates Retrofit service for order management endpoints.
     * Handles order CRUD operations, item management, and status updates.
     * Uses shared Retrofit instance from NetworkModule.
     */
    @Provides
    @Singleton
    fun provideOrderApiService(retrofit: Retrofit): OrderApiService {
        return retrofit.create(OrderApiService::class.java)
    }

    /**
     * Provide OrderRepository
     *
     * Binds OrderRepositoryImpl to OrderRepository interface.
     * Repository handles order operations and error mapping.
     *
     * Backend endpoints:
     * - GET /api/v1/tpv/venues/{venueId}/orders/{orderId}
     * - Other endpoints implemented as needed
     */
    @Provides
    @Singleton
    fun provideOrderRepository(
        apiService: OrderApiService,
        customerApiService: CustomerApiService
    ): OrderRepository {
        return OrderRepositoryImpl(apiService, customerApiService)
    }

    // ==========================================
    // CUSTOMER MANAGEMENT
    // ==========================================

    /**
     * Provide CustomerApiService
     *
     * Creates Retrofit service for customer management endpoints.
     * Handles customer search, lookup, and quick create operations.
     * Uses shared Retrofit instance from NetworkModule.
     */
    @Provides
    @Singleton
    fun provideCustomerApiService(retrofit: Retrofit): CustomerApiService {
        return retrofit.create(CustomerApiService::class.java)
    }

    /**
     * Provide CustomerRepository
     *
     * Binds CustomerRepositoryImpl to CustomerRepository interface.
     * Repository handles customer search and creation operations.
     *
     * Backend endpoints:
     * - GET /api/v1/tpv/venues/{venueId}/customers/search
     * - GET /api/v1/tpv/venues/{venueId}/customers/search/phone
     * - POST /api/v1/tpv/venues/{venueId}/customers/quick
     */
    @Provides
    @Singleton
    fun provideCustomerRepository(
        apiService: CustomerApiService
    ): CustomerRepository {
        return CustomerRepositoryImpl(apiService)
    }

    // ==========================================
    // DISCOUNT & COUPON MANAGEMENT
    // ==========================================

    /**
     * Provide DiscountApiService
     *
     * Creates Retrofit service for discount and coupon endpoints.
     * Handles predefined discounts, manual discounts, and coupon operations.
     * Uses shared Retrofit instance from NetworkModule.
     */
    @Provides
    @Singleton
    fun provideDiscountApiService(retrofit: Retrofit): DiscountApiService {
        return retrofit.create(DiscountApiService::class.java)
    }

    /**
     * Provide DiscountRepository
     *
     * Binds DiscountRepositoryImpl to DiscountRepository interface.
     * Repository handles discount and coupon operations.
     *
     * Backend endpoints:
     * - GET /api/v1/tpv/venues/{venueId}/discounts
     * - POST /api/v1/tpv/venues/{venueId}/orders/{orderId}/discounts/predefined
     * - POST /api/v1/tpv/venues/{venueId}/orders/{orderId}/discounts/manual
     * - POST /api/v1/tpv/venues/{venueId}/coupons/validate
     */
    @Provides
    @Singleton
    fun provideDiscountRepository(
        apiService: DiscountApiService
    ): DiscountRepository {
        return DiscountRepositoryImpl(apiService)
    }
}
