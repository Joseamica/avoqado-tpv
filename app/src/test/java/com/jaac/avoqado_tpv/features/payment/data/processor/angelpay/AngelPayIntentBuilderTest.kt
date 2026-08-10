package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Guardián del INVARIANTE DE DINERO en el camino app-to-app de AngelPay.
 *
 * AngelPay tiene DOS rutas de cobro y ambas tienen que mandar lo mismo:
 *   - SDK embebido       → `AngelPaySdkGateway.buildPaymentRequest` (cubierto por AngelPaySdkGatewayTest)
 *   - Intent app-to-app  → `AngelPayIntentBuilder.buildSaleTransactionJson` (cubierto AQUÍ)
 *
 * El bug del 2026-08-09/10 (11 ventas de Rest MX cobradas de menos por $1,225.65) vivió en la
 * ruta del SDK, y esta ruta se salvó sólo porque ya sumaba la propina — pero no tenía NINGÚN
 * test y su comentario invitaba explícitamente a "revertir" esa suma. Estos tests existen para
 * que ese cambio truene en CI en vez de en la caja del restaurante.
 *
 * La regla: **el cliente NUNCA paga menos de lo que aceptó** — lo que viaja al procesador es
 * el TOTAL (venta + propina); Avoqado guarda el desglose de su lado.
 */
class AngelPayIntentBuilderTest {

    private lateinit var builder: AngelPayIntentBuilder

    /** Lee el `subtotal` (en centavos) que realmente viaja al procesador. */
    private fun centavosEnviados(subtotal: BigDecimal, propina: BigDecimal): Long =
        JSONObject(builder.buildSaleTransactionJson(amount = subtotal, tip = propina))
            .getLong("subtotal")

    @Before
    fun setup() {
        builder = AngelPayIntentBuilder()
    }

    @Test
    fun `el monto enviado incluye la propina`() {
        // El caso real de Rest MX: venta $330.00 + propina $49.50 = $379.50 a cobrar.
        assertEquals(37_950L, centavosEnviados(BigDecimal("330.00"), BigDecimal("49.50")))
    }

    @Test
    fun `sin propina el monto enviado es la venta`() {
        assertEquals(10_000L, centavosEnviados(BigDecimal("100.00"), BigDecimal.ZERO))
    }

    @Test
    fun `el cobro NUNCA es menor al total registrado — barrido de montos`() {
        val casos = listOf(
            "100.00" to "0.00",
            "330.00" to "49.50",
            "838.00" to "83.80",
            "1837.00" to "183.70",
            "725.00" to "175.00",
            "0.01" to "0.01",
        )

        casos.forEach { (venta, propina) ->
            val subtotal = BigDecimal(venta)
            val tip = BigDecimal(propina)
            val esperado = subtotal.add(tip).movePointRight(2).toLong()

            assertEquals(
                "venta=$venta propina=$propina",
                esperado,
                centavosEnviados(subtotal, tip),
            )
        }
    }

    @Test
    fun `la propina no se manda por separado — va sumada dentro del subtotal`() {
        // Si algún día se agrega un campo `tip` aquí, el procesador lo RESTARÍA del subtotal
        // (es un desglose, no un extra) y el cliente volvería a pagar de menos.
        val payload = JSONObject(
            builder.buildSaleTransactionJson(
                amount = BigDecimal("330.00"),
                tip = BigDecimal("49.50"),
            )
        )

        assertFalse(
            "la propina no debe viajar como campo aparte en el camino app-to-app",
            payload.has("tip"),
        )
        assertEquals(37_950L, payload.getLong("subtotal"))
    }

    @Test
    fun `el camino app-to-app y el del SDK cobran EXACTAMENTE lo mismo`() {
        // Las dos rutas de AngelPay tienen que coincidir al centavo: si divergen, el monto
        // cobrado dependería de qué ruta tomó la terminal ese día.
        val subtotal = BigDecimal("838.00")
        val propina = BigDecimal("83.80")

        val appToApp = centavosEnviados(subtotal, propina)
        val sdk = AngelPaySdkGateway().buildPaymentRequest(
            subtotal = subtotal,
            tip = propina,
            waiter = null,
            reference = "ref-test",
        ).amountCents

        assertEquals(sdk, appToApp)
    }
}
