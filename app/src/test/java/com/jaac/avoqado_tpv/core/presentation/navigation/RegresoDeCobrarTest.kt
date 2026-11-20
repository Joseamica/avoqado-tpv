package com.jaac.avoqado_tpv.core.presentation.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * D34 (Codex r1 #1): sin «Órdenes», «atrás» desde la pantalla de cobro Blumon de una venta de Cobrar (con productos, ya con orden)
 * regresa a Cobrar con su carrito, igual que el riel AngelPay. Antes abría la orden en «Órdenes»; con el regreso genérico al
 * Inicio la cajera perdía el carrito. Estática a propósito, como [ArgumentosDelCobroSeLeenUnaVezTest].
 */
class RegresoDeCobrarTest {

    private val navegacion = File(
        "src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt",
    ).readText()

    @Test
    fun `P1 atras desde el cobro de una venta de Cobrar regresa a Cobrar antes que al Inicio`() {
        val cobro = navegacion.substring(navegacion.indexOf("composable(NavRoute.Payment.route)"))
        val alPedido = cobro.substringAfter("onNavigateToOrder = {").substringBefore("onNavigateToPayLaterOrders")
        val deCobrar = alPedido.indexOf("} else if (cameFromCheckout) {")
        val alInicio = alPedido.lastIndexOf("navController.popAlInicio()")
        assertThat(deCobrar).isAtLeast(0)
        assertThat(deCobrar).isLessThan(alInicio)
        assertThat(alPedido.substring(deCobrar, alInicio)).contains("navController.popBackStack()")
    }

    @Test
    fun `regresion Mesas y folios siguen antes que Cobrar`() {
        val cobro = navegacion.substring(navegacion.indexOf("composable(NavRoute.Payment.route)"))
        val alPedido = cobro.substringAfter("onNavigateToOrder = {").substringBefore("onNavigateToPayLaterOrders")
        assertThat(alPedido.indexOf("volverALosFolios(")).isLessThan(alPedido.indexOf("cameFromCheckout"))
        assertThat(alPedido.indexOf("volverALaCuentaDeLaMesa()")).isLessThan(alPedido.indexOf("cameFromCheckout"))
    }
}
