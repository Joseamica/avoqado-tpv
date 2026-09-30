package com.jaac.avoqado_tpv.core.di

import android.content.Context
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.location.CellLocationApi
import com.jaac.avoqado_tpv.core.location.CellLocationApiImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt para proveer dependencias a nivel de aplicación
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provee el contexto de la aplicación
     */
    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context {
        return context
    }

    /**
     * Provee la implementación de CellLocationApi
     * Usado por LocationService para fallback de ubicación por Cell ID
     */
    @Provides
    @Singleton
    fun provideCellLocationApi(apiService: ApiService): CellLocationApi {
        return CellLocationApiImpl(apiService)
    }

    /**
     * El reloj de pared, inyectable para que las reglas de fecha se prueben con una hora fija (corte de AngelPay, 29-sep).
     * UTC a propósito: cada regla elige su zona explícitamente, nunca la del aparato.
     */
    @Provides
    @Singleton
    fun provideClock(): java.time.Clock = java.time.Clock.systemUTC()
}
