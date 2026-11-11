package com.jaac.avoqado_campo

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Trabajo que no debe morir con una pantalla ni hacerla esperar (p. ej. refrescar la copia después de entrar). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AlcanceDeLaApp

@Module
@InstallIn(SingletonComponent::class)
object AlcanceModule {
    @Provides @Singleton @AlcanceDeLaApp
    fun proveerAlcance(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
