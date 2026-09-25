package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Track2Test {
    @Test fun `el separador D del chip corta el numero - AMEX de 15 digitos`() {
        assertThat(panDelTrack2("376668000002007D29121011234500000F")).isEqualTo("376668000002007")
    }

    @Test fun `el separador igual de la banda tambien corta`() {
        assertThat(panDelTrack2("4555120000008892=29121011234500000")).isEqualTo("4555120000008892")
    }

    @Test fun `los ultimos 4 nunca salen del relleno del final`() {
        assertThat(panDelTrack2("4555120000008892D2912101F").takeLast(4)).isEqualTo("8892")
    }

    @Test fun `vacio sigue vacio`() {
        assertThat(panDelTrack2("")).isEmpty()
    }
}
