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
import com.jaac.avoqado_campo.checada.turnoOlvidado
import com.jaac.avoqado_campo.checada.turnoVigente
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.identidad.Arranque
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
 * Lo que comparten [ControlDeGpsReal] y el servicio. Android cierra la app si un servicio arrancado con `startForegroundService`
 * se detiene antes de llamar `startForeground`; por eso `detener()` sólo hace `stopService` si el servicio ya está en primer
 * plano, y si no, deja [detenerPedido] para que el servicio se quite solo justo después de cumplir con `startForeground`.
 */
class PuertaDelServicio {
    @Volatile var detenerPedido = false
    @Volatile var enPrimerPlano = false
}

/**
 * Lo que hace el [ServicioDeUbicacion], fuera de él para probarlo sin Android: ponerse en primer plano sin tronar (R4-2) y
 * tomar una lectura del turno (I2: no escribe durante un borrado ni si cambió la generación mientras leía).
 */
class SeguimientoDelTurno(
    private val puerta: PuertaDelServicio,
    private val estado: EstadoDelGps,
    private val ubicacion: Ubicacion,
    private val pings: PingDao,
    private val piezas: PiezaDao,
    private val quienSoy: QuienSoy,
    private val ahora: () -> Long,
) {
    /**
     * SIEMPRE intenta [primerPlano] primero (cumple el contrato de `startForegroundService` aunque lance) y decide después:
     * con un `detener()` pedido antes de arrancar, con la sucursal del turno sin seguimiento ([debeSeguir], r3 #3: también
     * cuando Android reinicia el servicio solo, sin pasar por `iniciar()`) o sin permiso, [quitarPrimerPlano] y false.
     * false = quien llama hace `stopSelf()`; lo que pasó ya está en [EstadoDelGps].
     */
    suspend fun arrancar(tienePermiso: Boolean, primerPlano: () -> Unit, quitarPrimerPlano: () -> Unit): Boolean {
        try {
            primerPlano()
        } catch (e: SecurityException) {
            Log.w("ServicioDeUbicacion", "startForeground", e)
            return noSigue(ANDROID_NO_LO_PERMITIO)
        } catch (e: IllegalStateException) {
            Log.w("ServicioDeUbicacion", "startForeground", e)
            return noSigue(ANDROID_NO_LO_PERMITIO)
        }
        puerta.enPrimerPlano = true // después de esto, `detener()` ya puede hacer stopService
        val yo = quienSoy.instantanea()
        val t = piezas.turnoAhora()
        val ms = ahora()
        val fin: ResultadoGps? = when {
            puerta.detenerPedido -> null // la salida o el borrado llegaron antes que el servicio
            // D3: el reinicio de Android con el turno de un día ya terminado no sigue (sin aviso: no es un problema).
            turnoOlvidado(t, yo.staffId, ms) != null -> null
            !debeSeguir(yo, t, ms) -> ResultadoGps.NoLoUsaLaTienda
            !tienePermiso -> ResultadoGps.NoSigue(SIN_PERMISO)
            else -> {
                estado.publicar(ResultadoGps.Siguiendo)
                return true
            }
        }
        quitarPrimerPlano()
        alTerminar()
        fin?.let(estado::publicar)
        return false
    }

    /** El servicio ya no está en primer plano (lo llama `onDestroy`). */
    fun alTerminar() {
        puerta.enPrimerPlano = false
    }

    private fun noSigue(motivo: String): Boolean {
        estado.publicar(ResultadoGps.NoSigue(motivo))
        return false
    }

    /**
     * Una lectura → un ping de la sucursal del turno. false = ya no hay turno de esta persona, o su sucursal ya no sigue la
     * ubicación (G, r3 #4: la misma sucursal que lleva el ping): el servicio se detiene.
     * D3 (hz:nube0000:na1c5a48): un turno de un día ya terminado («salida olvidada») cuenta como sin turno: el SERVIDOR ya
     * lo cerró, así que no se guarda su ubicación y el servicio se para (privacidad y batería).
     */
    suspend fun tomarUno(): Boolean {
        val yo = quienSoy.instantanea() // r3: la persona del turno y su generación, juntas
        val t = piezas.turnoAhora()
        val ms = ahora()
        if (t == null || t.staffId != yo.staffId || !turnoVigente(t, ms)) return false
        if (!debeSeguir(yo, t, ms)) {
            estado.publicar(ResultadoGps.NoLoUsaLaTienda)
            return false
        }
        val l = leerSinTronar()
        // La lectura tarda hasta 10 s (y puede terminar sin posición o con un error). Al terminar se decide OTRA VEZ con la fila
        // del turno de ese momento y esa hora: si cruzó su límite no se guarda ni se espera otra vuelta (Codex r1 #2, r2 #1,
        // r3 #1); si mientras tanto se marcó una entrada nueva, la lectura es de ella sólo si llegó después de su toque (r2 #2).
        val capturado = ahora()
        val ahoraT = piezas.turnoAhora()
        if (ahoraT == null || ahoraT.staffId != yo.staffId || !turnoVigente(ahoraT, capturado)) return false
        if (!debeSeguir(yo, ahoraT, capturado)) {
            estado.publicar(ResultadoGps.NoLoUsaLaTienda)
            return false
        }
        if (l == null || (ahoraT.abiertoAt != null && capturado < ahoraT.abiertoAt)) return true
        val venueId = ahoraT.venueId ?: yo.sesion?.venueId ?: return true
        pings.guardarSi(Ping(venueId = venueId, lat = l.lat, lon = l.lon, precision = l.precision, capturadoAt = capturado)) {
            quienSoy.instantanea().let { !it.limpiando && it.generacion == yo.generacion }
        }
        return true
    }

    /** Un error del GPS (permiso quitado a media lectura, el proveedor) es «sin posición»: no salta la revisión del turno. */
    private suspend fun leerSinTronar(): Ubicacion.Lectura? = try {
        ubicacion.leer()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("ServicioDeUbicacion", "lectura del turno", e)
        null
    }
}

/**
 * El bucle del servicio: una lectura y luego [esperar] (30 min), hasta que [tomarUno] diga que ya no hay turno que seguir.
 * Codex r3 #2: un arranque nuevo (una entrada marcada) puede llegar mientras la lectura decidía con la fila vieja; entonces
 * no se para, se vuelve a decidir de inmediato con la de ahora. [arranques] lo sube `onStartCommand` en el hilo principal,
 * el mismo de este bucle, así que entre mirarlo y parar no se cuela otro arranque. Codex r3 #3: con un arranque nuevo
 * durante la lectura, la siguiente se hace ya, no en 30 min.
 */
internal suspend fun correrBucle(tomarUno: suspend () -> Boolean, arranques: () -> Int, esperar: suspend () -> Unit) {
    while (true) {
        val visto = arranques()
        val sigue = try {
            tomarUno()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ServicioDeUbicacion", "lectura del turno", e)
            true
        }
        if (arranques() != visto) continue
        if (!sigue) return
        esperar()
    }
}

/**
 * r13: parar el servicio cumpliendo antes `startForeground` (aunque falle por permiso o por Android, se intenta y se sigue):
 * un `startForegroundService` pendiente que se para sin cumplirlo cierra el proceso.
 */
internal fun pararCumpliendoPrimerPlano(primerPlano: () -> Unit, quitarPrimerPlano: () -> Unit, parar: () -> Unit) {
    try {
        primerPlano()
    } catch (e: SecurityException) {
        Log.w("ServicioDeUbicacion", "startForeground al parar", e)
    } catch (e: IllegalStateException) {
        Log.w("ServicioDeUbicacion", "startForeground al parar", e)
    }
    quitarPrimerPlano()
    parar()
}

/** Servicio en primer plano durante el turno: una lectura al arrancar y luego cada 30 min. Lo arranca [ControlDeGpsReal]. */
@AndroidEntryPoint
class ServicioDeUbicacion : Service() {
    @Inject lateinit var seguimiento: SeguimientoDelTurno
    @Inject lateinit var arranque: Arranque

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bucle: Job? = null
    /** Cuántos `onStartCommand` van (sólo el hilo principal lo toca). */
    private var arranques = 0

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * También lo llama Android solo (START_STICKY) tras matar el proceso, sin pasar por `iniciar()`: por eso decide aquí,
     * con [SeguimientoDelTurno.arrancar] (r3 #3). `alcance` es `Main.immediate`: `primerPlano` corre ya, antes de volver.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // r12: con el arranque DETENIDO (no pudo guardar) no se sigue nada. r13: aun así hay que cumplir `startForeground`
        // antes de pararse: Android puede reentregar un `startForegroundService` que quedó pendiente al morir el proceso, y
        // si el servicio se para sin cumplirlo, cierra la app (ForegroundServiceDidNotStartInTimeException).
        arranques++ // antes de todo y en el hilo principal: el bucle vivo lo mira antes de pararse (Codex r3 #2)
        if (arranque.detenido) {
            pararCumpliendoPrimerPlano(::ponerEnPrimerPlano, ::quitarPrimerPlano) { stopSelf() }
            return START_NOT_STICKY
        }
        alcance.launch {
            val enPrimerPlano = seguimiento.arrancar(
                tienePermisoDeUbicacion(this@ServicioDeUbicacion),
                primerPlano = ::ponerEnPrimerPlano,
                quitarPrimerPlano = ::quitarPrimerPlano,
            )
            if (!enPrimerPlano) {
                stopSelf()
                return@launch
            }
            if (bucle?.isActive != true) bucle = alcance.launch {
                correrBucle(seguimiento::tomarUno, arranques = { arranques }) { delay(CADA_MS) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun ponerEnPrimerPlano() {
        val tipo = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, AVISO_ID, aviso(), tipo)
    }

    private fun quitarPrimerPlano() = ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

    override fun onDestroy() {
        seguimiento.alTerminar()
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
