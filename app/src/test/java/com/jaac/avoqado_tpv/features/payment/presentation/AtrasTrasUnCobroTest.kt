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
 * La regla: tras un cobro, «atrás» hace lo mismo que el botón principal de la pantalla («Nueva Orden», «Nuevo Pago», «Listo»,
 * «Continuar pagando»…), que ya lleva a un lugar NUEVO. Mientras corre la animación de «Aprobado» (sin botones todavía) el
 * «atrás» se ignora, como el botón «Inicio», que tampoco sale antes (D13). La cámara de la prueba de venta se cierra con
 * «atrás» en vez de salir de la pantalla.
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
                """BackHandler\(enabled = !isKioskPayment\) \{\s*if \(showProofOfSaleCamera\) showProofOfSaleCamera = false else accionPrincipal\(\)\s*}""",
            ).containsMatchIn(exito),
        )
        assertTrue(
            "El botón «Nueva Orden»/«Nuevo Pago» debe usar la MISMA ruta que el «atrás» (successRouting).",
            Regex("""onClick = successRouting\.onClick""").containsMatchIn(exito),
        )
    }

    @Test
    fun `PAX - durante la animacion de Aprobado, atras se ignora`() {
        val rama = cuerpoDe(pantallaPax, "showApprovedAnimation -> {", "PaymentApprovedScreen(")
        assertTrue(
            "Mientras se ve «Aprobado» (sin botones), el «atrás» del sistema no debe sacar la pantalla de cobro.",
            Regex("""BackHandler\(enabled = !isKioskPayment\) \{""").containsMatchIn(rama),
        )
    }

    @Test
    fun `Nexgo - en el exito y en la cola, atras hace lo mismo que Nuevo Pago`() {
        val exito = cuerpoDe(exitoNexgo, "fun AngelPaySuccessContent(", "\n@Composable")
        assertTrue(
            "AngelPaySuccessContent (éxito y cola) debe atender el «atrás»: cerrar la cámara si está abierta y, si no, lo mismo " +
                "que su botón principal (onStartNewPayment).",
            Regex(
                """BackHandler \{\s*if \(showProofOfSaleCamera\) showProofOfSaleCamera = false else onStartNewPayment\(\)\s*}""",
            ).containsMatchIn(exito),
        )
    }

    @Test
    fun `Nexgo - durante la animacion de Aprobado, atras se ignora`() {
        val rama = cuerpoDe(pantallaNexgo, "showApprovedAnimation && !showSuccessContent -> {", "PaymentApprovedScreen(")
        assertTrue(
            "Mientras se ve «Aprobado» en la Nexgo, el «atrás» del sistema no debe sacar la pantalla de cobro.",
            Regex("""BackHandler \{""").containsMatchIn(rama),
        )
    }
}
