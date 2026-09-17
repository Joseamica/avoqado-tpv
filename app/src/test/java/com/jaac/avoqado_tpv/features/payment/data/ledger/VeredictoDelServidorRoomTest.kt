package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity
import com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checkpoint 2 (diseño v3, E1–E4): UNA operación durable de conciliación con el servidor — libreta y bandeja en una
 * transacción — sobre Room real. Cada regla de [PaymentAttemptDao.aplicarVeredictoDelServidor] tiene aquí su caso, y los
 * cuatro cambios de la tercera revisión de Codex (reaplicar la segunda captura con su ganador durable · promoción validada
 * no final → final sin degradar · el ganador por REST exige la solicitud ligada · la cola no consume sin veredicto durable —
 * este último en `PaymentSyncWorkerTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class VeredictoDelServidorRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var ledger: PaymentAttemptLedger
    private val now = 1_700_000_000_000L
    private val venue = "venue-1"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
    }

    @After fun tearDown() { db.close() }

    private suspend fun fila(
        attemptId: String, state: String, requestId: String? = "req-1", hostApproved: Boolean? = true,
        amount: Long = 10000, tip: Long = 500, legacy: Boolean = false, processor: String = "ANGELPAY", kind: String = "SALE",
        venueId: String = venue,
    ) = dao.insert(
        PaymentAttemptEntity(
            attemptId = attemptId, venueId = venueId, processor = processor, kind = kind, state = state,
            amountCents = amount, tipCents = tip, recordingRoute = "FAST",
            paymentContextJson = """{"venueId":"$venueId","terminalPaymentRequestId":"$requestId","orderId":"o1"}""",
            hostApproved = hostApproved, createdAt = now - 60_000, updatedAt = now - 60_000, legacyShadow = legacy,
            terminalPaymentRequestId = requestId,
        ),
    )

    private suspend fun bandeja(requestId: String = "req-1", status: String = RemotePaymentRequestEntity.STATUS_PROCESSING, finalJson: String? = null) =
        db.remotePaymentRequestDao().insert(
            RemotePaymentRequestEntity(
                requestId = requestId, venueId = venue, amountCents = 10000, tipCents = 500, rating = null, skipReview = true,
                orderId = "o1", processedByStaffId = null, senderDeviceName = null, sourceTimestamp = "2026-09-16T00:00:00Z",
                status = status, finalResultJson = finalJson, createdAt = now - 60_000, updatedAt = now - 60_000,
            ),
        )

    private fun s5(attemptId: String, paymentId: String = "pay-1", amount: Long = 10000, tip: Long = 500, requestId: String = "req-1") =
        VeredictoDeIntento.desdeAvisoS5(venue, requestId, attemptId, paymentId, "webhook", amount, tip)

    private fun s6(attemptId: String, outcome: String, paymentId: String = "pay-1", isWinner: Boolean = true, winner: String? = null,
                   amount: Long = 10000, tip: Long = 500, via: String = "webhook") =
        VeredictoDeIntento.desdeConsultaS6(
            venue, attemptId,
            TerminalAttemptStatusResponse(
                success = true, attemptId = attemptId, requestId = "req-1",
                attempt = TerminalAttemptResultDto(
                    attemptId = attemptId, outcome = outcome, paymentId = paymentId, paymentStatus = if (outcome == "RECORDED") "COMPLETED" else "PENDING",
                    recordedVia = via, amountCents = amount, tipCents = tip, isWinner = isWinner, winnerPaymentId = winner,
                ),
            ),
        )!!

    @Test fun `E1 RECORDED con los mismos montos desde un estado sin SDK dentro pasa a REGISTRADO y resuelve la bandeja con el ganador`() = runTest {
        fila("a1", "HOST_RESPONDIO"); bandeja()
        val r = dao.aplicarVeredictoDelServidor(s5("a1"), now)
        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.APLICADO)
        assertThat(r.transiciono).isTrue()
        assertThat(r.contradiccion).isFalse()
        val guardada = dao.getById("a1")!!
        assertThat(guardada.state).isEqualTo("REGISTRADO")
        assertThat(guardada.serverPaymentId).isEqualTo("pay-1")
        assertThat(guardada.serverOutcome).isEqualTo("RECORDED")
        assertThat(guardada.serverRecordedVia).isEqualTo("webhook")
        assertThat(guardada.serverWinnerPaymentId).isEqualTo("pay-1")
        assertThat(guardada.serverVerdictAt).isEqualTo(now)
        val b = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(b.status).isEqualTo("RESOLVED")
        assertThat(JSONObject(b.finalResultJson!!).optString("status")).isEqualTo("success")
        assertThat(JSONObject(b.finalResultJson!!).optString("paymentId")).isEqualTo("pay-1")
        assertThat(b.finalEmittedAt).isEqualTo(now)
        assertThat(r.bandejaResueltaJson).isEqualTo(b.finalResultJson)
        // Mismo Payment, mismos datos: idempotente por identidad Y datos, y la bandeja no cambia.
        val otraVez = dao.aplicarVeredictoDelServidor(s5("a1"), now + 1)
        assertThat(otraVez.decision).isEqualTo(ResultadoDelVeredicto.Decision.APLICADO)
        assertThat(otraVez.transiciono).isFalse()
        assertThat(otraVez.bandejaResueltaJson).isNull()
        assertThat(dao.getById("a1")!!.serverVerdictAt).isEqualTo(now) // conserva la PRIMERA vez
    }

    @Test fun `con el SDK dentro la evidencia se GUARDA sin liberar y se REAPLICA sin red cuando el callback vuelve incierto (E2)`() = runTest {
        fila("a2", "AUTORIZANDO", hostApproved = null); bandeja()
        val r = dao.aplicarVeredictoDelServidor(s5("a2"), now)
        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR)
        assertThat(dao.getById("a2")!!.state).isEqualTo("AUTORIZANDO")
        assertThat(dao.getById("a2")!!.serverOutcome).isEqualTo("RECORDED")
        // La bandeja SÍ se resuelve: el ganador está acreditado (S5 sólo se emite cuando ese Payment cerró la solicitud).
        assertThat(db.remotePaymentRequestDao().getById("req-1")!!.status).isEqualTo("RESOLVED")
        // El SDK vuelve INCIERTO ⇒ INDETERMINADO con motivo (callback vivo, no reloj).
        ledger.markIndeterminate("a2", "AngelPay sin veredicto")
        assertThat(dao.getById("a2")!!.state).isEqualTo("INDETERMINADO")
        assertThat(dao.veredictosPendientesDeAplicar(venue).map { it.attemptId }).containsExactly("a2")
        val re = dao.reaplicarVeredictoGuardado("a2", now + 5)!!
        assertThat(re.decision).isEqualTo(ResultadoDelVeredicto.Decision.APLICADO)
        assertThat(re.transiciono).isTrue()
        assertThat(dao.getById("a2")!!.state).isEqualTo("REGISTRADO")
        assertThat(dao.veredictosPendientesDeAplicar(venue)).isEmpty()
    }

    @Test fun `una cuarentena por reloj tambien se libera con un veredicto final aprobado (la misma prueba que hoy da la cadena historial a REST)`() = runTest {
        fila("a3", "INDETERMINADO", hostApproved = null)
        dao.casWithError("a3", listOf("INDETERMINADO"), "INDETERMINADO", now, PaymentAttemptEntity.CUARENTENA_POR_ANTIGUEDAD)
        val r = dao.aplicarVeredictoDelServidor(s5("a3"), now)
        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.APLICADO)
        assertThat(dao.getById("a3")!!.state).isEqualTo("REGISTRADO")
    }

    @Test fun `montos distintos guardan la evidencia como CONTRADICCION sin liberar`() = runTest {
        fila("a4", "HOST_RESPONDIO"); bandeja()
        val r = dao.aplicarVeredictoDelServidor(s5("a4", amount = 12000), now)
        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR)
        assertThat(r.contradiccion).isTrue()
        assertThat(dao.getById("a4")!!.state).isEqualTo("HOST_RESPONDIO")
        assertThat(dao.getById("a4")!!.serverAmountCents).isEqualTo(12000)
        assertThat(dao.esContradiccion("a4")).isTrue()
    }

    @Test fun `dinero acreditado sobre una DESCARTADA o un rechazo del host es contradiccion, veta negativos y sobrevive a la poda`() = runTest {
        fila("a5", "DESCARTADA", hostApproved = null); fila("a6", "HOST_RESPONDIO", hostApproved = false, requestId = "req-2")
        bandeja(); bandeja("req-2")
        val r5 = dao.aplicarVeredictoDelServidor(s5("a5"), now)
        val r6 = dao.aplicarVeredictoDelServidor(s5("a6", requestId = "req-2", paymentId = "pay-6"), now)
        assertThat(r5.contradiccion).isTrue(); assertThat(r6.contradiccion).isTrue()
        assertThat(r5.transiciono).isFalse(); assertThat(r6.transiciono).isFalse()
        assertThat(dao.getById("a5")!!.state).isEqualTo("DESCARTADA")
        assertThat(dao.getById("a6")!!.state).isEqualTo("HOST_RESPONDIO")
        // H.3: la DESCARTADA con dinero del servidor bloquea cualquier negativo de su solicitud.
        assertThat(db.remotePaymentRequestDao().contarIntentosBloqueadores("req-1")).isEqualTo(1)
        // Poda: una DESCARTADA vieja normal se va; la contradicción se queda.
        fila("a7", "DESCARTADA", hostApproved = null, requestId = "req-3")
        dao.pruneTerminalOlderThan(venue, now + 8L * 24 * 3600_000)
        assertThat(dao.getById("a7")).isNull()
        assertThat(dao.getById("a5")).isNotNull()
    }

    @Test fun `sin veredicto del servidor (columnas NULL) la fila NO es contradiccion — sigue en el aviso de pendientes y la poda y el cierre la alcanzan`() = runTest {
        // SQL trivalente: `NOT (NULL IN (...))` es NULL y dejaba fuera del aviso y de la poda a TODA fila sin veredicto.
        fila("n1", "INDETERMINADO", hostApproved = null); fila("n2", "DESCARTADA", hostApproved = null, requestId = "req-2")
        fila("n3", "REGISTRADO", requestId = "req-3")
        assertThat(dao.esContradiccion("n1")).isFalse()
        assertThat(dao.esContradiccion("n2")).isFalse()
        val pendientes = db.remotePaymentRequestDao().observePendingObligations(venue).first()
        assertThat(pendientes).hasSize(1)
        assertThat(pendientes.single().contradiccion).isEqualTo(0)
        assertThat(dao.closeRecordedOlderThan(venue, now + 2L * 24 * 3600_000, now + 2L * 24 * 3600_000)).isEqualTo(1)
        assertThat(dao.getById("n3")!!.state).isEqualTo("CERRADA")
        assertThat(dao.pruneTerminalOlderThan(venue, now + 8L * 24 * 3600_000)).isEqualTo(2)
        assertThat(dao.getById("n1")).isNotNull()
        // Y un RECORDED cuyo host NO contestó (host_approved NULL) tampoco es contradicción por sí solo (igual que la liberación).
        fila("n4", "INDETERMINADO", hostApproved = null, requestId = "req-4"); bandeja("req-4")
        assertThat(dao.aplicarVeredictoDelServidor(s5("n4", paymentId = "pay-4", requestId = "req-4"), now).contradiccion).isFalse()
    }

    @Test fun `segunda captura con ganador acreditado pasa a REGISTRADO, resuelve la bandeja con el GANADOR y nunca se cierra ni se poda`() = runTest {
        fila("b1", "AUTORIZADO"); bandeja()
        val r = dao.aplicarVeredictoDelServidor(s6("b1", "SECOND_CAPTURE_EVIDENCE", paymentId = "pay-b", isWinner = false, winner = "pay-ganador"), now)
        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.APLICADO)
        assertThat(r.transiciono).isTrue()
        assertThat(r.contradiccion).isTrue()
        assertThat(dao.getById("b1")!!.serverWinnerPaymentId).isEqualTo("pay-ganador")
        assertThat(JSONObject(db.remotePaymentRequestDao().getById("req-1")!!.finalResultJson!!).optString("paymentId")).isEqualTo("pay-ganador")
        dao.closeRecordedOlderThan(venue, now + 2L * 24 * 3600_000, now + 2L * 24 * 3600_000)
        assertThat(dao.getById("b1")!!.state).isEqualTo("REGISTRADO")
        dao.pruneTerminalOlderThan(venue, now + 8L * 24 * 3600_000)
        assertThat(dao.getById("b1")).isNotNull()
    }

    @Test fun `segunda captura guardada con el SDK dentro se reaplica con su ganador durable tras el callback incierto (Codex v3 cambio 1)`() = runTest {
        fila("b2", "AUTORIZANDO", hostApproved = null); bandeja()
        dao.aplicarVeredictoDelServidor(s6("b2", "SECOND_CAPTURE_EVIDENCE", paymentId = "pay-b2", isWinner = false, winner = "pay-ganador"), now)
        assertThat(dao.getById("b2")!!.state).isEqualTo("AUTORIZANDO")
        ledger.markIndeterminate("b2", "AngelPay sin veredicto")
        assertThat(dao.veredictosPendientesDeAplicar(venue).map { it.attemptId }).containsExactly("b2")
        val re = dao.reaplicarVeredictoGuardado("b2", now + 5)!!
        assertThat(re.transiciono).isTrue()
        assertThat(dao.getById("b2")!!.state).isEqualTo("REGISTRADO")
    }

    @Test fun `colision sin ganador guarda la evidencia y la bandeja CONSERVA su obligacion · la promocion a RECORDED del mismo Payment se acepta (Codex v3 cambio 2)`() = runTest {
        fila("c1", "HOST_RESPONDIO"); bandeja()
        val r = dao.aplicarVeredictoDelServidor(s6("c1", "REFERENCE_COLLISION_EVIDENCE", paymentId = "pay-c", isWinner = false), now)
        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR)
        assertThat(r.contradiccion).isTrue()
        assertThat(db.remotePaymentRequestDao().getById("req-1")!!.status).isEqualTo("PROCESSING")
        assertThat(dao.veredictosPendientesDeAplicar(venue)).isEmpty() // no es final: se RECONSULTA, no se reaplica
        // Estampada con count=1 ⇒ espaciado 20 min: a los 11 min NO le toca; a los 21 sí.
        assertThat(dao.candidatasDeConsultaAlServidor(venue, now, now + 11 * 60_000, 10 * 60_000, 24 * 3600_000)).isEmpty()
        assertThat(dao.candidatasDeConsultaAlServidor(venue, now, now + 21 * 60_000, 10 * 60_000, 24 * 3600_000).map { it.attemptId }).containsExactly("c1")
        // El servidor concilió: el MISMO Payment ahora es RECORDED y ganador.
        val promo = dao.aplicarVeredictoDelServidor(s6("c1", "RECORDED", paymentId = "pay-c", isWinner = true), now + 1)
        assertThat(promo.decision).isEqualTo(ResultadoDelVeredicto.Decision.APLICADO)
        assertThat(promo.transiciono).isTrue()
        assertThat(db.remotePaymentRequestDao().getById("req-1")!!.status).isEqualTo("RESOLVED")
        assertThat(dao.esContradiccion("c1")).isFalse()
        // Una respuesta ANTIGUA (otra vez colisión) no degrada el final.
        val vieja = dao.aplicarVeredictoDelServidor(s6("c1", "REFERENCE_COLLISION_EVIDENCE", paymentId = "pay-c", isWinner = false), now + 2)
        assertThat(vieja.decision).isEqualTo(ResultadoDelVeredicto.Decision.APLICADO)
        assertThat(dao.getById("c1")!!.serverOutcome).isEqualTo("RECORDED")
    }

    @Test fun `otro Payment o el mismo Payment con otros datos se rechazan sin pisar la evidencia`() = runTest {
        fila("d1", "HOST_RESPONDIO"); bandeja()
        dao.aplicarVeredictoDelServidor(s5("d1"), now)
        val otro = dao.aplicarVeredictoDelServidor(s5("d1", paymentId = "pay-otro"), now + 1)
        assertThat(otro.decision).isEqualTo(ResultadoDelVeredicto.Decision.RECHAZADO_OTRO_PAYMENT)
        assertThat(dao.getById("d1")!!.serverPaymentId).isEqualTo("pay-1")
        val distintos = dao.aplicarVeredictoDelServidor(s5("d1", amount = 999), now + 2)
        assertThat(distintos.decision).isEqualTo(ResultadoDelVeredicto.Decision.RECHAZADO_DATOS_DISTINTOS)
        assertThat(dao.getById("d1")!!.serverAmountCents).isEqualTo(10000)
        assertThat(dao.getById("d1")!!.serverCheckCount).isEqualTo(3) // cada intento estampa la consulta
    }

    @Test fun `pertenencia antes de escribir — otro venue, otra solicitud, heredada, otro procesador o devolucion no se tocan`() = runTest {
        fila("e1", "HOST_RESPONDIO"); fila("e2", "HOST_RESPONDIO", legacy = true, requestId = "req-e2")
        fila("e3", "HOST_RESPONDIO", processor = "BLUMON", requestId = "req-e3"); fila("e4", "HOST_RESPONDIO", kind = "REFUND", requestId = "req-e4")
        assertThat(dao.aplicarVeredictoDelServidor(s5("e1").copy(venueId = "otro-venue"), now).decision).isEqualTo(ResultadoDelVeredicto.Decision.RECHAZADO_PERTENENCIA)
        assertThat(dao.aplicarVeredictoDelServidor(s5("e1", requestId = "req-ajena"), now).decision).isEqualTo(ResultadoDelVeredicto.Decision.RECHAZADO_PERTENENCIA)
        // Codex (código, P1-2/P1-6): heredada, otro procesador o devolución NO son un rechazo — están FUERA del checkpoint
        // (la cola y la recuperación por aprobación siguen su camino anterior); venue/solicitud ajenos SÍ son un rechazo.
        assertThat(dao.aplicarVeredictoDelServidor(s5("e2", requestId = "req-e2"), now).decision).isEqualTo(ResultadoDelVeredicto.Decision.FUERA_DE_ALCANCE)
        assertThat(dao.aplicarVeredictoDelServidor(s5("e3", requestId = "req-e3"), now).decision).isEqualTo(ResultadoDelVeredicto.Decision.FUERA_DE_ALCANCE)
        assertThat(dao.aplicarVeredictoDelServidor(s5("e4", requestId = "req-e4"), now).decision).isEqualTo(ResultadoDelVeredicto.Decision.FUERA_DE_ALCANCE)
        assertThat(dao.aplicarVeredictoDelServidor(s5("no-existe"), now).decision).isEqualTo(ResultadoDelVeredicto.Decision.SIN_FILA)
        for (id in listOf("e1", "e2", "e3", "e4")) assertThat(dao.getById(id)!!.serverPaymentId).isNull()
    }

    @Test fun `P1-3 cincuenta veredictos finales INAPLICABLES (montos distintos) no bloquean la reaplicacion del que si aplica`() = runTest {
        // Codex (código): el lote de reaplicación tomaba siempre los 50 más antiguos por `server_verdict_at`; una contradicción
        // (RECORDED con montos distintos) nunca sale de ese conjunto y el intento 51 no se reaplicaba jamás — ni S6 lo
        // consultaba, porque ya tiene veredicto final.
        for (i in 1..50) {
            fila("c$i", "AUTORIZADO", requestId = "req-c$i"); bandeja("req-c$i")
            val r = dao.aplicarVeredictoDelServidor(s5("c$i", paymentId = "pay-c$i", requestId = "req-c$i", amount = 9_999), now - 100_000 + i)
            assertThat(r.transiciono).isFalse(); assertThat(r.contradiccion).isTrue()
        }
        fila("z", "AUTORIZANDO", hostApproved = null, requestId = "req-z"); bandeja("req-z")
        assertThat(dao.aplicarVeredictoDelServidor(s5("z", paymentId = "pay-z", requestId = "req-z"), now).decision)
            .isEqualTo(ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR)
        ledger.markIndeterminate("z", "sin veredicto")
        val lote = dao.veredictosPendientesDeAplicar(venue).map { it.attemptId }
        assertThat(lote).contains("z")
        assertThat(lote).containsNoneIn((1..50).map { "c$it" }) // una contradicción no es reaplicable: no ocupa el lote
        val r = dao.reaplicarVeredictoGuardado("z", now + 5)!!
        assertThat(r.transiciono).isTrue()
        assertThat(dao.getById("z")!!.state).isEqualTo("REGISTRADO")
        assertThat(db.remotePaymentRequestDao().getById("req-z")!!.status).isEqualTo("RESOLVED")
    }

    @Test fun `P1-4 una segunda captura del MISMO outcome sin ganador no borra el ganador final ya guardado`() = runTest {
        fila("w1", "AUTORIZANDO", hostApproved = null); bandeja()
        dao.aplicarVeredictoDelServidor(s6("w1", "SECOND_CAPTURE_EVIDENCE", paymentId = "pay-w1", isWinner = false, winner = "pay-ganador"), now)
        assertThat(dao.getById("w1")!!.serverWinnerPaymentId).isEqualTo("pay-ganador")
        // Misma identidad, mismos importes y origen, mismo outcome… pero sin ganador (respuesta menos completa).
        val r = dao.aplicarVeredictoDelServidor(s6("w1", "SECOND_CAPTURE_EVIDENCE", paymentId = "pay-w1", isWinner = false, winner = null), now + 1)
        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR)
        assertThat(dao.getById("w1")!!.serverWinnerPaymentId).isEqualTo("pay-ganador")
        // Y tras el callback incierto la reaplicación sigue teniendo con qué liberar (E1/E2).
        ledger.markIndeterminate("w1", "sin veredicto")
        assertThat(dao.veredictosPendientesDeAplicar(venue).map { it.attemptId }).contains("w1")
        assertThat(dao.reaplicarVeredictoGuardado("w1", now + 2)!!.transiciono).isTrue()
        assertThat(dao.getById("w1")!!.state).isEqualTo("REGISTRADO")
    }

    @Test fun `P1-5 cincuenta contradicciones CADUCADAS (mas de 72 h) no dejan fuera del aviso a un cobro incierto mas antiguo`() = runTest {
        val cuatroDias = 4L * 24 * 3600_000; val cincoDias = 5L * 24 * 3600_000
        for (i in 1..50) {
            fila("k$i", "AUTORIZADO", requestId = "req-k$i"); bandeja("req-k$i")
            dao.aplicarVeredictoDelServidor(s5("k$i", paymentId = "pay-k$i", requestId = "req-k$i", amount = 9_999), now - cuatroDias + i)
        }
        dao.insert(PaymentAttemptEntity(attemptId = "viejo", venueId = venue, processor = "ANGELPAY", kind = "SALE", state = "INDETERMINADO",
            amountCents = 12_050, tipCents = 0, recordingRoute = "FAST", paymentContextJson = """{"venueId":"$venue"}""",
            createdAt = now - cincoDias, updatedAt = now - cincoDias, hostApproved = null))
        val todas = db.remotePaymentRequestDao().observePendingObligations(venue).first()
        assertThat(todas.filter { it.contradiccion == 0 }.map { it.totalCentavos }).containsExactly(12_050L)
        val texto = com.jaac.avoqado_tpv.core.remotepayment.AvisoDeCobrosPendientes.texto(todas, now)!!
        assertThat(texto).contains("$120.50")
        assertThat(texto).doesNotContain("posible cobro doble") // las 50 contradicciones ya caducaron: no se muestran
    }

    @Test fun `la bandeja — un negativo previo lo reemplaza el exito, un exito sin paymentId se enriquece, el mismo ganador es no-op, RECEIVED y lapida no se tocan`() = runTest {
        val negativo = JSONObject().put("requestId", "req-1").put("status", "cancelled").put("outcomeEvidence", "PRE_AUTHORIZATION").toString()
        fila("f1", "HOST_RESPONDIO"); bandeja(status = "RESOLVED", finalJson = negativo)
        val r1 = dao.aplicarVeredictoDelServidor(s5("f1"), now)
        assertThat(JSONObject(db.remotePaymentRequestDao().getById("req-1")!!.finalResultJson!!).optString("status")).isEqualTo("success")
        assertThat(r1.bandejaResueltaJson).isNotNull()

        val sinId = JSONObject().put("requestId", "req-2").put("status", "success").put("paymentId", JSONObject.NULL).toString()
        fila("f2", "HOST_RESPONDIO", requestId = "req-2"); bandeja("req-2", status = "RESOLVED", finalJson = sinId)
        dao.aplicarVeredictoDelServidor(s5("f2", requestId = "req-2", paymentId = "pay-f2"), now)
        assertThat(JSONObject(db.remotePaymentRequestDao().getById("req-2")!!.finalResultJson!!).optString("paymentId")).isEqualTo("pay-f2")
        assertThat(dao.aplicarVeredictoDelServidor(s5("f2", requestId = "req-2", paymentId = "pay-f2"), now + 1).bandejaResueltaJson).isNull()

        fila("f3", "HOST_RESPONDIO", requestId = "req-3"); bandeja("req-3", status = "RECEIVED")
        dao.aplicarVeredictoDelServidor(s5("f3", requestId = "req-3", paymentId = "pay-f3"), now)
        assertThat(db.remotePaymentRequestDao().getById("req-3")!!.status).isEqualTo("RECEIVED")

        fila("f4", "HOST_RESPONDIO", requestId = "req-4")
        db.remotePaymentRequestDao().insert(RemotePaymentRequestEntity.tombstone("req-4", venue, now))
        dao.aplicarVeredictoDelServidor(s5("f4", requestId = "req-4", paymentId = "pay-f4"), now)
        assertThat(db.remotePaymentRequestDao().getById("req-4")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_NOT_FOUND_ANSWERED)
    }

    @Test fun `E3 el lote de 25 avanza — la consultada va al final y la 26 entra en la segunda pasada, sin NULLS FIRST`() = runTest {
        for (i in 1..26) fila("q$i".padStart(4, '0'), "ENTREGADA_A_COLA", requestId = "req-q$i")
        val primera = dao.candidatasDeConsultaAlServidor(venue, now, now, 10 * 60_000, 24 * 3600_000)
        assertThat(primera).hasSize(25)
        primera.forEach { dao.estamparConsultaAlServidor(it.attemptId, now) }
        val segunda = dao.candidatasDeConsultaAlServidor(venue, now + 1, now + 1, 10 * 60_000, 24 * 3600_000)
        assertThat(segunda.first().attemptId).isEqualTo("0q26")
        assertThat(segunda).hasSize(1) // las 25 esperan su espaciado (10 min)
        // A los 11 min las 25 siguen esperando (count=1 ⇒ 20 min); sólo la 26 (nunca consultada) está lista.
        assertThat(dao.candidatasDeConsultaAlServidor(venue, now + 11 * 60_000, now + 11 * 60_000, 10 * 60_000, 24 * 3600_000).map { it.attemptId })
            .containsExactly("0q26")
        // A los 21 min vuelven las 25 y la 26 sigue PRIMERA (NULL primero por defecto en SQLite, sin NULLS FIRST).
        val tercera = dao.candidatasDeConsultaAlServidor(venue, now + 21 * 60_000, now + 21 * 60_000, 10 * 60_000, 24 * 3600_000)
        assertThat(tercera.first().attemptId).isEqualTo("0q26")
        assertThat(tercera).hasSize(25)
    }

    @Test fun `E3 los estados con SDK dentro solo se consultan pasados 120 s y una fila con veredicto final ya no se consulta`() = runTest {
        fila("g1", "AUTORIZANDO", hostApproved = null); fila("g2", "HOST_RESPONDIO", requestId = "req-g2"); fila("g3", "REGISTRADO", requestId = "req-g3")
        dao.aplicarVeredictoDelServidor(s5("g3", requestId = "req-g3", paymentId = "pay-g3"), now)
        val recientes = dao.candidatasDeConsultaAlServidor(venue, now - 120_000, now, 10 * 60_000, 24 * 3600_000).map { it.attemptId }
        assertThat(recientes).containsExactly("g2") // g1 tiene 60 s; g3 ya tiene veredicto final
        val viejas = dao.candidatasDeConsultaAlServidor(venue, now, now, 10 * 60_000, 24 * 3600_000).map { it.attemptId }
        assertThat(viejas).containsExactly("g1", "g2")
    }

    @Test fun `VeredictoDelServidor se reconstruye desde S6 con el ganador correcto por outcome`() = runTest {
        val rec = s6("x", "RECORDED", isWinner = true)
        assertThat(rec.ganadorAcreditado).isEqualTo("pay-1")
        assertThat(s6("x", "RECORDED", isWinner = false).ganadorAcreditado).isNull()
        assertThat(s6("x", "SECOND_CAPTURE_EVIDENCE", winner = "w").ganadorAcreditado).isEqualTo("w")
        assertThat(s6("x", "REFERENCE_COLLISION_EVIDENCE", winner = "w").ganadorAcreditado).isNull()
        assertThat(VeredictoDeIntento.desdeConsultaS6(venue, "x", TerminalAttemptStatusResponse(attempt = TerminalAttemptResultDto(outcome = "NOT_RECORDED")))).isNull()
        assertThat(VeredictoDeIntento.desdeConsultaS6(venue, "x", TerminalAttemptStatusResponse(attempt = null))).isNull()
        assertThat(rec.outcome).isEqualTo(VeredictoDelServidor.RECORDED)
    }

    // ── Task 6 (ventana de confirmación): la LIBERACIÓN del servidor (S6 `request.outcome = NOT_CHARGED` por ventana o por
    //    declaración del cajero) cierra la fila INDETERMINADO como DESCARTADA sin tocar `host_approved`. ──

    private fun respuestaS6ConRequest(attemptId: String, requestJson: String, attemptOutcome: String? = null) =
        TerminalAttemptStatusResponse(
            success = true, attemptId = attemptId, requestId = "req-1",
            attempt = attemptOutcome?.let { TerminalAttemptResultDto(attemptId = attemptId, outcome = it, paymentId = "pay-1") },
            request = com.google.gson.JsonParser.parseString(requestJson).asJsonObject,
        )

    @Test fun `la liberacion del servidor cierra la fila INDETERMINADO como DESCARTADA sin tocar host_approved y destraba la venta`() = runTest {
        fila("a1", "INDETERMINADO", hostApproved = null)
        val n = dao.cerrarPorLiberacionDelServidor("a1", venue, "req-1", PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE,
            PaymentAttemptEntity.LAST_ERROR_LIBERADA_PREFIX + "NO_EVIDENCE_AFTER_WINDOW", now)
        assertThat(n).isEqualTo(1)
        val tras = dao.getById("a1")!!
        assertThat(tras.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(tras.hostApproved).isNull()
        assertThat(tras.serverOutcome).isEqualTo("RELEASED_NO_EVIDENCE")
        assertThat(tras.serverVerdictAt).isEqualTo(now)
        assertThat(dao.findUnresolvedOrder(venue, "\"orderId\":\"o1\"")).isNull()
        assertThat(dao.observeUnresolvedCount(venue).first()).isEqualTo(0)
        assertThat(dao.cerrarPorLiberacionDelServidor("a1", venue, "req-1", "RELEASED_NO_EVIDENCE", "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", now + 1)).isEqualTo(0) // idempotente
    }

    @Test fun `una fila con host_approved o con veredicto guardado NO se libera — el dinero manda`() = runTest {
        fila("a2", "INDETERMINADO", hostApproved = true)
        fila("a3", "INDETERMINADO", hostApproved = null); dao.aplicarVeredictoDelServidor(s6("a3", "RECORDED"), now)
        assertThat(dao.cerrarPorLiberacionDelServidor("a2", venue, "req-1", "RELEASED_NO_EVIDENCE", "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", now)).isEqualTo(0)
        assertThat(dao.cerrarPorLiberacionDelServidor("a3", venue, "req-1", "RELEASED_NO_EVIDENCE", "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", now)).isEqualTo(0)
    }

    @Test fun `una fila AUTORIZANDO (SDK dentro) o legacy o de otro venue o de OTRA solicitud NO se libera`() = runTest {
        fila("a4", "AUTORIZANDO", hostApproved = null)
        fila("a5", "INDETERMINADO", hostApproved = null, legacy = true)
        fila("a6", "INDETERMINADO", hostApproved = null, venueId = "otro-venue")
        fila("a9", "INDETERMINADO", hostApproved = null, requestId = "req-9")
        for (id in listOf("a4", "a5")) assertThat(dao.cerrarPorLiberacionDelServidor(id, venue, "req-1", "RELEASED_NO_EVIDENCE", "x", now)).isEqualTo(0)
        assertThat(dao.cerrarPorLiberacionDelServidor("a6", venue, "req-1", "RELEASED_NO_EVIDENCE", "x", now)).isEqualTo(0)
        // Task 7 · B (pertenencia): la liberación es de la solicitud `req-1`; una fila de `req-9` no es suya — ni por el DAO ni por la libreta.
        assertThat(dao.cerrarPorLiberacionDelServidor("a9", venue, "req-1", "RELEASED_NO_EVIDENCE", "x", now)).isEqualTo(0)
        assertThat(ledger.aplicarLiberacionDelServidor(LiberacionDelServidor(venue, "a9", "req-1", "NO_EVIDENCE_AFTER_WINDOW"), now).getOrNull()).isFalse()
        assertThat(dao.getById("a9")!!.state).isEqualTo("INDETERMINADO")
        assertThat(ledger.aplicarLiberacionDelServidor(LiberacionDelServidor(venue, "a9", "req-9", "NO_EVIDENCE_AFTER_WINDOW"), now).getOrNull()).isTrue()
        assertThat(dao.getById("a9")!!.state).isEqualTo("DESCARTADA")
    }

    @Test fun `un RECORDED que llega despues de la liberacion queda como contradiccion visible`() = runTest {
        fila("a7", "INDETERMINADO", hostApproved = null); bandeja()
        dao.cerrarPorLiberacionDelServidor("a7", venue, "req-1", "RELEASED_NO_EVIDENCE", "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", now)
        val r = dao.aplicarVeredictoDelServidor(s6("a7", "RECORDED"), now + 1)
        assertThat(r.contradiccion).isTrue()
    }

    @Test fun `LiberacionDelServidor se lee del request de S6 y solo con las dos evidencias del servidor`() {
        val ventana = respuestaS6ConRequest("a8", """{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW","evidenceClass":"SERVER"}""")
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a8", ventana)?.evidencia).isEqualTo("NO_EVIDENCE_AFTER_WINDOW")
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a8", ventana)?.requestId).isEqualTo("req-1")   // la solicitud liberada viaja con la liberación
        // Task 7 · B: sin `requestId` en la respuesta no hay pertenencia que comprobar ⇒ no es una liberación aplicable.
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a8", ventana.copy(requestId = null))).isNull()
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a8", ventana.copy(requestId = ""))).isNull()
        val cajero = respuestaS6ConRequest("a8", """{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED","evidenceClass":"OPERATOR"}""")
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a8", cajero)?.evidencia).isEqualTo("OPERATOR_RECONCILED")
        val declinado = respuestaS6ConRequest("a8", """{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"PROCESSOR_DECLINED"}""")
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a8", declinado)).isNull() // eso ya lo decide la propia terminal
        val sinDesenlace = respuestaS6ConRequest("a8", """{"status":"TIMED_OUT","outcome":"UNRESOLVED"}""")
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a8", sinDesenlace)).isNull()
        val conDinero = respuestaS6ConRequest("a8", """{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"}""", attemptOutcome = "RECORDED")
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a8", conDinero)).isNull() // contradicción: gana el intento con dinero
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a8", TerminalAttemptStatusResponse(success = true, request = null))).isNull()
    }
}
