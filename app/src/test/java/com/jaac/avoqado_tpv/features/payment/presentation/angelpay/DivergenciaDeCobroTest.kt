package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * La última red: compara lo que AngelPay REALMENTE cobró contra lo que Avoqado registró.
 *
 * Todo lo demás en la app verifica lo que *mandamos* — y eso siempre depende de suponer cómo
 * se comporta el procesador. Esto verifica lo que *pasó*, así que no le importa el tipo de
 * comercio, cómo interprete `tipCents`, ni si mañana cambia el SDK.
 *
 * Existe por Rest MX (2026-08-09/10): 11 ventas cobradas de menos por $1,225.65 que nadie
 * detectó en días, porque el cobro se aprobaba y la pantalla decía éxito. El daño no fue el
 * bug — fue el silencio.
 */
class DivergenciaDeCobroTest {

    @Test
    fun `no alarma cuando el cobro cuadra con venta mas propina`() {
        assertNull(
            describirDivergenciaDeCobro(
                cobradoCents = 37_950L,
                venta = BigDecimal("330.00"),
                propina = BigDecimal("49.50"),
            )
        )
    }

    @Test
    fun `no alarma cuando no hay propina y el cobro es la venta`() {
        assertNull(
            describirDivergenciaDeCobro(
                cobradoCents = 10_000L,
                venta = BigDecimal("100.00"),
                propina = BigDecimal.ZERO,
            )
        )
    }

    @Test
    fun `alarma cuando se cobra DE MENOS — el caso exacto de Rest MX`() {
        // Lo que de verdad pasó: se cobró la venta sin la propina.
        val alarma = describirDivergenciaDeCobro(
            cobradoCents = 33_000L,
            venta = BigDecimal("330.00"),
            propina = BigDecimal("49.50"),
        )

        assertNotNull("una venta cobrada de menos DEBE alarmar", alarma)
        assertTrue(alarma!!.contains("DE MENOS"))
        assertTrue("debe decir cuánto faltó", alarma.contains("-49.50"))
    }

    @Test
    fun `alarma cuando se cobra DE MAS`() {
        // P.ej. un comercio tipo restaurante que además pidiera propina en su propia pantalla.
        val alarma = describirDivergenciaDeCobro(
            cobradoCents = 40_000L,
            venta = BigDecimal("330.00"),
            propina = BigDecimal("49.50"),
        )

        assertNotNull("cobrar de más también es un descuadre", alarma)
        assertTrue(alarma!!.contains("DE MÁS"))
    }

    @Test
    fun `no alarma cuando el SDK no reporta monto`() {
        // `amount = 0` significa "no vino en esta respuesta", no "se cobró cero".
        // Alarmar aquí sería gritar en cada cobro que no traiga el dato.
        assertNull(
            describirDivergenciaDeCobro(
                cobradoCents = 0L,
                venta = BigDecimal("330.00"),
                propina = BigDecimal("49.50"),
            )
        )
    }

    @Test
    fun `detecta hasta un centavo de diferencia`() {
        val alarma = describirDivergenciaDeCobro(
            cobradoCents = 37_949L,
            venta = BigDecimal("330.00"),
            propina = BigDecimal("49.50"),
        )

        assertNotNull("un centavo también es dinero del cliente", alarma)
        assertTrue(alarma!!.contains("DE MENOS"))
    }

    @Test
    fun `no alarma con montos equivalentes escritos distinto`() {
        // "330" y "330.00" son el mismo dinero — la comparación es por valor, no por texto.
        assertNull(
            describirDivergenciaDeCobro(
                cobradoCents = 37_950L,
                venta = BigDecimal("330"),
                propina = BigDecimal("49.5"),
            )
        )
    }
}
