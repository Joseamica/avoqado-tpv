package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * El kernel contactless de la PAX decide antes de autorizar; estas pruebas fijan qué le decimos a la
 * cajera y con qué lector se reintenta. El caso que las originó: Testarudo 2026-09-07, nueve
 * `CtlssDeniedFailure` en tres ventas, todas resueltas con chip a la primera.
 */
class ContactlessKernelResultTest {

    /** Lo que de verdad imprime el SDK: el hash del objeto, sin el código. */
    private val toStringReal =
        "com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransFailure\$CtlssDeniedFailure@2cba583"

    @Test
    fun `P1 CtlssDenied es denegada por el kernel, manda al chip y no culpa al banco`() {
        val v = ContactlessKernelResult.classify("CtlssDeniedFailure", 12, toStringReal)

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.DENIED_BY_KERNEL)
        assertThat(v.chipOnlyOnRetry).isTrue()
        assertThat(v.userMessage).contains("INSERTE")
        assertThat(v.userMessage).contains("código 12")
        assertThat(v.userMessage).contains("No es un rechazo del banco")
    }

    @Test
    fun `P1 la clase decide aunque el toString sea sólo el hash`() {
        val v = ContactlessKernelResult.classify("CtlssDeniedFailure", null, "StartCtlssTransFailure\$CtlssDeniedFailure@95cecf")

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.DENIED_BY_KERNEL)
        assertThat(v.chipOnlyOnRetry).isTrue()
    }

    @Test
    fun `P1 CtlssUseContact pide chip y avisa que volver a acercar no sirve`() {
        val v = ContactlessKernelResult.classify("CtlssUseContactFailure", 7, null)

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.USE_CONTACT)
        assertThat(v.chipOnlyOnRetry).isTrue()
        assertThat(v.userMessage).contains("INSERTE")
        assertThat(v.userMessage).contains("rechazar otra vez")
    }

    @Test
    fun `P1 EmvNoApp manda al chip`() {
        val v = ContactlessKernelResult.classify("EmvNoAppFailure", null, null)

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.NO_APP)
        assertThat(v.chipOnlyOnRetry).isTrue()
        assertThat(v.userMessage).contains("INSERTE")
    }

    @Test
    fun `P2 SeePhone no cambia el lector, el cliente confirma en su teléfono y vuelve a acercarlo`() {
        val v = ContactlessKernelResult.classify("ContactlessSeePhoneFailure", 3, null)

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.SEE_PHONE)
        assertThat(v.chipOnlyOnRetry).isFalse()
        assertThat(v.userMessage).contains("teléfono")
    }

    @Test
    fun `P2 ReadingContactless conserva el mensaje de tarjeta retirada y permite volver a acercar`() {
        val v = ContactlessKernelResult.classify("ReadingContactlessFailure", null, null)

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.CARD_REMOVED)
        assertThat(v.chipOnlyOnRetry).isFalse()
        assertThat(v.userMessage).startsWith("La tarjeta se retiró demasiado rápido.")
    }

    @Test
    fun `P2 Timeout y Collision se siguen reconociendo por el texto, como antes`() {
        val timeout = ContactlessKernelResult.classify("ContactlessFailure", null, "…ContactlessFailure: Timeout waiting for card")
        val collision = ContactlessKernelResult.classify("ContactlessFailure", null, "…Collision detected")

        assertThat(timeout.outcome).isEqualTo(ContactlessOutcome.TIMEOUT)
        assertThat(timeout.userMessage).startsWith("Tiempo de espera agotado.")
        assertThat(collision.outcome).isEqualTo(ContactlessOutcome.COLLISION)
        assertThat(collision.userMessage).startsWith("Se detectaron múltiples tarjetas.")
        assertThat(timeout.chipOnlyOnRetry).isFalse()
        assertThat(collision.chipOnlyOnRetry).isFalse()
    }

    @Test
    fun `P2 un fallo desconocido cae al mensaje genérico sin forzar chip`() {
        val v = ContactlessKernelResult.classify("ContactlessFailure", 99, "ContactlessFailure@1a2b3c")

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.OTHER)
        assertThat(v.chipOnlyOnRetry).isFalse()
        assertThat(v.userMessage).startsWith("Error leyendo tarjeta contactless (código 99).")
    }

    @Test
    fun `P2 sin emvCode el mensaje no inventa un código`() {
        val v = ContactlessKernelResult.classify("CtlssDeniedFailure", null, null)

        assertThat(v.userMessage).doesNotContain("código")
    }

    @Test
    fun `P2 nombre y texto nulos caen a OTHER sin reventar`() {
        val v = ContactlessKernelResult.classify(null, null, null)

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.OTHER)
        assertThat(v.chipOnlyOnRetry).isFalse()
    }
}
