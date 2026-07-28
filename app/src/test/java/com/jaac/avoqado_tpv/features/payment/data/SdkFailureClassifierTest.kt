package com.jaac.avoqado_tpv.features.payment.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests de [SdkFailureClassifier].
 *
 * Los casos negativos son los importantes: un falso positivo dispara una reinicialización
 * innecesaria, y reinicializar de más nos lleva al rate limit de Blumon — que rompe los
 * cobros de verdad. Todos los cuerpos de la sección 2 son fallos REALES observados en
 * Doña Simona el 2026-07-26 con la terminal sana.
 */
class SdkFailureClassifierTest {

    // ─────────────────────────────────────────────────────────
    // 1. SÍ es estado del SDK → debe recuperar
    // ─────────────────────────────────────────────────────────

    @Test
    fun `detecta token vencido`() {
        assertThat(
            SdkFailureClassifier.isSdkStateFailure(
                """{"error":"invalid_token","error_description":"The access token expired"}"""
            )
        ).isTrue()
    }

    @Test
    fun `detecta NA_002`() {
        assertThat(SdkFailureClassifier.isSdkStateFailure("NA_002 - Invalid serial number")).isTrue()
    }

    @Test
    fun `detecta NO AUTORIZADO por posId viejo`() {
        assertThat(SdkFailureClassifier.isSdkStateFailure("NO AUTORIZADO")).isTrue()
    }

    @Test
    fun `es indiferente a mayusculas y minusculas`() {
        assertThat(SdkFailureClassifier.isSdkStateFailure("Invalid_Token")).isTrue()
        assertThat(SdkFailureClassifier.isSdkStateFailure("no autorizado")).isTrue()
    }

    // ─────────────────────────────────────────────────────────
    // 2. REGRESIÓN — declinaciones normales, NUNCA deben recuperar
    // ─────────────────────────────────────────────────────────

    @Test
    fun `REGRESION fondos insuficientes no dispara recuperacion`() {
        // Real, Doña Simona 15:02 del 26-jul. Llegó envuelto en MomentumFailure, igual que
        // el token vencido — por eso no se puede clasificar por el tipo de failure.
        assertThat(
            SdkFailureClassifier.isSdkStateFailure(
                "Pago rechazado:\n\nFONDOS INSUFICIENTES\n\nPor favor, solicita otra forma de pago."
            )
        ).isFalse()
    }

    @Test
    fun `REGRESION tarjeta retirada no dispara recuperacion`() {
        // Real, Doña Simona 16:20 del 26-jul.
        assertThat(SdkFailureClassifier.isSdkStateFailure("StartEmvTransFailure\$WithdrawnCardFailure")).isFalse()
    }

    @Test
    fun `REGRESION contactless denegado no dispara recuperacion`() {
        // Real, Doña Simona 16:20-16:21 del 26-jul, tres veces seguidas.
        assertThat(SdkFailureClassifier.isSdkStateFailure("StartCtlssTransFailure\$CtlssDeniedFailure")).isFalse()
    }

    @Test
    fun `REGRESION operacion cancelada no dispara recuperacion`() {
        assertThat(SdkFailureClassifier.isSdkStateFailure("StartEmvTransFailure\$CancelOperationFailure")).isFalse()
    }

    @Test
    fun `REGRESION el toString del objeto no alcanza para clasificar`() {
        // Esto es lo que hoy se loguea, y a propósito NO clasifica: no trae el cuerpo.
        // Si algún día alguien pasa esto en vez de la descripción real, preferimos NO
        // recuperar antes que recuperar por una razón equivocada.
        assertThat(SdkFailureClassifier.isSdkStateFailure("SaleCtlsFailure\$MomentumFailure@75c4246")).isFalse()
    }

    @Test
    fun `REGRESION nulo o vacio nunca dispara recuperacion`() {
        assertThat(SdkFailureClassifier.isSdkStateFailure(null)).isFalse()
        assertThat(SdkFailureClassifier.isSdkStateFailure("")).isFalse()
        assertThat(SdkFailureClassifier.isSdkStateFailure("   ")).isFalse()
    }
}
