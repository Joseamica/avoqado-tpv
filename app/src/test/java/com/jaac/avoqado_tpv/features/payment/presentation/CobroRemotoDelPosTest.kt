package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 🔴 INTERINO (P2-9): efectivo y cripto no se ofrecen en un cobro que pidió el POS.
 *
 * La terminal los cobra y los registra por su cuenta, pero el servidor sólo cierra la solicitud con
 * TARJETA: la fila queda UNKNOWN con el dinero ya cobrado y la tablet ofreciendo cobrar otra vez
 * (medido en hardware el 10-sep). Se revierte cambiando UNA constante, y por eso está fijada aquí.
 */
class CobroRemotoDelPosTest {

    @Test fun `un cobro que pidio el POS no ofrece efectivo ni cripto`() {
        assertThat(CobroRemotoDelPos.permiteEfectivoYCripto("SOCKET")).isFalse()
    }

    @Test fun `un cobro iniciado en la terminal los sigue ofreciendo`() {
        assertThat(CobroRemotoDelPos.permiteEfectivoYCripto(null)).isTrue()
        assertThat(CobroRemotoDelPos.permiteEfectivoYCripto("KIOSK")).isTrue()
    }

    @Test fun `es un interruptor, no una regla escrita a mano en cada pantalla`() {
        // Si alguien pone la constante en false, las DOS pantallas vuelven a ofrecerlos a la vez.
        assertThat(CobroRemotoDelPos.OCULTAR_EFECTIVO_Y_CRIPTO_EN_COBRO_REMOTO).isTrue()
        assertThat(CobroRemotoDelPos.FUENTE_SOCKET).isEqualTo("SOCKET")
    }

    /**
     * H8 (Codex, 26-sep; medido en QA-1): con el lector abierto, la guarda de la navegación rechazaba el cobro del POS con su
     * propio texto («Ya hay un pago en proceso en el terminal») y la tablet leía «rechazado». La terminal dice UNA sola verdad
     * para «este aparato ya está cobrando»: la misma que la barrera de la libreta. Estática, como las demás de `AppNavigation`.
     */
    @Test fun `la guarda de la navegacion rechaza con el MISMO texto de cobro en curso`() {
        val navegacion = java.io.File(
            "src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt",
        ).readText()
        val inicio = navegacion.indexOf("if (terminalGenuinelyBusy) {")
        assertThat(inicio).isAtLeast(0)
        val guarda = navegacion.substring(inicio, navegacion.indexOf("return@collect", inicio))
        assertThat(guarda).contains("errorMessage = CobroRemotoDelPos.NO_INICIADO_POR_COBRO_EN_CURSO")
        assertThat(navegacion).doesNotContain("Ya hay un pago en proceso")
    }
}
