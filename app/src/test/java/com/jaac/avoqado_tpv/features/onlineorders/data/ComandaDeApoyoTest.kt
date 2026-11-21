package com.jaac.avoqado_tpv.features.onlineorders.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.printer.ComandaNoEmpezada
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioModificador
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioProducto
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * La comanda de apoyo de un folio ya cobrado (P4, decisión 18 del diseño): reclama, imprime y confirma sólo si el papel salió.
 * Nadie la vigila: sin impresora, con el reclamo perdido o con el papel fallido no hace nada más y el folio sigue en «Por entregar».
 */
class ComandaDeApoyoTest {

    private val folios = mockk<FoliosRepository>()
    private val impresora = mockk<PrinterManager>()
    private val comanda = ComandaDeApoyo(folios, impresora)

    private val folio = ComandaDeFolio(
        orderId = "ord-1",
        orderNumber = "W7K3P",
        nombre = "Ana",
        nota = "sin azúcar, por favor",
        renglones = listOf(
            FolioProducto("Latte", 2, "tibio", listOf(FolioModificador("Leche de almendra", 1), FolioModificador("Shot extra", 2))),
            FolioProducto("Croissant", 1, null, emptyList()),
        ),
    )

    private fun imprime(resultado: Result<Unit>) {
        every { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any()) } returns resultado
    }

    @Before
    fun setUp() {
        every { impresora.isPrinterAvailable() } returns true
        coEvery { folios.reclamarImpresion("v1", "ord-1") } returns Result.success(ReclamoDeComanda(true))
        coEvery { folios.confirmarImpresion("v1", "ord-1") } returns Result.success(true)
        imprime(Result.success(Unit))
    }

    @Test
    fun `con impresora reclama, imprime y confirma, en ese orden`() = runTest {
        comanda.intentar("v1", folio)

        coVerifyOrder {
            folios.reclamarImpresion("v1", "ord-1")
            impresora.printKitchenTicket("W7K3P", null, any(), null, "Ana", "sin azúcar, por favor", true)
            folios.confirmarImpresion("v1", "ord-1")
        }
    }

    @Test
    fun `la comanda lleva cada renglon con su cantidad, sus extras y su nota, sin precios`() = runTest {
        val renglones = slot<List<OrderItem>>()
        every { impresora.printKitchenTicket(any(), any(), capture(renglones), any(), any(), any(), true) } returns Result.success(Unit)

        comanda.intentar("v1", folio)

        assertThat(renglones.captured.map { "${it.quantity}x ${it.productName}" }).containsExactly("2x Latte", "1x Croissant").inOrder()
        assertThat(renglones.captured[0].modifiers.map { it.name }).containsExactly("Leche de almendra", "2x Shot extra").inOrder()
        assertThat(renglones.captured[0].notes).isEqualTo("tibio")
        assertThat(renglones.captured[1].modifiers).isEmpty()
        assertThat(renglones.captured.all { it.orderId == "ord-1" }).isTrue()
    }

    @Test
    fun `si printKitchenTicket devuelve failure no confirma y el folio sigue en Por entregar`() = runTest {
        imprime(Result.failure(Exception("Error al imprimir comanda (código: 2)")))

        comanda.intentar("v1", folio)

        coVerify(exactly = 1) { folios.reclamarImpresion("v1", "ord-1") }
        coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }
    }

    @Test
    fun `sin impresora no intenta nada`() = runTest {
        every { impresora.isPrinterAvailable() } returns false

        comanda.intentar("v1", folio)

        coVerify(exactly = 0) { folios.reclamarImpresion(any(), any()) }
        verify(exactly = 0) { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }
    }

    @Test
    fun `reclamo perdido no imprime`() = runTest {
        coEvery { folios.reclamarImpresion("v1", "ord-1") } returns Result.success(ReclamoDeComanda(false))

        comanda.intentar("v1", folio)

        verify(exactly = 0) { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }
    }

    @Test
    fun `sin red al reclamar no imprime`() = runTest {
        coEvery { folios.reclamarImpresion("v1", "ord-1") } returns Result.failure(IOException("sin red"))

        comanda.intentar("v1", folio)

        verify(exactly = 0) { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }
    }

    @Test
    fun `un fallo de la impresora que truena no sale de la comanda`() = runTest {
        every { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("SDK")

        comanda.intentar("v1", folio)

        coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }
    }

    @Test
    fun `Ruling 9 con posibleRepetida la comanda dice POSIBLE REPETIDA arriba`() = runTest {
        coEvery { folios.reclamarImpresion("v1", "ord-1") } returns Result.success(ReclamoDeComanda(true, posibleRepetida = true))
        every { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        comanda.intentar("v1", folio)

        verify(exactly = 1) { impresora.printKitchenTicket("W7K3P", null, any(), null, "Ana", "sin azúcar, por favor", true, true) }
        coVerify(exactly = 1) { folios.confirmarImpresion("v1", "ord-1") }
    }

    @Test
    fun `D661 intentar dice si salio, si la tiene otra caja o si no salio aqui`() = runTest {
        assertThat(comanda.intentar("v1", folio)).isEqualTo(ResultadoDeComanda.IMPRESA)

        coEvery { folios.reclamarImpresion("v1", "ord-1") } returns Result.success(ReclamoDeComanda(false))
        assertThat(comanda.intentar("v1", folio)).isEqualTo(ResultadoDeComanda.OTRA_CAJA)

        coEvery { folios.reclamarImpresion("v1", "ord-1") } returns Result.success(ReclamoDeComanda(true))
        imprime(Result.failure(Exception("Error al imprimir comanda (código: 2)")))
        assertThat(comanda.intentar("v1", folio)).isEqualTo(ResultadoDeComanda.NO_SALIO)

        every { impresora.isPrinterAvailable() } returns false
        assertThat(comanda.puedeImprimir()).isFalse()
        assertThat(comanda.intentar("v1", folio)).isEqualTo(ResultadoDeComanda.NO_SALIO)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D661 · Ruling 50: un reclamo que envejeció esperando la impresora (ocupada, sin papel, otra comanda) ya no se imprime.
    // ═══════════════════════════════════════════════════════════════════════════

    private var monotonico = 0L
    private val conReloj = ComandaDeApoyo(folios, impresora) { monotonico }

    /** La impresora queda libre [espera] ms después y, ya libre, pregunta si todavía toca (como el PrinterManager de verdad). */
    private fun impresoraQueTarda(espera: Long) {
        every { impresora.printKitchenTicket(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            monotonico += espera
            if (arg<(() -> Boolean)?>(8)?.invoke() == false) Result.failure(ComandaNoEmpezada()) else Result.success(Unit)
        }
    }

    @Test
    fun `Ruling 50 un reclamo de 75 s al soltarse la impresora no se imprime ni se confirma - uno mas joven sale como hoy`() = runTest {
        impresoraQueTarda(75_000)
        assertThat(conReloj.intentar("v1", folio, edadMaximaDelReclamo = 75_000)).isEqualTo(ResultadoDeComanda.RECLAMO_VENCIDO)
        coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }

        impresoraQueTarda(74_999)
        assertThat(conReloj.intentar("v1", folio, edadMaximaDelReclamo = 75_000)).isEqualTo(ResultadoDeComanda.IMPRESA)
        coVerify(exactly = 1) { folios.confirmarImpresion("v1", "ord-1") }
    }

    @Test
    fun `Ruling 50 la edad cuenta desde que se pide el reclamo - una respuesta lenta tambien cuenta`() = runTest {
        coEvery { folios.reclamarImpresion("v1", "ord-1") } coAnswers {
            monotonico += 30_000
            Result.success(ReclamoDeComanda(true))
        }
        impresoraQueTarda(45_000)

        assertThat(conReloj.intentar("v1", folio, edadMaximaDelReclamo = 75_000)).isEqualTo(ResultadoDeComanda.RECLAMO_VENCIDO)
        coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }
    }

    @Test
    fun `Ruling 50 sin tope - la comanda de un folio pagado en caja sale aunque haya esperado`() = runTest {
        // Nadie más la pediría (no está en `comandasPorImprimir`): se prefiere imprimir tarde a no imprimir.
        impresoraQueTarda(200_000)
        assertThat(conReloj.intentar("v1", folio)).isEqualTo(ResultadoDeComanda.IMPRESA)
        coVerify(exactly = 1) { folios.confirmarImpresion("v1", "ord-1") }
    }
}
