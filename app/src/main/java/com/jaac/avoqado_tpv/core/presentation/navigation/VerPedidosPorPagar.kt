package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.navigation.NavBackStackEntry
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
 *
 * Ruling 106: lo mismo en la confirmación de una devolución (también está en `RUTAS_DEL_COBRO_CON_TARJETA`). En la Nexgo, AngelPay
 * devuelve ahí, en el scope de esa pantalla: con AngelPay contestando, [ventaEnCurso] (`isCharging()` incluye la devolución en curso)
 * deja el toque sin efecto, y la devolución se registra. Antes de la llamada no salió dinero; después, el registro es `NonCancellable`.
 */
internal fun NavController.verPedidosPorPagar(ventaEnCurso: Boolean) {
    val desdeElCobro = currentDestination?.route in NavRoute.RUTAS_DEL_COBRO_CON_TARJETA
    if (desdeElCobro && ventaEnCurso) return
    navigate(NavRoute.PorPagar.route) {
        if (desdeElCobro) popUpTo(NavRoute.Home.route) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Sale de la confirmación de una devolución de la Nexgo ([entrada]) al terminar de registrarla; [refrescarPagos] pide a «Pagos» recargar.
 *
 * D661 · Ruling 116: sólo si [entrada] sigue arriba. El registro puede tardar hasta 25 s después de que AngelPay contestó, ya sin la
 * bandera de devolución en curso; en ese rato «Ver» o un cobro del POS ponen otra pantalla, y antes se sacaba ésa.
 */
internal fun NavController.salirDeLaDevolucion(entrada: NavBackStackEntry, refrescarPagos: Boolean) {
    if (currentBackStackEntry !== entrada) return
    if (refrescarPagos) previousBackStackEntry?.savedStateHandle?.set("refreshAfterRefund", true)
    safePopBackStack()
}
