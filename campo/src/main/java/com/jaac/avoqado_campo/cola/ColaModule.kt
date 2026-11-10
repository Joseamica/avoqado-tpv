package com.jaac.avoqado_campo.cola

import android.content.Context
import androidx.room.Room
import com.jaac.avoqado_campo.identidad.AlmacenSeguro
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ColaModule {

    @Provides @Singleton
    fun proveerBase(@ApplicationContext context: Context): BaseDeCampo =
        Room.databaseBuilder(context, BaseDeCampo::class.java, "campo.db").build()

    @Provides
    fun proveerPiezaDao(base: BaseDeCampo): PiezaDao = base.piezaDao()

    @Provides @Singleton
    fun proveerSecuencia(almacen: AlmacenSeguro): SecuenciaDeCaptura = SecuenciaDeCaptura(almacen)

    @Provides @Singleton
    fun proveerEncolador(
        dao: PiezaDao, quienSoy: QuienSoy, reloj: RelojAnclado, secuencia: SecuenciaDeCaptura,
        @ApplicationContext context: Context,
    ): Encolador = Encolador(dao, quienSoy, reloj, secuencia, File(context.filesDir, "cola"))
}
