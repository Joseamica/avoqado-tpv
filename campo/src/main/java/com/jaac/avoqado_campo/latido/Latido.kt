package com.jaac.avoqado_campo.latido

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.jaac.avoqado_campo.BuildConfig
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.red.AparatoSinId
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.LatidoBody
import com.jaac.avoqado_campo.red.Llamada
import com.jaac.avoqado_campo.red.aDatos
import com.jaac.avoqado_campo.red.codigoDe
import com.jaac.avoqado_campo.red.llamarSeguro
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Le dice al SERVIDOR cuánto tiene por subir y la huella de cada pieza (lo que la gracia acepta) y trae de vuelta el estado,
 * las sucursales con sus verificadores y la hora. Sólo con la credencial: sirve en cualquier estado. Lo mandan el trabajo de
 * subida tras cada pasada y la app al abrir y cada 5 min; nunca más de uno por minuto (el SERVIDOR acepta 12).
 */
class Latido(
    private val api: CampoApi,
    private val quienSoy: QuienSoy,
    private val dao: PiezaDao,
    private val reloj: RelojAnclado,
    private val aparato: AparatoSinId = AparatoSinId.de(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME),
    private val monotonico: () -> Long = SystemClock::elapsedRealtime,
) {
    private val candado = Mutex()
    private var ultimoAt: Long? = null

    /** true = el SERVIDOR contestó y se guardó. */
    suspend fun mandar(): Boolean = candado.withLock {
        val t = monotonico()
        if (ultimoAt?.let { t - it < UN_MINUTO } == true) return@withLock false
        // r3 #2: credencial y generación de UNA instantánea: un borrado y otra activación en medio no las mezclan.
        val yo = quienSoy.instantanea()
        val credencial = yo.credencial ?: return@withLock false
        if (yo.limpiando) return@withLock false
        val generacion = yo.generacion
        val cuerpo = LatidoBody(dao.cuantasPorSubir().first(), dao.huellasPorSubir(500), aparato)

        val r = (llamarSeguro { api.latido(credencial, cuerpo) } as? Llamada.Contesto)?.r ?: return@withLock false
        ultimoAt = t
        val b = r.body()
        when {
            r.isSuccessful && b != null -> {
                runCatching { reloj.anclar(b.horaDelServidor!!) }.onFailure { Log.w("Latido", "horaDelServidor ilegible: ${b.horaDelServidor}") }
                // I2: no durante un borrado; R6-A1: no si otra activación ganó mientras volaba.
                // r10: si no se guardó (AlmacenNoGuardo), queda en memoria y el siguiente latido lo vuelve a traer: no truena.
                var guardado = false
                runCatching { quienSoy.guardarSi(generacion) { if (!limpiando) { guardarLatido(b.aDatos()); guardado = true } } }
                    .onFailure { guardado = false; Log.w("Latido", "no se guardó la respuesta del latido", it) }
                guardado
            }
            r.code() == 401 && codigoDe(r.errorBody()?.string()) == "CELULAR_DESCONOCIDO" -> {
                runCatching { quienSoy.guardarSi(generacion) { marcarDesconocido() } }
                false
            }
            else -> false
        }
    }

    private companion object { const val UN_MINUTO = 60_000L }
}
