package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 🔴 INTERINO (P2-9): efectivo y cripto no se ofrecen en un cobro que pidió el POS.
 *
 * La terminal los cobra y los registra por su cuenta, pero el servidor sólo cierra la solicitud con
 * TARJETA: la fila queda UNKNOWN con el dinero ya cobrado y la tablet ofreciendo cobrar otra vez
 * (medido en hardware el 10-sep). Se revierte cambiando UNA constante, y por eso está fijada aquí.
 */
class CobroRemotoDelPosTest {

    // 🔴 Founder, 29-sep-2026: sólo aparta lo que se está ejecutando EN ESE INSTANTE.
    @Test fun `P1 una devolucion hablando con AngelPay rechaza el cobro del POS con su motivo`() {
        assertThat(CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro = false, cobroActivo = false, devolucionEnCurso = true))
            .isEqualTo(CobroRemotoDelPos.NO_INICIADO_POR_DEVOLUCION_EN_CURSO)
    }

    @Test fun `un cobro activo en su pantalla sigue rechazando otro cobro del POS`() {
        assertThat(CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro = true, cobroActivo = true, devolucionEnCurso = false))
            .isEqualTo(CobroRemotoDelPos.NO_INICIADO_POR_COBRO_EN_CURSO)
    }

    @Test fun `P1 sin nada ejecutandose el cobro del POS entra - la pantalla de un resultado viejo no aparta`() {
        assertThat(CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro = true, cobroActivo = false, devolucionEnCurso = false)).isNull()
        assertThat(CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro = false, cobroActivo = true, devolucionEnCurso = false)).isNull()
        assertThat(CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro = false, cobroActivo = false, devolucionEnCurso = false)).isNull()
    }

    @Test fun `un cobro que pidio el POS no ofrece efectivo ni cripto`() {
        assertThat(CobroRemotoDelPos.permiteEfectivoYCripto("SOCKET")).isFalse()
    }

    // PAX, 3-nov: tras un rechazo, «Cancelar» regresaba a elegir método con el POS esperando y sin reloj; al salir de ahí
    // con el botón físico de atrás nadie le contestaba al POS y el SERVIDOR dejaba la terminal ocupada.
    @Test fun `sólo el cobro que mandó el POS cuenta como cobro del POS`() {
        assertThat(CobroRemotoDelPos.esCobroDelPos("SOCKET")).isTrue()
        assertThat(CobroRemotoDelPos.esCobroDelPos(null)).isFalse()
        assertThat(CobroRemotoDelPos.esCobroDelPos("KIOSK")).isFalse()
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
        // 29-sep: la guarda ya no escribe el texto a mano — lo decide `motivoParaNoIniciar` (cobro en curso o devolución en
        // curso), y para «el lector está cobrando» devuelve la MISMA constante de la barrera de la libreta.
        val navegacion = java.io.File(
            "src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt",
        ).readText()
        val inicio = navegacion.indexOf("val motivoParaNoIniciar = CobroRemotoDelPos.motivoParaNoIniciar(")
        assertThat(inicio).isAtLeast(0)
        val guarda = navegacion.substring(inicio, navegacion.indexOf("return@collect", inicio))
        assertThat(guarda).contains("errorMessage = motivoParaNoIniciar")
        assertThat(CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro = true, cobroActivo = true, devolucionEnCurso = false))
            .isEqualTo(CobroRemotoDelPos.NO_INICIADO_POR_COBRO_EN_CURSO)
        assertThat(navegacion).doesNotContain("Ya hay un pago en proceso")
    }

    // ── Codex TPV r1, ronda 2 (N1, 22-oct): la bandeja (antes de certificar el cese) y la navegación (al admitir una venta del
    //    POS) leen la MISMA regla, para que «libre» en el SERVIDOR signifique que la terminal SÍ aceptaría otra venta. ──

    private fun banderas(cobroActivo: Boolean, devolucion: Boolean) =
        object : com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateProvider {
            override fun isCharging() = cobroActivo || devolucion
            override fun isChargeAttemptActive() = cobroActivo
            override fun isRefundInFlight() = devolucion
        }

    @Test fun `P1 la bandeja dice ocupada siempre que la admision rechazaria una venta nueva, en cualquier ruta`() {
        for (cobroActivo in listOf(false, true)) for (devolucion in listOf(false, true)) {
            val estado = banderas(cobroActivo, devolucion)
            // Es la admisión con la pantalla de cobro abierta (la peor ruta: la bandeja no ve la navegación)…
            assertThat(CobroRemotoDelPos.laTerminalRechazariaUnaVentaNueva(estado))
                .isEqualTo(CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro = true, cobroActivo = cobroActivo, devolucionEnCurso = devolucion) != null)
            // …así que nunca dice «aceptaría» cuando la navegación rechaza, esté donde esté.
            for (enPantallaDeCobro in listOf(false, true)) {
                if (CobroRemotoDelPos.motivoParaNoIniciar(enPantallaDeCobro, estado) != null) {
                    assertThat(CobroRemotoDelPos.laTerminalRechazariaUnaVentaNueva(estado)).isTrue()
                }
            }
        }
    }

    // D1 (QA 2-nov): en un proceso nuevo, con la app en el Inicio, esta regla no niega el certificado de cese. Lo sostienen dos
    // cosas, y se fija cada una: nadie ha publicado nada al nacer, y el cobro activo sólo lo publican las pantallas de cobro.
    @Test fun `D1 el estado de un proceso recien nacido no niega el certificado`() {
        val recienNacido = com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder()
        assertThat(CobroRemotoDelPos.laTerminalRechazariaUnaVentaNueva(recienNacido)).isFalse()
    }

    @Test fun `D1 el cobro activo solo lo publican las pantallas de cobro, que en el Inicio no existen`() {
        val quienLoPublica = java.io.File("src").walkTopDown()
            .filter { it.extension == "kt" && !it.path.contains("/test/") && !it.path.contains("/androidTest/") }
            .filter { "setChargeAttemptActive(" in it.readText() }
            .map { it.name }
            .toSet()

        assertThat(quienLoPublica).containsExactly("PaymentStateHolder.kt", "PaymentViewModel.kt", "AngelPayPaymentViewModel.kt")
    }

    @Test fun `P1 la navegacion admite con las banderas del proveedor - la misma entrada que lee la bandeja`() {
        val navegacion = java.io.File(
            "src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt",
        ).readText()
        val inicio = navegacion.indexOf("val motivoParaNoIniciar = CobroRemotoDelPos.motivoParaNoIniciar(")
        assertThat(inicio).isAtLeast(0)
        val llamada = navegacion.substring(inicio, navegacion.indexOf("if (motivoParaNoIniciar != null)", inicio))
        assertThat(llamada).contains("estado = paymentStateProvider")
        assertThat(llamada).doesNotContain("isChargeAttemptActive")
        assertThat(llamada).doesNotContain("isRefundInFlight")
    }

    // D18 (PAX sin WiFi, 3-nov): el cobro del POS no trae botón de efectivo, y el texto lo ofrecía. Ese mismo texto le llega al POS.
    @Test fun `P2 sin red antes de cobrar - el cobro del POS no ofrece efectivo en el texto y dice que no se cobró`() {
        val delPos = CobroRemotoDelPos.textoSinRedAntesDeCobrar("SOCKET")
        assertThat(delPos).doesNotContain("efectivo")
        assertThat(delPos).contains("no se cobró")
        assertThat(CobroRemotoDelPos.textoSinRedAntesDeCobrar(null)).isEqualTo("Sin conexión a internet.\n\n¿Cobrar en efectivo?")
    }

    @Test fun `P2 las dos variantes de la PAX usan el texto del cobro del POS al bloquear sin red`() {
        for (variante in listOf("sandbox", "production")) {
            val vm = java.io.File("src/$variante/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt").readText()
            assertWithMessage(variante).that(vm).contains("message = CobroRemotoDelPos.textoSinRedAntesDeCobrar(_paymentSource)")
            assertWithMessage(variante).that(vm).doesNotContain("message = \"Sin conexión a internet.\\n\\n¿Cobrar en efectivo?\"")
        }
    }

    // D669: la terminal bloqueada (o en mantenimiento) desde Avoqado no inicia nada que mande el POS, y lo dice.
    @Test fun `P2 D669 bloqueada o en mantenimiento - el cobro y la devolucion del POS dicen por que no se iniciaron`() {
        assertThat(CobroRemotoDelPos.motivoDelCandado(bloqueada = true, enMantenimiento = false))
            .isEqualTo("Está bloqueada desde Avoqado. Este cobro NO se inició.")
        assertThat(CobroRemotoDelPos.motivoDelCandado(bloqueada = false, enMantenimiento = true))
            .isEqualTo("Está en mantenimiento. Este cobro NO se inició.")
        assertThat(CobroRemotoDelPos.motivoDelCandado(bloqueada = true, enMantenimiento = false, esDevolucion = true))
            .isEqualTo("La terminal está bloqueada desde Avoqado. No se abrió la devolución.")
        assertThat(CobroRemotoDelPos.motivoDelCandado(bloqueada = false, enMantenimiento = true, esDevolucion = true))
            .isEqualTo("La terminal está en mantenimiento. No se abrió la devolución.")
        // El bloqueo es el más fuerte: sólo Avoqado lo quita (el mantenimiento lo quita el cajero).
        assertThat(CobroRemotoDelPos.motivoDelCandado(bloqueada = true, enMantenimiento = true)).contains("bloqueada")
        assertThat(CobroRemotoDelPos.motivoDelCandado(bloqueada = false, enMantenimiento = false)).isNull()
        assertThat(CobroRemotoDelPos.motivoDelCandado(bloqueada = false, enMantenimiento = false, esDevolucion = true)).isNull()
    }

    /**
     * D669: la admisión del cobro del POS pregunta por el candado. Se lee la fuente (`AppNavigation` no se levanta en estas pruebas):
     * sin este cableado, la prueba de la bandeja daría un verde falso (la lección de D665).
     */
    @Test fun `P2 D669 la navegacion le pasa el candado a la admision del cobro del POS`() {
        val navegacion = java.io.File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
        val admision = navegacion.substring(navegacion.indexOf("remotePaymentCoordinator.prepareSocketPaymentRequest("))
            .substringBefore("when (admission)")
        assertThat(admision).contains("motivoDelCandado = {")
        assertThat(admision).contains("lockScreenManager.isCurrentlyLocked()")
        assertThat(admision).contains("maintenanceManager.isCurrentlyInMaintenance()")
    }

    /**
     * D669 (Codex r1 #2): un LOCK que ya se estaba aplicando cuando llegó la devolución termina antes de que la navegación la abra
     * (`beginAction` espera al comando). La navegación vuelve a mirar el candado ANTES de abrir el detalle.
     */
    @Test fun `P2 D669 la navegacion vuelve a mirar el candado antes de abrir la devolucion del POS`() {
        val navegacion = java.io.File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
        val devolucion = navegacion.substring(navegacion.indexOf("remotePaymentCoordinator.refundRequests.collect"))
            .substringBefore("NavRoute.Payments.createRoute(autoOpenPaymentId")
        assertThat(devolucion).contains("commandWindow.beginAction()")
        val mira = devolucion.indexOf("esDevolucion = true")
        assertThat(mira).isGreaterThan(devolucion.indexOf("commandWindow.beginAction()"))
        assertThat(devolucion).contains("lockScreenManager.isCurrentlyLocked()")
        assertThat(devolucion).contains("maintenanceManager.isCurrentlyInMaintenance()")
    }
}
