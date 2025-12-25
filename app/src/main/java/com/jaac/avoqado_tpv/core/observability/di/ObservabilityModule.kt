package com.jaac.avoqado_tpv.core.observability.di

import android.content.Context
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.core.observability.logger.FileLogger
import com.jaac.avoqado_tpv.core.observability.logger.RemoteLogger
import com.jaac.avoqado_tpv.core.observability.monitor.HealthMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Observability system
 */
@Module
@InstallIn(SingletonComponent::class)
object ObservabilityModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return Gson()
    }

    @Provides
    @Singleton
    fun provideRemoteLogger(
        socketManager: SocketManager,
        gson: Gson
    ): RemoteLogger {
        return RemoteLogger(socketManager, gson)
    }

    @Provides
    @Singleton
    fun provideFileLogger(
        @ApplicationContext context: Context,
        gson: Gson
    ): FileLogger {
        return FileLogger(context, gson)
    }

    @Provides
    @Singleton
    fun provideHealthMonitor(
        @ApplicationContext context: Context,
        socketManager: SocketManager
    ): HealthMonitor {
        return HealthMonitor(context, socketManager)
    }

    @Provides
    @Singleton
    fun provideObservabilityManager(
        @ApplicationContext context: Context,
        remoteLogger: RemoteLogger,
        fileLogger: FileLogger,
        healthMonitor: HealthMonitor
    ): ObservabilityManager {
        return ObservabilityManager(context, remoteLogger, fileLogger, healthMonitor)
    }
}
