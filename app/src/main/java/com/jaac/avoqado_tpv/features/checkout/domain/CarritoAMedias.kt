package com.jaac.avoqado_tpv.features.checkout.domain

import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.ordering.domain.Customer

/**
 * 💵 «Cobrar» sin red: el carrito que quedó a medias (guardado antes de la red, sin fila de pago) y que vuelve al abrir Cobrar con SU
 * llave: el reintento encuentra la misma orden por `externalId` si había llegado al servidor. [cliente] es lo mínimo para volver a
 * mostrarlo sin red (sin puntos ni grupo).
 */
data class CarritoAMedias(val llave: String, val cart: CartState, val cliente: Customer?)

enum class QueHacerConElCarritoAMedias { RESTAURAR, BORRAR }

/** Un carrito a medias de 24 h o más no se restaura (ruling del controlador para C2b). */
const val EDAD_MAXIMA_DEL_CARRITO_A_MEDIAS_MS: Long = 24L * 60 * 60 * 1000

/**
 * Qué hacer al abrir Cobrar con un carrito en curso guardado.
 *  - Con fila de `pending_payments` con su llave ⇒ la venta ya quedó guardada: BORRAR (el aviso de esa venta es de otra pieza, C5).
 *  - Sin hora de guardado, o de [EDAD_MAXIMA_DEL_CARRITO_A_MEDIAS_MS] o más ⇒ BORRAR: las filas ya subidas se purgan a los 7 días y,
 *    sin tope, un carrito viejo revivía una venta ya registrada.
 *  - Si no ⇒ RESTAURAR. Una hora «en el futuro» (el reloj del aparato se corrigió hacia atrás) cuenta como reciente.
 */
fun queHacerConElCarritoAMedias(guardadoEn: Long?, ahora: Long, hayFilaConSuLlave: Boolean): QueHacerConElCarritoAMedias = when {
    hayFilaConSuLlave -> QueHacerConElCarritoAMedias.BORRAR
    guardadoEn == null -> QueHacerConElCarritoAMedias.BORRAR
    ahora - guardadoEn >= EDAD_MAXIMA_DEL_CARRITO_A_MEDIAS_MS -> QueHacerConElCarritoAMedias.BORRAR
    else -> QueHacerConElCarritoAMedias.RESTAURAR
}
