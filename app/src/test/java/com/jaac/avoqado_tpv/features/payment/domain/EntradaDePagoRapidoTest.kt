package com.jaac.avoqado_tpv.features.payment.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 💵 D7 (19-nov-2026): cómo entra «Pago rápido» al cobro en la PAX. El efectivo nunca espera al lector de Blumon por
 * falta de red; la tarjeta sí. Codex r1 #1: justo al arrancar la terminal cree unos segundos que hay red (valor de
 * fábrica de `ConnectionState`), y con WiFi sin salida la regla de la tarjeta puede seguir viendo «internet»: ahí la
 * espera del lector falla y antes se trababa todo Pago rápido.
 */
class EntradaDePagoRapidoTest {

    private var esperas = 0

    private suspend fun decidir(salta: Boolean, lectorListo: Boolean, conexionCompleta: Boolean) =
        decidirEntradaDePagoRapido(
            saltaElLector = { salta },
            esperarAlLector = { esperas++; lectorListo },
            conexionCompleta = { conexionCompleta },
        )

    @Test
    fun `sin red y con el lector sin preparar entra en solo efectivo sin esperar al lector`() = runTest {
        assertThat(decidir(salta = true, lectorListo = false, conexionCompleta = false))
            .isEqualTo(EntradaDePagoRapido.SOLO_EFECTIVO)
        assertThat(esperas).isEqualTo(0)
    }

    @Test
    fun `con el lector listo tras esperar entra normal con tarjeta`() = runTest {
        assertThat(decidir(salta = false, lectorListo = true, conexionCompleta = true))
            .isEqualTo(EntradaDePagoRapido.NORMAL)
        assertThat(esperas).isEqualTo(1)
    }

    @Test
    fun `Codex r1 1 si la espera falla sin conexion completa entra en solo efectivo en vez de trabarse`() = runTest {
        assertThat(decidir(salta = false, lectorListo = false, conexionCompleta = false))
            .isEqualTo(EntradaDePagoRapido.SOLO_EFECTIVO)
    }

    @Test
    fun `si la espera falla con la conexion completa el problema es el lector y se avisa como antes`() = runTest {
        assertThat(decidir(salta = false, lectorListo = false, conexionCompleta = true))
            .isEqualTo(EntradaDePagoRapido.BLOQUEADA)
    }
}
