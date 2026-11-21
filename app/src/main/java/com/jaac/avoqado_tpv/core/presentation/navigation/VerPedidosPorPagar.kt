package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.navigation.NavController

/**
 * D661 (ronda 4): el «Ver» de la banda de los pedidos pagados en línea abre «Pedidos por pagar».
 *
 * Desde el cobro con tarjeta (su recibo o su rechazo, donde la banda también sale) primero sale al Inicio, como toda otra salida del
 * cobro (`popUpTo(Home)`, igual que «Nuevo pago» o «Ver en Pagos»). El ViewModel del cobro es de SU entrada: vivo debajo de «Pedidos
 * por pagar», el cobro siguiente (el «Cobrar» de un folio o uno que manda el POS) empujaba otro, y los dos escribían las banderas de
 * `PaymentStateHolder`, que tienen un solo escritor: el viejo podía apagar las de la venta nueva.
 *
 * [ventaEnCurso] se lee al tocar: la banda tarda hasta 250 ms en esconderse cuando arranca una venta, y un toque en ese rato no le quita
 * la pantalla al cobro (las otras salidas sólo existen con el cobro resuelto). Desde cualquier otra pantalla, como antes: «Pedidos por
 * pagar» encima y «atrás» regresa a donde estaba.
 */
internal fun NavController.verPedidosPorPagar(ventaEnCurso: Boolean) {
    val desdeElCobro = currentDestination?.route in NavRoute.RUTAS_DEL_COBRO_CON_TARJETA
    if (desdeElCobro && ventaEnCurso) return
    navigate(NavRoute.PorPagar.route) {
        if (desdeElCobro) popUpTo(NavRoute.Home.route) { inclusive = false }
        launchSingleTop = true
    }
}
