package com.jaac.avoqado_tpv.features.checkout

import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalYaGuardadaException
import com.jaac.avoqado_tpv.features.checkout.domain.CarritoAMedias
import com.jaac.avoqado_tpv.features.checkout.domain.QueHacerConElCarritoAMedias
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.checkout.domain.queHacerConElCarritoAMedias
import com.jaac.avoqado_tpv.features.checkout.domain.repository.VentaSinRedRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Customer

/**
 * El aparato de las pruebas de ViewModel de Cobrar: el carrito en curso y las filas de venta guardada en memoria. Un ViewModel NUEVO
 * con el MISMO objeto es «el proceso que murió y se reabrió». La implementación real (SharedPreferences + Room) se prueba aparte en
 * `VentaSinRedRepositoryImplTest`; la regla de qué restaurar es la misma función pura en las dos.
 */
class VentaSinRedEnMemoria(var ahora: () -> Long = { System.currentTimeMillis() }) : VentaSinRedRepository {

    data class Guardado(val llave: String, val cart: CartState, val cliente: Customer?, val guardadoEn: Long)

    val carritos = mutableMapOf<String, Guardado>()
    /** Ventas en $0 guardadas como fila. */
    val filas = mutableListOf<VentaLocal>()
    /** Llaves con fila de pago (las de [filas] y las que la pantalla de cobro insertaría). */
    val llavesConFila = mutableSetOf<String>()
    val vistas = mutableListOf<String>()
    var guardarCarritoFalla = false
    var turno: String? = "shift-1"
    var antesDeLeerCarrito: (suspend () -> Unit)? = null
    var antesDeLeerConfirmacion: (suspend (String) -> Unit)? = null
    var antesDeMarcarVista: (suspend (String) -> Unit)? = null

    override suspend fun guardarCarrito(venueId: String, llave: String, cart: CartState, cliente: Customer?): Boolean {
        if (guardarCarritoFalla) return false
        carritos[venueId] = Guardado(llave, cart, cliente, ahora())
        return true
    }

    override suspend fun borrarCarrito(venueId: String) {
        carritos.remove(venueId)
    }

    override suspend fun carritoParaRestaurar(venueId: String): CarritoAMedias? {
        antesDeLeerCarrito?.invoke()
        val g = carritos[venueId] ?: return null
        return when (queHacerConElCarritoAMedias(g.guardadoEn, ahora(), g.llave in llavesConFila)) {
            QueHacerConElCarritoAMedias.RESTAURAR -> CarritoAMedias(g.llave, g.cart, g.cliente)
            QueHacerConElCarritoAMedias.BORRAR -> { carritos.remove(venueId); null }
        }
    }

    override suspend fun turnoDeLaVenta(venueId: String): String? = turno

    override suspend fun guardarVentaEnCero(venta: VentaLocal): Result<Unit> {
        if (venta.llave in llavesConFila) return Result.failure(VentaLocalYaGuardadaException(venta.llave))
        filas += venta
        llavesConFila += venta.llave
        carritos.remove(venta.venueId)
        return Result.success(Unit)
    }

    override suspend fun ultimaVentaSinMostrar(venueId: String): com.jaac.avoqado_tpv.features.checkout.domain.model.VentaGuardadaVista? {
        antesDeLeerConfirmacion?.invoke(venueId)
        return filas.lastOrNull { it.venueId == venueId && it.llave !in vistas }?.let {
            com.jaac.avoqado_tpv.features.checkout.domain.model.VentaGuardadaVista(it)
        }
    }

    override suspend fun marcarVentaVista(llave: String) {
        antesDeMarcarVista?.invoke(llave)
        vistas += llave
    }
}
