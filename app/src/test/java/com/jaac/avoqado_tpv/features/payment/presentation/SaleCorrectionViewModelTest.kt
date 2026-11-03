package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableSalesRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableState
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/** La corrección sin señal: una venta que no está guardada en la terminal se dice en neutro, nunca como error. */
class SaleCorrectionViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val sales = mockk<ActionableSalesRepository>()
    private val auth = mockk<AuthRepository>()

    private fun vm(detalle: Result<com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto>): SaleCorrectionViewModel {
        every { auth.getStaffId() } returns "s1"
        every { sales.observe("s1") } returns flowOf(ActionableState(emptyList(), isOffline = true, nextCursor = null))
        coEvery { sales.detail("ver-1") } returns detalle
        return SaleCorrectionViewModel(sales, mockk<ProofOfSaleQueueRepository>(), auth, SavedStateHandle(mapOf("verificationId" to "ver-1")))
    }

    @Test fun `sin senal y sin la venta guardada dice que se abre cuando vuelva la senal, sin error`() {
        val state = vm(Result.failure(IOException("sin red"))).uiState.value

        assertThat(state.offlineNotice)
            .isEqualTo("Sin conexión: esta venta no está guardada en la terminal; ábrela cuando vuelva la señal")
        assertThat(state.error).isNull()
        assertThat(state.isLoading).isFalse()
    }

    @Test fun `con senal un rechazo del servidor sigue siendo Error al cargar la venta`() {
        val state = vm(Result.failure(Exception("HTTP 404: Not Found"))).uiState.value

        assertThat(state.error).isEqualTo("Error al cargar la venta")
        assertThat(state.offlineNotice).isNull()
    }
}
