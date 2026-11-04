package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** D20 (N86, 4-nov): un doble toque en «Reintentar» elegía otra cuenta de cobro en la lista que se abre debajo. */
class SeleccionDeCuentaTest {

    @Test fun `P2 el segundo toque de un doble toque no elige cuenta`() {
        assertThat(SeleccionDeCuenta.aceptaToque(abiertaEnMs = 1_000, ahoraMs = 1_200)).isFalse()
    }

    @Test fun `P2 pasado el medio segundo la lista se usa normal`() {
        assertThat(SeleccionDeCuenta.aceptaToque(abiertaEnMs = 1_000, ahoraMs = 1_500)).isTrue()
        assertThat(SeleccionDeCuenta.aceptaToque(abiertaEnMs = 1_000, ahoraMs = 9_000)).isTrue()
    }

    @Test fun `P2 la lista protege elegir cuenta y arrancar cobros`() {
        val pantalla = java.io.File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/MerchantSelectionContent.kt").readText()
        for (toque in listOf("onSelectMerchant(merchant)", "onStartPaymentWithMsi(null)", "onStartCashPayment()", "onStartCryptoPayment()")) {
            assertThat(pantalla).contains("if (aceptaToque()) $toque")
        }
    }
}
