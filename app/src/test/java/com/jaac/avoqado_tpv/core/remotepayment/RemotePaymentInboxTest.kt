package com.jaac.avoqado_tpv.core.remotepayment

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RemotePaymentInboxTest {

    private val dao = mockk<RemotePaymentRequestDao>(relaxed = true)
    private val inbox = RemotePaymentInbox(dao)

    private fun event(requestId: String = "req-1") = SocketEvent.TerminalPaymentRequest(
        requestId = requestId,
        amountCents = 10_000,
        tipCents = 1_000,
        rating = 5,
        skipReview = true,
        orderId = null,
        processedByStaffId = "staff-pos",
        senderDeviceName = "iPad caja",
        venueId = "venue-1",
        timestamp = "2026-09-03T14:00:00Z",
    )

    @Test
    fun `persiste request completo antes de permitir ACK y entrega`() = runTest {
        coEvery { dao.insert(any()) } returns 1L

        val decision = inbox.receive(event())

        assertThat(decision).isInstanceOf(RemotePaymentReceiveDecision.Deliver::class.java)
        coVerify(exactly = 1) {
            dao.insert(match {
                it.requestId == "req-1" &&
                    it.amountCents == 10_000L &&
                    it.tipCents == 1_000L &&
                    it.rating == 5 &&
                    it.skipReview &&
                    it.processedByStaffId == "staff-pos" &&
                    it.status == RemotePaymentRequestEntity.STATUS_RECEIVED
            })
        }
    }

    @Test
    fun `duplicado en procesamiento confirma pero nunca vuelve a lanzar SDK`() = runTest {
        coEvery { dao.insert(any()) } returns -1L
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_PROCESSING)

        val decision = inbox.receive(event())

        assertThat(decision).isEqualTo(RemotePaymentReceiveDecision.AckOnly)
    }

    @Test
    fun `duplicado resuelto reproduce el resultado durable al servidor`() = runTest {
        val finalJson = """{"requestId":"req-1","status":"success","paymentId":"pay-1"}"""
        coEvery { dao.insert(any()) } returns -1L
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(
            event(),
            status = RemotePaymentRequestEntity.STATUS_RESOLVED,
            finalResultJson = finalJson,
        )

        val decision = inbox.receive(event())

        assertThat(decision).isEqualTo(RemotePaymentReceiveDecision.ReplayResult(finalJson))
    }

    @Test
    fun `mismo requestId con otro dinero se rechaza como conflicto`() = runTest {
        coEvery { dao.insert(any()) } returns -1L
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_RECEIVED)

        val decision = inbox.receive(event().copy(amountCents = 99_999))

        assertThat(decision).isInstanceOf(RemotePaymentReceiveDecision.Reject::class.java)
    }

    @Test
    fun `resultado se guarda antes de que SocketManager pueda emitirlo`() = runTest {
        coEvery { dao.markResolved(any(), any(), any()) } returns 1

        val saved = inbox.markResolved("req-1", """{"requestId":"req-1","status":"failed"}""")

        assertThat(saved).isTrue()
        coVerify(exactly = 1) { dao.markResolved("req-1", any(), any()) }
    }
    @Test
    fun `timeout is uncertainty and must not resolve durable request`() = runTest {
        coEvery { dao.markResolved(any(), any(), any()) } returns 1
        assertThat(inbox.markResolved("req-1", """{"requestId":"req-1","status":"timeout"}""")).isFalse()
        coVerify(exactly = 0) { dao.markResolved(any(), any(), any()) }
    }


    // ═══ SONDA: el servidor pregunta por una solicitud; la bandeja contesta desde lo durable y NUNCA entrega ═══

    @Test
    fun `sonda sobre una solicitud RESUELTA reproduce el resultado durable`() = runTest {
        val finalJson = """{"requestId":"req-1","status":"cancelled","outcomeEvidence":"PRE_AUTHORIZATION"}"""
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_RESOLVED)
            .copy(finalResultJson = finalJson)
        val answer = inbox.probe("req-1", "venue-1")
        assertThat(answer.disposition).isEqualTo(RemotePaymentProbeDisposition.RESOLVED)
        assertThat(answer.finalResultJson).isEqualTo(finalJson)
        coVerify(exactly = 0) { dao.markProcessing(any(), any()) }
    }

    @Test
    fun `sonda sobre una solicitud EN PROCESO DE ESTE PROCESO contesta ACTIVE y no toca nada`() = runTest {
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
        val answer = inbox.probe("req-1", "venue-1", propiedadEnEsteProceso = true)
        assertThat(answer.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(answer.finalResultJson).isNull()
        coVerify(exactly = 0) { dao.resolveReceived(any(), any(), any()) }
    }

    @Test
    fun `P1 una fila EN PROCESO de un proceso MUERTO que nunca ejecuto deja de contestar ACTIVE para siempre`() = runTest {
        // 🔴 MEDIDO EN UNA NEXGO (21-sep): `PROCESSING` desde el 17-sep con `execution_started_at`
        // VACÍO. La app la reclamó, murió antes de ejecutarla y al arrancar no la recuperaba: la sonda
        // contestaba ACTIVE indefinidamente, el servidor conservaba la reserva y el cajero no podía
        // cobrar NI declarar. El callejón sin salida del 18-sep por otra puerta.
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
        coEvery { dao.resolverDesenlaceNegativo("req-1", any(), any()) } returns """{"requestId":"req-1","status":"cancelled","outcomeEvidence":"PRE_AUTHORIZATION"}"""

        val answer = inbox.probe("req-1", "venue-1")

        assertThat(answer.disposition).isEqualTo(RemotePaymentProbeDisposition.RECEIVED_CANCELLED)
        assertThat(answer.finalResultJson).contains("PRE_AUTHORIZATION")
    }

    @Test
    fun `P1 CONTROL una fila EN PROCESO que YA EMPEZO a ejecutar sigue siendo ACTIVE aunque el proceso no la reclame`() = runTest {
        // 🔴 El control que protege el dinero: con `executionStartedAt` puesto, el SDK pudo haber pedido
        // la tarjeta. Ahí la incertidumbre es REAL y la reserva se conserva — declarar sería mentir.
        coEvery { dao.getById("req-1") } returns
            RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
                .copy(executionStartedAt = 1_700_000_000_000L)

        val answer = inbox.probe("req-1", "venue-1")

        assertThat(answer.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        coVerify(exactly = 0) { dao.resolverDesenlaceNegativo(any(), any(), any()) }
    }

    @Test
    fun `sonda sobre una solicitud RECIBIDA y nunca reclamada la cancela DURABLEMENTE con evidencia pre-autorizacion`() = runTest {
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_RECEIVED)
        coEvery { dao.resolveReceived("req-1", any(), any()) } returns 1
        val answer = inbox.probe("req-1", "venue-1")
        assertThat(answer.disposition).isEqualTo(RemotePaymentProbeDisposition.RECEIVED_CANCELLED)
        assertThat(answer.finalResultJson).contains("\"status\":\"cancelled\"")
        assertThat(answer.finalResultJson).contains("PRE_AUTHORIZATION")
        // Nunca se entrega ni se marca en proceso: una sonda no puede iniciar un cobro.
        coVerify(exactly = 0) { dao.markProcessing(any(), any()) }
    }

    @Test
    fun `sonda sobre una solicitud RECIBIDA que otro hilo reclama en carrera no fabrica evidencia`() = runTest {
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_RECEIVED)
        coEvery { dao.resolveReceived("req-1", any(), any()) } returns 0 // perdió el CAS: ya la reclamaron
        val answer = inbox.probe("req-1", "venue-1")
        assertThat(answer.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(answer.finalResultJson).isNull()
    }

    // ═══ LÁPIDA (Codex 11-sep): NOT_FOUND es una promesa durable — «no la recibí y no la ejecutaré» ═══

    @Test
    fun `sonda sobre una solicitud que la bandeja no tiene contesta NOT_FOUND y deja una LAPIDA, nunca una solicitud entregable`() = runTest {
        coEvery { dao.getById("req-1") } returns null
        coEvery { dao.insert(any()) } returns 1L
        val answer = inbox.probe("req-1", "venue-1")
        assertThat(answer.disposition).isEqualTo(RemotePaymentProbeDisposition.NOT_FOUND)
        assertThat(answer.finalResultJson).isNull()
        coVerify(exactly = 1) {
            dao.insert(match {
                it.requestId == "req-1" && it.venueId == "venue-1" &&
                    it.status == RemotePaymentRequestEntity.STATUS_NOT_FOUND_ANSWERED &&
                    it.amountCents == 0L && it.finalResultJson == null
            })
        }
        coVerify(exactly = 0) { dao.markProcessing(any(), any()) }
        coVerify(exactly = 0) { dao.markProcessingForVenue(any(), any(), any()) }
    }

    @Test
    fun `una solicitud que llega DESPUES de haber contestado NOT_FOUND se rechaza y no se entrega`() = runTest {
        coEvery { dao.insert(any()) } returns -1L
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.tombstone("req-1", "venue-1")
        val decision = inbox.receive(event())
        assertThat(decision).isInstanceOf(RemotePaymentReceiveDecision.Reject::class.java)
        assertThat((decision as RemotePaymentReceiveDecision.Reject).reason).contains("nunca recibió")
        coVerify(exactly = 0) { dao.markProcessing(any(), any()) }
    }

    @Test
    fun `la sonda repetida sobre una lapida vuelve a contestar NOT_FOUND sin escribir otra`() = runTest {
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.tombstone("req-1", "venue-1")
        val answer = inbox.probe("req-1", "venue-1")
        assertThat(answer.disposition).isEqualTo(RemotePaymentProbeDisposition.NOT_FOUND)
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.resolveReceived(any(), any(), any()) }
    }

    @Test
    fun `carrera - si la solicitud se persiste entre la lectura y la lapida, la sonda contesta por lo que hay y nunca NOT_FOUND`() = runTest {
        coEvery { dao.getById("req-1") } returnsMany listOf(
            null,
            RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_RECEIVED),
        )
        coEvery { dao.insert(any()) } returns -1L // la lápida perdió: ya existe la solicitud real
        coEvery { dao.resolveReceived("req-1", any(), any()) } returns 1
        val answer = inbox.probe("req-1", "venue-1")
        assertThat(answer.disposition).isEqualTo(RemotePaymentProbeDisposition.RECEIVED_CANCELLED)
        assertThat(answer.finalResultJson).contains("PRE_AUTHORIZATION")
    }

    @Test
    fun `sin lapida escrita no hay NOT_FOUND - si Room falla, la sonda no contesta`() = runTest {
        coEvery { dao.getById("req-1") } returns null
        coEvery { dao.insert(any()) } throws IllegalStateException("Room no disponible")
        val error = runCatching { inbox.probe("req-1", "venue-1") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `sin venue la sonda no puede dejar lapida y no contesta`() = runTest {
        coEvery { dao.getById("req-1") } returns null
        val error = runCatching { inbox.probe("req-1", "") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `cancel remoto sobre una lapida no fabrica evidencia - contesta ACTIVE`() = runTest {
        coEvery { dao.resolveReceived("req-1", any(), any()) } returns 0
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.tombstone("req-1", "venue-1")
        val decision = inbox.cancel("req-1")
        assertThat(decision.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(decision.finalResultJson).isNull()
    }

    // ═══ B2 (22-oct): `executionAlive` / `closedForExecution` — cuándo se le pregunta a la libreta y cuándo no ═══

    @Test
    fun `P1 con propiedad y cero intentos ni la sonda ni el cancel le preguntan a la libreta aunque el CAS del cancel falle`() = runTest {
        var preguntas = 0
        val bandeja = RemotePaymentInbox(dao, laTerminalRechazariaUnaVentaNueva = { false }) { preguntas++; true }
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
        coEvery { dao.resolveReceived("req-1", any(), any()) } returns 0
        coEvery { dao.contarIntentosDeSolicitud("req-1") } returns 0
        coEvery { dao.contarIntentosBloqueadores("req-1") } returns 0
        coEvery { dao.cancelarTrasReclamar("req-1", any(), any(), true) } throws IllegalStateException("Room no disponible")

        val sonda = bandeja.probe("req-1", "venue-1", propiedadEnEsteProceso = true)
        val cancel = bandeja.cancel("req-1", propiedadEnEsteProceso = true)

        // La pantalla espera «Tarjeta»: con cero intentos la libreta cerraría sin ver la pantalla.
        assertThat(sonda).isEqualTo(RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE, executionAlive = true, closedForExecution = false))
        assertThat(cancel).isEqualTo(RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACTIVE, executionAlive = true, closedForExecution = false))
        assertThat(preguntas).isEqualTo(0)
        coVerify(exactly = 0) { dao.resolverDesenlaceNegativo(any(), any(), any()) }
    }

    @Test
    fun `P1 sin cablear la bandeja nunca certifica el cese`() = runTest {
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
        coEvery { dao.resolveReceived("req-1", any(), any()) } returns 0
        coEvery { dao.contarIntentosDeSolicitud("req-1") } returns 1
        coEvery { dao.resolverDesenlaceNegativo("req-1", any(), any()) } returns null // hay un intento en duda
        coEvery { dao.cancelarTrasReclamar("req-1", any(), any(), any()) } returns false

        val sonda = inbox.probe("req-1", "venue-1")
        val cancel = inbox.cancel("req-1")

        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(sonda.executionAlive).isTrue()
        assertThat(sonda.closedForExecution).isFalse()
        assertThat(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(cancel.executionAlive).isTrue()
        assertThat(cancel.closedForExecution).isFalse()
    }

    @Test
    fun `P2 ya cerrada para ejecucion pero la libreta hoy no certifica - contesta cerrada y viva, nunca sin ejecucion viva`() = runTest {
        // P. ej. la terminal pasó a app-a-app después del cierre, o la relectura encontró algo vivo: el cierre es un hecho
        // durable (la cerca rige), pero «sin ejecución viva» sólo lo dice la libreta en ESTA llamada.
        val bandeja = RemotePaymentInbox(dao, laTerminalRechazariaUnaVentaNueva = { false }) { false }
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
            .copy(executionClosedAt = 1_700_000_000_000L)
        coEvery { dao.resolveReceived("req-1", any(), any()) } returns 0
        coEvery { dao.contarIntentosDeSolicitud("req-1") } returns 1
        coEvery { dao.resolverDesenlaceNegativo("req-1", any(), any()) } returns null
        coEvery { dao.cancelarTrasReclamar("req-1", any(), any(), any()) } returns false

        val sonda = bandeja.probe("req-1", "venue-1")
        val cancel = bandeja.cancel("req-1")

        assertThat(sonda).isEqualTo(RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE, executionAlive = true, closedForExecution = true))
        assertThat(cancel).isEqualTo(RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACTIVE, executionAlive = true, closedForExecution = true))
    }

    @Test
    fun `P1 dueno con el banco rechazando y la pantalla ofreciendo Reintentar - el cancel que no pudo escribir dice ejecucion viva y no le pregunta a la libreta`() = runTest {
        // Fix round 0 (duda 2): con propiedad y sólo intentos que la pantalla puede dejar atrás (el rechazo final del banco, un
        // descarte), si el CAS del cancel no aterriza la respuesta es la del caso 1 — nunca el certificado de cese.
        var preguntas = 0
        val bandeja = RemotePaymentInbox(dao, laTerminalRechazariaUnaVentaNueva = { false }) { preguntas++; true }
        coEvery { dao.getById("req-1") } returns RemotePaymentRequestEntity.from(event(), status = RemotePaymentRequestEntity.STATUS_PROCESSING)
        coEvery { dao.resolveReceived("req-1", any(), any()) } returns 0
        coEvery { dao.contarIntentosDeSolicitud("req-1") } returns 1 // el rechazo del banco
        coEvery { dao.contarIntentosBloqueadores("req-1") } returns 0 // DESCARTADA con host_approved = 0, sin dinero
        coEvery { dao.cancelarTrasReclamar("req-1", any(), any(), true) } throws IllegalStateException("Room no disponible")

        val cancel = bandeja.cancel("req-1", propiedadEnEsteProceso = true)
        val sonda = bandeja.probe("req-1", "venue-1", propiedadEnEsteProceso = true)

        assertThat(cancel).isEqualTo(RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACTIVE, executionAlive = true, closedForExecution = false))
        assertThat(sonda).isEqualTo(RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE, executionAlive = true, closedForExecution = false))
        assertThat(preguntas).isEqualTo(0)
        coVerify(exactly = 0) { dao.resolverDesenlaceNegativo(any(), any(), any()) }
    }
}
