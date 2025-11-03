package com.jaac.avoqado_tpv.di

import android.content.Context
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
}
