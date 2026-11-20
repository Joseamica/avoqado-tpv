package com.jaac.avoqado_tpv.core.presentation.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * D34 (Codex r1 #1 y r2 #1-#2): sin «Órdenes», «atrás» desde la pantalla de cobro Blumon de una venta de Cobrar (con productos, ya
 * con orden) abre un Cobrar NUEVO y vacío. Nunca vuelve al carrito de esa venta: el mismo camino lo toma «atrás» DESPUÉS de un cobro
 * registrado («sin papel» al imprimir) y el carrito lleno invitaba a cobrarla otra vez. Estática a propósito, como
 * [ArgumentosDelCobroSeLeenUnaVezTest].
 */
class RegresoDeCobrarTest {

    private val navegacion = File(
        "src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt",
    ).readText()

    @Test
    fun `P1 atras desde el cobro de una venta de Cobrar abre un Cobrar nuevo, nunca el carrito de esa venta`() {
        val cobro = navegacion.substring(navegacion.indexOf("composable(NavRoute.Payment.route)"))
        val alPedido = cobro.substringAfter("onNavigateToOrder = {").substringBefore("onNavigateToPayLaterOrders")
        val deCobrar = alPedido.indexOf("} else if (cameFromCheckout) {")
        val alInicio = alPedido.lastIndexOf("navController.popAlInicio()")
        assertThat(deCobrar).isAtLeast(0)
        assertThat(deCobrar).isLessThan(alInicio)
        val rama = alPedido.substring(deCobrar, alInicio)
        // Entrada NUEVA de Cobrar (ViewModel nuevo, carrito vacío), igual que «Nuevo Pago» de Cobrar.
        assertThat(rama).contains("navController.navigate(NavRoute.Checkout.route)")
        assertThat(rama).contains("popUpTo(NavRoute.Home.route) { inclusive = false }")
        // Un pop regresaría al Cobrar de antes, con el carrito lleno de una venta que pudo cobrarse ya.
        assertThat(rama).doesNotContain("popBackStack(")
    }

    @Test
    fun `regresion Mesas y folios siguen antes que Cobrar`() {
        val cobro = navegacion.substring(navegacion.indexOf("composable(NavRoute.Payment.route)"))
        val alPedido = cobro.substringAfter("onNavigateToOrder = {").substringBefore("onNavigateToPayLaterOrders")
        assertThat(alPedido.indexOf("volverALosFolios(")).isLessThan(alPedido.indexOf("cameFromCheckout"))
        assertThat(alPedido.indexOf("volverALaCuentaDeLaMesa()")).isLessThan(alPedido.indexOf("cameFromCheckout"))
    }
}
