package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Hardware-free unit tests for the Blumon decline discriminator used by the DECLINE GUARD in
 * `performOnlineAuthorization`. An issuer decline arrives as a non-null SaleIcc response with a
 * BLANK authorization; a genuine approval always carries an authorization code (verified in prod:
 * 2850/2850 real Blumon approvals). These pin that behavior without needing a terminal.
 */
class BlumonDeclineDetectionTest {

    @Test
    fun `blank authorization yields decline message including the issuer reason`() {
        val msg = blumonDeclineMessage(
            authorization = "",
            description = "PAGO NO PERMITIDO EMISOR",
            isRefund = false,
        )
        assertThat(msg).isNotNull()
        assertThat(msg).contains("Pago rechazado")
        assertThat(msg).contains("PAGO NO PERMITIDO EMISOR")
    }

    @Test
    fun `null authorization is treated as a decline`() {
        assertThat(blumonDeclineMessage(null, "FONDOS INSUFICIENTES", isRefund = false))
            .contains("FONDOS INSUFICIENTES")
    }

    @Test
    fun `whitespace-only authorization is treated as a decline`() {
        assertThat(blumonDeclineMessage("   ", "TARJETA INVALIDA", isRefund = false))
            .contains("TARJETA INVALIDA")
    }

    @Test
    fun `non-blank authorization returns null so an approval proceeds untouched`() {
        assertThat(blumonDeclineMessage("766817", "APROBADA", isRefund = false)).isNull()
    }

    @Test
    fun `blank authorization with no description falls back to a generic reason`() {
        assertThat(blumonDeclineMessage("", null, isRefund = false))
            .contains("El banco no autorizó la transacción")
    }

    @Test
    fun `refund mode uses the refund prefix`() {
        assertThat(blumonDeclineMessage("", "X", isRefund = true)).contains("Reembolso rechazado")
    }
}

/**
 * El rechazo del banco en la PAX llega como `Sale*Failure$MomentumFailure`, cuyo `toString()` es el
 * hash de identidad (`…MomentumFailure@75c4246`): el código del emisor vive en
 * `momentumFailure.code`. Medido en la PAX 2841548417 el 24-sep con una tarjeta real: Blumon contestó
 * `{"httpStatusCode":409,"code":"57","description":"PAGO NO PERMITIDO EMISOR"}` y la terminal lo trató
 * como resultado desconocido y quedó apartada. Estas clases imitan la forma del SDK de producción.
 */
class BlumonIssuerCodeTest {
    // Como en el SDK: data class (su toString trae `code=57`); el contenedor NO sobreescribe toString.
    private data class MomentumDataFailure(val httpCode: Int, val code: String?, val description: String?)
    private class MomentumFailure(val momentumFailure: MomentumDataFailure?)
    private class GenericFailure(val value: String) { override fun toString() = value }
    private class LegacyStringFailure(private val s: String) { override fun toString() = s }

    private fun momentum(code: String?) = MomentumFailure(MomentumDataFailure(409, code, "MOTIVO"))

    @Test fun `el rechazo 57 que llega dentro del objeto del SDK es definitivo`() {
        val f = momentum("57")
        assertThat(blumonIssuerCode(f)).isEqualTo("57")
        assertThat(blumonRechazoDefinitivo(f, isContactless = true)).isTrue()
        assertThat(blumonRechazoDefinitivo(f, isContactless = false)).isTrue()
    }

    @Test fun `fondos insuficientes 51 dentro del objeto tambien es definitivo`() {
        assertThat(blumonRechazoDefinitivo(momentum("51"), isContactless = false)).isTrue()
    }

    // PAX 2841548417, 3-nov: tarjeta que Blumon no reconoce. `MomentumDataFailure(httpCode=200, code=56, TARJETA INVALIDA)`.
    // ISO 8583: «no card record», el emisor la rechaza. Sin esto, la venta quedaba en «Estamos confirmando el cobro».
    @Test fun `tarjeta sin registro 56 es un rechazo definitivo, con chip y sin contacto`() {
        val f = momentum("56")
        assertThat(blumonIssuerCode(f)).isEqualTo("56")
        assertThat(blumonRechazoDefinitivo(f, isContactless = true)).isTrue()
        assertThat(blumonRechazoDefinitivo(f, isContactless = false)).isTrue()
    }

    @Test fun `un codigo que no es rechazo explicito del emisor sigue en duda`() {
        assertThat(blumonIssuerCode(momentum("91"))).isEqualTo("91")
        assertThat(blumonRechazoDefinitivo(momentum("91"), isContactless = false)).isFalse()
    }

    // Founder, 3-nov: «ningún rechazo de Blumon directo debería de trabar la terminal». Si Momentum contestó que no, es un
    // rechazo normal; la duda queda para cuando no contestó o dijo que no pudo saberlo.
    @Test fun `Blumon contesto que no, sin codigo ni en la lista, es un rechazo`() {
        val noAutorizado = MomentumFailure(MomentumDataFailure(409, null, "NO AUTORIZADO"))
        assertThat(blumonIssuerCode(noAutorizado)).isNull()
        assertThat(blumonRechazoDefinitivo(noAutorizado, isContactless = false)).isTrue()
        assertThat(blumonRechazoDefinitivo(MomentumFailure(MomentumDataFailure(200, "12", "TRANSACCION INVALIDA")), isContactless = true)).isTrue()
        assertThat(blumonRechazoDefinitivo(MomentumFailure(MomentumDataFailure(400, "TX_010", "TARJETA INVALIDA")), isContactless = false)).isTrue()
    }

    @Test fun `si Blumon no pudo saberlo sigue en duda`() {
        for (codigo in listOf("06", "09", "19", "22", "68", "90", "91", "92", "96")) {
            assertThat(blumonRechazoDefinitivo(MomentumFailure(MomentumDataFailure(200, codigo, "SISTEMA")), isContactless = false)).isFalse()
        }
    }

    // 🔴 Un código de aprobado nunca se lee como rechazo, aunque venga dentro de un fallo.
    @Test fun `un codigo de aprobado dentro de un fallo sigue en duda`() {
        for (codigo in listOf("00", "08", "10", "11", "16")) {
            assertThat(blumonRechazoDefinitivo(MomentumFailure(MomentumDataFailure(200, codigo, "APROBADA")), isContactless = false)).isFalse()
        }
    }

    @Test fun `sin respuesta clara de Blumon sigue en duda`() {
        assertThat(blumonRechazoDefinitivo(MomentumFailure(MomentumDataFailure(500, "XX", "ERROR")), isContactless = false)).isFalse()
        assertThat(blumonRechazoDefinitivo(MomentumFailure(MomentumDataFailure(503, null, "ERROR")), isContactless = false)).isFalse()
        assertThat(blumonRechazoDefinitivo(MomentumFailure(MomentumDataFailure(408, "XX", "TIEMPO")), isContactless = false)).isFalse()
        assertThat(blumonRechazoDefinitivo(MomentumFailure(MomentumDataFailure(409, null, null)), isContactless = false)).isFalse()
        assertThat(blumonRechazoDefinitivo(MomentumFailure(MomentumDataFailure(409, null, "  ")), isContactless = false)).isFalse()
        assertThat(blumonRechazoDefinitivo(MomentumFailure(null), isContactless = false)).isFalse()
    }

    @Test fun `un fallo que no es respuesta de Momentum sigue en duda`() {
        class TimeoutFailure { override fun toString() = "MomentumDataFailure(httpCode=409, code=XX, description=NO)" }
        assertThat(blumonRechazoDefinitivo(TimeoutFailure(), isContactless = false)).isFalse()
    }

    @Test fun `sin codigo el emisor no se inventa`() {
        assertThat(blumonIssuerCode(momentum(null))).isNull()
        assertThat(blumonIssuerCode(MomentumFailure(null))).isNull()
    }

    @Test fun `1A sin contacto es definitivo, con chip no`() {
        assertThat(blumonRechazoDefinitivo(momentum("1A"), isContactless = true)).isTrue()
        assertThat(blumonRechazoDefinitivo(momentum("1A"), isContactless = false)).isFalse()
    }

    @Test fun `GenericFailure nunca resuelve aunque el texto traiga un codigo`() {
        assertThat(blumonRechazoDefinitivo(GenericFailure("codeResponse=57"), isContactless = false)).isFalse()
    }

    @Test fun `el formato viejo en texto se sigue reconociendo`() {
        assertThat(blumonIssuerCode(LegacyStringFailure("Failure(codeResponse=05)"))).isEqualTo("05")
        assertThat(blumonRechazoDefinitivo(LegacyStringFailure("{\"codeResponse\":\"05\"}"), isContactless = false)).isTrue()
    }

    // Hasta el 3-nov esto quedaba en duda. Blumon lo rechazó de frente: es la regla «si contestó que no, es un rechazo».
    @Test fun `NO AUTORIZADO con code 000000 no es codigo del emisor, pero Blumon si lo rechazo`() {
        val f = MomentumFailure(MomentumDataFailure(403, "000000", "NO AUTORIZADO"))
        assertThat(blumonIssuerCode(f)).isNull()
        assertThat(blumonRechazoDefinitivo(f, isContactless = true)).isTrue()
    }

    @Test fun `lo que contesto Blumon se guarda corto y sin el cuerpo del banco`() {
        val guardado = blumonMotivoSinVeredicto(momentum(null), "NO AUTORIZADO")
        assertThat(guardado).isEqualTo("Blumon sin veredicto: MomentumFailure · NO AUTORIZADO")
        assertThat(blumonRespuestaGuardada(guardado)).isEqualTo("NO AUTORIZADO")

        val largo = blumonMotivoSinVeredicto(momentum(null), "{\"a\":\"" + "X".repeat(200) + "\"}")
        assertThat(largo).doesNotContain("{")
        assertThat(largo).doesNotContain("\"")
        assertThat(blumonRespuestaGuardada(largo)!!.length).isAtMost(60)
    }

    @Test fun `sin descripcion se guarda la clase y no hay respuesta que mostrar`() {
        val guardado = blumonMotivoSinVeredicto(momentum(null), null)
        assertThat(guardado).isEqualTo("Blumon sin veredicto: MomentumFailure")
        assertThat(blumonRespuestaGuardada(guardado)).isNull()
        assertThat(blumonRespuestaGuardada("declarado_sin_cobro:Cajero · NO AUTORIZADO")).isNull()
        assertThat(blumonRespuestaGuardada(null)).isNull()
    }
}

/**
 * ¿Blumon RECHAZÓ el reembolso? Medido el 25-sep en la PAX de pruebas: se acercó otra tarjeta y Blumon contestó
 * `{"status":false,"error":{"httpStatusCode":400,"code":"TX_010","description":"TARJETA INVALIDA"}}`; el SDK lo entrega
 * como `CancelIccFailure$MomentumFailure(momentumFailure = MomentumDataFailureCancel(httpCode=400, code=TX_010, …))`.
 * Un 4xx es un «no» de Blumon: nada se devolvió y la terminal debe seguir cobrando. La red o un 5xx no dicen nada.
 */
class BlumonReembolsoRechazadoTest {
    private data class MomentumDataFailureCancel(val httpCode: Int?, val code: String?, val description: String?)
    private class MomentumFailure(val momentumFailure: MomentumDataFailureCancel?)
    private class NetworkConnectionFailure { override fun toString() = "NetworkConnectionFailure" }
    private class GenericFailure(val value: String) { override fun toString() = value }

    private fun blumon(http: Int?, code: String? = "TX_010") =
        MomentumFailure(MomentumDataFailureCancel(http, code, "TARJETA INVALIDA"))

    @Test fun `TX_010 tarjeta invalida con 400 es un rechazo definitivo`() {
        assertThat(blumonReembolsoRechazado(blumon(400))).isTrue()
    }

    @Test fun `un rechazo del emisor 409 tambien es definitivo`() {
        assertThat(blumonReembolsoRechazado(blumon(409, "57"))).isTrue()
    }

    @Test fun `un 5xx o un 408 de Blumon no dicen si se devolvio - siguen en duda`() {
        assertThat(blumonReembolsoRechazado(blumon(500))).isFalse()
        assertThat(blumonReembolsoRechazado(blumon(503))).isFalse()
        assertThat(blumonReembolsoRechazado(blumon(408))).isFalse()
    }

    @Test fun `sin codigo HTTP no hay veredicto`() {
        assertThat(blumonReembolsoRechazado(blumon(null))).isFalse()
        assertThat(blumonReembolsoRechazado(MomentumFailure(null))).isFalse()
    }

    @Test fun `la red y los fallos genericos nunca son un rechazo aunque el texto traiga un 400`() {
        assertThat(blumonReembolsoRechazado(NetworkConnectionFailure())).isFalse()
        assertThat(blumonReembolsoRechazado(GenericFailure("httpCode=400"))).isFalse()
    }
}
