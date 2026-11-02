package com.jaac.avoqado_tpv.features.checkout.domain

import com.jaac.avoqado_tpv.core.domain.models.ApiException

/**
 * 💵 «Cobrar» sin red (D3 del spec): por dónde sale la venta al tocar «Cobrar». Puro, para que la regla se pruebe sin pantalla.
 *
 * - [RAPIDO]: sólo importes con red ⇒ `PaymentNavigationPayload.Fast`, como hoy (no se crea orden).
 * - [EN_LINEA]: productos con red ⇒ se crea la orden en el servidor, como hoy (con la llave guardada antes del POST).
 * - [LOCAL]: sin red ⇒ venta local (sólo efectivo); un carrito en $0 se guarda como fila sin abrir la pantalla de cobro.
 */
enum class CaminoDelCobro { RAPIDO, EN_LINEA, LOCAL }

/**
 * Sólo importes en $0 (cortesía o descuento completo) va al camino local AUNQUE haya red: hoy saldría `Fast("0")` a la pantalla de
 * cobro, y en Nexgo la guarda de monto > 0 lo rechaza (Codex plan-r2 #6). Con productos y red, el $0 sigue en línea: el servidor lo
 * cierra (`CompletedFreeCart`, como hoy).
 */
fun caminoDelCobro(hayProductos: Boolean, totalCents: Int, hayRed: Boolean): CaminoDelCobro = when {
    !hayProductos && totalCents == 0 -> CaminoDelCobro.LOCAL
    !hayRed -> CaminoDelCobro.LOCAL
    !hayProductos -> CaminoDelCobro.RAPIDO
    else -> CaminoDelCobro.EN_LINEA
}

/**
 * ¿Un fallo al crear la orden en línea pasa a la venta local (con la MISMA llave)? Sólo si el servidor no contestó
 * ([ApiException.servidorNoContesto]: red, timeout, 502-504, 520-527). Un 4xx o un 500 son una respuesta: como hoy, error en pantalla.
 */
fun pasaALaVentaLocal(error: Throwable): Boolean = (error as? ApiException)?.servidorNoContesto == true
