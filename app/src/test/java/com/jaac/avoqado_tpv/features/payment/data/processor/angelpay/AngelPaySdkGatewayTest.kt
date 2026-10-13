package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.MerchantSummary
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
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
        assertEquals(normal.captureSignature, fallback.captureSignature)
    }

    /**
     * T14 (founder, 29-sep, APK 2.12.0): el panel de FIRMA del SDK va ENCENDIDO. Estuvo apagado desde el 18-sep hasta
     * saber si servía: AngelPay contestó (Norman, 29-sep) que la firma capturada SÍ es evidencia en contracargos, que la
     * piden menos del 10 % de los cobros (Amex nuevas sin NIP, algunas tarjetas de vales, extranjeras) y recomienda dejarla
     * encendida — es el default del SDK. La auditoría del 1.0.21 confirmó que el panel sale SÓLO después de que el banco
     * aprobó, con tope de 60 s, y que no puede cambiar el cobro. Van las DOS rutas: si una lo olvida, un tipo de comercio
     * se queda sin firma.
     */
    @Test
    fun `P1 T14 el panel de firma del SDK va encendido en la ruta normal y en el fallback`() {
        val subtotal = java.math.BigDecimal("100.00")
        val tip = java.math.BigDecimal("10.00")
        val normal = gateway.buildPaymentRequest(subtotal, tip, "Ana", "ref-firma")
        val fallback = gateway.buildQaTipFallbackRequest(subtotal, tip, "Ana", "ref-firma")

        assertEquals(true, normal.captureSignature)
        assertEquals(true, fallback.captureSignature)
    }
}

/** Real gateway + public Printer fake. No claim of paper or SDK execution. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, application = android.app.Application::class, sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AngelPayPublicPrinterTest {
    private val callbacks = mutableListOf<com.nexgo.oaf.apiv3.device.printer.OnPrintListener>()
    private val text = mutableListOf<String>()
    private lateinit var printer: com.nexgo.oaf.apiv3.device.printer.Printer
    private lateinit var gateway: AngelPaySdkGateway

    @Before fun setup() {
        mockkObject(AngelPaySDK)
        io.mockk.every { AngelPaySDK.printTicket(any()) } returns Result.success(Unit)
        printer = io.mockk.mockk(relaxed = true)
        io.mockk.every { printer.getStatus() } returns 0
        io.mockk.every { printer.initPrinter() } returns 0
        io.mockk.every { printer.appendPrnStr(any<String>(), any<Int>(), any<com.nexgo.oaf.apiv3.device.printer.AlignEnum>(), any<Boolean>()) } answers {
            text.add(firstArg()); 0
        }
        io.mockk.every { printer.startPrint(any(), any()) } answers {
            callbacks.add(secondArg()); 0
        }
        gateway = AngelPaySdkGateway()
        gateway.receiptPrinterFactory = { printer }
    }
    @After fun finish() { unmockkAll() }
    private fun ticket(label: String = "A") = com.angelpay.angelpaysdk.models.PrintTicketRequest(
        body = listOf(com.angelpay.angelpaysdk.models.PrintTicketItemRequest(
            type = com.angelpay.angelpaysdk.models.PrintTicketItemType.TEXT, text = label, addSpacingAfter = 0)))

    // Public Nexgo PrinterImpl rejects TextUtils.isEmpty before startPrint.
    // Keep this stricter SDK boundary local to these regressions; old fixtures stay exact.
    private fun rejectSdkEmptyText() {
        io.mockk.every { printer.appendPrnStr(any<String>(), any<Int>(), any<com.nexgo.oaf.apiv3.device.printer.AlignEnum>(), any<Boolean>()) } answers {
            val value = firstArg<String>()
            text.add(value)
            if (android.text.TextUtils.isEmpty(value)) com.nexgo.oaf.apiv3.SdkResult.Printer_Wrong_Package
            else com.nexgo.oaf.apiv3.SdkResult.Success
        }
    }

    @Test fun `public printer built receipt default spacing reaches final callback without SDK empty text`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        rejectSdkEmptyText()
        val built = AngelPayTicketBuilder().buildPaymentTicket(
            amount = java.math.BigDecimal("100.00"), tipAmount = java.math.BigDecimal("5.00"),
            authCode = "", referenceNumber = null, venueName = "FULLTEST-blank-row",
            staffName = null, orderNumber = null, receiptUrl = null)
        val pending = async { gateway.printTicket(built) }
        runCurrent()
        val early = if (pending.isCompleted) pending.await() else null
        assertEquals("must reach start; early SDK error: ${early?.exceptionOrNull()?.message}", 1, callbacks.size)
        assertEquals(listOf("FULLTEST-blank-row", " "), text.take(2))
        assertTrue("no empty text may reach the SDK", text.none { it.isEmpty() })
        assertTrue("accepted start still waits final callback", !pending.isCompleted)
        callbacks.single().onPrintResult(0)
        assertTrue(pending.await().isSuccess)
        io.mockk.verify(exactly = 0) { AngelPaySDK.printTicket(any()) }
    }

    @Test fun `public printer blank spacer paragraph and post spacing preserve row order and style`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        rejectSdkEmptyText()
        val rightBold = com.angelpay.angelpaysdk.models.PrintStyleRequest(
            fontSize = com.angelpay.angelpaysdk.models.PrintFontSizeRequest.LARGE,
            alignment = com.angelpay.angelpaysdk.models.PrintAlignmentRequest.RIGHT, isBold = true)
        val centerSmall = com.angelpay.angelpaysdk.models.PrintStyleRequest(
            fontSize = com.angelpay.angelpaysdk.models.PrintFontSizeRequest.SMALL,
            alignment = com.angelpay.angelpaysdk.models.PrintAlignmentRequest.CENTER, isBold = false)
        val built = com.angelpay.angelpaysdk.models.PrintTicketRequest(paperWidthChars = 32,
            header = listOf(com.angelpay.angelpaysdk.models.PrintTicketItemRequest(
                type = com.angelpay.angelpaysdk.models.PrintTicketItemType.TEXT, text = "A", style = rightBold, addSpacingAfter = 2)),
            body = listOf(
                com.angelpay.angelpaysdk.models.PrintTicketItemRequest(
                    type = com.angelpay.angelpaysdk.models.PrintTicketItemType.SPACER, lines = 2, style = centerSmall, addSpacingAfter = 0),
                com.angelpay.angelpaysdk.models.PrintTicketItemRequest(
                    type = com.angelpay.angelpaysdk.models.PrintTicketItemType.PARAGRAPH, text = "\nB\n", maxCharsPerLine = 32,
                    style = rightBold, addSpacingAfter = 1)),
            footer = listOf(com.angelpay.angelpaysdk.models.PrintTicketItemRequest(
                type = com.angelpay.angelpaysdk.models.PrintTicketItemType.TEXT, text = "C", addSpacingAfter = 0)))
        val pending = async { gateway.printTicket(built) }
        runCurrent()
        val early = if (pending.isCompleted) pending.await() else null
        assertEquals("must reach start; early SDK error: ${early?.exceptionOrNull()?.message}", 1, callbacks.size)
        assertEquals(listOf("A", " ", " ", " ", " ", " ", "B", " ", " ", "C"), text)
        assertTrue(text.none { it.isEmpty() })
        io.mockk.verify(exactly = 5) { printer.appendPrnStr(" ", 24, com.nexgo.oaf.apiv3.device.printer.AlignEnum.RIGHT, true) }
        io.mockk.verify(exactly = 2) { printer.appendPrnStr(" ", 16, com.nexgo.oaf.apiv3.device.printer.AlignEnum.CENTER, false) }
        assertTrue(!pending.isCompleted)
        callbacks.single().onPrintResult(0)
        assertTrue(pending.await().isSuccess)
    }

    @Test fun `public printer explicit empty TEXT keeps SDK error and next request can print`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        rejectSdkEmptyText()
        val failed = gateway.printTicket(ticket(""))
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()!!.message!!.contains("-1006"))
        assertEquals(listOf(""), text)
        assertTrue(callbacks.isEmpty())
        io.mockk.verify(exactly = 0) { printer.startPrint(any(), any()) }
        val next = async { gateway.printTicket(ticket("next")) }
        runCurrent()
        assertEquals(1, callbacks.size)
        assertTrue(!next.isCompleted)
        callbacks.single().onPrintResult(0)
        assertTrue(next.await().isSuccess)
        assertEquals(listOf("", "next"), text)
    }

    @Test fun `public printer waits final callback instead of accepted start`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val pending = async { gateway.printTicket(ticket()) }
        runCurrent()
        assertEquals(1, callbacks.size)
        assertTrue("accepted start is not paper completion", !pending.isCompleted)
        callbacks.single().onPrintResult(0)
        assertTrue(pending.await().isSuccess)
        assertEquals(listOf("A"), text)
        io.mockk.verify(exactly = 0) { AngelPaySDK.printTicket(any()) }
    }

    @Test fun `public printer final errors never become success and preserve error code`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        for (code in listOf(-1005, -1001, -1008, -1009, -1999)) {
            val pending = async { gateway.printTicket(ticket()) }
            runCurrent()
            assertEquals(1, callbacks.size)
            assertTrue(!pending.isCompleted)
            callbacks.removeAt(0).onPrintResult(code)
            val result = pending.await()
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains(code.toString()))
        }
    }

    @Test fun `public printer rejects busy before touching buffer`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        io.mockk.every { printer.getStatus() } returns -1004
        assertTrue(gateway.printTicket(ticket()).isFailure)
        io.mockk.verify(exactly = 0) { printer.initPrinter() }
        io.mockk.verify(exactly = 0) { printer.startPrint(any(), any()) }
        assertTrue(text.isEmpty())
    }

    @Test fun `public printer init append and start errors stop at failing boundary`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        io.mockk.every { printer.initPrinter() } returns -1007
        assertTrue(gateway.printTicket(ticket()).isFailure)
        assertTrue(text.isEmpty())
        io.mockk.every { printer.initPrinter() } returns 0
        io.mockk.every { printer.appendPrnStr(any<String>(), any<Int>(), any<com.nexgo.oaf.apiv3.device.printer.AlignEnum>(), any<Boolean>()) } returns -1002
        assertTrue(gateway.printTicket(ticket()).isFailure)
        io.mockk.verify(exactly = 0) { printer.startPrint(any(), any()) }
        io.mockk.every { printer.appendPrnStr(any<String>(), any<Int>(), any<com.nexgo.oaf.apiv3.device.printer.AlignEnum>(), any<Boolean>()) } returns 0
        io.mockk.every { printer.startPrint(any(), any()) } returns -1004
        assertTrue(gateway.printTicket(ticket()).isFailure)
    }

    @Test fun `public printer local and remote never interleave buffers`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val local = async { gateway.printTicket(ticket("local")) }
        runCurrent()
        assertEquals(1, callbacks.size)
        val remote = gateway.printTicket(ticket("remote"))
        assertTrue(remote.isFailure)
        assertEquals(listOf("local"), text)
        callbacks.removeAt(0).onPrintResult(0)
        assertTrue(local.await().isSuccess)
        val next = async { gateway.printTicket(ticket("remote")) }
        runCurrent()
        assertEquals(listOf("local", "remote"), text)
        callbacks.removeAt(0).onPrintResult(0)
        assertTrue(next.await().isSuccess)
    }

    @Test fun `public printer cancellation keeps hardware owner until callback and duplicate cannot unlock next`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val original = async { gateway.printTicket(ticket("original")) }
        runCurrent()
        assertEquals(1, callbacks.size)
        val oldCallback = callbacks.removeAt(0)
        original.cancel()
        original.join()
        assertTrue(gateway.printTicket(ticket("blocked")).isFailure)
        assertEquals(listOf("original"), text)
        oldCallback.onPrintResult(0)
        val next = async { gateway.printTicket(ticket("next")) }
        runCurrent()
        assertEquals(1, callbacks.size)
        oldCallback.onPrintResult(0)
        assertTrue(gateway.printTicket(ticket("still blocked")).isFailure)
        assertEquals(listOf("original", "next"), text)
        callbacks.single().onPrintResult(0)
        assertTrue(next.await().isSuccess)
    }

    @Test fun `public printer synchronous callback resolves once after start returns`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        io.mockk.every { printer.startPrint(any(), any()) } answers {
            val listener = secondArg<com.nexgo.oaf.apiv3.device.printer.OnPrintListener>()
            listener.onPrintResult(0); listener.onPrintResult(-1005); 0
        }
        assertTrue(gateway.printTicket(ticket()).isSuccess)
        assertTrue(gateway.printTicket(ticket("second")).isSuccess)
        assertEquals(listOf("A", "second"), text)
    }

    @Test fun `public printer invalid unsupported model does not touch hardware`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val bad = com.angelpay.angelpaysdk.models.PrintTicketRequest(body = listOf(
            com.angelpay.angelpaysdk.models.PrintTicketItemRequest(
                type = com.angelpay.angelpaysdk.models.PrintTicketItemType.IMAGE_URI, imageUri = "https://example.invalid/image")))
        assertTrue(gateway.printTicket(bad).isFailure)
        io.mockk.verify(exactly = 0) { printer.initPrinter() }
        io.mockk.verify(exactly = 0) { printer.startPrint(any(), any()) }
    }
    @Test fun `public printer thrown start holds uncertain owner until its late callback`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        io.mockk.every { printer.startPrint(any(), any()) } answers {
            callbacks.add(secondArg()); throw IllegalStateException("start transport unknown")
        }
        assertTrue(gateway.printTicket(ticket("uncertain")).isFailure)
        assertEquals(1, callbacks.size)
        assertTrue(gateway.printTicket(ticket("blocked")).isFailure)
        assertEquals(listOf("uncertain"), text)
        callbacks.removeAt(0).onPrintResult(-1005)
        io.mockk.every { printer.startPrint(any(), any()) } answers { callbacks.add(secondArg()); 0 }
        val next = async { gateway.printTicket(ticket("next")) }
        runCurrent()
        assertEquals(1, callbacks.size)
        callbacks.single().onPrintResult(0)
        assertTrue(next.await().isSuccess)
    }

    @Test fun `public printer factory failure does not retain idle owner`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        gateway.receiptPrinterFactory = { throw IllegalStateException("no device") }
        assertTrue(gateway.printTicket(ticket()).isFailure)
        io.mockk.verify(exactly = 0) { printer.initPrinter() }
        gateway.receiptPrinterFactory = { printer }
        val next = async { gateway.printTicket(ticket()) }
        runCurrent()
        assertEquals(1, callbacks.size)
        callbacks.single().onPrintResult(0)
        assertTrue(next.await().isSuccess)
    }

    @Test fun `public printer renders existing QR bitmap with decodable original URL`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val images = mutableListOf<android.graphics.Bitmap>()
        io.mockk.every { printer.appendImage(any(), any()) } answers { images.add(firstArg()); 0 }
        val url = "https://pay.avoqado.io/r/original-A"
        val built = AngelPayTicketBuilder().buildPaymentTicket(
            amount = java.math.BigDecimal("100"), tipAmount = java.math.BigDecimal("5"),
            authCode = "123", referenceNumber = "ref-A", venueName = "Negocio A", staffName = "Ana", orderNumber = "A", receiptUrl = url,
            autofacturaAvailable = true)
        val pending = async { gateway.printTicket(built) }
        runCurrent()
        assertEquals(1, callbacks.size)
        assertEquals(1, images.size)
        val bitmap = images.single()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val decoded = com.google.zxing.qrcode.QRCodeReader().decode(
            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
        assertEquals(url, decoded.text)
        assertTrue(text.any { it.contains("IVA incluido") })
        assertTrue(!pending.isCompleted)
        callbacks.single().onPrintResult(0)
        assertTrue(pending.await().isSuccess)
    }

    @Test fun `history UI real adapter waits shared printer and surfaces final paper error`() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        gateway.receiptPrintDispatcher = dispatcher
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val adapter = io.mockk.spyk(AngelPaySdkPostOperationsAdapter(AngelPayTicketBuilder(), gateway))
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(emptyList())
        val factory = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory>()
        io.mockk.every { factory.get(any()) } returns adapter
        val vm = com.jaac.avoqado_tpv.features.payment.presentation.transactions.PaymentTransactionsViewModel(
            androidx.lifecycle.SavedStateHandle(), factory)
        try {
            runCurrent()
            vm.clearMessage()
            vm.printTicket(historyTransaction())
            runCurrent()
            assertEquals(1, callbacks.size)
            assertEquals(null, vm.uiState.value.message)
            assertTrue(gateway.printTicket(ticket("remote while history")).isFailure)
            callbacks.removeAt(0).onPrintResult(-1005)
            runCurrent()
            assertTrue(vm.uiState.value.message!!.contains("-1005"))
            vm.clearMessage()
            vm.printTicket(historyTransaction())
            runCurrent()
            assertEquals(1, callbacks.size)
            assertEquals(null, vm.uiState.value.message)
            callbacks.removeAt(0).onPrintResult(0)
            runCurrent()
            assertEquals("Ticket enviado a imprimir", vm.uiState.value.message)
            io.mockk.verify(exactly = 0) { AngelPaySDK.printTicket(any()) }
        } finally {
            callbacks.toList().forEach { it.onPrintResult(-1005) }
            vm.viewModelScope.cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    private fun historyTransaction() = com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction(
        processorType = com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.ANGELPAY,
        reference = "ref-A", amount = 100.0, authorizationCode = "123", status = "APPROVED",
        cardType = "VISA", last4 = "1234", cardBin = "411111", entryMode = "CHIP",
        date = "2026-10-10", time = "10:00", operationType = "SALE", merchantName = "Negocio A",
        affiliation = "123", terminal = "N86", folio = "1", waiter = "Ana", tip = 5.0,
        issuingBank = null, cardMethod = "CREDIT", creationDate = "2026-10-10", aid = null, arqc = null,
        postOperationType = null, postOperationReference = null, postOperationAuthorization = null,
        postOperationStatus = null)

    @Test fun `application context prints before first financial SDK initialization`() = runTest {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        io.mockk.mockkStatic(com.nexgo.oaf.apiv3.APIProxy::class)
        val device = io.mockk.mockk<com.nexgo.oaf.apiv3.DeviceEngine>()
        io.mockk.every { com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(context) } returns device
        io.mockk.every { device.printer } returns printer
        val original = AngelPaySdkGateway(context)
        original.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val pending = async { original.printTicket(ticket("before first payment")) }
        runCurrent()
        assertEquals(1, callbacks.size)
        assertTrue(!pending.isCompleted)
        callbacks.single().onPrintResult(0)
        assertTrue(pending.await().isSuccess)
        io.mockk.verify { AngelPaySDK wasNot Called }
        io.mockk.verify(exactly = 1) { com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(context) }
    }

    @Test fun `synchronous callback then start exception delivers one failure and releases only original owner`() = runTest {
        gateway.receiptPrintDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        io.mockk.every { printer.startPrint(any(), any()) } answers {
            secondArg<com.nexgo.oaf.apiv3.device.printer.OnPrintListener>().onPrintResult(0)
            throw IllegalStateException("start returned by throwing")
        }
        val failed = gateway.printTicket(ticket())
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()!!.message!!.contains("start returned"))
        io.mockk.every { printer.startPrint(any(), any()) } answers { callbacks.add(secondArg()); 0 }
        val next = async { gateway.printTicket(ticket("next")) }
        runCurrent()
        assertEquals(1, callbacks.size)
        callbacks.single().onPrintResult(0)
        assertTrue(next.await().isSuccess)
    }
}
