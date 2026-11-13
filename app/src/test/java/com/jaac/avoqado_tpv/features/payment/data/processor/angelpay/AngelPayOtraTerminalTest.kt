package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.PaymentResult
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Cancelar o devolver una venta cobrada en OTRA Nexgo del mismo comercio (D1, 12-nov-2026; SDK 1.0.23).
 *
 * El gateway de AngelPay busca la venta original por la terminal que la cobró. Sin `originalTerminal` usa el serial del
 * equipo actual, y desde otro equipo contesta M2AP («transacción original no localizada»). El dato viene del historial:
 * `TransactionItem.terminal` → [UnifiedTransaction.terminal]. Vacío o nulo, el SDK cae solo al serial actual (`o.u.a` del
 * AAR 1.0.23: recorta y trata el vacío como nulo), así que se manda tal cual.
 *
 * 🔴 Con NOMBRES: el décimo argumento del SDK es `pin` y el onceavo `originalTerminal` (javap del AAR). La primera versión de
 * esta prueba los contaba por posición y capturaba el `pin` (Codex r1 #2): probaba otra cosa.
 */
class AngelPayOtraTerminalTest {

    private val terminales = mutableListOf<String?>()
    private val adapter = AngelPaySdkPostOperationsAdapter(ticketBuilder = mockk(relaxed = true), sdkGateway = mockk(relaxed = true))
    private val aprobada = PaymentResult(
        approved = true, status = PaymentResult.Status.APPROVED, message = "APROBADA", amount = 10_000L,
        requireSignature = false, authorizationAttempted = true,
    )

    @Before fun setup() {
        mockkObject(AngelPaySDK)
        coEvery {
            AngelPaySDK.cancelTransaction(
                originalReference = any(), amount = any(), authCode = any(), entryMode = any(), cardBin = any(),
                transactionDate = any(), isManual = any(), latitude = any(), longitude = any(), pin = isNull(),
                originalTerminal = captureNullable(terminales),
            )
        } returns Result.success(aprobada)
        coEvery {
            AngelPaySDK.refundTransaction(
                originalReference = any(), amount = any(), authCode = any(), entryMode = any(), cardBin = any(),
                transactionDate = any(), isManual = any(), latitude = any(), longitude = any(), pin = isNull(),
                originalTerminal = captureNullable(terminales),
            )
        } returns Result.success(aprobada)
    }

    @After fun tearDown() = unmockkAll()

    private fun venta(terminal: String?) = UnifiedTransaction(
        processorType = ProcessorType.ANGELPAY, reference = "000000188231", amount = 100.0, authorizationCode = "473067",
        status = "APROBADA", cardType = null, last4 = "8892", cardBin = "45551203", entryMode = "contactless",
        date = "2026-11-12", time = "12:00:00", operationType = "VENTA", merchantName = null, affiliation = null,
        terminal = terminal, folio = "F1", waiter = null, tip = 0.0, issuingBank = null, cardMethod = null,
        creationDate = "2026-11-12", aid = null, arqc = null, postOperationType = null, postOperationReference = null,
        postOperationAuthorization = null, postOperationStatus = null,
    )

    @Test
    fun `P1 cancelar una venta de otra Nexgo le dice a AngelPay que terminal la cobro`() = runTest {
        val resultado = adapter.cancelTransaction(venta(terminal = "N860W173233"))

        assertThat(resultado.getOrThrow().approved).isTrue()
        assertThat(terminales).containsExactly("N860W173233")
    }

    @Test
    fun `P1 devolver una venta de otra Nexgo le dice a AngelPay que terminal la cobro`() = runTest {
        val resultado = adapter.refundTransaction(venta(terminal = "N860W173233"))

        assertThat(resultado.getOrThrow().approved).isTrue()
        assertThat(terminales).containsExactly("N860W173233")
    }

    @Test
    fun `P2 sin terminal en el historial se manda nula y el SDK usa el serial del equipo, como antes`() = runTest {
        adapter.cancelTransaction(venta(terminal = null))
        adapter.refundTransaction(venta(terminal = null))

        assertThat(terminales).containsExactly(null, null)
    }
}
