package com.jaac.avoqado_campo.red

import com.jaac.avoqado_campo.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RedModule {

    @Provides @Singleton
    fun proveerCliente(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS) // fotos
        // BASIC = sólo la línea de petición y de respuesta: los cuerpos y cabeceras llevan PIN, credencial y tokens.
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Provides @Singleton
    fun proveerRetrofit(cliente: OkHttpClient): Retrofit = crearRetrofit(BuildConfig.API_BASE_URL, cliente)

    /** La base va aparte para que las pruebas apunten a MockWebServer. */
    fun crearRetrofit(baseUrl: String, cliente: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(cliente)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides @Singleton
    fun proveerApi(retrofit: Retrofit): CampoApi = retrofit.create(CampoApi::class.java)
}
