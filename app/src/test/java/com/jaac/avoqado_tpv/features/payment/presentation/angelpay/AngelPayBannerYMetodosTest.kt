package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AuthErrorKind
import org.junit.Test

/**
 * T26 (Testarudo, 2026-09-11): el banner decía «AngelPay: AngelPay credentials missing from
 * both backend confi…» (inglés, truncado, sin botón) con la red caída; y mientras AngelPay
 * autenticaba en fondo, el botón de Efectivo quedaba apagado aunque el efectivo no depende
 * de AngelPay.
 */
class AngelPayBannerYMetodosTest {

    // El banner antepone "AngelPay: " y trunca a 60 caracteres.
    private fun cabe(texto: String) = assertThat(texto.length).isAtMost(60)

    @Test
    fun `sin red el banner lo dice en espanol y que se reintenta sola`() {
        val texto = textoDeAuthError(
            AngelPayAuthState.AuthError("AngelPay credentials missing from both backend config and BuildConfig", AuthErrorKind.SIN_RED),
        )
        assertThat(texto.lowercase()).contains("sin conexión")
        assertThat(texto).doesNotContain("credentials")
        cabe(texto)
    }

    @Test
    fun `sin credenciales y cuenta ausente tienen textos propios en espanol`() {
        val sinCreds = textoDeAuthError(AngelPayAuthState.AuthError("x", AuthErrorKind.SIN_CREDENCIALES))
        val sinCuenta = textoDeAuthError(AngelPayAuthState.AuthError("x", AuthErrorKind.CUENTA_NO_EN_CONFIG))
        assertThat(sinCreds.lowercase()).contains("credenciales")
        assertThat(sinCuenta.lowercase()).contains("cuenta")
        assertThat(sinCreds).isNotEqualTo(sinCuenta)
        cabe(sinCreds)
        cabe(sinCuenta)
    }

    @Test
    fun `un error cualquiera conserva su mensaje truncado a 60`() {
        val largo = "x".repeat(200)
        val texto = textoDeAuthError(AngelPayAuthState.AuthError(largo))
        assertThat(texto).startsWith("xxxx")
        cabe(texto)
    }

    @Test
    fun `el banner ofrece Reintentar en los errores recuperables y sin autenticar`() {
        assertThat(ofreceReintentarAuth(AngelPayAuthState.AuthError("x", AuthErrorKind.SIN_RED))).isTrue()
        assertThat(ofreceReintentarAuth(AngelPayAuthState.AuthError("x", AuthErrorKind.SIN_CREDENCIALES))).isTrue()
        assertThat(ofreceReintentarAuth(AngelPayAuthState.AuthError("x", AuthErrorKind.CUENTA_NO_EN_CONFIG))).isTrue()
        assertThat(ofreceReintentarAuth(AngelPayAuthState.Unauthenticated)).isTrue()
    }

    @Test
    fun `el banner NO ofrece Reintentar donde no serviria`() {
        // PIN rechazado o bloqueo de config: repetir no arregla nada (el cobro re-autentica igual).
        assertThat(ofreceReintentarAuth(AngelPayAuthState.AuthError("PIN invalido"))).isFalse()
        assertThat(ofreceReintentarAuth(AngelPayAuthState.Authenticating)).isFalse()
        assertThat(ofreceReintentarAuth(AngelPayAuthState.Authenticated)).isFalse()
    }

    @Test
    fun `P1 la recuperacion de fondo apaga Tarjeta pero NO Efectivo`() {
        val bloqueo = bloqueoDeMetodosDePago(
            selectionInProgress = false,
            inFlightSwitch = null,
            authState = AngelPayAuthState.Recuperando,
        )
        assertThat(bloqueo.tarjeta).isTrue()
        assertThat(bloqueo.efectivo).isFalse()
    }

    @Test
    fun `la auth de la pantalla (Authenticating) sigue apagando los dos, como antes`() {
        // selectMerchant → switchAccount pasa por Authenticating: su fallo escribe el estado de
        // la pantalla y taparía un cobro en efectivo ya registrado (se conserva el 2026-05-19).
        assertThat(bloqueoDeMetodosDePago(false, null, AngelPayAuthState.Authenticating))
            .isEqualTo(BloqueoMetodosDePago(tarjeta = true, efectivo = true))
    }

    @Test
    fun `el banner dice reconectando durante la recuperacion y no ofrece Reintentar`() {
        assertThat(ofreceReintentarAuth(AngelPayAuthState.Recuperando)).isFalse()
    }

    @Test
    fun `un cambio de comercio que dispara el cajero sigue apagando los dos`() {
        // selectMerchant escribe el estado de la pantalla al fallar: un cobro en efectivo encima
        // quedaría tapado por «No se pudo cambiar de merchant».
        val porSeleccion = bloqueoDeMetodosDePago(true, null, AngelPayAuthState.Authenticated)
        val porCambio = bloqueoDeMetodosDePago(false, 11, AngelPayAuthState.Authenticated)
        assertThat(porSeleccion).isEqualTo(BloqueoMetodosDePago(tarjeta = true, efectivo = true))
        assertThat(porCambio).isEqualTo(BloqueoMetodosDePago(tarjeta = true, efectivo = true))
    }

    @Test
    fun `sin nada en vuelo los dos quedan libres`() {
        assertThat(bloqueoDeMetodosDePago(false, null, AngelPayAuthState.Authenticated))
            .isEqualTo(BloqueoMetodosDePago(tarjeta = false, efectivo = false))
    }
}
