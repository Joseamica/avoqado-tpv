package com.jaac.avoqado_tpv.features.payment.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReferenciaDeEfectivoTest {
    @Test fun `sale de la llave y no del reloj, con el prefijo CASH-`() {
        assertThat(referenciaDeEfectivo("9f3a2c1e-5b7d-4a60-8e11-0123456789ab"))
            .isEqualTo("CASH-" + "9f3a2c1e5b7d4a608e110123456789ab".takeLast(16))
        assertThat(referenciaDeEfectivo("llave-1")).isEqualTo(referenciaDeEfectivo("llave-1"))
    }

    @Test fun `una llave corta se usa entera y el prefijo del kiosco se conserva`() {
        assertThat(referenciaDeEfectivo("ab-cd")).isEqualTo("CASH-abcd")
        assertThat(referenciaDeEfectivo("9f3a2c1e-5b7d-4a60-8e11-0123456789ab", prefijo = "CASH-KIOSK-"))
            .isEqualTo("CASH-KIOSK-" + "9f3a2c1e5b7d4a608e110123456789ab".takeLast(16))
    }

    @Test fun `una llave cuid de 25 caracteres da 16 caracteres tras el prefijo`() {
        val llave = "cmgx1y2z30000abcdefghijkl"
        assertThat(llave).hasLength(25)
        assertThat(referenciaDeEfectivo(llave)).isEqualTo("CASH-" + llave.takeLast(16))
    }
}
