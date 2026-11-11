package com.jaac.avoqado_campo.gps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.jaac.avoqado_campo.checada.Ubicacion
import com.jaac.avoqado_campo.cola.BaseDeCampo
import com.jaac.avoqado_campo.cola.MutexDeSubida
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.TurnoLocal
import com.jaac.avoqado_campo.identidad.AlmacenSeguro
import com.jaac.avoqado_campo.identidad.Instantanea
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface ResultadoGps {
    object Siguiendo : ResultadoGps
    data class NoSigue(val motivo: String) : ResultadoGps
    /** La sucursal de la sesión no sigue la ubicación (H-A1): no es un problema, nada que avisar ni arreglar. */
    object NoLoUsaLaTienda : ResultadoGps
}

/** Arranca y para el seguimiento de la ubicación del turno. */
interface ControlDeGps {
    suspend fun iniciar(): ResultadoGps
    fun detener()
    /** G: si la sucursal del turno ya no sigue la ubicación, para el servicio vivo. Nunca arranca nada (eso es de `iniciar`). */
    suspend fun revisar()
}

/**
 * Familia G (r3 #3 y #4): los pings son de la sucursal del TURNO abierto de esta persona, así que su ajuste decide (sin
 * turno, la de la sesión). `seguimientoDeUbicacion`: false = no; true o null (SERVIDOR viejo) = sí. La usan `iniciar()`, el
 * arranque del servicio (también el reinicio que hace Android solo), cada lectura del servicio y `revisar()`.
 */
fun debeSeguir(yo: Instantanea, turno: TurnoLocal?): Boolean {
    val venueId = turno?.takeIf { it.abierto && it.staffId == yo.staffId }?.venueId ?: yo.sesion?.venueId
    return yo.sucursales.firstOrNull { it.venueId == venueId }?.seguimientoDeUbicacion != false
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
 * H-A1 + G: si la sucursal del turno ([debeSeguir]) trae `seguimientoDeUbicacion: false` (como la PAX con
 * `trackPromoterLocation`), no arranca; si el servicio ya corría (un latido lo apagó con el turno abierto), lo para.
 */
class ControlDeGpsReal(
    private val context: Context,
    private val estado: EstadoDelGps,
    private val puerta: PuertaDelServicio,
    private val quienSoy: QuienSoy,
    private val dao: PiezaDao,
    private val arrancar: (Intent) -> Unit = { ContextCompat.startForegroundService(context, it) },
) : ControlDeGps {
    private fun intent() = Intent(context, ServicioDeUbicacion::class.java)

    override suspend fun iniciar(): ResultadoGps {
        if (pararSiNoDebe()) return ResultadoGps.NoLoUsaLaTienda
        val motivo = when {
            !tienePermisoDeUbicacion(context) -> SIN_PERMISO
            !LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java)) -> UBICACION_APAGADA
            else -> {
                // Antes de arrancar: si después el servicio no logra ponerse en primer plano, su NoSigue gana (R4-2).
                estado.publicar(ResultadoGps.Siguiendo)
                puerta.detenerPedido = false
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

    /** Sin `stopService` antes de que el servicio llegue a primer plano: ahí lo cierra él mismo ([PuertaDelServicio]). */
    override fun detener() {
        puerta.detenerPedido = true
        if (puerta.enPrimerPlano) context.stopService(intent())
    }

    override suspend fun revisar() { pararSiNoDebe() }

    /** true = la sucursal del turno no sigue: se paró el servicio (si corría) y quedó `NoLoUsaLaTienda`. */
    private suspend fun pararSiNoDebe(): Boolean {
        if (debeSeguir(quienSoy.instantanea(), dao.turnoAhora())) return false
        detener()
        estado.publicar(ResultadoGps.NoLoUsaLaTienda)
        return true
    }
}

/**
 * G: cada cambio de las sucursales guardadas (un latido, un `entrar`) revisa el servicio vivo: si la sucursal de su turno ya
 * no sigue la ubicación, lo para en ese momento; si la prenden, lo arranca el siguiente `reanudarSiHayTurno`. También revisa
 * al arrancar la app (un reinicio de Android con el ajuste ya apagado). Lo lanza `CampoApplication`.
 */
fun CoroutineScope.vigilarElSeguimiento(quienSoy: QuienSoy, gps: ControlDeGps): Job = launch {
    quienSoy.identidad.map { it.sucursales }.distinctUntilChanged().collect {
        try {
            gps.revisar()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ControlDeGps", "revisar el seguimiento", e)
        }
    }
}

/**
 * Al abrir la app (la Tarea 13 lo llama desde MainActivity): si hay un turno abierto de esta persona, se vuelve a intentar
 * seguirla; el Inicio muestra el motivo si no sigue. null = no hay turno (no se intenta).
 */
suspend fun reanudarSiHayTurno(gps: ControlDeGps, dao: PiezaDao, quienSoy: QuienSoy): ResultadoGps? {
    val t = dao.turnoAhora()
    val yo = quienSoy.instantanea()
    if (yo.limpiando || t == null || !t.abierto || t.staffId != yo.staffId) return null
    return gps.iniciar()
}

@Module
@InstallIn(SingletonComponent::class)
object GpsModule {
    @Provides @Singleton
    fun proveerEstadoDelGps(almacen: AlmacenSeguro): EstadoDelGps = EstadoDelGps(almacen)

    @Provides @Singleton
    fun proveerPuerta(): PuertaDelServicio = PuertaDelServicio()

    @Provides @Singleton
    fun proveerControlDeGps(
        @ApplicationContext context: Context, estado: EstadoDelGps, puerta: PuertaDelServicio, quienSoy: QuienSoy, dao: PiezaDao,
    ): ControlDeGps = ControlDeGpsReal(context, estado, puerta, quienSoy, dao)

    @Provides
    fun proveerPingDao(base: BaseDeCampo): PingDao = base.pingDao()

    @Provides @Singleton
    fun proveerSeguimiento(
        puerta: PuertaDelServicio, estado: EstadoDelGps, ubicacion: Ubicacion, pings: PingDao, piezas: PiezaDao, quienSoy: QuienSoy,
        reloj: RelojAnclado,
    ): SeguimientoDelTurno = SeguimientoDelTurno(puerta, estado, ubicacion, pings, piezas, quienSoy) { reloj.ahora().epochMs }

    @Provides @Singleton
    fun proveerEnviador(
        dao: PingDao, api: CampoApi, guardia: GuardiaDeSesion, quienSoy: QuienSoy, mutex: MutexDeSubida, reloj: RelojAnclado,
    ): EnviadorDePings = EnviadorDePings(dao, api, guardia, quienSoy, mutex) { reloj.ahora().epochMs }
}
