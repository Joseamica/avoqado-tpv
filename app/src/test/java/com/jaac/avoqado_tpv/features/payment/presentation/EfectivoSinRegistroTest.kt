package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import org.junit.Test

/**
 * 6a2254 (founder, 24-nov: el freno mínimo, «que no sea overengineer»). POS → TPV, PAX: con «Error registrando pago en
 * efectivo» de la cuenta X en la pantalla, la tablet podía mandar X otra vez y la terminal abría la tarjeta encima. El efectivo
 * ya pudo entrar al cajón sin registro: se le cobraba dos veces al cliente.
 *
 * El freno: una marca EN MEMORIA de la cuenta X mientras su pantalla muestra ese error, y la admisión del cobro del POS de ESA
 * cuenta contesta «no se inició» con la razón. Las demás cuentas, y el Pago rápido del POS (sin cuenta), pasan.
 */
class EfectivoSinRegistroTest {

    @Test fun `P1 la cuenta con el error de registro del efectivo no se cobra desde el POS`() {
        val estado = PaymentStateHolder()
        val pantalla = estado.entraPantallaDeCobro()
        estado.marcarEfectivoSinRegistro(pantalla, "cuenta-X")

        assertThat(CobroRemotoDelPos.motivoPorEfectivoSinRegistro("cuenta-X", estado))
            .isEqualTo(CobroRemotoDelPos.NO_INICIADO_POR_EFECTIVO_SIN_REGISTRO)
    }

    @Test fun `P1 otra cuenta y el Pago rapido del POS pasan`() {
        val estado = PaymentStateHolder()
        val pantalla = estado.entraPantallaDeCobro()
        estado.marcarEfectivoSinRegistro(pantalla, "cuenta-X")

        assertThat(CobroRemotoDelPos.motivoPorEfectivoSinRegistro("cuenta-Y", estado)).isNull()
        assertThat(CobroRemotoDelPos.motivoPorEfectivoSinRegistro(null, estado)).isNull()
    }

    @Test fun `sin marca nada se frena`() {
        assertThat(CobroRemotoDelPos.motivoPorEfectivoSinRegistro("cuenta-X", PaymentStateHolder())).isNull()
    }

    @Test fun `P1 al salir del error la marca se quita y la cuenta vuelve a poder cobrarse`() {
        val estado = PaymentStateHolder()
        val pantalla = estado.entraPantallaDeCobro()
        estado.marcarEfectivoSinRegistro(pantalla, "cuenta-X")

        estado.quitarEfectivoSinRegistro(pantalla)

        assertThat(estado.cuentaConEfectivoSinRegistro()).isNull()
        assertThat(CobroRemotoDelPos.motivoPorEfectivoSinRegistro("cuenta-X", estado)).isNull()
    }

    // D666: una pantalla muere ~700 ms después de salir. Si en ese rato otra pantalla mostró SU error, la vieja no se lo quita.
    @Test fun `P1 la pantalla vieja que muere no quita la marca de una pantalla mas nueva`() {
        val estado = PaymentStateHolder()
        val vieja = estado.entraPantallaDeCobro()
        val nueva = estado.entraPantallaDeCobro()
        estado.marcarEfectivoSinRegistro(nueva, "cuenta-X")

        estado.quitarEfectivoSinRegistro(vieja)

        assertThat(estado.cuentaConEfectivoSinRegistro()).isEqualTo("cuenta-X")
    }

    @Test fun `la marca no aparta la terminal - no es un cobro en curso`() {
        val estado = PaymentStateHolder()
        estado.marcarEfectivoSinRegistro(estado.entraPantallaDeCobro(), "cuenta-X")

        assertThat(estado.isChargeAttemptActive()).isFalse()
        assertThat(estado.isCharging()).isFalse()
        assertThat(CobroRemotoDelPos.laTerminalRechazariaUnaVentaNueva(estado)).isFalse()
    }

    // Codex r1 #1: una devolución del POS abre Pagos ENCIMA del error; la pantalla del error sigue viva debajo y su efectivo sigue
    // sin registro. El freno se queda a propósito (soltarlo dejaría cobrar X con tarjeta) y el texto no dice que «se ve».
    @Test fun `P1 el freno dura mientras la pantalla del error exista aunque otra la tape, y el texto no dice que se ve`() {
        val estado = PaymentStateHolder()
        val delError = estado.entraPantallaDeCobro()
        estado.marcarEfectivoSinRegistro(delError, "cuenta-X")
        // Otra pantalla de cobro nace encima (p. ej. el cobro del POS de otra cuenta) y se va: no toca la marca de la de abajo.
        val encima = estado.entraPantallaDeCobro()
        estado.quitarEfectivoSinRegistro(encima)
        estado.salePantallaDeCobro(encima, soltarVentanaDelDinero = true)

        assertThat(CobroRemotoDelPos.motivoPorEfectivoSinRegistro("cuenta-X", estado)).isNotNull()
        assertThat(CobroRemotoDelPos.NO_INICIADO_POR_EFECTIVO_SIN_REGISTRO).contains("tiene abierto")
        assertThat(CobroRemotoDelPos.NO_INICIADO_POR_EFECTIVO_SIN_REGISTRO).doesNotContain("muestra")
    }

    @Test fun `el texto le dice a la tablet que no se inicio y que el cliente pudo haber pagado en efectivo`() {
        val texto = CobroRemotoDelPos.NO_INICIADO_POR_EFECTIVO_SIN_REGISTRO
        assertThat(texto).contains("NO se inició")
        assertThat(texto).contains("efectivo")
    }

    // ── Cableado (la TPV no se compila a mano en la nube: estas pruebas fijan que las piezas se usan donde deben) ──

    private fun leer(ruta: String) = java.io.File(ruta).readText()

    @Test fun `P1 la admision del cobro del POS encadena la regla antes de abrir la pantalla`() {
        val navegacion = leer("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt")
        val inicio = navegacion.indexOf("val motivoParaNoIniciar = CobroRemotoDelPos.motivoParaNoIniciar(")
        assertThat(inicio).isAtLeast(0)
        val guarda = navegacion.substring(inicio, navegacion.indexOf("if (motivoParaNoIniciar != null)", inicio))
        assertThat(guarda).contains("CobroRemotoDelPos.motivoPorEfectivoSinRegistro(request.orderId, paymentStateProvider)")
    }

    @Test fun `P1 las dos variantes de la PAX ponen la marca con el error y la quitan al salir, igual`() {
        val lineas = listOf(
            "errorDeEfectivoSinRegistro = orderIdForFlow?.let { errorDelEfectivo to it }",
            "_state.value = errorDelEfectivo",
            "paymentStateHolder.marcarEfectivoSinRegistro(pantallaDeCobro, efectivo.second)",
            "paymentStateHolder.quitarEfectivoSinRegistro(pantallaDeCobro)",
        )
        val variantes = listOf("sandbox", "production").associateWith {
            leer("src/$it/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt")
        }
        for ((variante, vm) in variantes) {
            for (linea in lineas) assertWithMessage("$variante: $linea").that(vm).contains(linea)
            // La marca nace ANTES de publicar el error (el colector la lee al ver ese estado).
            assertWithMessage(variante).that(vm.indexOf("errorDeEfectivoSinRegistro = orderIdForFlow?.let"))
                .isLessThan(vm.indexOf("_state.value = errorDelEfectivo"))
            // Y al morir la pantalla también se quita (pantalla por pantalla: D666).
            val alMorir = vm.substring(vm.indexOf("override fun onCleared()"))
            assertWithMessage(variante).that(alMorir).contains("paymentStateHolder.quitarEfectivoSinRegistro(pantallaDeCobro)")
        }
    }
}
