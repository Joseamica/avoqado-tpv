package com.jaac.avoqado_tpv.features.permissions.presentation.override

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.permissions.data.override.ErrorDeAutorizacion
import com.jaac.avoqado_tpv.features.permissions.data.override.PermissionOverrideRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

sealed interface Autorizacion {
    data object NoHaceFalta : Autorizacion
    data class ConPin(val token: String) : Autorizacion
    data object Cancelada : Autorizacion
    /** El negocio tiene el PIN de gerente apagado: no se abre el diálogo ni se llama al servidor. */
    data class Negada(val texto: String) : Autorizacion
}

data class PromptDePin(val id: Long, val motivo: String, val error: String?, val enviando: Boolean)

/**
 * Decide si una cortesía (u otro permiso, [paraPermiso]) necesita el PIN de un gerente, lo pide por un diálogo (StateFlow) y
 * devuelve el token de un solo uso. Un diálogo a la vez; las demás solicitudes esperan su turno.
 */
@Singleton
class AutorizacionDeGerente @Inject constructor(
    private val permisos: PermissionsRepository,
    private val repo: PermissionOverrideRepository,
    private val secureStorage: SecureStorage,
) {
    private class Solicitud(val id: Long) {
        val resultado = CompletableDeferred<Autorizacion>()
        val pins = Channel<String>(Channel.UNLIMITED)
        val enviando = AtomicBoolean(false)
    }

    private val turno = Mutex()
    private val ids = AtomicLong(0)
    private val epoca = AtomicLong(0)
    private val actual = AtomicReference<Solicitud?>(null)
    private val _prompt = MutableStateFlow<PromptDePin?>(null)
    val prompt: StateFlow<PromptDePin?> = _prompt.asStateFlow()

    suspend fun paraCortesia(venueId: String, motivo: String, forzar: Boolean = false): Autorizacion =
        paraPermiso(venueId, PERMISO_LOCAL, PERMISO_SERVIDOR, motivo, forzar = forzar)

    /**
     * El PIN de un gerente para [permisoServidor] cuando la sesión no tiene [permisoLocal]. [operationKey] ata la autorización a UNA
     * operación (p. ej. `descuento-cliente:<cliente>:<descuento>`); sin ella el cuerpo es el de siempre.
     */
    suspend fun paraPermiso(
        venueId: String,
        permisoLocal: String,
        permisoServidor: String,
        motivo: String,
        operationKey: String? = null,
        forzar: Boolean = false,
    ): Autorizacion {
        val esCortesia = permisoServidor == PERMISO_SERVIDOR
        val epocaAlLlegar = epoca.get()
        if (forzar) {
            PermissionsRepository.invalidarListaTrasRechazo(secureStorage, venueId, secureStorage.getStaffId())
        } else if (permisos.hasPermission(permisoLocal)) {
            return Autorizacion.NoHaceFalta
        }
        if (pinPrendidoCacheado(venueId) == false) {
            if (epoca.get() != epocaAlLlegar) return Autorizacion.Cancelada
            return Autorizacion.Negada(if (esCortesia) TEXTO_NEGADA else TEXTO_NEGADA_OTRO)
        }
        return turno.withLock {
            val s = Solicitud(ids.incrementAndGet())
            // actual antes de mirar la época: un cancelarTodo() concurrente siempre ve esta solicitud o ya cambió la época.
            actual.set(s)
            try {
                if (epoca.get() != epocaAlLlegar) return@withLock Autorizacion.Cancelada
                _prompt.value = PromptDePin(s.id, motivo, null, false)
                coroutineScope {
                    val worker = launch {
                        for (pin in s.pins) {
                            if (s.resultado.isCompleted) break
                            val r = try {
                                repo.pedirToken(venueId, pin, permisoServidor, operationKey)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                            r.getOrNull()?.let { s.resultado.complete(Autorizacion.ConPin(it)) }
                            if (r.isFailure) {
                                val e = r.exceptionOrNull()
                                if (e is ErrorDeAutorizacion.PinApagado) guardar(venueId, false)
                                // El texto de PinApagado habla de la cortesía: para otro permiso se dice sin nombrarla.
                                val texto = if (e is ErrorDeAutorizacion.PinApagado && !esCortesia) PIN_APAGADO_OTRO
                                else (e as? ErrorDeAutorizacion)?.texto ?: GENERICO
                                _prompt.update { p -> if (p?.id == s.id) p.copy(error = texto, enviando = false) else p }
                                s.enviando.set(false)
                            }
                        }
                    }
                    try { s.resultado.await() } finally { worker.cancel() }
                }
            } finally {
                actual.compareAndSet(s, null)
                _prompt.update { p -> if (p?.id == s.id) null else p }
            }
        }
    }

    /** ¿Tiene el negocio prendido el PIN de gerente? El mismo dato y la misma caché que [paraCortesia]; `null` = no se sabe. */
    suspend fun pinPrendido(venueId: String): Boolean? = pinPrendidoCacheado(venueId)

    private val cachePin = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    internal var reloj: () -> Long = System::nanoTime

    /** true/false se guardan 5 min por negocio; null (no se sabe) nunca. */
    private suspend fun pinPrendidoCacheado(venueId: String): Boolean? {
        cachePin[venueId]?.let { (v, hasta) -> if (reloj() - hasta < 0) return v }
        // Con WiFi malo el GET puede tardar 25 s: pasados 3 s es «no se sabe» y se abre el diálogo como antes.
        val v = withTimeoutOrNull(ESPERA_MS) { repo.pinPrendido(venueId) }
        if (v != null) guardar(venueId, v)
        return v
    }

    private fun guardar(venueId: String, v: Boolean) { cachePin[venueId] = v to (reloj() + CACHE_NS) }

    fun enviarPin(id: Long, pin: String) {
        val s = actual.get() ?: return
        if (s.id != id || s.resultado.isCompleted || pin.length < 4) return
        if (!s.enviando.compareAndSet(false, true)) return
        _prompt.update { p -> if (p?.id == id) p.copy(error = null, enviando = true) else p }
        s.pins.trySend(pin)
    }

    fun cancelar(id: Long) {
        actual.get()?.takeIf { it.id == id }?.resultado?.complete(Autorizacion.Cancelada)
    }

    /** Cobro remoto cancelado o sesión vencida: cierra el diálogo y cancela también a los que esperan turno. */
    fun cancelarTodo() {
        epoca.incrementAndGet()
        actual.get()?.resultado?.complete(Autorizacion.Cancelada)
    }

    private companion object {
        const val PERMISO_LOCAL = "tpv-orders:comp"
        const val PERMISO_SERVIDOR = "orders:comp"
        const val CACHE_NS = 5 * 60_000_000_000L
        const val ESPERA_MS = 3_000L
        const val TEXTO_NEGADA = "Esta cortesía la da un gerente con su sesión"
        const val TEXTO_NEGADA_OTRO = "Lo autoriza un gerente con su sesión"
        const val PIN_APAGADO_OTRO = "En este negocio el PIN de gerente está apagado. $TEXTO_NEGADA_OTRO"
        const val GENERICO = "No se pudo autorizar. Intenta otra vez"
    }
}
