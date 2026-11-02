package com.jaac.avoqado_tpv.features.checkout.domain

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import org.junit.Test

/**
 * «Cobrar» sin red, C2b: por dónde sale la venta al tocar «Cobrar» (D3 del spec). Con red, igual que hoy; sin red, la venta local;
 * un carrito en $0 nunca va a la pantalla de cobro como `Fast("0")`.
 */
class CaminoDelCobroTest {

    @Test fun `con red y productos ⇒ en línea como hoy`() {
        assertThat(caminoDelCobro(hayProductos = true, totalCents = 5000, hayRed = true)).isEqualTo(CaminoDelCobro.EN_LINEA)
        // La cortesía completa con red sigue en línea: el servidor la cierra (CompletedFreeCart, como hoy).
        assertThat(caminoDelCobro(hayProductos = true, totalCents = 0, hayRed = true)).isEqualTo(CaminoDelCobro.EN_LINEA)
    }

    @Test fun `sólo importes con red ⇒ cobro rápido como hoy`() {
        assertThat(caminoDelCobro(hayProductos = false, totalCents = 5000, hayRed = true)).isEqualTo(CaminoDelCobro.RAPIDO)
    }

    @Test fun `sin red ⇒ venta local, con productos o sólo importes`() {
        assertThat(caminoDelCobro(hayProductos = true, totalCents = 5000, hayRed = false)).isEqualTo(CaminoDelCobro.LOCAL)
        assertThat(caminoDelCobro(hayProductos = false, totalCents = 5000, hayRed = false)).isEqualTo(CaminoDelCobro.LOCAL)
        assertThat(caminoDelCobro(hayProductos = true, totalCents = 0, hayRed = false)).isEqualTo(CaminoDelCobro.LOCAL)
    }

    @Test fun `sólo importes en $0 ⇒ venta local también con red (nunca Fast de $0)`() {
        assertThat(caminoDelCobro(hayProductos = false, totalCents = 0, hayRed = true)).isEqualTo(CaminoDelCobro.LOCAL)
        assertThat(caminoDelCobro(hayProductos = false, totalCents = 0, hayRed = false)).isEqualTo(CaminoDelCobro.LOCAL)
    }

    @Test fun `sólo el servidor que no contestó pasa a la venta local`() {
        assertThat(pasaALaVentaLocal(ApiException.HttpError(503, "x"))).isTrue()
        assertThat(pasaALaVentaLocal(ApiException.HttpError(524, "x"))).isTrue()
        assertThat(pasaALaVentaLocal(ApiException.NetworkError(java.io.IOException("socket")))).isTrue()
        // Una respuesta del servidor (4xx, 500) es una respuesta: como hoy.
        assertThat(pasaALaVentaLocal(ApiException.HttpError(422, "x"))).isFalse()
        assertThat(pasaALaVentaLocal(ApiException.HttpError(500, "x"))).isFalse()
        assertThat(pasaALaVentaLocal(Exception("Invalid response from server"))).isFalse()
        // El repositorio ya tipa la red; una IOException pelona no es la regla.
        assertThat(pasaALaVentaLocal(java.io.IOException("pelona"))).isFalse()
    }
}
