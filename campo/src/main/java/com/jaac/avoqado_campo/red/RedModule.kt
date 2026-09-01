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
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Provides @Singleton
    fun proveerRetrofit(cliente: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(cliente)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides @Singleton
    fun proveerApi(retrofit: Retrofit): CampoApi = retrofit.create(CampoApi::class.java)
}
