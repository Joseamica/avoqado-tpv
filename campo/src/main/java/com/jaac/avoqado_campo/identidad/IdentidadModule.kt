package com.jaac.avoqado_campo.identidad

import android.content.Context
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IdentidadModule {

    @Provides @Singleton
    fun proveerAlmacen(@ApplicationContext context: Context): AlmacenSeguro = AlmacenSeguroCifrado(context)

    @Provides @Singleton
    fun proveerQuienSoy(almacen: AlmacenSeguro): QuienSoy = QuienSoy(almacen)

    @Provides @Singleton
    fun proveerReloj(almacen: AlmacenSeguro, @ApplicationContext context: Context): RelojAnclado =
        RelojAnclado(almacen, arranque = { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) })

    @Provides @Singleton
    fun proveerPinLocal(almacen: AlmacenSeguro): PinLocal = PinLocal(almacen, System::currentTimeMillis)
}
