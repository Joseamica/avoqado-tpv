package com.jaac.avoqado_campo.identidad

import android.content.Context
import android.os.SystemClock
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

    private fun arranqueDe(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    @Provides @Singleton
    fun proveerReloj(almacen: AlmacenSeguro, @ApplicationContext context: Context): RelojAnclado =
        RelojAnclado(almacen, arranque = { arranqueDe(context) })

    @Provides @Singleton
    fun proveerPinLocal(almacen: AlmacenSeguro, reloj: RelojAnclado, @ApplicationContext context: Context): PinLocal =
        PinLocal(almacen, { reloj.ahora().epochMs }, SystemClock::elapsedRealtime, { arranqueDe(context) })
}
