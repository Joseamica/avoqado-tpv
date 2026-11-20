package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.navigation.NavController

/**
 * D34 (founder, 20-nov): «Órdenes» se quitó de la terminal («para ordering se usa Mesas y Cobrar»). Los regresos de la
 * pantalla de cobro que antes caían en sus pantallas (la orden, la lista de cuentas por cobrar, el plano viejo) ahora
 * regresan al Inicio.
 *
 * Un solo salto con `popBackStack` directo, sin el `safePopBackStack` con su espera global: dos navegaciones seguidas se
 * perdían por esa espera y dejaban una pantalla en blanco tras un cobro (igual que el regreso de Mesas). Si el Inicio no
 * está en la pila, se abre.
 */
internal fun NavController.popAlInicio() {
    if (!popBackStack(NavRoute.Home.route, inclusive = false)) {
        navigate(NavRoute.Home.route) { launchSingleTop = true }
    }
}
