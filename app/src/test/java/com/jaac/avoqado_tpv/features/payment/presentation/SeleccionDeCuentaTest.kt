package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** D20 (N86, 4-nov): un doble toque en «Reintentar» elegía otra cuenta de cobro en la lista que se abre debajo. */
class SeleccionDeCuentaTest {

    @Test fun `P2 el segundo toque de un doble toque no elige cuenta`() {
        assertThat(SeleccionDeCuenta.aceptaToque(abiertaEnMs = 1_000, ahoraMs = 1_200)).isFalse()
        // Medido en la N86 (4-nov): dos toques seguidos a 844 ms. Con 500 ms de margen eligió otra cuenta.
        assertThat(SeleccionDeCuenta.aceptaToque(abiertaEnMs = 1_000, ahoraMs = 1_844)).isFalse()
    }

    @Test fun `P2 pasado el segundo la lista se usa normal`() {
        assertThat(SeleccionDeCuenta.aceptaToque(abiertaEnMs = 1_000, ahoraMs = 2_000)).isTrue()
        assertThat(SeleccionDeCuenta.aceptaToque(abiertaEnMs = 1_000, ahoraMs = 9_000)).isTrue()
    }

    @Test fun `P2 la lista protege elegir cuenta y arrancar cobros`() {
        val pantalla = java.io.File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/MerchantSelectionContent.kt").readText()
        for (toque in listOf("onSelectMerchant(merchant)", "onStartPaymentWithMsi(null)", "onStartCashPayment()", "onStartCryptoPayment()")) {
            assertThat(pantalla).contains("if (aceptaToque()) $toque")
        }
    }
}
