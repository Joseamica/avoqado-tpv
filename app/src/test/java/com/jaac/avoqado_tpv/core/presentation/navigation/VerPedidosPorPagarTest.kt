package com.jaac.avoqado_tpv.core.presentation.navigation

import android.app.Application
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.Navigator
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationResult
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordAngelPayRefundUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D661 · ronda 4 (Important). El «Ver» de la banda de pagados en línea, en el recibo o el rechazo de un cobro con tarjeta, abría
 * «Pedidos por pagar» SIN sacar la entrada del cobro: su ViewModel (uno por entrada) seguía vivo debajo y, con el cobro siguiente,
 * dos escribían las banderas de un solo escritor de `PaymentStateHolder`. Ahora sale al Inicio primero, como toda otra salida del
 * cobro. Se prueba con un NavController real (Robolectric) y destinos de mentira con las MISMAS rutas de la app, como
 * [VolverALaCuentaDeLaMesaTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class VerPedidosPorPagarTest {

    @Navigator.Name("prueba")
    private class NavegadorDePrueba : Navigator<NavDestination>() {
        override fun createDestination(): NavDestination = NavDestination(this)
    }

    /** Arranca en el Inicio, como la app, y recorre [camino]. */
    private fun controlador(vararg camino: NavRoute): NavHostController {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        val navegador = NavegadorDePrueba()
        nav.navigatorProvider.addNavigator(navegador)
        val grafo = NavGraph(nav.navigatorProvider.getNavigator(NavGraphNavigator::class.java))
        listOf(
            NavRoute.Home, NavRoute.FastPaymentEntry, NavRoute.Checkout, NavRoute.Tables,
            NavRoute.Payment, NavRoute.AngelPayPayment, NavRoute.PorPagar, NavRoute.RefundConfirmation,
        ).forEach { ruta -> grafo.addDestination(navegador.createDestination().apply { route = ruta.route }) }
        grafo.setStartDestination(NavRoute.Home.route)
        nav.graph = grafo
        camino.forEach { nav.navigate(it.route) }
        return nav
    }

    private fun NavController.tiene(ruta: NavRoute): Boolean = runCatching { getBackStackEntry(ruta.route) }.isSuccess

    @Test
    fun `P1 Ver desde el recibo o el rechazo de cada riel no deja ninguna entrada de cobro en la pila`() {
        // PAX (Blumon) desde Pago rápido y Nexgo (AngelPay) desde Cobrar, cada riel por una puerta de siempre.
        for ((antes, cobro) in listOf(NavRoute.FastPaymentEntry to NavRoute.Payment, NavRoute.Checkout to NavRoute.AngelPayPayment)) {
            val nav = controlador(antes, cobro)

            nav.verPedidosPorPagar(ventaEnCurso = false)

            assertEquals(NavRoute.PorPagar.route, nav.currentDestination?.route)
            assertFalse("${cobro.route} sigue en la pila: su ViewModel seguiría vivo bajo «Pedidos por pagar»", nav.tiene(cobro))
            assertFalse("${antes.route} sigue en la pila: «atrás» volvería a una venta ya cobrada", nav.tiene(antes))
            assertTrue(nav.popBackStack())
            assertEquals("«Atrás» desde «Pedidos por pagar» regresa al Inicio", NavRoute.Home.route, nav.currentDestination?.route)
        }
    }

    @Test
    fun `P1 con una venta que acaba de arrancar en el cobro Ver no hace nada`() {
        // La banda tarda hasta 250 ms en esconderse cuando arranca una venta; un toque en ese rato no le quita la pantalla al cobro.
        for (cobro in listOf(NavRoute.Payment, NavRoute.AngelPayPayment)) {
            val nav = controlador(NavRoute.FastPaymentEntry, cobro)

            nav.verPedidosPorPagar(ventaEnCurso = true)

            assertEquals(cobro.route, nav.currentDestination?.route)
            assertFalse(nav.tiene(NavRoute.PorPagar))
        }
    }

    @Test
    fun `regresion desde otra pantalla Ver pone Pedidos por pagar encima y atras regresa ahi`() {
        val nav = controlador(NavRoute.Tables)

        nav.verPedidosPorPagar(ventaEnCurso = true) // la venta en curso sólo cuenta en el cobro con tarjeta

        assertEquals(NavRoute.PorPagar.route, nav.currentDestination?.route)
        assertTrue(nav.popBackStack())
        assertEquals(NavRoute.Tables.route, nav.currentDestination?.route)
    }

    @Test
    fun `regresion Ver desde Pedidos por pagar no la apila dos veces`() {
        val nav = controlador(NavRoute.PorPagar)

        nav.verPedidosPorPagar(ventaEnCurso = false)

        assertEquals(NavRoute.PorPagar.route, nav.currentDestination?.route)
        assertTrue(nav.popBackStack())
        assertEquals(NavRoute.Home.route, nav.currentDestination?.route)
    }

    /**
     * Ruling 106 (Codex r2, BLOQUEANTE). En la Nexgo la devolución corre en `RefundConfirmation`, dentro del `rememberCoroutineScope` de
     * esa pantalla, que muere en cuanto la pantalla se va: se modela igual, con un scope que se cancela cuando la navegación deja
     * `RefundConfirmation`. Con la respuesta de AngelPay detenida, «Ver» no hace nada; al llegar la aprobación, se registra (o se encola).
     */
    @Test
    fun `Ruling 106 con AngelPay contestando una devolucion Ver no hace nada y la aprobacion se registra`() = runTest {
        val estado = com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder()
        val libreta = mockk<com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger>(relaxed = true)
        com.jaac.avoqado_tpv.features.shift.data.bridgeLegacyLedgerFixture(libreta)
        coEvery { libreta.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        coEvery { libreta.markAuthorizing(any()) } returns true
        coEvery { libreta.devolucionSinResolver(any()) } returns null
        coEvery { libreta.markHostResponded(any(), any(), any(), any(), any()) } returns true
        coEvery { libreta.markDiscardedBeforeCharge(any(), any()) } returns true
        val angelPay = mockk<PaymentPostOperationsAdapter>(relaxed = true)
        val fabrica = mockk<PostOperationsAdapterFactory>(relaxed = true)
        every { fabrica.get(ProcessorType.ANGELPAY) } returns angelPay
        coEvery { angelPay.getTransactionHistory(any()) } returns Result.success(listOf(ventaDeAngelPay()))
        val respuesta = CompletableDeferred<Result<PostOperationResult>>()
        coEvery { angelPay.cancelTransaction(any(), any(), any(), any()) } coAnswers { respuesta.await() }
        val registro = mockk<RefundRecorder>(relaxed = true)
        coEvery { registro.recordRefund(any(), any(), any(), any(), any(), any()) } returns Result.failure(java.io.IOException("sin red"))
        val cola = mockk<com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository>(relaxed = true)
        coEvery { cola.enqueueClaimed(any(), any()) } returns Result.success(Unit)
        val sesion = mockk<com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository>(relaxed = true)
        every { sesion.getStaffId() } returns "staff-1"
        val devolucion = RecordAngelPayRefundUseCase(
            registro, sesion, fabrica, cola, libreta,
            java.time.Clock.fixed(java.time.Instant.parse("2026-07-01T18:00:00Z"), java.time.ZoneOffset.UTC), estado,
            shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(),
        )

        val nav = controlador(NavRoute.RefundConfirmation)
        val pantalla = CoroutineScope(coroutineContext + Job())
        nav.addOnDestinationChangedListener { _, destino, _ ->
            if (destino.route != NavRoute.RefundConfirmation.route) pantalla.cancel()
        }
        // Lo que hace «Confirmar Reembolso» en la Nexgo: AngelPay devuelve y, aprobada, se registra.
        pantalla.launch {
            devolucion.processSdkRefund(
                paymentReference = "000000188231", createdAt = java.time.Instant.parse("2026-07-01T12:00:00Z"),
                requestedReason = RefundReason.CUSTOMER_REQUEST, appContext = mockk(relaxed = true),
                requestedAmount = BigDecimal("1000.00"), originalAmount = BigDecimal("1000.00"), alreadyRefundedAmount = BigDecimal.ZERO,
                originalPaymentId = "pay-001", paymentVenueId = "venue-1",
            ).onSuccess { aprobada ->
                devolucion.recordInBackend(
                    paymentId = "pay-001", orderId = null, paymentVenueId = "venue-1", merchantAccountId = "merchant-1",
                    originalTotalAmount = BigDecimal("1000.00"), refundAmount = BigDecimal("1000.00"),
                    refundReason = RefundReason.CUSTOMER_REQUEST, sdkReferenceNumber = "000000188231", tipRefundCents = null,
                    refundedAmount = BigDecimal.ZERO, idempotencyKey = aprobada.idempotencyKey,
                )
            }
        }
        runCurrent()
        assertTrue("AngelPay está contestando la devolución", estado.isCharging())

        nav.verPedidosPorPagar(ventaEnCurso = estado.isChargeAttemptActive() || estado.isCharging())

        assertEquals("«Ver» no saca la pantalla de la devolución", NavRoute.RefundConfirmation.route, nav.currentDestination?.route)
        respuesta.complete(Result.success(PostOperationResult(true, "APPROVED", null, "A1", "R1", "S000")))
        advanceUntilIdle()
        coVerify(exactly = 1) { cola.enqueueClaimed(any(), any()) }
        coVerify(exactly = 1) { registro.recordRefund(any(), any(), any(), any(), any(), any()) }
        pantalla.cancel()
    }

    private fun ventaDeAngelPay() = com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction(
        processorType = ProcessorType.ANGELPAY, reference = "000000188231", amount = 1000.0,
        authorizationCode = "473067", status = "APROBADA", cardType = null, last4 = "8892", cardBin = "45551203",
        entryMode = "contactless", date = "2026-07-01", time = "06:00:00", operationType = "VENTA",
        merchantName = null, affiliation = null, terminal = null, folio = "F1", waiter = null, tip = 0.0,
        issuingBank = null, cardMethod = null, creationDate = "2026-07-01T12:00:00Z", aid = null, arqc = null,
        postOperationType = null, postOperationReference = null, postOperationAuthorization = null, postOperationStatus = null,
    )

    /** El montaje de la banda no se puede ejecutar aquí (`createComposeRule` sólo está en `androidTest`): se lee la fuente. */
    @Test
    fun `P1 el Ver de la banda usa verPedidosPorPagar con la venta en curso leida al tocar`() {
        val navegacion = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
        val ver = navegacion.substringAfter("onVer = {").substringBefore("onEntendido")
        assertTrue(ver, ver.contains("navController.verPedidosPorPagar("))
        assertTrue(ver, ver.contains("paymentStateProvider.isChargeAttemptActive() || paymentStateProvider.isCharging()"))
        assertFalse(ver, ver.contains("navigate(NavRoute.PorPagar.route)"))
    }
}
