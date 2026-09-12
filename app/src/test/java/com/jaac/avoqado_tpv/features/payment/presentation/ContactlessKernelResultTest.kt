package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.pax.jemv.clcommon.RetCode
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Traducción del `emvCode` (2026-09-09)
    //
    // El `emvCode` que carga `StartCtlssTransFailure` es literalmente
    // `TransResult.getResultCode()` del kernel de PAX — una constante de
    // `com.pax.jemv.clcommon.RetCode`, que viene en `emv/libs/COMMON_v103.jar`. Verificado
    // desensamblando `TransProcessRepositoryImpl.startCtlssTrans` en los dos AAR:
    //
    //   SDK de producción HOY : lookupswitch de 4 casos  (-6, -23, -27, -2)
    //   SDK del 10-dic-2025   : lookupswitch de 5 casos  (-6, -23, -27, -2, **-40**)
    //
    // En los dos, TODO lo que no está en el switch cae al `default`, que devuelve
    // `CtlssDeniedFailure`. Por eso hoy «denegada por el lector» agrupa un rechazo real
    // (-27) con «vuelve a acercarla» (-48), «tarjeta vencida» (-36) y «confirma en tu
    // teléfono» (-40), y la cajera no puede distinguirlos.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `P1 el codigo -40 es «confirma en tu telefono», no un rechazo — y no cierra el lector NFC`() {
        // Testarudo: con el SDK de producción de hoy este código llega como CtlssDeniedFailure
        // porque -40 no está en su switch. El SDK nuevo lo devuelve como ContactlessSeePhoneFailure.
        // Traducimos el código para comportarnos como el SDK nuevo sin haberlo actualizado todavía.
        val v = ContactlessKernelResult.classify("CtlssDeniedFailure", -40, toStringReal)

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.SEE_PHONE)
        assertThat(v.chipOnlyOnRetry).isFalse()
        assertThat(v.userMessage).contains("teléfono")
        assertThat(v.userMessage).doesNotContain("INSERTE")
    }

    @Test
    fun `P1 el -40 sigue liberando la terminal — la traduccion NO toca la seguridad del dinero`() {
        val antes = ContactlessKernelResult.classify("CtlssDeniedFailure", null, toStringReal)
        val ahora = ContactlessKernelResult.classify("CtlssDeniedFailure", -40, toStringReal)

        assertThat(antes.outcome).isIn(KERNEL_REFUSALS_WITHOUT_CHARGE)
        assertThat(ahora.outcome).isIn(KERNEL_REFUSALS_WITHOUT_CHARGE)
    }

    @Test
    fun `P1 ningun codigo conocido puede sacar una negativa del kernel del conjunto que libera la terminal`() {
        // 🔴 Guarda de dinero. Traducir el código sólo puede afinar el MENSAJE y el lector del
        // reintento; si alguna traducción sacara el veredicto de KERNEL_REFUSALS_WITHOUT_CHARGE,
        // el cobro pasaría a marcarse INDETERMINADO y la terminal se quedaría retenida.
        EmvKernelCode.SIGNIFICADOS.keys.forEach { codigo ->
            val v = ContactlessKernelResult.classify("CtlssDeniedFailure", codigo, toStringReal)
            assertThat(v.outcome).isIn(KERNEL_REFUSALS_WITHOUT_CHARGE)
        }
    }

    @Test
    fun `P1 las constantes son las de PAX, no numeros copiados a mano`() {
        // 🔴 Si PAX cambia un valor en COMMON_v103.jar, esta prueba truena en vez de que la
        // cajera lea un motivo equivocado. `main` no puede importar RetCode (compila también
        // para Nexgo), así que los números viven ahí a mano y se fijan AQUÍ contra el jar.
        assertThat(EmvKernelCode.CLSS_REFER_CONSUMER_DEVICE).isEqualTo(RetCode.CLSS_REFER_CONSUMER_DEVICE)
        assertThat(EmvKernelCode.CLSS_DECLINE).isEqualTo(RetCode.CLSS_DECLINE)
        assertThat(EmvKernelCode.CLSS_USE_CONTACT).isEqualTo(RetCode.CLSS_USE_CONTACT)
        assertThat(EmvKernelCode.CLSS_CARD_EXPIRED).isEqualTo(RetCode.CLSS_CARD_EXPIRED)
        assertThat(EmvKernelCode.CLSS_TRY_AGAIN).isEqualTo(RetCode.CLSS_TRY_AGAIN)
        assertThat(EmvKernelCode.CLSS_TRY_ANOTHER_CARD).isEqualTo(RetCode.CLSS_TRY_ANOTHER_CARD)
        assertThat(EmvKernelCode.CLSS_CVMDECLINE).isEqualTo(RetCode.CLSS_CVMDECLINE)
        assertThat(EmvKernelCode.EMV_NO_APP).isEqualTo(RetCode.EMV_NO_APP)
        assertThat(EmvKernelCode.ICC_CMD_ERR).isEqualTo(RetCode.ICC_CMD_ERR)
    }

    @Test
    fun `P2 una tarjeta vencida lo DICE, y sigue mandando al chip`() {
        val v = ContactlessKernelResult.classify("CtlssDeniedFailure", -36, toStringReal)

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.DENIED_BY_KERNEL)
        assertThat(v.chipOnlyOnRetry).isTrue()
        assertThat(v.userMessage).contains("vencida")
        assertThat(v.userMessage).contains("código -36")
    }

    @Test
    fun `P2 un rechazo real del kernel (-27) se distingue por texto de los demas`() {
        val rechazo = ContactlessKernelResult.classify("CtlssDeniedFailure", -27, toStringReal)
        val vencida = ContactlessKernelResult.classify("CtlssDeniedFailure", -36, toStringReal)
        val otra = ContactlessKernelResult.classify("CtlssDeniedFailure", -28, toStringReal)

        // 🔴 Comparar que los mensajes DIFIERAN no guardaba nada: el número del código ya los
        // hace distintos. MEDIDO rompiendo el fix a propósito (2026-09-09, run-avoqado-tpv.Lo0WBl):
        // con `$motivo` borrado del mensaje, esta prueba seguía PASANDO. Ahora se exige el MOTIVO
        // de cada uno, que es lo único que `$motivo` aporta.
        assertThat(rechazo.userMessage).contains("rechazó el pago sin salir a autorizar")
        assertThat(vencida.userMessage).contains("está vencida")
        assertThat(otra.userMessage).contains("pide otra tarjeta")
    }

    @Test
    fun `P2 un codigo negativo que no conocemos da el numero y no inventa un motivo`() {
        val v = ContactlessKernelResult.classify("CtlssDeniedFailure", -137, toStringReal)

        assertThat(v.outcome).isEqualTo(ContactlessOutcome.DENIED_BY_KERNEL)
        assertThat(v.userMessage).contains("código -137")
        // sin dos puntos DENTRO del paréntesis: no hay motivo que traducir
        assertThat(v.userMessage).doesNotContain("código -137:")
    }
}
