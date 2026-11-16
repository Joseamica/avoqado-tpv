package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * D10 (Codex r1), ESTÁTICA como `FolioEnLasPantallasDeCobroTest`: el «← Volver» de la verificación previa de la PAX hace lo
 * mismo que la flecha y el botón físico (`flechaAtras` → `goBackOneStep()`). Si llamara directo a
 * `goBackFromPrePaymentVerification()`, en un cobro del POS regresaría a la calificación y, al seguir, la propina del POS
 * saldría en $0.
 */
class VolverDeLaVerificacionPreviaTest {

    private val pax =
        File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentScreen.kt").readText()

    @Test
    fun `D10 el Volver de la verificacion previa usa la flecha de atras`() {
        val inicio = pax.indexOf("is PaymentState.VerifyingPrePayment ->")
        assertWithMessage("rama de la verificación previa").that(inicio).isAtLeast(0)
        val fin = pax.indexOf("is PaymentState.SelectingMerchant ->", inicio)
        assertWithMessage("fin de la rama").that(fin).isGreaterThan(inicio)
        val rama = pax.substring(inicio, fin)
        assertWithMessage("«← Volver» usa la flecha").that(rama).contains("onNavigateBack = flechaAtras")
        assertWithMessage("no salta la regla del cobro del POS")
            .that(rama).doesNotContain("viewModel.goBackFromPrePaymentVerification()")
    }
}
