package com.jaac.avoqado_campo.cola

import android.content.Context
import androidx.room.Room
import com.jaac.avoqado_campo.AlcanceDeLaApp
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.entrada.RepositorioDeEntrada
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.EstadoDelGps
import com.jaac.avoqado_campo.identidad.AlmacenSeguro
import com.jaac.avoqado_campo.identidad.BorradoDelCelular
import com.jaac.avoqado_campo.identidad.MutexDeSesion
import com.jaac.avoqado_campo.identidad.PinLocal
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.latido.Latido
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ColaModule {

    @Provides @Singleton
    fun proveerBase(@ApplicationContext context: Context): BaseDeCampo =
        Room.databaseBuilder(context, BaseDeCampo::class.java, "campo.db").addMigrations(MIGRACION_1_2).build()

    @Provides
    fun proveerPiezaDao(base: BaseDeCampo): PiezaDao = base.piezaDao()

    @Provides @Singleton
    fun proveerSecuencia(almacen: AlmacenSeguro): SecuenciaDeCaptura = SecuenciaDeCaptura(almacen)

    @Provides @Singleton
    fun proveerEncolador(
        dao: PiezaDao, quienSoy: QuienSoy, reloj: RelojAnclado, secuencia: SecuenciaDeCaptura,
        @ApplicationContext context: Context,
    ): Encolador = Encolador(dao, quienSoy, reloj, secuencia, File(context.filesDir, "cola"))

    @Provides @Singleton
    fun proveerSubidorDeFotos(cliente: OkHttpClient): SubidorDeFotos = SubidorDeFotos(cliente)

    @Provides @Singleton
    fun proveerSubidor(
        dao: PiezaDao, api: CampoApi, guardia: GuardiaDeSesion, quienSoy: QuienSoy, fotos: SubidorDeFotos,
        mutex: MutexDeSubida, @ApplicationContext context: Context,
    ): Subidor = Subidor(dao, api, guardia, quienSoy, fotos, mutex, File(context.filesDir, "cola"), File(context.cacheDir, "fotos"))

    @Provides @Singleton
    fun proveerSubidorDeGracia(
        dao: PiezaDao, api: CampoApi, quienSoy: QuienSoy, fotos: SubidorDeFotos, mutex: MutexDeSubida,
    ): SubidorDeGracia = SubidorDeGracia(dao, api, quienSoy, fotos, mutex)

    @Provides @Singleton
    fun proveerCopia(
        @ApplicationContext context: Context, api: CampoApi, guardia: GuardiaDeSesion, dao: PiezaDao, quienSoy: QuienSoy,
    ): RepositorioDeCopia = RepositorioDeCopia(context, api, guardia, dao, quienSoy)

    @Provides @Singleton
    fun proveerLatido(api: CampoApi, quienSoy: QuienSoy, dao: PiezaDao, reloj: RelojAnclado): Latido = Latido(api, quienSoy, dao, reloj)

    @Provides @Singleton
    fun proveerEntrada(
        api: CampoApi, quienSoy: QuienSoy, reloj: RelojAnclado, pinLocal: PinLocal, mutexDeSesion: MutexDeSesion,
        dao: PiezaDao, almacen: AlmacenSeguro, copia: RepositorioDeCopia, @AlcanceDeLaApp alcance: CoroutineScope,
    ): RepositorioDeEntrada = RepositorioDeEntrada(api, quienSoy, reloj, pinLocal, mutexDeSesion, dao, almacen, copia, alcance)

    @Provides @Singleton
    fun proveerBorrado(
        quienSoy: QuienSoy, mutexDeSubida: MutexDeSubida, mutexDeSesion: MutexDeSesion, gps: ControlDeGps, base: BaseDeCampo,
        dao: PiezaDao, copia: RepositorioDeCopia, pinLocal: PinLocal, estadoDelGps: EstadoDelGps, @ApplicationContext context: Context,
    ): BorradoDelCelular = BorradoDelCelular(
        quienSoy, mutexDeSubida, mutexDeSesion, gps, base::clearAllTables, dao, File(context.filesDir, "cola"), copia, pinLocal, estadoDelGps,
        File(context.cacheDir, "fotos"),
    )
}
