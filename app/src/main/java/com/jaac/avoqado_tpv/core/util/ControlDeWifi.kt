package com.jaac.avoqado_tpv.core.util

import android.content.Context
import android.net.wifi.WifiManager
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ResultadoReinicio {
    data object Reiniciado : ResultadoReinicio
    data class NoSeReinicio(val motivo: String) : ResultadoReinicio
    /** Se pidió prender y el radio no lo confirmó: la marca queda y cada evaluación y el arranque vuelven a pedirlo. */
    data object Incierto : ResultadoReinicio
}

/**
 * El ÚNICO que reinicia el WiFi por cuenta de la app (Testarudo, 29-sep-2026): apagar → ~3 s → prender, lo que Square
 * le pide hacer a mano al vendedor. Singleton con [mutex] (varias `MainActivity` no reinician a la vez) y
 * [NonCancellable] (cerrar la pantalla no deja el WiFi apagado a la mitad). Reglas del plan: 3, 4, 5, 6, 7.
 */
@Singleton
class ControlDeWifi @Inject constructor(
    private val wifi: WifiFailoverController,
    private val critica: CriticalNetworkOperationManager,
    private val marca: MarcaDeWifi,
) {
    private val mutex = Mutex()
    private val reinicios = ArrayDeque<Long>()
    private var reiniciosCargados = false

    @VisibleForTesting
    internal var ahora: () -> Long = System::currentTimeMillis

    /**
     * [confirmarEnVivo]: sonda a Google SIN el límite de 30 s (las banderas de servidor y socket llegan con atraso).
     * [puedeActuar]: modo AUTO_ENFORCED + señales, releído sin suspensión justo antes del hardware y antes del DAL.
     */
    suspend fun reiniciar(confirmarEnVivo: suspend () -> Boolean, puedeActuar: () -> Boolean): ResultadoReinicio =
        mutex.withLock {
            withContext(NonCancellable) {
                val now = ahora()
                if (!reiniciosCargados) {
                    // El tope y los 60 s sobreviven a la muerte del proceso (QA N86, 30-sep). Lo guardado en el FUTURO
                    // (el reloj se movió hacia atrás) se descarta: bloquearía reinicios sin razón.
                    reinicios.addAll(marca.reinicios().filter { it <= now }.sorted())
                    reiniciosCargados = true
                }
                while (reinicios.isNotEmpty() && now - reinicios.first() > VENTANA_TOPE_MS) reinicios.removeFirst()
                when {
                    critica.isAnyCriticalOperationInProgress() -> ResultadoReinicio.NoSeReinicio("cobro_en_curso")
                    // Marca pendiente = un apagado que todavía puede concretarse: no se reinicia encima ni se toca la marca
                    // (poner/quitar la borrarían); la restauración de cada evaluación sigue pidiendo prender (Codex r2 #1).
                    marca.desde() != null -> ResultadoReinicio.Incierto
                    reinicios.size >= MAX_REINICIOS_POR_HORA -> ResultadoReinicio.NoSeReinicio("tope_por_hora")
                    // Dos pantallas que piden a la vez: la segunda espera el Mutex y NO reinicia encima del anterior.
                    reinicios.lastOrNull()?.let { now - it < REINICIO_RECIENTE_MS } == true ->
                        ResultadoReinicio.NoSeReinicio("reinicio_reciente")
                    !puedeActuar() -> ResultadoReinicio.NoSeReinicio("ya_no_aplica")
                    // La marca va ANTES de la sonda: su escritura suspende en IO y no puede quedar entre la sonda y el
                    // apagado (Codex código #2).
                    !marca.poner(now) -> ResultadoReinicio.NoSeReinicio("marca_no_guardada")
                    // Si el enlace volvió justo antes, no se corta nada que acabara de empezar (Codex v3 #1).
                    !confirmarEnVivo() -> {
                        marca.quitar()
                        ResultadoReinicio.NoSeReinicio("el_enlace_volvio")
                    }
                    // Última revisión SIN suspensión entre ésta (y la sonda) y la petición de apagado (setWifiEnabled no
                    // suspende antes de pedirlo). El radio sigue prendido: quitar la marca aquí es seguro.
                    critica.isAnyCriticalOperationInProgress() || !puedeActuar() -> {
                        marca.quitar()
                        ResultadoReinicio.NoSeReinicio("cambio_a_ultimo_momento")
                    }
                    else -> apagarYPrender(puedeActuar)
                }
            }
        }

    private suspend fun apagarYPrender(puedeActuar: () -> Boolean): ResultadoReinicio {
        // Cuenta para el tope y los 60 s desde que se PIDE, con la hora de ESTE momento (marca y sonda pudieron tardar):
        // un apagado aceptado y lento también corta (Codex código #5 y r2 #2).
        reinicios.addLast(ahora())
        marca.anotarReinicios(reinicios.toList())   // sin suspensión: nada se mete entre la guarda y el apagado
        wifi.setWifiEnabled(
            enabled = false,
            source = "wifi_sin_salida",
            antesDelCanalPax = { !critica.isAnyCriticalOperationInProgress() && puedeActuar() },
        )
        val seApago = esperarEstado(WifiManager.WIFI_STATE_DISABLED, ESPERA_APAGADO_MS)
        if (seApago) {
            delay(PAUSA_MS)
        } else {
            Timber.w("📶 [ControlDeWifi] el radio no reportó apagado en ${ESPERA_APAGADO_MS} ms: igual se pide prender")
        }
        // SIEMPRE se pide prender, forzado: un apagado aceptado y lento se concretaría después y dejaría la terminal sin
        // WiFi. Android atiende las peticiones en orden: la última (prender) gana.
        val prendido = prender("wifi_sin_salida:prender")
        return when {
            !prendido -> ResultadoReinicio.Incierto
            seApago -> ResultadoReinicio.Reiniciado
            else -> ResultadoReinicio.NoSeReinicio("el_radio_no_se_apago")
        }
    }

    /**
     * Prender no corta nada: sin guarda. La marca se quita SÓLO si Android ACEPTÓ el pedido y después se ve
     * `WIFI_STATE_ENABLED`: un rechazo con la lectura vieja en ENABLED dejaría un apagado pendiente sin marca (Codex código #1).
     */
    private suspend fun prender(source: String): Boolean {
        val aceptado = wifi.setWifiEnabled(enabled = true, source = source, forzar = true).requestResult
        val prendido = aceptado && esperarEstado(WifiManager.WIFI_STATE_ENABLED, ESPERA_PRENDIDO_MS)
        if (prendido) marca.quitar() else Timber.e("📶 [ControlDeWifi] se pidió prender y no se vio: la marca queda ($source)")
        return prendido
    }

    /** Si quedó una marca (proceso muerto a la mitad, o un encendido no confirmado), se pide prender. true = nada pendiente. */
    suspend fun restaurarSiQuedoApagado(): Boolean = mutex.withLock {
        withContext(NonCancellable) {
            if (marca.desde() == null) true else prender("restaurar_marca")
        }
    }

    /** Estado REAL del radio (`WIFI_STATE_*`); `isWifiEnabled()` no distingue las transiciones. */
    private suspend fun esperarEstado(estado: Int, maxMs: Long): Boolean {
        var esperado = 0L
        while (wifi.estadoDelRadio() != estado && esperado < maxMs) {
            delay(SONDEO_RADIO_MS)
            esperado += SONDEO_RADIO_MS
        }
        return wifi.estadoDelRadio() == estado
    }

    companion object {
        const val MAX_REINICIOS_POR_HORA = 3
        const val VENTANA_TOPE_MS = 3_600_000L
        const val REINICIO_RECIENTE_MS = 60_000L
        const val ESPERA_APAGADO_MS = 5_000L
        const val PAUSA_MS = 3_000L
        const val ESPERA_PRENDIDO_MS = 10_000L
        const val SONDEO_RADIO_MS = 250L
    }
}

/** «La app apagó el WiFi», en disco. I/O delgado sin reglas propias: lo cubren `ControlDeWifiTest` (mockeado) y el hardware. */
@Singleton
class MarcaDeWifi @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun desde(): Long? = prefs.getLong(KEY, 0L).takeIf { it > 0L }

    /** `commit()` síncrono, fuera de Main: tiene que estar en disco ANTES de apagar. false ⇒ no se apaga. */
    suspend fun poner(ahoraMs: Long): Boolean = withContext(Dispatchers.IO) { prefs.edit().putLong(KEY, ahoraMs).commit() }

    suspend fun quitar() {
        withContext(Dispatchers.IO) { prefs.edit().remove(KEY).commit() }
    }

    /** Horas (ms) de los apagados pedidos, para que el tope por hora sobreviva a la muerte del proceso. */
    fun reinicios(): List<Long> =
        prefs.getString(KEY_REINICIOS, null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    /** `apply()`: no suspende ni bloquea. Mejor esfuerzo: si se pierde, el tope sólo vuelve a empezar. */
    fun anotarReinicios(lista: List<Long>) {
        prefs.edit().putString(KEY_REINICIOS, lista.joinToString(",")).apply()
    }

    companion object {
        private const val PREFS = "wifi_restauracion"
        private const val KEY = "apagado_por_la_app_ms"
        private const val KEY_REINICIOS = "reinicios_ms"
    }
}
