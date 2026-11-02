package com.jaac.avoqado_tpv.features.checkout.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * «Cobrar» sin red, C2b: qué se hace al abrir Cobrar con un carrito en curso guardado. Tope de 24 h (ruling del controlador): las
 * filas ya subidas se purgan a los 7 días y, sin el tope, un carrito viejo revivía una venta ya registrada.
 */
class CarritoAMediasTest {

    private val ahora = 1_760_000_000_000L
    private val hora = 60L * 60 * 1000

    @Test fun `menos de 24 h y sin fila ⇒ se restaura`() {
        assertThat(queHacerConElCarritoAMedias(guardadoEn = ahora - 23 * hora, ahora = ahora, hayFilaConSuLlave = false))
            .isEqualTo(QueHacerConElCarritoAMedias.RESTAURAR)
    }

    @Test fun `24 h o más ⇒ se borra sin restaurar`() {
        assertThat(queHacerConElCarritoAMedias(guardadoEn = ahora - 24 * hora, ahora = ahora, hayFilaConSuLlave = false))
            .isEqualTo(QueHacerConElCarritoAMedias.BORRAR)
        assertThat(queHacerConElCarritoAMedias(guardadoEn = ahora - 30 * 24 * hora, ahora = ahora, hayFilaConSuLlave = false))
            .isEqualTo(QueHacerConElCarritoAMedias.BORRAR)
    }

    @Test fun `sin hora de guardado ⇒ se borra sin restaurar`() {
        assertThat(queHacerConElCarritoAMedias(guardadoEn = null, ahora = ahora, hayFilaConSuLlave = false))
            .isEqualTo(QueHacerConElCarritoAMedias.BORRAR)
    }

    @Test fun `con fila de pago con su llave ⇒ se borra sin restaurar aunque sea reciente`() {
        assertThat(queHacerConElCarritoAMedias(guardadoEn = ahora - 1000, ahora = ahora, hayFilaConSuLlave = true))
            .isEqualTo(QueHacerConElCarritoAMedias.BORRAR)
    }

    @Test fun `reloj del aparato corregido hacia atrás ⇒ cuenta como reciente`() {
        // La N86 iba +4 min: al corregirse, lo guardado queda «en el futuro». Sigue siendo la venta a medias de hace un momento.
        assertThat(queHacerConElCarritoAMedias(guardadoEn = ahora + 4 * 60 * 1000, ahora = ahora, hayFilaConSuLlave = false))
            .isEqualTo(QueHacerConElCarritoAMedias.RESTAURAR)
    }
}
