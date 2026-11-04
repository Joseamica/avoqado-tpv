package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableSalesRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableState
import com.jaac.avoqado_tpv.features.verification.data.NumberCapture
import com.jaac.avoqado_tpv.features.verification.data.PhotoCapture
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val queue = mockk<ProofOfSaleQueueRepository>()

    private fun vm(detalle: Result<com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto>): SaleCorrectionViewModel {
        every { auth.getStaffId() } returns "s1"
        every { sales.observe("s1") } returns flowOf(ActionableState(emptyList(), isOffline = true, nextCursor = null))
        coEvery { sales.detail("ver-1") } returns detalle
        return SaleCorrectionViewModel(sales, queue, auth, SavedStateHandle(mapOf("verificationId" to "ver-1")))
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

    @Test fun `C7 la foto lleva su numero y Corregir numero encola solo el numero de esta verificacion`() {
        every { auth.getVenueId() } returns "v1"
        every { auth.getVenueSlug() } returns "tienda-a"
        coEvery { queue.enqueuePhoto(any()) } returns "k1"
        coEvery { queue.enqueueNumberOnly(any()) } returns "n1"
        coEvery { queue.awaitResult(any(), any()) } returns null
        val detalle = com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto(
            id = "ver-1", paymentId = "pay-1", amount = 149.0, orderNumber = null, date = "2026-11-01T10:00:00.000Z",
            serialNumbers = listOf("8952"), isPortabilidad = true, photos = emptyList(), requiredPhotos = 2, status = "FAILED",
        )
        val vm = vm(Result.success(detalle))

        vm.enqueuePhoto(ProofSlot.VINCULACION, "/cache/cam.jpg", "5512345678")
        vm.correctNumber(ProofSlot.PORTABILIDAD, "551234567")

        coVerify(exactly = 1) {
            queue.enqueuePhoto(PhotoCapture("v1", "tienda-a", "s1", "pay-1", "ver-1", ProofSlot.VINCULACION, "/cache/cam.jpg", "5512345678"))
        }
        coVerify(exactly = 1) { queue.enqueueNumberOnly(NumberCapture("v1", "s1", "pay-1", "ver-1", ProofSlot.PORTABILIDAD, "551234567")) }
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test fun `si la terminal no pudo guardar la foto lo dice en neutro, sin tronar, y se puede intentar otra vez`() {
        every { auth.getVenueId() } returns "v1"
        every { auth.getVenueSlug() } returns "tienda-a"
        coEvery { queue.enqueuePhoto(any()) } throws IOException("disco lleno")
        val detalle = com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto(
            id = "ver-1", paymentId = "pay-1", amount = 149.0, orderNumber = null, date = "2026-11-01T10:00:00.000Z",
            serialNumbers = listOf("8952"), isPortabilidad = false, photos = emptyList(), requiredPhotos = 1, status = "FAILED",
        )
        val vm = vm(Result.success(detalle))

        vm.enqueuePhoto(ProofSlot.VINCULACION, "/cache/cam.jpg")

        assertThat(vm.uiState.value.error).isEqualTo("No se pudo guardar la foto en la terminal. Intenta de nuevo.")
        assertThat(vm.uiState.value.isUploading).isFalse()
    }
}
