package com.jaac.avoqado_campo.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds @Singleton
    abstract fun enlazarRepositorio(impl: RepositorioAuthCampoImpl): RepositorioAuthCampo
}
