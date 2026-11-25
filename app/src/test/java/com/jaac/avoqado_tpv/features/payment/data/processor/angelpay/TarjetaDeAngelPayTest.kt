package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import org.junit.Test

/** hz:248b195b:D27 — lo que AngelPay dice de la tarjeta llega al registro del cobro, y lo raro queda como antes. */
class TarjetaDeAngelPayTest {

    @Test
    fun `los ultimos 4 de AngelPay se guardan enmascarados`() {
        assertThat(TarjetaDeAngelPay.numeroEnmascarado("1234")).isEqualTo("************1234")
        assertThat(TarjetaDeAngelPay.numeroEnmascarado(" 0042 ")).isEqualTo("************0042")
    }

    @Test
    fun `P1 nunca se guarda algo que no sean exactamente 4 digitos`() {
        // Un PAN completo o un BIN jamás entra al registro (PCI): sólo los últimos 4.
        assertThat(TarjetaDeAngelPay.numeroEnmascarado("4111111111111111")).isEqualTo("")
        assertThat(TarjetaDeAngelPay.numeroEnmascarado("411111")).isEqualTo("")
        assertThat(TarjetaDeAngelPay.numeroEnmascarado("123")).isEqualTo("")
        assertThat(TarjetaDeAngelPay.numeroEnmascarado("12a4")).isEqualTo("")
        assertThat(TarjetaDeAngelPay.numeroEnmascarado("****1234")).isEqualTo("")
    }

    @Test
    fun `sin ultimos 4 queda vacio como hasta hoy`() {
        assertThat(TarjetaDeAngelPay.numeroEnmascarado(null)).isEqualTo("")
        assertThat(TarjetaDeAngelPay.numeroEnmascarado("")).isEqualTo("")
        assertThat(TarjetaDeAngelPay.numeroEnmascarado("   ")).isEqualTo("")
    }

    @Test
    fun `los textos del SDK 1_0_23 se traducen al modo de lectura`() {
        assertThat(TarjetaDeAngelPay.modoDeLectura("chip")).isEqualTo(CardEntryMode.CHIP)
        assertThat(TarjetaDeAngelPay.modoDeLectura("contactless")).isEqualTo(CardEntryMode.CONTACTLESS)
        assertThat(TarjetaDeAngelPay.modoDeLectura("contactless_card")).isEqualTo(CardEntryMode.CONTACTLESS)
    }

    @Test
    fun `otras formas del historial tambien se leen`() {
        assertThat(TarjetaDeAngelPay.modoDeLectura("CHIP")).isEqualTo(CardEntryMode.CHIP)
        assertThat(TarjetaDeAngelPay.modoDeLectura("CONTACTLESS")).isEqualTo(CardEntryMode.CONTACTLESS)
        assertThat(TarjetaDeAngelPay.modoDeLectura("banda")).isEqualTo(CardEntryMode.SWIPE)
        assertThat(TarjetaDeAngelPay.modoDeLectura("MAGNETIC")).isEqualTo(CardEntryMode.SWIPE)
        assertThat(TarjetaDeAngelPay.modoDeLectura("manual")).isEqualTo(CardEntryMode.MANUAL)
    }

    @Test
    fun `lo que no se reconoce queda OTHER como hasta hoy`() {
        assertThat(TarjetaDeAngelPay.modoDeLectura(null)).isEqualTo(CardEntryMode.OTHER)
        assertThat(TarjetaDeAngelPay.modoDeLectura("")).isEqualTo(CardEntryMode.OTHER)
        assertThat(TarjetaDeAngelPay.modoDeLectura("qr")).isEqualTo(CardEntryMode.OTHER)
    }
}
