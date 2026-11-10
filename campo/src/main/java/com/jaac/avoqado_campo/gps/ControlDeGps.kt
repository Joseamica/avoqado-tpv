package com.jaac.avoqado_campo.gps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.jaac.avoqado_campo.checada.Ubicacion
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.identidad.AlmacenSeguro
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

sealed interface ResultadoGps {
    object Siguiendo : ResultadoGps
    data class NoSigue(val motivo: String) : ResultadoGps
}

/** Arranca y para el seguimiento de la ubicación del turno. */
interface ControlDeGps {
    fun iniciar(): ResultadoGps
    fun detener()
}

internal const val SIN_PERMISO = "no diste permiso de ubicación"
internal const val UBICACION_APAGADA = "la ubicación del teléfono está apagada"
internal const val ANDROID_NO_LO_PERMITIO = "Android no lo permitió; abre la app y vuelve a intentar"

internal fun tienePermisoDeUbicacion(c: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    .any { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

/**
 * P11: revisa permiso y ubicación encendida antes de arrancar el [ServicioDeUbicacion], y lo que Android rechace al arrancarlo
 * (`SecurityException`, `ForegroundServiceStartNotAllowedException`) se vuelve `NoSigue`: nunca sale una excepción.
 * Se llama con la app visible (la checada, o la app al abrir con turno abierto). Lo que devuelve también queda en [EstadoDelGps].
 */
class ControlDeGpsReal(
    private val context: Context,
    private val estado: EstadoDelGps,
    private val arrancar: (Intent) -> Unit = { ContextCompat.startForegroundService(context, it) },
) : ControlDeGps {
    private fun intent() = Intent(context, ServicioDeUbicacion::class.java)

    override fun iniciar(): ResultadoGps {
        val motivo = when {
            !tienePermisoDeUbicacion(context) -> SIN_PERMISO
            !LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java)) -> UBICACION_APAGADA
            else -> {
                // Antes de arrancar: si después el servicio no logra ponerse en primer plano, su NoSigue gana (R4-2).
                estado.publicar(ResultadoGps.Siguiendo)
                try {
                    arrancar(intent())
                    return ResultadoGps.Siguiendo
                } catch (_: SecurityException) {
                    ANDROID_NO_LO_PERMITIO
                } catch (_: IllegalStateException) {
                    ANDROID_NO_LO_PERMITIO
                }
            }
        }
        return ResultadoGps.NoSigue(motivo).also(estado::publicar)
    }

    override fun detener() {
        context.stopService(intent())
    }
}

/**
 * Al abrir la app (la Tarea 13 lo llama desde MainActivity): si hay un turno abierto de esta persona, se vuelve a intentar
 * seguirla; el Inicio muestra el motivo si no sigue. null = no hay turno (no se intenta).
 */
suspend fun reanudarSiHayTurno(gps: ControlDeGps, dao: PiezaDao, quienSoy: QuienSoy): ResultadoGps? {
    val t = dao.turnoAhora()
    if (quienSoy.limpiando || t == null || !t.abierto || t.staffId != quienSoy.staffId) return null
    return gps.iniciar()
}

@Module
@InstallIn(SingletonComponent::class)
object GpsModule {
    @Provides @Singleton
    fun proveerEstadoDelGps(almacen: AlmacenSeguro): EstadoDelGps = EstadoDelGps(almacen)

    @Provides @Singleton
    fun proveerControlDeGps(@ApplicationContext context: Context, estado: EstadoDelGps): ControlDeGps = ControlDeGpsReal(context, estado)

    @Provides
    fun proveerPingDao(base: BaseDeCampo): PingDao = base.pingDao()

    @Provides @Singleton
    fun proveerSeguimiento(
        estado: EstadoDelGps, ubicacion: Ubicacion, pings: PingDao, piezas: PiezaDao, quienSoy: QuienSoy, reloj: RelojAnclado,
    ): SeguimientoDelTurno = SeguimientoDelTurno(estado, ubicacion, pings, piezas, quienSoy) { reloj.ahora().epochMs }

    @Provides @Singleton
    fun proveerEnviador(dao: PingDao, api: CampoApi, guardia: GuardiaDeSesion, quienSoy: QuienSoy, reloj: RelojAnclado): EnviadorDePings =
        EnviadorDePings(dao, api, guardia, quienSoy) { reloj.ahora().epochMs }
}
