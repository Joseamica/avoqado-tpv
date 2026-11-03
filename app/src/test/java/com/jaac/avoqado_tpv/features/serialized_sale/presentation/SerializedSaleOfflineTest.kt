package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.serialized_sale.domain.repository.SerializedSaleRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/** «Vender SIM» sin señal: un texto honesto, no el error crudo (sin red es un estado normal). */
class SerializedSaleOfflineTest {

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

    private val iccid = "8952000000000000000"  + "0"

    @Test
    fun `un IOException al escanear dice que se necesita senal`() {
        coEvery { repo.scanItem(any()) } returns Result.failure(IOException("Unable to resolve host"))
        val v = vm()
        v.onBarcodeScanned(iccid)
        assertThat(v.uiState.value.error).isEqualTo(SerializedSaleViewModel.SIN_SENAL_AL_ESCANEAR)
        assertThat(v.uiState.value.error).isEqualTo("Sin conexión: para vender una SIM se necesita señal")
        assertThat(v.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `UnknownHostException tambien cuenta como sin senal`() {
        coEvery { repo.scanItem(any()) } returns Result.failure(UnknownHostException("api"))
        val v = vm()
        v.onBarcodeScanned(iccid)
        assertThat(v.uiState.value.error).isEqualTo(SerializedSaleViewModel.SIN_SENAL_AL_ESCANEAR)
    }

    @Test
    fun `un error del servidor sigue mostrando su mensaje`() {
        coEvery { repo.scanItem(any()) } returns Result.failure(Exception("SIM no encontrada"))
        val v = vm()
        v.onBarcodeScanned(iccid)
        assertThat(v.uiState.value.error).isEqualTo("SIM no encontrada")
    }

    @Test
    fun `un IOException al confirmar la venta dice que se necesita senal, no el error crudo`() {
        coEvery { repo.quickSell(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("timeout"))
        val v = vm()

        v.onConfirmSale {}

        assertThat(v.uiState.value.error).isEqualTo("Sin conexión: para vender una SIM se necesita señal")
        assertThat(v.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `al confirmar, un error del servidor sigue mostrando su mensaje`() {
        coEvery { repo.quickSell(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(Exception("Este SIM ya fue vendido"))
        val v = vm()

        v.onConfirmSale {}

        assertThat(v.uiState.value.error).isEqualTo("Este SIM ya fue vendido")
    }
}
