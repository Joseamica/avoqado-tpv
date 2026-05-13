package com.jaac.avoqado_tpv.features.checkout.di

import com.jaac.avoqado_tpv.features.checkout.data.repository.MosaicRepositoryImpl
import com.jaac.avoqado_tpv.features.checkout.data.repository.SavedCartsRepositoryImpl
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the unified Checkout feature.
 *
 * `ActiveCartState` and `CheckoutViewModel` are resolved automatically via
 * `@Singleton` and `@HiltViewModel` annotations, so they don't appear here.
 * This module only wires interface→impl bindings that need explicit binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CheckoutModule {

    @Binds
    @Singleton
    abstract fun bindSavedCartsRepository(
        impl: SavedCartsRepositoryImpl,
    ): SavedCartsRepository

    @Binds
    @Singleton
    abstract fun bindMosaicRepository(
        impl: MosaicRepositoryImpl,
    ): MosaicRepository
}
