package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * El CABLEADO del botón «No se cobró», que aquí no se puede ejecutar: `MainActivity` no se levanta con Hilt en estas pruebas
 * (`NexgoFlavorHiltGraphTest`) y `createComposeRule` sólo está en `androidTest` (`AngelPayPaymentScreenSalidaTest`). Se lee la fuente, como
 * en `EstadoDelProcesoTest`. Lo que decide se prueba de verdad en `FlujoDeNoSeCobroTest` y `DeclaracionDesdeElAvisoRoomTest`.
 */
class NoSeCobroCableadoTest {
    private val navegacion = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
    private val dialogos = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/NoSeCobroDesdeElAviso.kt").readText()

    @Test fun `AppNavigation consigue la declaracion por el punto de entrada de Hilt y le da el banner`() {
        assertThat(navegacion).contains("fun declaracionDesdeElAviso(): com.jaac.avoqado_tpv.features.payment.data.ledger.DeclaracionDesdeElAviso")
        assertThat(navegacion).contains("kioskEntryPoint.declaracionDesdeElAviso()")
        assertThat(navegacion).contains("BannerDeCobrosPendientes(")
    }

    /** F1 (tarea 4): el Inicio ya no declara nada: su aviso lleva a «Revisar» (Pagos), donde entra el gerente con su PIN. */
    @Test fun `F1 el banner del Inicio ya no ofrece declarar - sin dialogo ni flujo, solo Revisar y Ayuda`() {
        val banner = dialogos.substring(dialogos.indexOf("internal fun BannerDeCobrosPendientes("), dialogos.indexOf("internal fun CobrosPorRevisar("))
        assertThat(banner).contains("aviso: String?,")
        assertThat(banner).doesNotContain("DialogoDeNoSeCobro(")
        assertThat(banner).doesNotContain("FlujoDeNoSeCobro(")
        assertThat(banner).contains("onRevisar = onRevisar")
    }

    @Test fun `D20 Pagos lleva el recuadro Por revisar con la misma declaracion, sin la ventana de 10 min`() {
        val pagos = File("src/main/java/com/jaac/avoqado_tpv/features/payments/presentation/PaymentsScreen.kt").readText()
        assertThat(pagos).contains("encabezado()")
        val desdeLaRuta = navegacion.substring(navegacion.indexOf("route = NavRoute.Payments.route,"))
        val rutaDePagos = desdeLaRuta.substring(0, desdeLaRuta.indexOf("composable("))
        assertThat(rutaDePagos).contains("encabezado = {")
        assertThat(rutaDePagos).contains("CobrosPorRevisar(")
        assertThat(rutaDePagos).contains("kioskEntryPoint.declaracionDesdeElAviso()")
        val recuadro = dialogos.substring(dialogos.indexOf("internal fun CobrosPorRevisar("))
        assertThat(recuadro).contains("DeclaracionDesdeElAviso.porRevisar(")
        assertThat(recuadro).doesNotContain("DeclaracionDesdeElAviso.vigentes(")
        assertThat(recuadro).contains("FlujoDeNoSeCobro(")
        assertThat(recuadro).contains("declaracion.refrescarUno(")
        assertThat(recuadro).contains("repeatOnLifecycle(Lifecycle.State.STARTED)")
        // Codex r2: con la lista llena, «Ver anteriores» amplía la consulta en vez de sólo avisar.
        assertThat(recuadro).contains("\"Ver anteriores\"")
        assertThat(recuadro).contains("limite += DeclaracionDesdeElAviso.TOPE_DE_LA_CONSULTA")
    }

    @Test fun `el aviso sigue sin poder taparse, y el comentario ya no dice que no se puede descartar`() {
        assertThat(navegacion).doesNotContain("No se puede descartar a propósito")
        assertThat(navegacion).contains("se resuelve declarando")
    }

    @Test fun `el refresco corre mientras el banner esta a la vista y haya declarables, cada 15 s, sin la consulta de Caja`() {
        assertThat(dialogos).contains("repeatOnLifecycle(Lifecycle.State.STARTED)")
        assertThat(dialogos).contains("declaracion.refrescarUno(venueId, vigentesAlDia)")
        assertThat(dialogos).contains("REFRESCO_DEL_BANNER_MS = 15_000L")
        assertThat(dialogos).doesNotContain("ConsultaDelCierreDeCaja")
        assertThat(dialogos).doesNotContain("liberarDudasLocales")
        assertThat(dialogos).contains("if (!hayPorRevisar) return@LaunchedEffect")
    }

    @Test fun `F1 el aviso del Inicio lleva Revisar y Ayuda, con el texto arriba y los botones abajo - sin No se cobro`() {
        val banner = dialogos.substring(dialogos.indexOf("internal fun AvisoConBotones("), dialogos.indexOf("private fun DialogoDeNoSeCobro("))
        val texto = banner.indexOf("text = aviso")
        val fila = banner.indexOf("Row(")
        assertThat(texto).isAtLeast(0)
        assertThat(fila).isGreaterThan(texto)
        assertThat(banner).doesNotContain("\"No se cobró\"")
        for (boton in listOf("\"Revisar\"", "\"Ayuda\"")) assertThat(banner).contains(boton)
        // Y en Pagos, el recuadro «Por revisar» abre la revisión del gerente.
        val recuadro = dialogos.substring(dialogos.indexOf("internal fun CobrosPorRevisar("), dialogos.indexOf("internal fun AvisoConBotones("))
        assertThat(recuadro).contains("Text(\"Revisar\")")
        assertThat(recuadro).doesNotContain("\"No se cobró\"")
    }

    @Test fun `los textos de los dialogos son los acordados`() {
        // F1 (Codex código r1 #10): un solo paso — el PIN del gerente y [Firmar] juntos; sin «Continuar» ni pantalla de decisión aparte.
        for (texto in listOf(
            "¿Cuál vas a revisar?",
            "Gerente: revisé la terminal y este cobro de",
            "no aparece. Escribe tu PIN para firmarlo.",
            "PIN de gerente",
            "Ese PIN no es de un gerente con permiso.",
            "Queda en Avoqado con tu nombre y la hora. Necesita internet.",
            "Firmar",
            "Cancelar",
        )) assertThat(dialogos).contains(texto)
        for (texto in listOf("Sí, no se cobró", "\"Continuar\"", "Firma del gerente", "ConfirmacionDialog")) assertThat(dialogos).doesNotContain(texto)
    }

    @Test fun `el campo del PIN acepta lo mismo que el servidor y se escribe como contrasena`() {
        assertThat(dialogos).contains("isPassword = true")
        assertThat(dialogos).contains("KeyboardType.NumberPassword")
        assertThat(dialogos).contains("nuevo.length <= PIN_MAXIMO")
        assertThat(dialogos).contains("pinDeGerenteValido(pin)")
    }

    @Test fun `cada dialogo y el banner tienen su vista previa en 360 por 640`() {
        val previas = Regex("""@Preview\([^)]*widthDp = 360, heightDp = 640[^)]*\)\s*@Composable\s*private fun (\w+)\(""")
            .findAll(dialogos).map { it.groupValues[1] }.toList()
        assertThat(previas).containsAtLeast(
            "BannerConBotonPreview", "ListaPreview", "PinPreview", "AvisoPreview",
        )
    }

    @Test fun `el PIN se firma tambien con la palomita del teclado - con el teclado abierto no se ve Firmar`() {
        // QA en la N86, 3-nov: la palomita no hacía nada y el gerente tenía que cerrar el teclado para encontrar el botón.
        val fuente = java.io.File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/NoSeCobroDesdeElAviso.kt").readText()
        com.google.common.truth.Truth.assertThat(fuente).contains("imeAction = ImeAction.Done")
        com.google.common.truth.Truth.assertThat(fuente).contains("onDone = { if (pinDeGerenteValido(pin)) onFirmar(pin) }")
    }
    // ── F1 mínimo, tarea 5 (17-nov): en la PAX el resultado incierto sólo muestra el importe y [Seguir] ──────────────────────

    private val recuperacionPax = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/BlumonRecoveryContent.kt").readText()
    private val pantallaPax = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentScreen.kt").readText()

    @Test fun `F1 el texto de la tarjeta en duda lleva el total con propina y dice que no se repita`() {
        assertThat(tarjetaPorConfirmar(java.math.BigDecimal("105.00"))).isEqualTo("Tarjeta $105.00 por confirmar. No la repitas. Avisa al gerente.")
        assertThat(tarjetaPorConfirmar(null)).isEqualTo("Tarjeta por confirmar. No la repitas. Avisa al gerente.")
        assertThat(totalDelIntento(com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
            attemptId = "a", venueId = "v", processor = "BLUMON", state = "INDETERMINADO", amountCents = 10_000, tipCents = 500,
            recordingRoute = "ORDER", paymentContextJson = "{}", createdAt = 1, updatedAt = 2,
        ))).isEqualTo(java.math.BigDecimal("105.00"))
    }

    @Test fun `F1 la recuperacion de la PAX no ofrece ninguna decision al cajero - solo Seguir, siempre activo y una sola vez`() {
        for (fuera in listOf("Ya revisé la terminal", "El cliente no presentó tarjeta", "Consultar de nuevo", "\"Regresar\"", "onDeclare", "onConsult"))
            assertThat(recuperacionPax).doesNotContain(fuera)
        val boton = recuperacionPax.substring(recuperacionPax.indexOf("Text(\"Seguir\")") - 300, recuperacionPax.indexOf("Text(\"Seguir\")"))
        assertThat(boton).doesNotContain("enabled")
        // Un doble toque sale UNA vez.
        assertThat(recuperacionPax).contains("if (!salio) { salio = true; onSeguir() }")
        assertThat(recuperacionPax).contains("tarjetaPorConfirmar(")
    }

    @Test fun `F1 la PaymentScreen monta la recuperacion con Seguir = la salida de siempre, sin cancelar el intento`() {
        val montaje = pantallaPax.substring(pantallaPax.indexOf("BlumonRecoveryContent("), pantallaPax.indexOf("} else PaymentErrorContent("))
        assertThat(montaje).contains("onSeguir = { viewModel.resetPayment(); navigateBack() }")
        assertThat(montaje).doesNotContain("declare")
        assertThat(montaje).doesNotContain("consultAgain")
    }

    // D8: la solicitud no admitida se contesta en el acto, con el motivo, en vez de callar.
    @Test fun `el cobro que la terminal no admite se le contesta al POS`() {
        val rama = navegacion.substringAfter("is com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentAdmission.NotAdmitted ->")
            .substringBefore("return@collect")
        assertThat(rama).contains("rejectRemotePaymentBeforeAuthorization(")
        assertThat(rama).contains("admission.motivo")
    }
}
