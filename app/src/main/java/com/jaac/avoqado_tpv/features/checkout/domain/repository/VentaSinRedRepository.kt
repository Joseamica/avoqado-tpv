package com.jaac.avoqado_tpv.features.checkout.domain.repository

import com.jaac.avoqado_tpv.features.checkout.domain.CarritoAMedias
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.ordering.domain.Customer

/**
 * 💵 «Cobrar» sin red: lo que Cobrar guarda EN EL APARATO para no perder una venta si se cae la red o el sistema mata el proceso.
 */
interface VentaSinRedRepository {
    /**
     * El carrito, su llave y el cliente seleccionado, ANTES del primer POST en línea y antes de navegar con una venta local.
     * `false` = no quedó escrito: quien llama no sale a la red.
     */
    suspend fun guardarCarrito(venueId: String, llave: String, cart: CartState, cliente: Customer?): Boolean

    /** La venta terminó (en línea, cobro rápido, $0 guardada) o el cajero vació el carrito: ya no hay venta a medias. */
    suspend fun borrarCarrito(venueId: String)

    /**
     * Al abrir Cobrar: el carrito a medias que hay que devolver (con SU llave), o null. Lo que no se debe restaurar (con fila de pago,
     * de 24 h o más, sin hora, vacío) lo borra. Una lectura fallida se propaga y conserva el contenido.
     */
    suspend fun carritoParaRestaurar(venueId: String): CarritoAMedias?

    /** Último turno abierto confirmado, o null (sin turnos o sin guardar). Un fallo de lectura se propaga: no es ausencia. */
    suspend fun turnoDeLaVenta(venueId: String): String?

    /**
     * La venta de $0 como UNA fila de efectivo de `pending_payments` (PENDING, con su pedido), con el procesador y los seriales del
     * efectivo de este aparato. Al quedar, el carrito a medias se borra. La misma llave dos veces falla (no duplica ni pisa).
     */
    suspend fun guardarVentaEnCero(venta: VentaLocal): Result<Unit>

    /** El cajero vio que la venta guardada terminó (`exito_mostrado = 1`): al reabrir no se le vuelve a avisar. */
    suspend fun marcarVentaVista(llave: String)

    /** La siguiente confirmación del venue desde Room y ticket_json. null sólo significa ausencia; un ticket ilegible falla. */
    suspend fun ultimaVentaSinMostrar(venueId: String): com.jaac.avoqado_tpv.features.checkout.domain.model.VentaGuardadaVista?
}
