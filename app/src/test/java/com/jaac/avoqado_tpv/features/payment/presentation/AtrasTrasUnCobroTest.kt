package com.jaac.avoqado_tpv.features.payment.presentation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔙 Encargo f84e3a (hz:nube0000:naca8ca6, 24-nov): con el cobro YA aprobado, el «atrás» FÍSICO de Android no tenía quién lo
 * atendiera en la pantalla de éxito (PAX: `BackHandler(enabled = showTopBar)` y `showTopBar = state !is Success`; Nexgo: el
 * éxito y la cola se pintan ANTES del `BackHandler` de la pantalla, con `return`). El sistema sacaba la pantalla de cobro y
 * regresaba a la entrada anterior: desde Cobrar, el MISMO Cobrar con el carrito lleno (el carrito no se vacía al pasar al
 * cobro), y un segundo «Cobrar» creaba otra venta con la misma mercancía. Lo mismo desde Mesas (el cobro viejo con el saldo de
 * antes) y desde el reembolso (la confirmación del reembolso ya hecho).
 *
 * La regla (rediseñada tras Codex r3): desde que hubo un cobro aprobado, el «atrás» de antes de cobrar se apaga. Con el recibo a
 * la vista, «atrás» hace UNA vez lo mismo que el botón principal («Nueva Orden», «Nuevo Pago», «Listo», «Continuar pagando»…),
 * que ya lleva a un lugar NUEVO; en cualquier otro momento (la animación de «Aprobado», la espera tras ese botón, «No se pudo
 * imprimir») se ignora. En un cobro del POS sin recibo todavía también se ignora: salir callaría el aviso de éxito al POS. La
 * cámara de la prueba de venta se cierra con «atrás» en vez de salir de la pantalla.
 *
 * Prueba ESTÁTICA, como [com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentScreenSalidaTest]: el repo no
 * monta pantallas de Compose en las pruebas de JVM. El comportamiento se acredita en el aparato (PAX y N86).
 */
class AtrasTrasUnCobroTest {

    private val pantallaPax = File(
        "src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentScreen.kt",
    ).readText()
    private val pantallaNexgo = File(
        "src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt",
    ).readText()
    private val exitoNexgo = File(
        "src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPaySuccessContent.kt",
    ).readText()

    private fun cuerpoDe(texto: String, firma: String, hasta: String): String {
        val inicio = texto.indexOf(firma)
        require(inicio >= 0) { "No encontré «$firma»" }
        val fin = texto.indexOf(hasta, inicio + firma.length)
        require(fin > inicio) { "No encontré «$hasta» después de «$firma»" }
        return texto.substring(inicio, fin)
    }

    @Test
    fun `PAX - en el exito, atras hace lo mismo que el boton principal`() {
        val exito = cuerpoDe(pantallaPax, "private fun PaymentSuccessContent(", "\n@Composable")
        assertTrue(
            "PaymentSuccessContent debe calcular su acción principal UNA vez (reembolso → Listo, saldo → Continuar pagando, si no " +
                "la ruta de resolveSuccessRouting) para que el botón y el «atrás» no puedan separarse.",
            Regex(
                """val accionPrincipal: \(\) -> Unit = when \{\s*isRefund -> onRefundComplete\s*hasRemainingBalance -> onContinuePayment\s*else -> successRouting\.onClick\s*}""",
            ).containsMatchIn(exito),
        )
        assertTrue(
            "El «atrás» del sistema en el éxito de la PAX debe cerrar la cámara si está abierta y, si no, hacer la acción principal " +
                "(sin esto regresaba a Cobrar con el carrito lleno: encargo f84e3a).",
            Regex(
                """BackHandler\(enabled = !isKioskPayment\) \{\s*when \{\s*showProofOfSaleCamera -> showProofOfSaleCamera = false\s*atrasEsperaAlRecibo -> Timber\.d\([^\n]*\)\s*yaSalioPorAtras -> Timber\.d\([^\n]*\)\s*else -> \{\s*yaSalioPorAtras = true\s*accionPrincipal\(\)\s*}\s*}\s*}""",
            ).containsMatchIn(exito),
        )
        assertTrue(
            "El botón «Nueva Orden»/«Nuevo Pago» debe usar la MISMA ruta que el «atrás» (successRouting).",
            Regex("""onClick = \{ yaSalioPorAtras = true; successRouting\.onClick\(\) }""").containsMatchIn(exito),
        )
    }

    @Test
    fun `PAX - en un cobro del POS, atras espera el recibo para no callar el aviso de exito (Codex r2 2)`() {
        val llamada = cuerpoDe(pantallaPax, "PaymentSuccessContent(\n", "\n                            )")
        assertTrue(
            "La pantalla debe pedir que el «atrás» espere el recibo justo cuando observeSocketPaymentResult todavía no emite: cobro del " +
                "POS y sin paymentId.",
            Regex(
                """atrasEsperaAlRecibo = CobroRemotoDelPos\.esCobroDelPos\(paymentSource\) &&\s*currentState\.receipt\?\.paymentId\.isNullOrBlank\(\)""",
            ).containsMatchIn(llamada),
        )
    }

    @Test
    fun `PAX - despues de un cobro aprobado, el atras de antes de cobrar se apaga y fuera del recibo se ignora (Codex r3)`() {
        val pantalla = cuerpoDe(pantallaPax, "fun PaymentScreen(", "Scaffold(")
        assertTrue(
            "huboExito se enciende con Success (fuera del kiosco).",
            Regex("""LaunchedEffect\(state\) \{\s*if \(state is PaymentState\.Success && !isKioskPayment\) huboExito = true\s*}""")
                .containsMatchIn(pantalla),
        )
        assertTrue(
            "huboExito sobrevive a recrear la pantalla (rememberSaveable, Codex r4 #3).",
            pantalla.contains("var huboExito by rememberSaveable { mutableStateOf(false) }"),
        )
        assertTrue(
            "La flecha lee el estado AL TOCAR: si el cobro se aprobó en ese instante, no regresa a antes de cobrar (Codex r4 #1).",
            Regex("""val flechaAtras: \(\) -> Unit = \{[^}]*if \(!isKioskPayment && viewModel\.state\.value is PaymentState\.Success\) \{""")
                .containsMatchIn(pantalla),
        )
        assertTrue(
            "En Success y después (animación, espera de Mesas, «No se pudo imprimir») el «atrás» del sistema se ignora.",
            Regex("""BackHandler\(enabled = !isKioskPayment && \(state is PaymentState\.Success \|\| huboExito\)\) \{""")
                .containsMatchIn(pantalla),
        )
        assertTrue(
            "El «atrás» de antes de cobrar (la flecha) ya no corre después de un cobro: desde Mesas abría otra cuenta de la mesa.",
            Regex("""BackHandler\(enabled = showTopBar && !huboExito, onBack = flechaAtras\)""").containsMatchIn(pantalla),
        )
        assertTrue(
            "El manejador que ignora debe registrarse ANTES que el de la flecha y que el del recibo (el último registrado gana).",
            pantalla.indexOf("BackHandler(enabled = !isKioskPayment && (state is PaymentState.Success || huboExito))") <
                pantalla.indexOf("BackHandler(enabled = showTopBar && !huboExito, onBack = flechaAtras)"),
        )
    }

    @Test
    fun `Nexgo - en el exito y en la cola, atras hace lo mismo que Nuevo Pago`() {
        val exito = cuerpoDe(exitoNexgo, "fun AngelPaySuccessContent(", "\n@Composable")
        assertTrue(
            "AngelPaySuccessContent (éxito y cola) debe atender el «atrás»: cerrar la cámara si está abierta y, si no, lo mismo " +
                "que su botón principal (onStartNewPayment).",
            Regex(
                """BackHandler \{\s*when \{\s*showProofOfSaleCamera -> showProofOfSaleCamera = false\s*yaSalioPorAtras -> Unit\s*else -> \{\s*yaSalioPorAtras = true\s*onStartNewPayment\(\)\s*}\s*}\s*}""",
            ).containsMatchIn(exito),
        )
        assertTrue(
            "El botón principal de la Nexgo también enciende la marca: el «atrás» que llegue después no repite la salida (Codex r4 #5).",
            exito.contains("onClick = { yaSalioPorAtras = true; onStartNewPayment() },"),
        )
    }

    @Test
    fun `Nexgo - en todo el tramo del exito sin recibo a la vista, atras se ignora (Codex r1 1 y 2)`() {
        // Antes de los overlays (que hacen `return`): se compone en TODO estado, también en el primer cuadro de Success (la
        // animación se enciende en un efecto) y en la espera tras el botón principal (Idle con la bandera de éxito puesta).
        val antesDeLosOverlays = cuerpoDe(pantallaNexgo, "fun AngelPayPaymentScreen(", "// ── Full-screen overlays (no scaffold)")
        assertTrue(
            "AngelPayPaymentScreen debe ignorar el «atrás» del sistema en Success, Queued, la animación y la espera tras el botón.",
            Regex(
                """BackHandler\(\s*enabled = state is AngelPayPaymentState\.Success \|\| state is AngelPayPaymentState\.Queued \|\|\s*state is AngelPayPaymentState\.RecordingPayment \|\| state is AngelPayPaymentState\.ProcessingCash \|\|\s*showApprovedAnimation \|\| showSuccessContent \|\| huboExito,\s*\) \{""",
            ).containsMatchIn(antesDeLosOverlays),
        )
        assertTrue(
            "La bandera huboExito se enciende con Success o Queued (tras el botón de un cobro en cola el estado ya es Idle).",
            Regex(
                """LaunchedEffect\(state\) \{\s*if \(state is AngelPayPaymentState\.Success \|\| state is AngelPayPaymentState\.Queued\) huboExito = true\s*}""",
            ).containsMatchIn(antesDeLosOverlays),
        )
        assertTrue(
            "huboExito de la Nexgo sobrevive a recrear la pantalla (Codex r4 #3).",
            antesDeLosOverlays.contains("var huboExito by rememberSaveable { mutableStateOf(false) }"),
        )
        assertTrue(
            "El «atrás» de D36 (antes de cobrar, en Cobrar) se apaga después de un cobro.",
            pantallaNexgo.contains("enabled = atrasDelSistemaComoLaFlecha && !huboExito && ("),
        )
    }
}
