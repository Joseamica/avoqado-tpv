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

    @Test
    fun `P1 D36 atras desde el cobro de Cobrar pide retirar la venta SOLO si la pantalla nunca intento cobrar (PAX y Nexgo)`() {
        val cobro = navegacion.substring(navegacion.indexOf("composable(NavRoute.Payment.route)"))
        // El ViewModel que lee la navegación es el MISMO que usa la pantalla (se le pasa explícito).
        assertThat(cobro.substringBefore("onKioskPaymentSuccess")).contains("val pagoVm: PaymentViewModel = hiltViewModel()")
        assertThat(cobro.substringBefore("onKioskPaymentSuccess")).contains("viewModel = pagoVm,")
        val alPedido = cobro.substringAfter("onNavigateToOrder = {").substringBefore("onNavigateToPayLaterOrders")
        val deCobrar = alPedido.substring(alPedido.indexOf("} else if (cameFromCheckout) {")).substringBefore("} else {")
        assertThat(deCobrar).contains("if (!pagoVm.yaSeIntentoCobrar() && !wasPayLaterOrder) {")
        assertThat(deCobrar).contains("retiroDeVentaSinCobro.alRegresarSinCobrar(secureStorage.getVenueId(), continueOrderId)")
        // Se pide ANTES de navegar (la navegación no espera a la red, pero la petición nace aunque la pantalla muera).
        assertThat(deCobrar.indexOf("alRegresarSinCobrar")).isLessThan(deCobrar.indexOf("navController.navigate(NavRoute.Checkout.route)"))

        val nexgo = navegacion.substring(navegacion.indexOf("composable(NavRoute.AngelPayPayment.route)"))
        assertThat(nexgo.substringBefore("onNavigateBack = {")).contains("viewModel = angelVm,")
        val atrasNexgo = nexgo.substringAfter("onNavigateBack = {").substringBefore("onNavigateHome = {")
        assertThat(atrasNexgo).contains("if (cameFromCheckout && folio == null && !angelVm.yaSeIntentoCobrar()) {")
        assertThat(atrasNexgo).contains("retiroDeVentaSinCobro.alRegresarSinCobrar(secureStorage.getVenueId(), orderId)")
    }

    @Test
    fun `D36 si el lector no se prepara o la cajera sale mientras se espera, la venta de Cobrar se retira (Codex r1 #10 y r2 #5)`() {
        val cobrar = navegacion.substring(navegacion.indexOf("composable(NavRoute.Checkout.route)")).substringBefore("navController.navigate(getPaymentRoute())")
        val espera = cobrar.substringAfter("val lectorNoListo = try {").substringBefore("navController.currentBackStackEntry")
        // La cajera sale de Cobrar mientras se espera el lector: la corrutina se cancela y se pide retirar antes de re-lanzar.
        val alSalir = espera.substringAfter("catch (salio: kotlinx.coroutines.CancellationException) {").substringBefore("throw salio")
        assertThat(alSalir).contains("retiroDeVentaSinCobro.alRegresarSinCobrar(secureStorage.getVenueId(), payload.orderId)")
        // El lector no quedó listo: tampoco hubo pantalla de cobro.
        val noListo = espera.substringAfter("if (lectorNoListo) {").substringBefore("return@launch")
        assertThat(noListo).contains("retiroDeVentaSinCobro.alRegresarSinCobrar(secureStorage.getVenueId(), payload.orderId)")
    }

    @Test
    fun `P1 D36 la espera del lector DENTRO del cobro no pide retirar (vuelve a salir tras tocar Tarjeta, Codex r2 #1)`() {
        val ruta = navegacion.substring(navegacion.indexOf("composable(NavRoute.Payment.route)"))
        val gate = ruta.substringAfter("val ready = awaitPaxPaymentReady(context, initializationManager, \"Payment route\")").substringBefore("safePopBackStack()")
        assertThat(gate).doesNotContain("alRegresarSinCobrar")
    }

    @Test
    fun `D36 Nexgo el atras del SISTEMA pasa por la misma salida que la flecha, SOLO en una venta de Cobrar (Codex r1 #5 y r2 #7)`() {
        val nexgo = navegacion.substring(navegacion.indexOf("composable(NavRoute.AngelPayPayment.route)"))
        assertThat(nexgo.substringBefore("onNavigateBack = {")).contains("atrasDelSistemaComoLaFlecha = cameFromCheckout && folio == null,")
        val pantalla = File(
            "src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt",
        ).readText()
        assertThat(pantalla).contains("atrasDelSistemaComoLaFlecha: Boolean = false,")
        // El manejador de D36 (desde su comentario): antes de él, la pantalla ignora el «atrás» en el tramo del éxito (f84e3a).
        val manejador = pantalla.substringAfter("// D36 (Codex r1 #5 y r2 #5/#7)").substringAfter("BackHandler(").substringBefore("Scaffold(")
        assertThat(manejador).contains("enabled = atrasDelSistemaComoLaFlecha && !huboExito && (") // f84e3a: no después de un cobro
        assertThat(manejador).contains("state is AngelPayPaymentState.SelectingMerchant")
        assertThat(manejador).contains("state is AngelPayPaymentState.Error")
        assertThat(manejador).contains("if (!viewModel.goBackOneStep()) onNavigateBack()")
        // Ni en el cobro en curso ni en el «sin confirmar»: ahí el atrás del sistema sigue como hoy.
        assertThat(manejador).doesNotContain("WaitingForResult")
        assertThat(manejador).doesNotContain("ResultadoIncierto")
    }

    @Test
    fun `D36 abrir Cobrar reintenta los retiros que quedaron sin red`() {
        val cobrar = navegacion.substring(navegacion.indexOf("composable(NavRoute.Checkout.route)")).substringBefore("CheckoutScreen(")
        assertThat(cobrar).contains("retiroDeVentaSinCobro.reintentarPendientes()")
    }
}
