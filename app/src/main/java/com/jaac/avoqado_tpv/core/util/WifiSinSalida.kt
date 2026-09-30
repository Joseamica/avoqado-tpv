package com.jaac.avoqado_tpv.core.util

/** Qué dijo un sitio AJENO a Avoqado (Google `generate_204`). Separa «se cayó el WiFi» de «se cayó Avoqado». */
enum class ProbeExterno { ALCANZABLE, INALCANZABLE, SIN_PROBAR }

/**
 * Cualquier respuesta HTTP (204, 503, 302 de portal cautivo) prueba que el enlace PASA DATOS: reiniciar el WiFi no
 * arregla nada y, con Avoqado caído, reiniciaría el WiFi de toda la flota. Sólo el fallo de transporte (`null`) cuenta.
 */
fun respuestaExterna(codigoHttp: Int?): ProbeExterno =
    if (codigoHttp == null) ProbeExterno.INALCANZABLE else ProbeExterno.ALCANZABLE

enum class VeredictoWifi { SANO, SOSPECHA, SERVIDOR_O_DNS, WIFI_SIN_SALIDA }

/** Lo que el banner debe decir sobre el WiFi. */
enum class EstadoWifiSinSalida { NO, DETECTADO, REINICIANDO }

data class LecturaDeRed(
    val ahoraMs: Long,
    val tipo: NetworkType,
    /** `NetworkMonitor`: capacidad INTERNET, NO `VALIDATED`: un WiFi enlazado pero muerto cuenta como conectado. */
    val conectada: Boolean,
    /** `ConnectionStateManager.hasServer`, ya con la histéresis de 2 fallos del heartbeat. */
    val hayServidor: Boolean,
    val socketVivo: Boolean,
    val externo: ProbeExterno,
)

/**
 * ¿El WiFi de ESTA terminal está enlazado pero sin pasar datos? (Testarudo, 29-sep-2026: 38 min así con el WiFi del
 * local sano.) Puro: quien llama pasa la hora. Confirma sólo con fallo de transporte sostenido [confirmarTrasMs].
 */
class WifiSinSalidaDetector(private val confirmarTrasMs: Long = CONFIRMAR_TRAS_MS) {
    private var sospechaDesdeMs: Long? = null

    fun evaluar(l: LecturaDeRed): VeredictoWifi {
        val sospechoso = l.tipo == NetworkType.WIFI && l.conectada && !l.hayServidor && !l.socketVivo
        if (!sospechoso) {
            sospechaDesdeMs = null
            return VeredictoWifi.SANO
        }
        if (l.externo == ProbeExterno.ALCANZABLE) {
            sospechaDesdeMs = null
            return VeredictoWifi.SERVIDOR_O_DNS
        }
        val desde = sospechaDesdeMs ?: l.ahoraMs.also { sospechaDesdeMs = it }
        return if (l.externo == ProbeExterno.INALCANZABLE && l.ahoraMs - desde >= confirmarTrasMs) {
            VeredictoWifi.WIFI_SIN_SALIDA
        } else {
            VeredictoWifi.SOSPECHA
        }
    }

    /** Tras un reinicio del WiFi, la siguiente confirmación exige otros [confirmarTrasMs] completos (regla 8). */
    fun reiniciarCuenta() {
        sospechaDesdeMs = null
    }

    companion object {
        const val CONFIRMAR_TRAS_MS = 60_000L
    }
}
