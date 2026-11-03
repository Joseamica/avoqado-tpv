package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableItem
import com.jaac.avoqado_tpv.features.verification.data.ActionableSalesRepository
import com.jaac.avoqado_tpv.features.verification.data.ActionableState
import com.jaac.avoqado_tpv.features.verification.data.NumberCapture
import com.jaac.avoqado_tpv.features.verification.data.PhotoCapture
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.verification.data.ProofSyncResult
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.verification.presentation.canDiscardCapture
import com.jaac.avoqado_tpv.features.verification.presentation.isPhotoCapture
import com.jaac.avoqado_tpv.features.verification.presentation.queuedCaptureMessage
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleConfig
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleFeatures
import com.jaac.avoqado_tpv.features.modules.domain.model.VenueModule
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
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
    private val numeros = mutableListOf<NumberCapture>()
    private val entregada = CompletableDeferred<ProofSyncResult?>()

    private fun vm(refresh: Result<Unit> = Result.success(Unit)): PendingVerificationsViewModel {
        every { auth.getVenueId() } returns "v1"
        every { auth.getVenueSlug() } returns "tienda-a"
        every { auth.getStaffId() } returns "s1"
        every { sales.observe("s1") } returns lista
        coEvery { sales.refresh() } returns refresh
        coEvery { sales.loadMore() } returns Result.success(Unit)
        coEvery { queue.enqueuePhoto(any()) } answers { capturas += firstArg<PhotoCapture>(); "k${capturas.size}" }
        coEvery { queue.enqueueNumberOnly(any()) } answers { numeros += firstArg<NumberCapture>(); "n${numeros.size}" }
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

    // ── C7: el número con la foto y «Corregir número» ─────────────────────────

    @Test fun `C7 la foto se encola con el numero de su ranura`() = runTest {
        lista.value = ActionableState(listOf(ActionableItem(detalle("pay-1"), "pay-1", emptyList(), localOnly = false)), false, null)
        val vm = vm()

        vm.enqueuePhoto("pay-1", ProofSlot.VINCULACION, "/cache/cam.jpg", "5512345678")

        assertThat(capturas.single())
            .isEqualTo(PhotoCapture("v1", "tienda-a", "s1", "pay-1", "ver-pay-1", ProofSlot.VINCULACION, "/cache/cam.jpg", "5512345678"))
    }

    @Test fun `C7 Corregir numero encola solo el numero con la verificacion de la tarjeta y al entregarse refresca`() = runTest {
        lista.value = ActionableState(listOf(ActionableItem(detalle("pay-1"), "pay-1", emptyList(), localOnly = false)), false, null)
        val vm = vm()

        vm.correctNumber("pay-1", ProofSlot.PORTABILIDAD, "5598765432")
        entregada.complete(ProofSyncResult("n1", "APPLIED", linkingDuplicate = false, portedDuplicate = false))

        assertThat(numeros).containsExactly(NumberCapture("v1", "s1", "pay-1", "ver-pay-1", ProofSlot.PORTABILIDAD, "5598765432"))
        assertThat(capturas).isEmpty()
        coVerify(exactly = 2) { sales.refresh() }
    }

    @Test fun `C7 en una venta que el servidor aun no registra Corregir numero no encola nada`() = runTest {
        lista.value = ActionableState(
            listOf(ActionableItem(detalle("pay-2").copy(id = ""), "pay-2", emptyList(), localOnly = true)), true, null,
        )
        val vm = vm()

        vm.correctNumber("pay-2", ProofSlot.VINCULACION, "5512345678")
        vm.correctNumber("pay-desconocida", ProofSlot.VINCULACION, "5512345678")

        assertThat(numeros).isEmpty()
        assertThat(lista.value.items.single().verificationId).isNull() // la pantalla deja el botón en gris
    }

    @Test fun `C7 bajo cada foto va el numero en camino, si no el del servidor con su aviso de repetido`() {
        val card = detalle("pay-1").copy(
            isPortabilidad = true, requiredPhotos = 2, linkingNumber = "5511111111", portedNumber = "5522222222",
            alerts = listOf("PORTED_DUPLICATE"),
        ).let { ActionableItem(it, "pay-1", emptyList(), false).toPendingCard() }
        val enCamino = fila("PENDING").copy(linkingNumber = "5533333333")
        val fallida = fila("FAILED", "PERMANENT").copy(idempotencyKey = "k-f", slot = "PORTABILIDAD", portedNumber = "5544444444")

        assertThat(slotNumber(card, listOf(enCamino), ProofSlot.VINCULACION)).isEqualTo(SlotNumber("5533333333", locked = false, duplicate = false))
        assertThat(slotNumber(card, emptyList(), ProofSlot.VINCULACION)).isEqualTo(SlotNumber("5511111111", locked = false, duplicate = false))
        // Lo que ya no se sube solo no cambió el número: manda el del servidor, con el aviso de Isaac.
        assertThat(slotNumber(card, listOf(fallida), ProofSlot.PORTABILIDAD)).isEqualTo(SlotNumber("5522222222", locked = false, duplicate = true))
    }

    @Test fun `C7 un numero corregido por el back-office se muestra bloqueado y la foto lo deja como esta`() {
        val card = detalle("pay-1").copy(linkingNumber = "5511111111", linkingNumberLocked = true, alerts = listOf("LINKING_DUPLICATE"))
            .let { ActionableItem(it, "pay-1", emptyList(), false).toPendingCard() }

        assertThat(slotNumber(card, listOf(fila("PENDING").copy(linkingNumber = "5533333333")), ProofSlot.VINCULACION))
            .isEqualTo(SlotNumber("5511111111", locked = true, duplicate = false))
    }

    @Test fun `C7 el campo del numero sale con el interruptor del modulo`() = runTest {
        val modulos = MutableStateFlow<List<VenueModule>>(emptyList())
        val repo = mockk<ModulesRepository> { every { modules } returns modulos }
        vm()
        val conModulos = PendingVerificationsViewModel(sales, queue, auth, repo)
        assertThat(conModulos.linkingNumberEnabled.value).isFalse()

        modulos.value = listOf(
            VenueModule("m1", "SERIALIZED_INVENTORY", "Inventario", ModuleConfig(features = ModuleFeatures(linkingNumber = true))),
        )

        assertThat(conModulos.linkingNumberEnabled.value).isTrue()
        assertThat(PendingVerificationsViewModel(sales, queue, auth).linkingNumberEnabled.value).isFalse()
    }

    @Test fun `C7 una correccion de numero en la cola no cuenta como foto y dice lo que pasa con el numero`() {
        val numero = fila("PENDING").copy(idempotencyKey = "n", kind = "NUMBER_ONLY", localPath = null, linkingNumber = "5512345678")
        val foto = fila("PENDING")
        val card = ActionableItem(detalle("pay-1"), "pay-1", listOf(numero), false).toPendingCard()

        // Corregir el número no llena la ranura de la foto, y la miniatura en cola sigue siendo la de la foto.
        assertThat(cardProgress(card, listOf(numero), emptySet(), null)).isEqualTo(CardProgress(0, 1, complete = false))
        assertThat(cardProgress(card, listOf(numero, foto), emptySet(), null)).isEqualTo(CardProgress(1, 1, complete = true))
        assertThat(listOf(numero, foto).filter(::isPhotoCapture)).containsExactly(foto)
        val casos = listOf(
            numero to "Se subirá solo",
            numero.copy(syncStatus = "NEEDS_ATTENTION", outcome = "SALE_ALREADY_APPROVED") to "La venta ya estaba aprobada; este número no se guardó",
            numero.copy(syncStatus = "FAILED", outcome = "SALE_REJECTED") to "La venta fue rechazada; este número no se guardó",
            numero.copy(syncStatus = "NEEDS_ATTENTION", outcome = "NUMBER_DISABLED") to
                "El número no se guardó: esta función está apagada para tu organización",
            numero.copy(syncStatus = "FAILED", outcome = "PERMANENT") to "No se pudo guardar el número: corrígelo de nuevo",
        )
        casos.forEach { (row, texto) -> assertThat(queuedCaptureMessage(row)).isEqualTo(texto) }
        assertThat(casos.map { canDiscardCapture(it.first) }).containsExactly(false, true, true, true, true).inOrder()
    }
}
