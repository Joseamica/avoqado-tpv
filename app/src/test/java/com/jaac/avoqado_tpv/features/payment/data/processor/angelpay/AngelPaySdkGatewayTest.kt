package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.MerchantSummary
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for AngelPaySdkGateway multi-merchant primitives (Task 26 — spec §6.5, §18.1).
 *
 * Covers the two new methods that wrap AngelPay SDK 1.0.5:
 *   - getUserMerchants(): returns the merchant list the authenticated user can switch between
 *   - switchMerchant(): swaps active merchant without re-authentication
 *
 * Both surfaces categorize SDK errors as AngelPayAuthExpiredError / AngelPayNetworkError /
 * pass-through, so Task 30's AuthRepository can drive the retry / re-auth state machine.
 *
 * AngelPaySDK is a Kotlin object — mock via `mockkObject`.
 */
class AngelPaySdkGatewayTest {

    private lateinit var gateway: AngelPaySdkGateway

    @Before
    fun setup() {
        mockkObject(AngelPaySDK)
        gateway = AngelPaySdkGateway()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getUserMerchants returns SDK list on success`() = runTest {
        val sdkList = listOf(
            MerchantSummary(id = 42, name = "Acme Café", affiliationNumber = "1234567", isActive = true),
            MerchantSummary(id = 99, name = "Acme Bar",  affiliationNumber = "7654321", isActive = false),
        )
        coEvery { AngelPaySDK.getUserMerchants() } returns Result.success(sdkList)

        val result = gateway.getUserMerchants()

        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        val merchants = result.getOrThrow()
        assertEquals(2, merchants.size)
        assertEquals(42, merchants[0].id)
        assertEquals("Acme Café", merchants[0].name)
        assertEquals("1234567", merchants[0].affiliationNumber)
        assertEquals(true, merchants[0].isActive)
        assertEquals(false, merchants[1].isActive)
    }

    @Test
    fun `getUserMerchants returns failure when SDK fails`() = runTest {
        val sdkError = IllegalStateException("something unexpected")
        coEvery { AngelPaySDK.getUserMerchants() } returns Result.failure<List<MerchantSummary>>(sdkError)

        val result = gateway.getUserMerchants()

        assertTrue("expected failure", result.isFailure)
        // Generic exception should be passed through (not auth or network).
        val err = result.exceptionOrNull()
        assertEquals(sdkError, err)
    }

    @Test
    fun `switchMerchant SDK success returns Result Unit`() = runTest {
        coEvery { AngelPaySDK.switchMerchant(merchantId = 42) } returns Result.success(Unit)

        val result = gateway.switchMerchant(merchantId = 42)

        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun `switchMerchant SDK auth error returns categorized AuthExpired failure`() = runTest {
        val sdkError = RuntimeException("401 Unauthorized — JWT expired")
        coEvery { AngelPaySDK.switchMerchant(merchantId = 42) } returns Result.failure<Unit>(sdkError)

        val result = gateway.switchMerchant(merchantId = 42)

        assertTrue("expected failure", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            "expected AngelPayAuthExpiredError, got ${err?.javaClass?.simpleName}: ${err?.message}",
            err is AngelPayAuthExpiredError,
        )
        assertEquals(sdkError, err?.cause)
    }

    @Test
    fun `switchMerchant SDK network error returns categorized Network failure`() = runTest {
        val sdkError = IOException("connection timeout while reaching gateway")
        coEvery { AngelPaySDK.switchMerchant(merchantId = 7) } returns Result.failure<Unit>(sdkError)

        val result = gateway.switchMerchant(merchantId = 7)

        assertTrue("expected failure", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            "expected AngelPayNetworkError, got ${err?.javaClass?.simpleName}: ${err?.message}",
            err is AngelPayNetworkError,
        )
        assertEquals(sdkError, err?.cause)
    }

    @Test
    fun `switchMerchant SDK generic error returns generic failure`() = runTest {
        val sdkError = IllegalStateException("merchant not found in account")
        coEvery { AngelPaySDK.switchMerchant(merchantId = 999) } returns Result.failure<Unit>(sdkError)

        val result = gateway.switchMerchant(merchantId = 999)

        assertTrue("expected failure", result.isFailure)
        val err = result.exceptionOrNull()
        // Generic error should be passed through unchanged — Task 30 falls back to surfacing
        // it to the user without any retry logic.
        assertEquals(sdkError, err)
        assertTrue("should not be categorized", err !is AngelPayAuthExpiredError)
        assertTrue("should not be categorized", err !is AngelPayNetworkError)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 💰 EL COBRO NUNCA PUEDE SER MENOR AL TOTAL REGISTRADO
    //
    // Incidente Rest MX / Restbar (2026-08-09/10): 11 ventas cobradas de menos
    // por $1,225.65 — exactamente la propina de cada una.
    //
    // CAUSA: `amountCents` de AngelPay es el TOTAL A COBRAR, y `tipCents` es
    // cuánto de ese total es propina (AngelPay lo RESTA para mostrar el
    // importe). Probado con su recibo:
    //
    //     Pago con tarjeta $330.00 = Importe $280.50 + Propina $49.50
    //                ↑ nuestro amountCents (la venta sin propina)
    //
    // Mandábamos el SUBTOTAL en amountCents, así que AngelPay cobraba la venta
    // sin propina Y encima le restaba la propina al importe.
    //
    // Sólo se veía en comercios tipo RESTAURANTE: los retail (p.ej. un salón)
    // rechazan la propina con C208 y caen al fallback, que ya mandaba el total
    // — por eso ese venue nunca falló.
    //
    // Blumon/PAX siempre mandó el total (`calculateTotal(amount, tip)` →
    // SaleIcc). Esto alinea AngelPay con el resto de la plataforma.
    // ══════════════════════════════════════════════════════════════════════

    private val subtotal = java.math.BigDecimal("330.00")
    private val tip = java.math.BigDecimal("49.50")

    @Test
    fun `buildPaymentRequest cobra el TOTAL, no el subtotal`() {
        val req = gateway.buildPaymentRequest(subtotal, tip, waiter = "Ana", reference = "ref-1")

        // 379.50 en centavos — el cliente paga la venta MÁS la propina.
        assertEquals(37950L, req.amountCents)
    }

    @Test
    fun `la propina NUNCA viaja en tipCents — va sumada dentro del monto`() {
        val req = gateway.buildPaymentRequest(subtotal, tip, waiter = "Ana", reference = "ref-2")

        // 🔴 Para AngelPay `tipCents` NO se suma: se RESTA de `amountCents`. Mandar ahí el
        // desglose real sólo lo ejercitan los comercios tipo restaurante (los retail lo
        // rechazan con C208), o sea que era una ruta imposible de probar que se estrenaba
        // en producción con dinero real. Se manda 0 siempre: un solo camino, el probado.
        assertEquals(0L, req.tipCents)
        // Y el cobro sigue siendo el total completo.
        assertEquals(37950L, req.amountCents)
    }

    @Test
    fun `tipCents es 0 con cualquier propina — barrido`() {
        listOf("0.00", "0.01", "49.50", "175.00", "999.99").forEach { propina ->
            val req = gateway.buildPaymentRequest(
                subtotal, java.math.BigDecimal(propina), "Ana", "ref-sweep",
            )
            assertEquals("propina=$propina", 0L, req.tipCents)
        }
    }

    @Test
    fun `sin propina el cobro es exactamente el subtotal`() {
        val req = gateway.buildPaymentRequest(subtotal, java.math.BigDecimal.ZERO, null, "ref-3")

        assertEquals(33000L, req.amountCents)
        assertEquals(0L, req.tipCents)
    }

    @Test
    fun `el fallback y la ruta normal cobran EXACTAMENTE lo mismo`() {
        // El fallback (comercios que rechazan propina) ya cobraba bien. Las dos
        // rutas deben cobrar idéntico — si divergen, un tipo de comercio vuelve
        // a cobrar de menos.
        val normal = gateway.buildPaymentRequest(subtotal, tip, "Ana", "ref-4")
        val fallback = gateway.buildQaTipFallbackRequest(subtotal, tip, "Ana", "ref-4")

        assertEquals(fallback.amountCents, normal.amountCents)
        // Desde 2026-08-10 son idénticos también en el desglose: la ruta normal ya no
        // manda propina, así que ningún comercio puede tomar un camino distinto al probado.
        assertEquals(fallback.tipCents, normal.tipCents)
    }

    @Test
    fun `el cobro NUNCA es menor al total registrado — barrido de montos`() {
        // El invariante en una línea. Cualquier cambio futuro que vuelva a
        // mandar el subtotal truena aquí.
        val casos = listOf(
            "100.00" to "0.00",
            "330.00" to "49.50",   // el caso real de Restbar
            "838.00" to "83.80",
            "1837.00" to "183.70",
            "725.00" to "175.00",  // propina no-porcentual
            "0.01" to "0.01",      // centavos
        )
        casos.forEach { (s, t) ->
            val sub = java.math.BigDecimal(s)
            val prop = java.math.BigDecimal(t)
            val esperado = sub.add(prop).movePointRight(2).toLong()
            val req = gateway.buildPaymentRequest(sub, prop, "Ana", "ref")
            assertEquals("subtotal=$s propina=$t debe cobrar ${sub.add(prop)}", esperado, req.amountCents)
            assertTrue("el cobro no puede ser menor al total", req.amountCents >= esperado)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
// CONTRATO: se manda EXACTAMENTE LO MISMO sin importar el tipo de comercio.
//
// AngelPay documenta TRES tipos (`MerchantInfo.type`, DevHub / SDK Android):
//     "Venta" (retail) · "Venta con propina" (restaurante) · "Check In" (hotel)
//
// La garantía de que los tres reciben lo mismo NO es un acuerdo ni una prueba
// caso por caso: es estructural. `buildPaymentRequest` recibe (subtotal, tip,
// waiter, reference) — **el tipo de comercio no es un parámetro**, así que la
// función no puede ramificar por él ni aunque quisiera.
//
// Este test fija el request COMPLETO para que ningún cambio futuro meta esa
// dependencia por la puerta de atrás (p.ej. un `if (esRestaurante)` en el
// ViewModel que pase otros valores). Si alguien toca un solo campo, truena aquí.
// ══════════════════════════════════════════════════════════════════════════

class AngelPaySdkGatewayContratoDeCobroTest {

    private val gateway = AngelPaySdkGateway()

    @Test
    fun `el request es identico campo por campo — el tipo de comercio no lo altera`() {
        val req = gateway.buildPaymentRequest(
            subtotal = java.math.BigDecimal("330.00"),
            tip = java.math.BigDecimal("49.50"),
            waiter = "Ana",
            reference = "attempt-123",
        )

        // 💰 Lo que se le cobra al cliente: SIEMPRE el total.
        assertEquals(37_950L, req.amountCents)
        // 🔴 La propina va dentro del monto, nunca como campo aparte.
        assertEquals(0L, req.tipCents)

        // El resto del contrato, fijado para que nadie lo mueva sin darse cuenta.
        assertEquals("Ana", req.waiter)
        assertEquals("attempt-123", req.reference)
        assertEquals("attempt-123", req.integratorReference)
        assertEquals(null, req.msi)
        assertEquals(false, req.isCheckIn)   // nunca pre-autorización de hotel
        assertEquals(null, req.checkInId)
        assertTrue(req.allowSwipe && req.allowChip && req.allowContactless)
        assertEquals(0.0, req.latitude, 0.0)
        assertEquals(0.0, req.longitude, 0.0)
        assertEquals(3000L, req.approvedResultDisplayMillis)
        assertEquals(5000L, req.errorResultDisplayMillis)
    }

    @Test
    fun `los dos caminos posibles producen requests equivalentes`() {
        // Sólo existen dos rutas hacia el SDK: la normal y el fallback de C208.
        // Si producen lo mismo, ningún comercio —de ningún tipo— puede recibir
        // algo distinto, porque no hay una tercera ruta que tomar.
        val subtotal = java.math.BigDecimal("838.00")
        val tip = java.math.BigDecimal("83.80")

        val normal = gateway.buildPaymentRequest(subtotal, tip, "Ana", "ref-x")
        val fallback = gateway.buildQaTipFallbackRequest(subtotal, tip, "Ana", "ref-x")

        assertEquals(normal.amountCents, fallback.amountCents)
        assertEquals(normal.tipCents, fallback.tipCents)
        assertEquals(normal.waiter, fallback.waiter)
        assertEquals(normal.reference, fallback.reference)
        assertEquals(normal.integratorReference, fallback.integratorReference)
        assertEquals(normal.isCheckIn, fallback.isCheckIn)
        assertEquals(normal.msi, fallback.msi)
    }
}
