package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableItem
import com.jaac.avoqado_tpv.features.verification.data.ActionableSalesRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableState
import com.jaac.avoqado_tpv.features.verification.data.PhotoCapture
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.verification.data.ProofSyncResult
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.verification.presentation.canDiscardCapture
import com.jaac.avoqado_tpv.features.verification.presentation.queuedCaptureMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * «Pendientes» encola en vez de subir, y dice la verdad de cada captura. La lista y la cola son mocks: lo que guarda y
 * quita tarjetas lo prueba `ActionableSalesRepositoryTest` contra Room y HTTP reales.
 */
class PendingVerificationsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val sales = mockk<ActionableSalesRepository>()
    private val queue = mockk<ProofOfSaleQueueRepository>()
    private val auth = mockk<AuthRepository>()
    private val lista = MutableStateFlow(ActionableState(emptyList(), isOffline = false, nextCursor = null))
    private val capturas = mutableListOf<PhotoCapture>()
    private val entregada = CompletableDeferred<ProofSyncResult?>()

    private fun vm(refresh: Result<Unit> = Result.success(Unit)): PendingVerificationsViewModel {
        every { auth.getVenueId() } returns "v1"
        every { auth.getVenueSlug() } returns "tienda-a"
        every { auth.getStaffId() } returns "s1"
        every { sales.observe("s1") } returns lista
        coEvery { sales.refresh() } returns refresh
        coEvery { sales.loadMore() } returns Result.success(Unit)
        coEvery { queue.enqueuePhoto(any()) } answers { capturas += firstArg<PhotoCapture>(); "k${capturas.size}" }
        coEvery { queue.awaitResult(any(), any()) } coAnswers { entregada.await() }
        coEvery { queue.discard(any()) } returns Unit
        return PendingVerificationsViewModel(sales, queue, auth)
    }

    private fun detalle(payment: String) = VerificationDetailDto(
        id = "ver-$payment", paymentId = payment, amount = 149.0, orderNumber = null, date = "2026-11-01T10:00:00.000Z",
        serialNumbers = listOf("8952"), isPortabilidad = false, photos = emptyList(), requiredPhotos = 1, status = "PENDING",
    )

    private fun fila(status: String, outcome: String? = null) = ProofOfSaleQueueEntity(
        idempotencyKey = "k-$status-$outcome", kind = "PHOTO", venueId = "v1", staffId = "s1", paymentId = "pay-1", verificationId = null,
        slot = "VINCULACION", localPath = "/files/proof_of_sale/k.jpg", linkingNumber = null, portedNumber = null, capturedAt = 1L,
        captureSeq = 1L, captureDeviceId = "d", syncStatus = status, outcome = outcome, createdAt = 1L,
    )

    @Test fun `tomar una foto la encola con la tienda de la sesion y la verificacion de su tarjeta, sin subir nada`() = runTest {
        lista.value = ActionableState(
            listOf(
                ActionableItem(detalle("pay-1"), "pay-1", emptyList(), localOnly = false),
                ActionableItem(detalle("pay-2").copy(id = ""), "pay-2", emptyList(), localOnly = true),
            ),
            isOffline = true, nextCursor = null,
        )
        val vm = vm() // sin VerificationUploadManager en el constructor: la pantalla ya no puede subir

        vm.enqueuePhoto("pay-1", ProofSlot.VINCULACION, "/cache/cam-1.jpg")
        vm.enqueuePhoto("pay-2", ProofSlot.PORTABILIDAD, "/cache/cam-2.jpg")

        assertThat(capturas).containsExactly(
            PhotoCapture("v1", "tienda-a", "s1", "pay-1", "ver-pay-1", ProofSlot.VINCULACION, "/cache/cam-1.jpg", null),
            PhotoCapture("v1", "tienda-a", "s1", "pay-2", null, ProofSlot.PORTABILIDAD, "/cache/cam-2.jpg", null),
        ).inOrder()
        assertThat(vm.savingPaymentId.value).isNull()
        assertThat(vm.error.value).isNull()
    }

    @Test fun `cada captura dice su estado y Descartar sale en las cuatro que ya no se suben solas`() {
        val casos = listOf(
            fila("PENDING") to "Se subirá sola",
            fila("SYNCING") to "Se subirá sola",
            fila("NEEDS_ATTENTION", "SALE_ALREADY_APPROVED") to "La venta ya estaba aprobada; esta foto no se agregó",
            fila("FAILED", "SALE_REJECTED") to "La venta fue rechazada; esta foto no se subió",
            fila("NEEDS_ATTENTION", "NUMBER_DISABLED") to "La foto se subió, pero el número no se guardó",
            fila("FAILED", "PERMANENT") to "No se pudo subir: tómala de nuevo",
        )
        casos.forEach { (row, texto) -> assertThat(queuedCaptureMessage(row)).isEqualTo(texto) }
        assertThat(casos.map { canDiscardCapture(it.first) }).containsExactly(false, false, true, true, true, true).inOrder()
    }

    @Test fun `Descartar borra solo esa captura de la cola`() = runTest {
        val vm = vm()

        vm.discard("k-9")

        coVerify(exactly = 1) { queue.discard("k-9") }
    }

    @Test fun `Cargar mas pide la siguiente pagina`() = runTest {
        val vm = vm()

        vm.loadMore()

        coVerify(exactly = 1) { sales.loadMore() }
    }

    @Test fun `al abrir refresca y, cuando la foto se entrega, vuelve a pedir la lista para traer la foto del servidor`() = runTest {
        lista.value = ActionableState(listOf(ActionableItem(detalle("pay-1"), "pay-1", emptyList(), false)), false, null)
        val vm = vm()
        coVerify(exactly = 1) { sales.refresh() }

        vm.enqueuePhoto("pay-1", ProofSlot.VINCULACION, "/cache/cam.jpg")
        entregada.complete(ProofSyncResult("k1", "APPLIED", linkingDuplicate = false, portedDuplicate = false))

        coVerify(exactly = 2) { sales.refresh() }
    }

    @Test fun `sin senal no hay aviso de error porque la pantalla ya dice que es la lista guardada`() = runTest {
        val vm = vm(refresh = Result.failure(IOException("sin red")))

        assertThat(vm.error.value).isNull()
    }

    @Test fun `un rechazo del servidor si se avisa`() = runTest {
        val vm = vm(refresh = Result.failure(BackendHttpException(500, "boom")))

        assertThat(vm.error.value).isEqualTo("No se pudo actualizar la lista")
    }

    @Test fun `sin sesion no encola y lo dice`() = runTest {
        val vm = vm()
        every { auth.getStaffId() } returns null

        vm.enqueuePhoto("pay-1", ProofSlot.VINCULACION, "/cache/cam.jpg")

        assertThat(capturas).isEmpty()
        assertThat(vm.error.value).isEqualTo("No se pudo subir: tómala de nuevo")
    }

    @Test fun `una tarjeta de una venta con solo capturas en la cola arma sus ranuras de esas`() {
        val item = ActionableItem(null, "pay-1", listOf(fila("PENDING").copy(slot = "PORTABILIDAD", createdAt = 7L)), localOnly = true)

        val card = item.toPendingCard()

        assertThat(card.paymentId).isEqualTo("pay-1")
        assertThat(card.isPortabilidad).isTrue()
        assertThat(card.requiredPhotos).isEqualTo(2)
        assertThat(card.id).isEmpty()
    }

    @Test fun `una venta en Revisar con sus dos fotos no se pinta completa y trae los motivos del back-office`() {
        val revisar = detalle("pay-r").copy(
            isPortabilidad = true, requiredPhotos = 2, photos = listOf("https://fb/v.jpg", "https://fb/p.jpg"), status = "FAILED",
            rejectionReasons = listOf("REVIEW_ILLEGIBLE_IMAGES", "REVIEW_DUPLICATE_VINCULACION"), reviewNotes = "La INE no se lee",
        )
        val item = ActionableItem(revisar, "pay-r", emptyList(), localOnly = false)

        val review = item.review()
        val progreso = cardProgress(item.toPendingCard(), item.queued, item.deliveredSlots, review)

        assertThat(review).isEqualTo(CardReview(listOf("Imágenes ilegibles", "# de vinculación duplicada"), "La INE no se lee"))
        assertThat(REVIEW_CHIP_TEXT).isEqualTo("Revisar")
        assertThat(progreso).isEqualTo(CardProgress(filled = 2, required = 2, complete = false))
    }

    @Test fun `una venta pendiente con su foto si va completa y no trae motivos`() {
        val item = ActionableItem(detalle("pay-1").copy(photos = listOf("https://fb/v.jpg")), "pay-1", emptyList(), localOnly = false)

        assertThat(item.review()).isNull()
        assertThat(cardProgress(item.toPendingCard(), emptyList(), emptySet(), null)).isEqualTo(CardProgress(1, 1, complete = true))
        // Notas en blanco y sin motivos: la tarjeta en «Revisar» dice que vuelva a tomar las fotos.
        val sinMotivos = ActionableItem(detalle("pay-2").copy(status = "FAILED", reviewNotes = "  "), "pay-2", emptyList(), false)
        assertThat(sinMotivos.review()).isEqualTo(CardReview(emptyList(), null))
    }
}
