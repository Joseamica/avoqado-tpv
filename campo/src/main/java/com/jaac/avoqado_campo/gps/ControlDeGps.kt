package com.jaac.avoqado_campo.gps

import com.jaac.avoqado_campo.identidad.AlmacenSeguro
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

sealed interface ResultadoGps {
    object Siguiendo : ResultadoGps
    data class NoSigue(val motivo: String) : ResultadoGps
}

/** Arranca y para el seguimiento de la ubicación del turno (Tarea 12). */
interface ControlDeGps {
    fun iniciar(): ResultadoGps
    fun detener()
}

/** Provisional hasta que la Tarea 12 traiga el servicio de ubicación: nunca sigue y detener no hace nada. */
object ControlDeGpsProvisional : ControlDeGps {
    override fun iniciar(): ResultadoGps = ResultadoGps.NoSigue("todavía no")
    override fun detener() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
object GpsModule {
    @Provides @Singleton
    fun proveerControlDeGps(): ControlDeGps = ControlDeGpsProvisional

    @Provides @Singleton
    fun proveerEstadoDelGps(almacen: AlmacenSeguro): EstadoDelGps = EstadoDelGps(almacen)
}
