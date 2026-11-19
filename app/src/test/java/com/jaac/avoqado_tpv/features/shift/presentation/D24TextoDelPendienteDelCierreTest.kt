package com.jaac.avoqado_tpv.features.shift.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * D24 (soporte por WhatsApp, 18-nov): en «Registros de esta caja», el efectivo de Mesas que detiene el cierre sólo decía «pendiente de
 * registro». Si el SERVIDOR lo rechazó, la caja se queda detenida hasta que un gerente lo resuelve en Mesas › «Acciones rechazadas», y
 * la cajera no sabía a dónde ir.
 */
class D24TextoDelPendienteDelCierreTest {

    @Test
    fun `el efectivo de Mesas dice donde se resuelve si no sube solo`() {
        val texto = textoDelPendienteDelCierre("TABLE_CASH")

        assertThat(texto).contains("Efectivo de Mesas")
        assertThat(texto).contains("Mesas › «Acciones rechazadas»")
        assertThat(texto).contains("gerente")
    }

    @Test
    fun `los demas renglones no cambian`() {
        assertThat(textoDelPendienteDelCierre("CRYPTO")).isEqualTo("Pago cripto pendiente de confirmación")
        assertThat(textoDelPendienteDelCierre("ATTEMPT")).isEqualTo("Operación pendiente de resolver; no vuelvas a cobrarla")
        assertThat(textoDelPendienteDelCierre("REMOTE")).isEqualTo("Operación pendiente de resolver; no vuelvas a cobrarla")
        assertThat(textoDelPendienteDelCierre("REFUND")).isEqualTo("Devolución pendiente de registro o revisión")
        assertThat(textoDelPendienteDelCierre("PAYMENT")).isEqualTo("Pago pendiente de registro o revisión")
    }
}
