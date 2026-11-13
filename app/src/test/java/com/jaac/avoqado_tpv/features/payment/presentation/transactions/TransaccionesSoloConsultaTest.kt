package com.jaac.avoqado_tpv.features.payment.presentation.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * D3 (encargo 0292fc, Codex r1 #3 de D1, 12-nov): la pantalla «Transacciones» del superadmin cancelaba o devolvía llamando
 * DIRECTO al adaptador de AngelPay, sin la libreta, sin el candado de «devolución pendiente» y sin registrar nada en Avoqado.
 * Una devolución hecha ahí no quedaba en Avoqado y no impedía otra por el camino normal sobre el mismo pago.
 *
 * Desde aquí la pantalla SÓLO CONSULTA: el único camino que mueve dinero de vuelta es Pagos → el pago → Reembolso
 * (`RecordAngelPayRefundUseCase`), que sí escribe la libreta, revisa los candados y registra en Avoqado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransaccionesSoloConsultaTest {

    private val dispatcher = StandardTestDispatcher()
    private val adapter = mockk<PaymentPostOperationsAdapter>(relaxed = true)
    private val factory = mockk<PostOperationsAdapterFactory>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { factory.get(any()) } returns adapter
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `abrir la pantalla con una referencia ya no devuelve ni cancela nada`() = runTest(dispatcher) {
        // La ruta vieja aceptaba `autoRefundReference` y, al encontrar la venta en el historial, la devolvía sola.
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(venta("ref-A")))
        val vm = PaymentTransactionsViewModel(
            SavedStateHandle(mapOf("processorType" to "ANGELPAY", "autoRefundReference" to "ref-A")),
            factory,
        )
        try {
            advanceUntilIdle()
            coVerify(exactly = 0) { adapter.refundTransaction(any(), any(), any(), any()) }
            coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
            assertThat(vm.uiState.value.transactions).hasSize(1)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `la pantalla y su ViewModel no llaman a cancelar ni a devolver del adaptador`() {
        val base = "src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/transactions"
        val viewModel = File("$base/PaymentTransactionsViewModel.kt").readText()
        val pantalla = File("$base/PaymentTransactionsScreen.kt").readText()
        for (fuente in listOf(viewModel, pantalla)) {
            assertThat(fuente).doesNotContain(".cancelTransaction(")
            assertThat(fuente).doesNotContain(".refundTransaction(")
            assertThat(fuente).doesNotContain("autoRefund")
        }
        // La tarjeta de cada venta ya no trae botones de dinero. («Cancelar» sigue existiendo, a propósito, en el diálogo de
        // correo: cierra el diálogo y no toca dinero; por eso se mira sólo la tarjeta.)
        val tarjeta = pantalla.substring(pantalla.indexOf("private fun TransactionCard("))
        assertThat(tarjeta).doesNotContain("Text(\"Cancelar\")")
        assertThat(tarjeta).doesNotContain("Text(\"Devolver\")")
        assertThat(tarjeta).doesNotContain("onCancel")
        assertThat(tarjeta).doesNotContain("onRefund")
        // Y le dice al superadmin por dónde sí se devuelve (texto escrito aquí, no la constante: así esta prueba compila
        // también contra el código viejo y su rojo es por el defecto, no por una referencia que no existe).
        assertThat(pantalla).contains(
            "Aquí sólo se consulta. Para cancelar o devolver una venta, entra a Pagos, abre el pago y usa Reembolso: así queda registrado en Avoqado."
        )
    }

    @Test fun `la ruta ya no acepta una referencia para devolver sola`() {
        val rutas = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/NavRoute.kt").readText()
        val navegacion = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
        assertThat(rutas).doesNotContain("autoRefundReference")
        assertThat(navegacion).doesNotContain("autoRefundReference")
    }

    private fun venta(ref: String) = UnifiedTransaction(
        processorType = ProcessorType.ANGELPAY,
        reference = ref, amount = 100.0, authorizationCode = "123", status = "APPROVED",
        cardType = "VISA", last4 = "1234", cardBin = "411111", entryMode = "CHIP",
        date = "2026-11-13", time = "10:00", operationType = "SALE", merchantName = "Negocio A",
        affiliation = "123", terminal = "N86", folio = "1", waiter = "Ana", tip = 0.0,
        issuingBank = null, cardMethod = "CREDIT", creationDate = "2026-11-13", aid = null, arqc = null,
        postOperationType = null, postOperationReference = null, postOperationAuthorization = null,
        postOperationStatus = null,
    )
}
