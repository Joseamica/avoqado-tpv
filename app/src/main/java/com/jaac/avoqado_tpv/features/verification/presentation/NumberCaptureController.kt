package com.jaac.avoqado_tpv.features.verification.presentation

import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.verification.data.remote.ProofOfSaleRemote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Qué número se captura con cada foto (PlayTelecom). `query` es el `kind` de `number-check`. Textos de Isaac, exactos. */
enum class NumberKind(val query: String, val label: String, val duplicateText: String) {
    LINKING("linking", "Número de vinculación", "Este número de vinculación ya fue registrado en otra venta"),
    PORTED("ported", "Número portado", "Este número portado ya fue registrado en otra venta"),
}

/** La foto de Vinculación lleva el número de vinculación; la de Portabilidad, el portado. */
val ProofSlot.numberKind: NumberKind get() = if (this == ProofSlot.PORTABILIDAD) NumberKind.PORTED else NumberKind.LINKING

/** El aviso de repetido, con «Corregir» / «Guardar así». */
data class DuplicatePrompt(val text: String)

/**
 * La pregunta de repetido al tocar «Confirmar» (spec §5.5). Con señal pregunta a `number-check` (tope de 3 s, en
 * [ProofOfSaleRemote.isDuplicate]); sin señal, con error o sin respuesta a tiempo sigue sin preguntar: el servidor lo
 * vuelve a revisar al recibirlo. Repetido: muestra [prompt] y espera a la persona (opción A de Isaac: avisa y deja guardar).
 *
 * Vive en el ViewModel y la pantalla lo llama desde su scope: si el diálogo se cierra a media pregunta, la cancelación
 * limpia el aviso.
 */
class NumberCaptureController(private val remote: ProofOfSaleRemote) {
    private val _prompt = MutableStateFlow<DuplicatePrompt?>(null)
    val prompt: StateFlow<DuplicatePrompt?> = _prompt.asStateFlow()

    private val _checking = MutableStateFlow(false)
    /** Preguntando al servidor (hasta 3 s): «Confirmar» no se puede tocar dos veces. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private var answer: CompletableDeferred<Boolean>? = null

    /** @return true = guardar (no repetido, sin respuesta o «Guardar así»); false = «Corregir» o un toque repetido. */
    suspend fun confirm(number: String, kind: NumberKind, verificationId: String?): Boolean {
        if (_checking.value || answer != null) return false // ya hay una pregunta en curso: este toque no cuenta
        _checking.value = true
        val duplicate = try {
            remote.isDuplicate(number, kind.query, verificationId)
        } finally {
            _checking.value = false
        }
        if (duplicate != true) return true
        val pending = CompletableDeferred<Boolean>()
        answer = pending
        _prompt.value = DuplicatePrompt(kind.duplicateText)
        return try {
            pending.await()
        } finally {
            _prompt.value = null
            answer = null
        }
    }

    /** «Corregir»: regresa al campo. */
    fun correct() {
        answer?.complete(false)
    }

    /** «Guardar así». */
    fun saveAnyway() {
        answer?.complete(true)
    }
}
