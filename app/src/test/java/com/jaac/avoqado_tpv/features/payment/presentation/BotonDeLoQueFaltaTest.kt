package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * D34 (Codex r1 #2): sin «Órdenes», «Continuar pagando» de un abono que mandó el POS ya no abre la cuenta en la terminal (el
 * pedido es del POS: abrirlo aquí invitaba a cobrarlo otra vez). El botón lo dice y regresa a donde estaba la terminal.
 */
class BotonDeLoQueFaltaTest {

    @Test
    fun `un cobro de la terminal que deja saldo sigue diciendo Continuar pagando`() {
        assertThat(textoDelBotonDeLoQueFalta(BigDecimal("50"), restoLoCobraElPos = false)).isEqualTo("Continuar pagando \$50.00")
    }

    @Test
    fun `P1 un abono que mando el POS dice que lo que falta se cobra en el POS`() {
        assertThat(textoDelBotonDeLoQueFalta(BigDecimal("50.5"), restoLoCobraElPos = true)).isEqualTo("Listo · faltan \$50.50 en el POS")
    }

    @Test
    fun `P1 el boton de un abono del POS regresa a donde estaba la terminal, no a la cuenta`() {
        val pantalla = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentScreen.kt").readText()
        val bloque = pantalla.substringAfter("onContinuePayment = {").substringBefore("restoLoCobraElPos = ")
        val delPos = bloque.indexOf("CobroRemotoDelPos.esCobroDelPos(paymentSource)")
        assertThat(delPos).isAtLeast(0)
        // La rama del POS (antes del `else`) regresa con onNavigateBack y no abre la orden.
        val ramaDelPos = bloque.substring(delPos).substringBefore("} else {")
        assertThat(ramaDelPos).contains("onNavigateBack()")
        assertThat(ramaDelPos).doesNotContain("onNavigateToOrder(")
    }
}
