package com.jaac.avoqado_tpv.features.verification.presentation

import com.jaac.avoqado_tpv.features.verification.data.PhotoCapture
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** Lo que ve el promotor en cada ranura de la foto de comprobante, en la pantalla del cobro. */
sealed interface ProofSlotState {
    data object Idle : ProofSlotState
    /** Guardándose en la terminal y esperando la respuesta de su fila (hasta 10 s). */
    data object Saving : ProofSlotState
    data object Uploaded : ProofSlotState
    /** Guardada en la cola: se sube sola. Cuenta como lista. */
    data class Queued(val message: String) : ProofSlotState
    /** Una persona tiene que verla (en «Pendientes» queda con «Descartar»). No cuenta como lista. */
    data class NeedsAttention(val message: String) : ProofSlotState
}

const val PROOF_QUEUED_MESSAGE = "Se subirá sola"
const val PROOF_OFFLINE_MESSAGE = "Sin conexión: la foto y el número se guardaron en la terminal y se subirán solos"
const val PROOF_FAILED_MESSAGE = "No se pudo subir: tómala de nuevo"
const val PROOF_SALE_QUEUED_MESSAGE = "La venta quedó en cola; sube la foto y el número desde \"Pendientes\" cuando se registre"

/** El estado de la ranura según el desenlace final de su fila en la cola (C4). */
fun proofSlotStateFor(outcome: String): ProofSlotState = when (outcome) {
    "APPLIED", "SUPERSEDED_BY_NEWER" -> ProofSlotState.Uploaded
    "SALE_ALREADY_APPROVED" -> ProofSlotState.NeedsAttention("La venta ya estaba aprobada; esta foto no se agregó")
    "SALE_REJECTED" -> ProofSlotState.NeedsAttention("La venta fue rechazada; esta foto no se subió")
    "NUMBER_DISABLED" -> ProofSlotState.NeedsAttention("La foto se subió, pero el número no se guardó")
    else -> ProofSlotState.NeedsAttention(PROOF_FAILED_MESSAGE) // PERMANENT
}

/**
 * El texto bajo las fotos: el de cada ranura en cola o a la vista. Con dos ranuras y textos distintos, cada uno con el
 * nombre de su ranura. null si no hay nada que decir (vacías, guardándose o subidas).
 */
fun proofOfSaleMessage(states: Map<ProofSlot, ProofSlotState>, isPortabilidad: Boolean): String? {
    val slots = requiredSlots(isPortabilidad)
    val lines = slots.mapNotNull { slot ->
        when (val s = states[slot]) {
            is ProofSlotState.Queued -> slot to s.message
            is ProofSlotState.NeedsAttention -> slot to s.message
            else -> null
        }
    }
    if (lines.isEmpty()) return null
    val texts = lines.map { it.second }.distinct()
    if (lines.size == slots.size && texts.size == 1) return texts.single()
    return lines.joinToString("\n") { (slot, text) ->
        val name = if (slot == ProofSlot.VINCULACION) "Vinculación" else "Portabilidad"
        "$name: $text"
    }
}

private fun requiredSlots(isPortabilidad: Boolean) =
    if (isPortabilidad) listOf(ProofSlot.VINCULACION, ProofSlot.PORTABILIDAD) else listOf(ProofSlot.VINCULACION)

/**
 * La foto de comprobante en los tres cobros (PAX production y sandbox, y la Nexgo): bloque 0. Ninguna pantalla sube:
 * «Confirmar» encola en [ProofOfSaleQueueRepository] (archivo y fila antes de la red) y espera la respuesta de SU fila
 * hasta 10 s; sin red no espera. Si la fila llega después, la ranura se pone al día mientras la pantalla siga ahí.
 * Retomar y «Nuevo Pago» no borran nada, ni en Firebase ni en la cola: la nueva captura es otra fila con su propio
 * `captureSeq` y el servidor decide cuál gana.
 *
 * Todo corre en [scope] (el `viewModelScope`, hilo principal): los mapas no necesitan candado.
 *
 * @param queue null sólo en pruebas viejas que no la proveen; Hilt siempre la da.
 */
class ProofOfSaleSlots(
    private val scope: CoroutineScope,
    private val queue: ProofOfSaleQueueRepository?,
    private val isOnline: () -> Boolean,
    private val isPortabilidad: () -> Boolean,
) {
    private val _states = MutableStateFlow<Map<ProofSlot, ProofSlotState>>(emptyMap())
    val states: StateFlow<Map<ProofSlot, ProofSlotState>> = _states.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _complete = MutableStateFlow(false)
    /** true sólo si cada ranura requerida está subida o guardada en la cola. */
    val complete: StateFlow<Boolean> = _complete.asStateFlow()

    private val jobs = mutableMapOf<ProofSlot, Job>()

    fun confirm(
        photoPath: String, paymentId: String, slot: ProofSlot, number: String?,
        venueId: String?, venueSlug: String?, staffId: String?,
    ) {
        jobs.remove(slot)?.cancel()
        val q = queue
        if (q == null || venueId == null || staffId == null) {
            Timber.e("📸 [PROOF-OF-SALE] Sin sesión para guardar la foto | slot=$slot | paymentId=$paymentId")
            set(slot, ProofSlotState.NeedsAttention(PROOF_FAILED_MESSAGE))
            return
        }
        set(slot, ProofSlotState.Saving)
        jobs[slot] = scope.launch {
            val key = try {
                q.enqueuePhoto(PhotoCapture(venueId, venueSlug, staffId, paymentId, null, slot, photoPath, number))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "📸 [PROOF-OF-SALE] No se pudo guardar la foto en la terminal | slot=$slot | paymentId=$paymentId")
                set(slot, ProofSlotState.NeedsAttention(PROOF_FAILED_MESSAGE))
                return@launch
            }
            Timber.i("📸 [PROOF-OF-SALE] En la cola | slot=$slot | key=$key")
            val first = if (isOnline()) {
                q.awaitResult(key, FIRST_WAIT_MS)?.let { proofSlotStateFor(it.outcome) }
                    ?: ProofSlotState.Queued(PROOF_QUEUED_MESSAGE)
            } else {
                ProofSlotState.Queued(PROOF_OFFLINE_MESSAGE)
            }
            set(slot, first)
            if (first is ProofSlotState.Queued) {
                // Sigue escuchando: si la fila se resuelve con la pantalla abierta, la ranura dice la verdad.
                q.awaitResult(key, Long.MAX_VALUE)?.let { set(slot, proofSlotStateFor(it.outcome)) }
            }
        }
    }

    /** La ranura vuelve a pedir foto. No borra nada: lo ya encolado sigue su camino. */
    fun retake(slot: ProofSlot) {
        jobs.remove(slot)?.cancel()
        set(slot, ProofSlotState.Idle)
    }

    /** «Nuevo Pago»: la pantalla olvida sus ranuras. No borra nada de la cola. */
    fun reset() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _states.value = emptyMap()
        refresh()
    }

    private fun set(slot: ProofSlot, state: ProofSlotState) {
        _states.value = _states.value + (slot to state)
        refresh()
    }

    private fun refresh() {
        val s = _states.value
        _saving.value = s.values.any { it == ProofSlotState.Saving }
        _complete.value = requiredSlots(isPortabilidad()).all { s[it] is ProofSlotState.Uploaded || s[it] is ProofSlotState.Queued }
    }

    private companion object {
        /** El tope de la espera en pantalla (spec §5.5): después, «Se subirá sola». */
        const val FIRST_WAIT_MS = 10_000L
    }
}
