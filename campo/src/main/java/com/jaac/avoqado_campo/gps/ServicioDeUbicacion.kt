package com.jaac.avoqado_campo.gps

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.jaac.avoqado_campo.MainActivity
import com.jaac.avoqado_campo.checada.Ubicacion
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.identidad.QuienSoy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lo que hace el [ServicioDeUbicacion], fuera de él para probarlo sin Android: ponerse en primer plano sin tronar (R4-2) y
 * tomar una lectura del turno (I2: no escribe durante un borrado ni si cambió la generación mientras leía).
 */
class SeguimientoDelTurno(
    private val estado: EstadoDelGps,
    private val ubicacion: Ubicacion,
    private val pings: PingDao,
    private val piezas: PiezaDao,
    private val quienSoy: QuienSoy,
    private val ahora: () -> Long,
) {
    /** false = no quedó en primer plano: quien llama se detiene; el motivo ya está en [EstadoDelGps]. */
    fun arrancar(tienePermiso: Boolean, primerPlano: () -> Unit): Boolean {
        if (!tienePermiso) return noSigue(SIN_PERMISO)
        try {
            primerPlano()
        } catch (e: SecurityException) {
            Log.w("ServicioDeUbicacion", "startForeground", e)
            return noSigue(ANDROID_NO_LO_PERMITIO)
        } catch (e: IllegalStateException) {
            Log.w("ServicioDeUbicacion", "startForeground", e)
            return noSigue(ANDROID_NO_LO_PERMITIO)
        }
        estado.publicar(ResultadoGps.Siguiendo)
        return true
    }

    private fun noSigue(motivo: String): Boolean {
        estado.publicar(ResultadoGps.NoSigue(motivo))
        return false
    }

    /** Una lectura → un ping de la sucursal del turno. false = ya no hay turno de esta persona (el servicio se detiene). */
    suspend fun tomarUno(): Boolean {
        val generacion = quienSoy.generacion
        val t = piezas.turnoAhora()
        if (t == null || !t.abierto || t.staffId != quienSoy.staffId) return false
        val venueId = t.venueId ?: quienSoy.sesion?.venueId ?: return true
        val l = ubicacion.leer() ?: return true
        pings.guardarSi(Ping(venueId = venueId, lat = l.lat, lon = l.lon, precision = l.precision, capturadoAt = ahora())) {
            !quienSoy.limpiando && quienSoy.generacion == generacion
        }
        return true
    }
}

/** Servicio en primer plano durante el turno: una lectura al arrancar y luego cada 30 min. Lo arranca [ControlDeGpsReal]. */
@AndroidEntryPoint
class ServicioDeUbicacion : Service() {
    @Inject lateinit var seguimiento: SeguimientoDelTurno

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bucle: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enPrimerPlano = seguimiento.arrancar(tienePermisoDeUbicacion(this)) {
            val tipo = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, AVISO_ID, aviso(), tipo)
        }
        if (!enPrimerPlano) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bucle?.isActive != true) bucle = alcance.launch {
            while (true) {
                val sigue = try {
                    seguimiento.tomarUno()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ServicioDeUbicacion", "lectura del turno", e)
                    true
                }
                if (!sigue) break
                delay(CADA_MS)
            }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alcance.cancel()
        super.onDestroy()
    }

    private fun aviso() = NotificationCompat.Builder(this, CANAL)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Avoqado Campo")
        .setContentText("Turno activo · tu supervisor ve tu ubicación")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()
        .also {
            NotificationManagerCompat.from(this).createNotificationChannel(
                NotificationChannelCompat.Builder(CANAL, NotificationManagerCompat.IMPORTANCE_LOW).setName("Turno").build(),
            )
        }

    private companion object {
        const val CANAL = "turno"
        const val AVISO_ID = 12
        const val CADA_MS = 30 * 60_000L
    }
}
