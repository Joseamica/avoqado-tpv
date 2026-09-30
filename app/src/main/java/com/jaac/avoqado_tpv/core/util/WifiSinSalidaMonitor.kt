package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.features.payment.domain.model.CellularFailoverMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pregunta a Google, no a Avoqado: separa «se cayó el WiFi» de «se cayó nuestro servidor». `HttpURLConnection` pelón a
 * propósito: sin redirecciones, sin reintentos por 503, sin los interceptores ni el callTimeout de 25 s de la API.
 */
@Singleton
class InternetExternoProbe @Inject constructor() {
    suspend fun consultar(): ProbeExterno = withContext(Dispatchers.IO) {
        val codigo = try {
            val conexion = URL(URL_SONDA).openConnection() as HttpURLConnection
            try {
                conexion.instanceFollowRedirects = false
                conexion.useCaches = false
                conexion.connectTimeout = TIMEOUT_MS
                conexion.readTimeout = TIMEOUT_MS
                conexion.responseCode
            } finally {
                conexion.disconnect()
            }
        } catch (e: IOException) {
            null
        }
        if (codigo != null && codigo != 204) Timber.w("📶 [WifiSinSalida] Google respondió $codigo: el enlace pasa datos")
        respuestaExterna(codigo)
    }

    companion object {
        const val URL_SONDA = "https://connectivitycheck.gstatic.com/generate_204"
        const val TIMEOUT_MS = 5_000
    }
}

/**
 * Vigila el WiFi «enlazado pero muerto» en TODAS las terminales sin tocar la red: estado del banner y evidencia al
 * confirmar y al resolverse. Serializado ([mutex]): el loop, el observador y «Reintentar» lo llaman a la vez. El
 * reinicio lo hace [ControlDeWifi] y sólo lo pide `ConnectionViewModel` en `AUTO_ENFORCED`.
 */
@Singleton
class WifiSinSalidaMonitor @Inject constructor(
    private val probe: InternetExternoProbe,
    private val socketManager: SocketManager,
    private val networkMonitor: NetworkMonitor,
    private val connectionStateManager: ConnectionStateManager,
    private val observability: ObservabilityManager,
) {
    private val mutex = Mutex()
    private val detector = WifiSinSalidaDetector()
    private var ultimoProbe = ProbeExterno.SIN_PROBAR
    private var ultimoProbeAtMs: Long? = null
    private var incidente: Incidente? = null

    private class Incidente(val inicioMs: Long) {
        var confirmado = false
        var causa: String? = null
        // Conjunto ordenado: cada evaluación repite «cobro_en_curso» o «tope_por_hora»; el reporte guarda cada acción una vez.
        val acciones = linkedSetOf<String>()
    }

    /** Relee red, servidor y socket AHORA. [ControlDeWifi] lo usa justo antes de apagar. */
    fun sigueSinSalida(): Boolean {
        val red = networkMonitor.getCurrentNetworkInfo()
        return red.type == NetworkType.WIFI && red.isConnected &&
            !connectionStateManager.connectionState.value.hasServer &&
            !socketManager.isCurrentlyConnected()
    }

    /**
     * Sonda EN VIVO, sin el límite de 30 s: `hasServer` y el socket pueden llegar con ~30 s de atraso. Si el enlace volvió
     * justo antes del reinicio, no se toca (Codex v3 #1). true = sigue muerto por transporte.
     */
    suspend fun confirmarEnVivo(): Boolean =
        sigueSinSalida() && probe.consultar() == ProbeExterno.INALCANZABLE && sigueSinSalida()

    suspend fun evaluar(ahoraMs: Long, causaUltimoFallo: String?, modo: CellularFailoverMode): VeredictoWifi = mutex.withLock {
        if (!sigueSinSalida()) {
            ultimoProbe = ProbeExterno.SIN_PROBAR
            ultimoProbeAtMs = null
        } else if (ultimoProbeAtMs.let { it == null || ahoraMs - it >= PROBE_CADA_MS }) {
            ultimoProbe = probe.consultar()
            ultimoProbeAtMs = ahoraMs
        }
        // Relectura DESPUÉS de la sonda (hasta 5 s + 5 s): pudo volver el servidor o el socket, o cambiar la red.
        val red = networkMonitor.getCurrentNetworkInfo()
        val conexion = connectionStateManager.connectionState.value
        val lectura = LecturaDeRed(ahoraMs, red.type, red.isConnected, conexion.hasServer, socketManager.isCurrentlyConnected(), ultimoProbe)
        val veredicto = detector.evaluar(lectura)
        if (veredicto != VeredictoWifi.SANO) {
            Timber.d("📶 [WifiSinSalida] $veredicto red=${lectura.tipo}/${lectura.conectada} servidor=${lectura.hayServidor} socket=${lectura.socketVivo} externo=${lectura.externo}")
        }

        when (veredicto) {
            VeredictoWifi.SOSPECHA, VeredictoWifi.WIFI_SIN_SALIDA -> {
                val inc = incidente ?: Incidente(ahoraMs).also { incidente = it }
                if (causaUltimoFallo != null) inc.causa = causaUltimoFallo
                if (veredicto == VeredictoWifi.WIFI_SIN_SALIDA && !inc.confirmado) {
                    inc.confirmado = true
                    connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO)
                    observability.logWarning(TAG, "WiFi sin salida confirmado", metadatos(inc, ahoraMs, red, modo))
                }
            }
            VeredictoWifi.SANO, VeredictoWifi.SERVIDOR_O_DNS -> {
                // Durante los ~3 s del reinicio el detector dice SANO (tipo NONE) pero el incidente NO terminó: se cierra al
                // volver el servidor, o si el internet ajeno responde (entonces es Avoqado, no el WiFi).
                if (conexion.hasServer || veredicto == VeredictoWifi.SERVIDOR_O_DNS) cerrar(ahoraMs, red, modo)
            }
        }
        veredicto
    }

    /** Bajo [mutex], como [trasReiniciar]. Nunca llamarlo desde dentro de [evaluar]: `Mutex` no es reentrante. */
    suspend fun registrarAccion(accion: String) {
        mutex.withLock { incidente?.acciones?.add(accion) }
    }

    /**
     * Tras un reinicio: la siguiente confirmación exige otros 60 s (regla 8); el incidente y su reporte siguen siendo uno.
     * Bajo [mutex]: una evaluación suspendida en la sonda escribiría su resultado viejo DESPUÉS (Codex código #3).
     */
    suspend fun trasReiniciar() {
        mutex.withLock {
            detector.reiniciarCuenta()
            ultimoProbe = ProbeExterno.SIN_PROBAR
            ultimoProbeAtMs = null
        }
    }

    private fun cerrar(ahoraMs: Long, red: NetworkInfo, modo: CellularFailoverMode) {
        // NO aunque ya no haya incidente: limpia un DETECTADO que el VM publicó tras un cierre concurrente (Codex código #4).
        connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.NO)
        val inc = incidente ?: return
        incidente = null
        if (inc.confirmado) observability.logWarning(TAG, "WiFi sin salida resuelto", metadatos(inc, ahoraMs, red, modo))
    }

    private fun metadatos(inc: Incidente, ahoraMs: Long, red: NetworkInfo, modo: CellularFailoverMode): Map<String, Any?> = mapOf(
        "duracionS" to (ahoraMs - inc.inicioMs) / 1_000L,
        "acciones" to inc.acciones.joinToString(","),
        "causa" to inc.causa,
        "redAhora" to red.type.name,
        "modo" to modo.name,
    )

    companion object {
        const val TAG = "WifiSinSalida"
        const val PROBE_CADA_MS = 30_000L
    }
}
