package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.verification.data.PhotoCapture
import com.jaac.avoqado_tpv.features.verification.data.ProofOfSaleQueueRepository
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.verification.data.ProofSyncResult
import com.jaac.avoqado_tpv.features.verification.presentation.PROOF_SALE_QUEUED_MESSAGE
import com.jaac.avoqado_tpv.features.verification.presentation.ProofOfSaleSlots
import com.jaac.avoqado_tpv.features.verification.presentation.ProofSlotState
import com.jaac.avoqado_tpv.features.verification.presentation.proofOfSaleMessage
import com.jaac.avoqado_tpv.features.verification.presentation.proofOfSalePaymentId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * Bloque 0 en el cobro: la foto de comprobante ya no se sube desde la pantalla. [ProofOfSaleSlots] es lo que usan los
 * tres cobros (PAX production y sandbox, y la Nexgo): encola en la cola durable (C4), espera la respuesta de SU fila
 * hasta 10 s y dice la verdad por ranura. Retomar y «Nuevo Pago» no borran nada.
 *
 * La cola es un mock cuyo `awaitResult` se comporta como el real (plazo sobre un SharedFlow de resultados), así el
 * plazo de 10 s se mide con el reloj virtual y no con un `null` de mentira.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProofOfSaleBlock0Test {
    private val results = MutableSharedFlow<ProofSyncResult>(replay = 16)
    private val queue = mockk<ProofOfSaleQueueRepository>()
    private val capturas = mutableListOf<PhotoCapture>()
    private var online = true
    private var portabilidad = false

    private fun TestScope.slots(q: ProofOfSaleQueueRepository? = queue): ProofOfSaleSlots {
        var n = 0
        coEvery { queue.enqueuePhoto(any()) } answers { capturas += firstArg<PhotoCapture>(); "k${++n}" }
        coEvery { queue.awaitResult(any(), any()) } coAnswers {
            val key = firstArg<String>()
            withTimeoutOrNull(secondArg<Long>()) { results.first { it.key == key } }
        }
        return ProofOfSaleSlots(backgroundScope, q, { online }, { portabilidad })
    }

    private fun ProofOfSaleSlots.confirmar(slot: ProofSlot = ProofSlot.VINCULACION, path: String = "/cache/cam.jpg", number: String? = null) =
        confirm(path, "pay-1", slot, number, venueId = "v1", venueSlug = "tienda-a", staffId = "s1")

    private fun r(key: String, outcome: String) = ProofSyncResult(key, outcome, linkingDuplicate = false, portedDuplicate = false)

    private fun ProofOfSaleSlots.de(slot: ProofSlot = ProofSlot.VINCULACION) = states.value[slot]

    @Test fun `confirmar encola la captura completa y espera la respuesta de su fila 10 s`() = runTest {
        val s = slots()

        s.confirmar(number = "5512345678")
        runCurrent()

        assertThat(capturas.single()).isEqualTo(
            PhotoCapture("v1", "tienda-a", "s1", "pay-1", null, ProofSlot.VINCULACION, "/cache/cam.jpg", "5512345678"),
        )
        coVerify(exactly = 1) { queue.awaitResult("k1", 10_000) }
        assertThat(s.de()).isEqualTo(ProofSlotState.Saving)
        assertThat(s.saving.value).isTrue()
        assertThat(s.complete.value).isFalse()
    }

    @Test fun `APPLIED y SUPERSEDED_BY_NEWER dejan la ranura subida`() = runTest {
        val s = slots()
        s.confirmar(); runCurrent()
        results.emit(r("k1", "APPLIED")); runCurrent()
        assertThat(s.de()).isEqualTo(ProofSlotState.Uploaded)
        assertThat(s.complete.value).isTrue()
        assertThat(s.saving.value).isFalse()

        s.confirmar(); runCurrent()
        results.emit(r("k2", "SUPERSEDED_BY_NEWER")); runCurrent()
        assertThat(s.de()).isEqualTo(ProofSlotState.Uploaded)
    }

    @Test fun `sin respuesta en 10 s queda en cola con Se subira sola, y se pone al dia si la fila llega despues`() = runTest {
        val s = slots()
        s.confirmar(); runCurrent()

        advanceTimeBy(9_999); runCurrent()
        assertThat(s.de()).isEqualTo(ProofSlotState.Saving)
        advanceTimeBy(2); runCurrent()

        assertThat(s.de()).isEqualTo(ProofSlotState.Queued("Se subirá sola"))
        assertThat(s.complete.value).isTrue() // guardada en la cola cuenta como lista
        assertThat(s.saving.value).isFalse()

        results.emit(r("k1", "APPLIED")); runCurrent()
        assertThat(s.de()).isEqualTo(ProofSlotState.Uploaded)
    }

    @Test fun `sin red queda en cola de inmediato con el texto de sin conexion, sin esperar los 10 s`() = runTest {
        online = false
        val s = slots()

        s.confirmar(); runCurrent()

        assertThat(s.de()).isEqualTo(
            ProofSlotState.Queued("Sin conexión: la foto y el número se guardaron en la terminal y se subirán solos"),
        )
        assertThat(s.complete.value).isTrue()
        coVerify(exactly = 0) { queue.awaitResult(any(), 10_000) }
        // Al volver la red, la fila sube sola y la pantalla lo ve si sigue ahí.
        results.emit(r("k1", "APPLIED")); runCurrent()
        assertThat(s.de()).isEqualTo(ProofSlotState.Uploaded)
    }

    @Test fun `lo que una persona debe ver deja la ranura a la vista y la venta incompleta`() = runTest {
        val esperado = mapOf(
            "SALE_ALREADY_APPROVED" to "La venta ya estaba aprobada; esta foto no se agregó",
            "SALE_REJECTED" to "La venta fue rechazada; esta foto no se subió",
            "NUMBER_DISABLED" to "La foto se subió, pero el número no se guardó",
            "PERMANENT" to "No se pudo subir: tómala de nuevo",
        )
        val s = slots()
        esperado.entries.forEachIndexed { i, (outcome, texto) ->
            s.confirmar(); runCurrent()
            results.emit(r("k${i + 1}", outcome)); runCurrent()
            assertThat(s.de()).isEqualTo(ProofSlotState.NeedsAttention(texto))
            assertThat(s.complete.value).isFalse()
        }
    }

    @Test fun `en portabilidad la venta queda completa solo con las dos ranuras subidas o en cola`() = runTest {
        portabilidad = true
        val s = slots()
        s.confirmar(ProofSlot.VINCULACION); runCurrent()
        results.emit(r("k1", "APPLIED")); runCurrent()
        assertThat(s.complete.value).isFalse()

        s.confirmar(ProofSlot.PORTABILIDAD); runCurrent()
        advanceTimeBy(10_001); runCurrent()

        assertThat(s.de(ProofSlot.PORTABILIDAD)).isEqualTo(ProofSlotState.Queued("Se subirá sola"))
        assertThat(s.complete.value).isTrue()
        assertThat(capturas.map { it.slot }).containsExactly(ProofSlot.VINCULACION, ProofSlot.PORTABILIDAD).inOrder()
    }

    @Test fun `Retomar no borra nada - la nueva captura es otra fila y la respuesta de la vieja ya no la pinta`() = runTest {
        val s = slots()
        s.confirmar(path = "/cache/vieja.jpg"); runCurrent()
        advanceTimeBy(10_001); runCurrent()

        s.retake(ProofSlot.VINCULACION)

        assertThat(s.de()).isEqualTo(ProofSlotState.Idle)
        assertThat(s.complete.value).isFalse()
        coVerify(exactly = 0) { queue.discard(any()) }

        s.confirmar(path = "/cache/nueva.jpg"); runCurrent()
        assertThat(capturas.map { it.sourcePath }).containsExactly("/cache/vieja.jpg", "/cache/nueva.jpg").inOrder()
        results.emit(r("k1", "SUPERSEDED_BY_NEWER")); runCurrent() // la vieja: no es la de la ranura
        assertThat(s.de()).isEqualTo(ProofSlotState.Saving)
        results.emit(r("k2", "APPLIED")); runCurrent()
        assertThat(s.de()).isEqualTo(ProofSlotState.Uploaded)
        coVerify(exactly = 0) { queue.discard(any()) }
    }

    @Test fun `Nuevo Pago no borra fotos de la cola y lo que llegue despues no ensucia el cobro siguiente`() = runTest {
        val s = slots()
        s.confirmar(); runCurrent()
        advanceTimeBy(10_001); runCurrent()

        s.reset()

        assertThat(s.states.value).isEmpty()
        assertThat(s.complete.value).isFalse()
        results.emit(r("k1", "SALE_REJECTED")); runCurrent()
        assertThat(s.states.value).isEmpty()
        coVerify(exactly = 0) { queue.discard(any()) }
    }

    @Test fun `si no se pudo guardar en la terminal lo dice y no toca la foto de la camara`() = runTest {
        val camara = File.createTempFile("cam", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val s = slots()
        coEvery { queue.enqueuePhoto(any()) } throws java.io.IOException("disco lleno")

        s.confirmar(path = camara.absolutePath); runCurrent()

        assertThat(s.de()).isEqualTo(ProofSlotState.NeedsAttention("No se pudo subir: tómala de nuevo"))
        assertThat(camara.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))

        // Sin sesión (sin tienda o promotor) tampoco se finge.
        val sinSesion = slots()
        sinSesion.confirm("/cache/x.jpg", "pay-1", ProofSlot.VINCULACION, null, venueId = null, venueSlug = null, staffId = "s1")
        runCurrent()
        assertThat(sinSesion.de()).isEqualTo(ProofSlotState.NeedsAttention("No se pudo subir: tómala de nuevo"))
        camara.delete()
    }

    @Test fun `el texto bajo la foto dice el estado de cada ranura`() {
        val cola = ProofSlotState.Queued("Se subirá sola")
        assertThat(proofOfSaleMessage(emptyMap(), isPortabilidad = false)).isNull()
        assertThat(proofOfSaleMessage(mapOf(ProofSlot.VINCULACION to ProofSlotState.Uploaded), false)).isNull()
        assertThat(proofOfSaleMessage(mapOf(ProofSlot.VINCULACION to cola), false)).isEqualTo("Se subirá sola")
        assertThat(proofOfSaleMessage(mapOf(ProofSlot.VINCULACION to cola, ProofSlot.PORTABILIDAD to cola), true))
            .isEqualTo("Se subirá sola")
        assertThat(
            proofOfSaleMessage(
                mapOf(
                    ProofSlot.VINCULACION to ProofSlotState.Uploaded,
                    ProofSlot.PORTABILIDAD to ProofSlotState.NeedsAttention("No se pudo subir: tómala de nuevo"),
                ),
                true,
            ),
        ).isEqualTo("Portabilidad: No se pudo subir: tómala de nuevo")
    }

    @Test fun `en la Nexgo sin paymentId la pantalla manda a Pendientes con el texto exacto`() {
        assertThat(PROOF_SALE_QUEUED_MESSAGE)
            .isEqualTo("La venta quedó en cola; sube la foto y el número desde \"Pendientes\" cuando se registre")
    }

    @Test fun `un paymentId vacio cuenta como venta sin registrar - la camara no encola una fila que el servidor rechaza`() {
        fun recibo(id: String) = PaymentReceipt(paymentId = id, receiptUrl = "", accessKey = "", amount = BigDecimal.TEN, tipAmount = BigDecimal.ZERO)
        // El S6 de la Nexgo arma el recibo con `fila.serverPaymentId ?: ""`.
        assertThat(proofOfSalePaymentId(recibo(""))).isNull()
        assertThat(proofOfSalePaymentId(recibo("   "))).isNull()
        assertThat(proofOfSalePaymentId(null)).isNull()
        assertThat(proofOfSalePaymentId(recibo("pay-1"))).isEqualTo("pay-1")
    }
}
