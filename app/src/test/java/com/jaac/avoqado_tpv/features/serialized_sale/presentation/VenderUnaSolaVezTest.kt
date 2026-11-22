package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.QuickSellResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.repository.SerializedSaleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

/**
 * D671 (Codex r1 #2) · TPV SOLA · VENTA DE SIMs. Dos toques a «Vender» antes de que se repinte «Procesando…» mandaban dos
 * quick-sell: el SERVIDOR crea una orden PENDIENTE por cada uno (la SIM sólo se marca vendida al cobrar), y la que no llega a la
 * pantalla de cobro se queda sin cobro, en «Sin cobrar». Mientras una venta está en camino, otra no sale.
 */
class VenderUnaSolaVezTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val repo = mockk<SerializedSaleRepository>(relaxed = true)
    private val modules = mockk<ModulesRepository>(relaxed = true)
    private val permissions = mockk<PermissionsRepository>(relaxed = true)

    private fun vm(): SerializedSaleViewModel {
        coEvery { repo.getCategories() } returns Result.success(emptyList())
        every { modules.getModuleConfig(any()) } returns null
        coEvery { permissions.hasPermission(any()) } returns false
        return SerializedSaleViewModel(repo, modules, mockk(relaxed = true), permissions)
    }

    @Test
    fun `P1 dos toques a Vender mientras la primera venta va en camino mandan un solo quick-sell`() {
        val respuesta = CompletableDeferred<Result<QuickSellResult>>()
        coEvery { repo.quickSell(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { respuesta.await() }
        val v = vm()
        var abiertos = 0

        v.onConfirmSale { abiertos++ }
        v.onConfirmSale { abiertos++ } // el segundo toque, antes de repintar
        respuesta.complete(Result.success(mockk(relaxed = true)))

        coVerify(exactly = 1) { repo.quickSell(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(abiertos).isEqualTo(1)
    }

    @Test
    fun `P1 crear una categoria mientras la venta va en camino no vuelve a dejar vender (Codex r2 #3)`() {
        val respuesta = CompletableDeferred<Result<QuickSellResult>>()
        coEvery { repo.quickSell(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { respuesta.await() }
        coEvery { repo.createCategory(any(), any(), any()) } returns Result.success(mockk(relaxed = true))
        val v = vm()

        v.onConfirmSale {}
        v.createCategory("SIM nueva") // su respuesta apaga el `isLoading` que también usaba la venta
        v.onConfirmSale {}
        respuesta.complete(Result.success(mockk(relaxed = true)))

        coVerify(exactly = 1) { repo.quickSell(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tras una venta que fallo se puede volver a vender`() {
        coEvery { repo.quickSell(any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(Exception("x"))
        val v = vm()

        v.onConfirmSale {}
        v.onConfirmSale {}

        coVerify(exactly = 2) { repo.quickSell(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
