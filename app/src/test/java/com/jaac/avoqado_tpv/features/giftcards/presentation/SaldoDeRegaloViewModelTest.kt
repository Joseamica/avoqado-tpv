package com.jaac.avoqado_tpv.features.giftcards.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.giftcards.data.CobroConSaldoRepository
import com.jaac.avoqado_tpv.features.giftcards.data.ConsultaDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.DesenlaceDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.FalloDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.FolioCerrado
import com.jaac.avoqado_tpv.features.giftcards.data.PruebaDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.TextosDeSaldo
import com.jaac.avoqado_tpv.features.onlineorders.data.DespuesDeCobrarFolio
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.presentation.AperturaDeCobro
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** P2 (tienda híbrida, parte 2): «Saldo o tarjeta de regalo» al cobrar un folio en la TPV (mockups v2 2e, 2e·2, 2e·3 y 2e·4). */
@OptIn(ExperimentalCoroutinesApi::class)
class SaldoDeRegaloViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = mockk<CobroConSaldoRepository>()
    private val despues = mockk<DespuesDeCobrarFolio>(relaxed = true)

    private val comanda = ComandaDeFolio("cord1", "W7K3P", "Ana", null, emptyList())
    private val apertura = AperturaDeCobro(initialAmount = "134.00", orderId = "cord1", orderNumber = "W7K3P", folio = comanda)

    // Códigos con su dígito de control real (Luhn mod 32 sobre Crockford, `giftCardCode.ts`).
    private val valido = "K7Q2-9DXM-4R8H-HB7T-R"
    private val malTecleado = "K7Q2-9DXM-4R8H-HB7T-Q"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repo.pendienteDe(any()) } returns null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SaldoDeRegaloViewModel(repo, despues).also { it.iniciar(apertura) }

    private fun saldo(cents: Long) = coEvery { repo.consultar(any()) } returns Result.success(ConsultaDeSaldo("HB7T", cents, true, null))

    // region — el código

    @Test
    fun `el codigo se limpia, lleva guiones solos y el digito de control lo revisa`() {
        assertThat(CodigoDeTarjeta.limpiar("k7q2 9dxm-4r8h hb7t r")).isEqualTo("K7Q29DXM4R8HHB7TR")
        // I y L se leen como 1 y O como 0, igual que el SERVIDOR.
        assertThat(CodigoDeTarjeta.limpiar("ILO")).isEqualTo("110")
        assertThat(CodigoDeTarjeta.limpiar("K7Q29DXM4R8HHB7TRXYZ")).hasLength(17)
        assertThat(CodigoDeTarjeta.formatear("K7Q29DXM4R8HHB7TR")).isEqualTo(valido)
        assertThat(CodigoDeTarjeta.formatear("K7Q29")).isEqualTo("K7Q2-9")
        assertThat(CodigoDeTarjeta.esValido("K7Q29DXM4R8HHB7TR")).isTrue()
        assertThat(CodigoDeTarjeta.esValido("M3TX8KQ42WZN7RJCW")).isTrue()
        assertThat(CodigoDeTarjeta.esValido("K7Q29DXM4R8HHB7TQ")).isFalse()
        assertThat(CodigoDeTarjeta.esValido("K7Q29DXM")).isFalse()
    }

    @Test
    fun `2e-4 un codigo mal tecleado se avisa al instante y no llama al SERVIDOR`() = runTest {
        val vm = vm()

        vm.teclear(malTecleado)

        assertThat(vm.ui.value.codigoInvalido).isTrue()
        assertThat(TextosDeSaldo.CODIGO_NO_VALIDO).isEqualTo("Código no válido: revísalo con la clienta.")
        coVerify(exactly = 0) { repo.consultar(any()) }
    }

    // endregion

    // region — el saldo

    @Test
    fun `2e teclear el codigo muestra el saldo y cobra lo menor entre el saldo y lo que falta`() = runTest {
        saldo(35000)
        val vm = vm()

        vm.teclear(valido)

        coVerify { repo.consultar(PruebaDeSaldo.Codigo("K7Q29DXM4R8HHB7TR")) }
        assertThat(vm.ui.value.montoCents).isEqualTo(13400)
        assertThat(vm.ui.value.puedeCobrar).isTrue()
        assertThat(textoDeSaldo(vm.ui.value)).isEqualTo("Saldo $350.00: alcanza para $134.00" to null)
        assertThat(TextosDeSaldo.cobrar(13400)).isEqualTo("Cobrar $134.00")
    }

    @Test
    fun `2e-2 si el saldo no alcanza dice cuanto cubre y cuanto falta`() = runTest {
        saldo(5000)
        val vm = vm()

        vm.teclear(valido)

        assertThat(vm.ui.value.montoCents).isEqualTo(5000)
        assertThat(textoDeSaldo(vm.ui.value)).isEqualTo(
            "Saldo $50.00: no alcanza" to "Cobra $50.00 con saldo; los $84.00 que faltan, con tarjeta o efectivo.",
        )
    }

    @Test
    fun `escanear el QR con la liga de la tarjeta la consulta por su liga`() = runTest {
        saldo(35000)
        val vm = vm()

        vm.escaneado("https://avoqado.io/tarjeta/AbCdEfGhIjKlMnOpQrStUvWxYz012345")

        coVerify { repo.consultar(PruebaDeSaldo.Liga("AbCdEfGhIjKlMnOpQrStUvWxYz012345")) }
        assertThat(vm.ui.value.escaneando).isFalse()
    }

    @Test
    fun `una tarjeta que no se puede usar no deja cobrar y dice por que`() = runTest {
        coEvery { repo.consultar(any()) } returns Result.success(ConsultaDeSaldo("HB7T", 0, false, "Vencida"))
        val vm = vm()

        vm.teclear(valido)

        assertThat(vm.ui.value.puedeCobrar).isFalse()
        assertThat(textoDeSaldo(vm.ui.value)).isEqualTo("Tarjeta •••• HB7T: Vencida" to null)
    }

    @Test
    fun `sin red la consulta lo dice y no deja cobrar`() = runTest {
        coEvery { repo.consultar(any()) } returns Result.failure(FalloDeSaldo(TextosDeSaldo.SIN_RED, sinRed = true))
        val vm = vm()

        vm.teclear(valido)

        assertThat(vm.ui.value.sinRed).isTrue()
        assertThat(vm.ui.value.error).isEqualTo(TextosDeSaldo.SIN_RED)
        assertThat(vm.ui.value.puedeCobrar).isFalse()
    }

    // endregion

    // region — cobrar

    @Test
    fun `si el saldo cubre todo, el folio se confirma UNA sola vez`() = runTest {
        saldo(35000)
        coEvery { repo.cobrar(any(), any(), any(), any()) } returns DesenlaceDeSaldo.Cobrado(0, 13400, "HB7T", 21600)
        val vm = vm()
        vm.teclear(valido)

        vm.cobrar()
        vm.cobrar()

        coVerify(exactly = 1) { repo.cobrar("cord1", PruebaDeSaldo.Codigo("K7Q29DXM4R8HHB7TR"), 13400, "W7K3P") }
        verify(exactly = 1) { despues.alConfirmarse(comanda) }
        assertThat(vm.ui.value.pagado).isEqualTo(PagoConSaldo(13400, "HB7T", 21600))
        assertThat(vm.ui.value.faltaPorCobrar).isNull()
    }

    @Test
    fun `2e-3 si falta, pide abrir el cobro de siempre por lo que falta y no confirma el folio`() = runTest {
        saldo(5000)
        coEvery { repo.cobrar(any(), any(), any(), any()) } returns DesenlaceDeSaldo.Cobrado(8400, 5000, "HB7T", 0)
        val vm = vm()
        vm.teclear(valido)

        vm.cobrar()

        assertThat(vm.ui.value.faltaPorCobrar).isEqualTo(5000)
        verify(exactly = 0) { despues.alConfirmarse(any()) }
        vm.faltaAtendida()
        assertThat(vm.ui.value.faltaPorCobrar).isNull()
    }

    @Test
    fun `mientras se cobra dice Comprobando el cobro con saldo y un segundo toque no cobra otra vez`() = runTest {
        saldo(35000)
        val respuesta = CompletableDeferred<DesenlaceDeSaldo>()
        coEvery { repo.cobrar(any(), any(), any(), any()) } coAnswers { respuesta.await() }
        val vm = vm()
        vm.teclear(valido)

        vm.cobrar()
        assertThat(vm.ui.value.cobrando).isTrue()
        vm.cobrar()
        respuesta.complete(DesenlaceDeSaldo.Cobrado(0, 13400, "HB7T", 21600))

        coVerify(exactly = 1) { repo.cobrar(any(), any(), any(), any()) }
        assertThat(vm.ui.value.cobrando).isFalse()
    }

    @Test
    fun `si no se sabe si se cobro, queda en duda y Volver a comprobar resuelve con la misma solicitud`() = runTest {
        saldo(35000)
        coEvery { repo.cobrar(any(), any(), any(), any()) } returns DesenlaceDeSaldo.Incierto
        coEvery { repo.resolver("cord1") } returns DesenlaceDeSaldo.Cobrado(0, 13400, "HB7T", 21600)
        val vm = vm()
        vm.teclear(valido)

        vm.cobrar()
        assertThat(vm.ui.value.enDuda).isTrue()
        assertThat(vm.ui.value.puedeCobrar).isFalse()

        vm.comprobar()

        coVerify(exactly = 1) { repo.cobrar(any(), any(), any(), any()) }
        assertThat(vm.ui.value.enDuda).isFalse()
        verify(exactly = 1) { despues.alConfirmarse(comanda) }
    }

    @Test
    fun `un rechazo definitivo dice el texto del SERVIDOR y vuelve a leer el saldo`() = runTest {
        saldo(35000)
        coEvery { repo.cobrar(any(), any(), any(), any()) } returns DesenlaceDeSaldo.NoSalio("Saldo insuficiente")
        val vm = vm()
        vm.teclear(valido)

        vm.cobrar()

        assertThat(vm.ui.value.error).isEqualTo("Saldo insuficiente")
        coVerify(exactly = 2) { repo.consultar(any()) }
    }

    @Test
    fun `H4 folio vencido o pagado en otra caja dice el texto del mockup, Cobrar en gris y sin monto`() = runTest {
        saldo(35000)
        coEvery { repo.cobrar(any(), any(), any(), any()) } returns DesenlaceDeSaldo.NoSalio(TextosDeSaldo.FOLIO_VENCIDO, FolioCerrado.VENCIDO)
        val vm = vm()
        vm.teclear(valido)

        vm.cobrar()

        assertThat(vm.ui.value.error).isEqualTo(TextosDeSaldo.FOLIO_VENCIDO)
        assertThat(vm.ui.value.puedeCobrar).isFalse()
        assertThat(etiquetaDeCobrar(vm.ui.value)).isEqualTo("Cobrar")
        // No vuelve a leer el saldo: el folio ya no se cobra desde aquí.
        coVerify(exactly = 1) { repo.consultar(any()) }
        // Teclear otro código tampoco lo reabre.
        vm.teclear(valido)
        assertThat(vm.ui.value.puedeCobrar).isFalse()
    }

    @Test
    fun `H5 Cobrar ensena el monto solo cuando se puede cobrar`() = runTest {
        saldo(5000)
        val vm = vm()
        assertThat(etiquetaDeCobrar(vm.ui.value)).isEqualTo("Cobrar")

        vm.teclear(valido)

        assertThat(etiquetaDeCobrar(vm.ui.value)).isEqualTo("Cobrar $50.00")
    }

    @Test
    fun `al entrar con una solicitud pendiente de ese folio la comprueba antes de dejar cobrar`() = runTest {
        every { repo.pendienteDe("cord1") } returns mockk()
        coEvery { repo.resolver("cord1") } returns DesenlaceDeSaldo.Incierto

        val vm = vm()

        coVerify(exactly = 1) { repo.resolver("cord1") }
        assertThat(vm.ui.value.enDuda).isTrue()
    }

    // endregion
}
