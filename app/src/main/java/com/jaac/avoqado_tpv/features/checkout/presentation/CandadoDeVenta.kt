package com.jaac.avoqado_tpv.features.checkout.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** Un toque que llega mientras la venta se prepara (otro «Cobrar», un cupón): se rechaza con este texto. */
class VentaEnPreparacionException : IllegalStateException("Espera un momento: la venta se está preparando")

/**
 * 🔒 «Venta en preparación» (deuda MEDIA del 19-oct «Pagar después borra lo agregado mientras corre»): mientras se arma y se manda
 * la venta (`prepareForPayment`, `createPayLaterOrder`), el carrito NO cambia. Así lo que se vacía al terminar es exactamente lo que
 * viajó en la orden: nada que el cajero agregó a media espera se pierde en silencio.
 *
 * Todo en el hilo principal (ViewModel + Compose): revisar y prender no compiten. Se suelta SIEMPRE al terminar, también si la orden
 * falla o la corrutina se cancela (la pantalla se fue), para que el cajero nunca quede trabado.
 */
internal class CandadoDeVenta {
    private val _activo = MutableStateFlow(false)
    val activo: StateFlow<Boolean> = _activo.asStateFlow()

    /** Corre [bloque] con el candado puesto. Si ya había una venta preparándose, no corre nada (no sale otra orden). */
    suspend fun <T> mientras(bloque: suspend () -> Result<T>): Result<T> {
        if (_activo.value) return Result.failure(VentaEnPreparacionException())
        _activo.value = true
        return try {
            bloque()
        } finally {
            _activo.value = false
        }
    }

    /** `true` (y lo deja en el log) si [accion] sobre el carrito llega mientras la venta se prepara: quien llama no hace nada. */
    fun rechaza(accion: String): Boolean {
        if (!_activo.value) return false
        Timber.w("🔒 «$accion» rechazado: la venta se está preparando")
        return true
    }
}
