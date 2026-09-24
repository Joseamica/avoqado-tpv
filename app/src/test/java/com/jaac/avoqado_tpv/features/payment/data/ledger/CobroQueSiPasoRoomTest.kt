package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 La SALIDA de una terminal apartada por un cobro que SÍ pasó (decisión del founder, 23-sep: «corrige y avisa»).
 *
 * El caso: la terminal anotó «no se cobró» (el lector dijo «cancelado», o se liberó) y DESPUÉS el banco aprobó y Avoqado
 * registró el Payment. La marca protectora aparta el aparato — y hasta hoy para siempre (Codex r17/r18, P1). La salida: el
 * cajero ve «ese cobro SÍ pasó, no lo vuelvas a cobrar» y toca «Entendido»; la fila pasa a REGISTRADO conservando lo que dijo
 * el lector y quién lo confirmó. Sólo el caso LIMPIO (Payment de ESTE intento, mismos importes, sin veto ni otro ganador):
 * lo demás sigue apartado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CobroQueSiPasoRoomTest {
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

    /** Pago rápido de $40 (sin solicitud ni orden) que la terminal cerró como «no se cobró». */
    private suspend fun cobroLocalDescartado(attemptId: String, hostApproved: Boolean? = null, orderId: String? = null) {
        dao.insert(
            PaymentAttemptEntity(
                attemptId = attemptId, venueId = venue, processor = "ANGELPAY", state = "DESCARTADA", amountCents = 4000, tipCents = 0,
                recordingRoute = "FAST",
                paymentContextJson = if (orderId == null) """{"amount":40.00}""" else """{"amount":40.00,"orderId":"$orderId"}""",
                hostApproved = hostApproved,
                lastError = "sin_autorizacion:sdk=1.0.19;code=U100", createdAt = now - 600_000, updatedAt = now - 600_000,
                terminalPaymentRequestId = null,
            ),
        )
    }

    /** El servidor acredita dinero de ESE intento (S6), como lo aplica hoy la recuperación. */
    private suspend fun elServidorDice(attemptId: String, outcome: VeredictoDelServidor, amountCents: Long = 4000, requestId: String? = null, ganador: String? = null) {
        ledger.aplicarVeredictoDelServidor(
            VeredictoDeIntento(
                venueId = venue, attemptId = attemptId, requestId = requestId, outcome = outcome, paymentId = "pay-$attemptId",
                recordedVia = "webhook", amountCents = amountCents, tipCents = 0, ganadorAcreditado = ganador,
                fuente = VeredictoDeIntento.Fuente.CONSULTA_S6,
            ),
            now,
        ).getOrThrow()
    }

    private suspend fun otroCobroEntra(id: String = "otro"): Boolean =
        dao.reserveTerminal(id, venue, "ANGELPAY", "SALE", 5000, 0, "FAST", """{"amount":50.00}""", null, now + 10_000, null, false) != -1L

    @Test fun `el caso limpio - aparece para confirmar, Entendido lo registra y la terminal vuelve a cobrar`() = runTest {
        cobroLocalDescartado("a")
        elServidorDice("a", VeredictoDelServidor.RECORDED)
        assertWithMessage("antes de confirmar, el aparato está apartado").that(otroCobroEntra("antes")).isFalse()
        assertThat(ledger.observarCobrosPorReconocer(venue).first().map { it.attemptId }).containsExactly("a")

        val ok = ledger.reconocerCobroRegistrado(venue, "a", "Ana López (stf-1)", now + 5_000)

        assertThat(ok).isTrue()
        val f = dao.getById("a")!!
        assertThat(f.state).isEqualTo("REGISTRADO")
        assertWithMessage("queda quién lo confirmó y cuándo").that(f.acknowledgedBy).isEqualTo("Ana López (stf-1)")
        assertThat(f.acknowledgedAt).isEqualTo(now + 5_000)
        assertWithMessage("lo que dijo el lector se conserva").that(f.lastError).isEqualTo("sin_autorizacion:sdk=1.0.19;code=U100")
        assertThat(f.hostApproved).isNull()
        assertThat(dao.esContradiccion("a")).isFalse()
        assertThat(ledger.observarCobrosPorReconocer(venue).first()).isEmpty()
        assertWithMessage("confirmado, otro cobro ya entra").that(otroCobroEntra("despues")).isTrue()
    }

    @Test fun `con un rechazo del host guardado tambien sale, y deja de ser contradiccion sin reescribir el rechazo`() = runTest {
        cobroLocalDescartado("h", hostApproved = false)
        elServidorDice("h", VeredictoDelServidor.RECORDED)

        assertThat(ledger.reconocerCobroRegistrado(venue, "h", "Ana (stf-1)", now + 5_000)).isTrue()

        val f = dao.getById("h")!!
        assertThat(f.state).isEqualTo("REGISTRADO")
        assertThat(f.hostApproved).isFalse()
        assertWithMessage("el rechazo del host quedó reconocido por una persona").that(dao.esContradiccion("h")).isFalse()
        assertThat(otroCobroEntra()).isTrue()
    }

    @Test fun `con otros importes NO se ofrece ni se deja confirmar - sigue apartado`() = runTest {
        cobroLocalDescartado("m")
        elServidorDice("m", VeredictoDelServidor.RECORDED, amountCents = 4500)

        assertThat(ledger.observarCobrosPorReconocer(venue).first()).isEmpty()
        assertThat(ledger.reconocerCobroRegistrado(venue, "m", "Ana (stf-1)", now + 5_000)).isFalse()
        assertThat(dao.getById("m")!!.state).isEqualTo("DESCARTADA")
        assertThat(otroCobroEntra()).isFalse()
    }

    @Test fun `una evidencia sin registro final (PENDING) NO se deja confirmar`() = runTest {
        cobroLocalDescartado("p")
        elServidorDice("p", VeredictoDelServidor.PENDING_EVIDENCE)

        assertThat(ledger.observarCobrosPorReconocer(venue).first()).isEmpty()
        assertThat(ledger.reconocerCobroRegistrado(venue, "p", "Ana (stf-1)", now + 5_000)).isFalse()
        assertThat(otroCobroEntra()).isFalse()
    }

    @Test fun `con un veto del servidor NO se deja confirmar`() = runTest {
        cobroLocalDescartado("v")
        elServidorDice("v", VeredictoDelServidor.RECORDED)
        dao.marcarVetoDelServidor("v", venue, PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION, now + 1)

        assertThat(ledger.observarCobrosPorReconocer(venue).first()).isEmpty()
        assertThat(ledger.reconocerCobroRegistrado(venue, "v", "Ana (stf-1)", now + 5_000)).isFalse()
    }

    @Test fun `un cobro del POS cuyo Payment NO es el ganador de la solicitud NO se deja confirmar`() = runTest {
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "r", venueId = venue, processor = "ANGELPAY", state = "DESCARTADA", amountCents = 4000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = """{"terminalPaymentRequestId":"req-r"}""", hostApproved = null,
                createdAt = now - 600_000, updatedAt = now - 600_000, terminalPaymentRequestId = "req-r",
            ),
        )
        elServidorDice("r", VeredictoDelServidor.RECORDED, requestId = "req-r", ganador = null)

        assertThat(ledger.observarCobrosPorReconocer(venue).first()).isEmpty()
        assertThat(ledger.reconocerCobroRegistrado(venue, "r", "Ana (stf-1)", now + 5_000)).isFalse()
    }

    @Test fun `un cobro del POS cuyo Payment SI es el ganador se deja confirmar`() = runTest {
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "g", venueId = venue, processor = "ANGELPAY", state = "DESCARTADA", amountCents = 4000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = """{"terminalPaymentRequestId":"req-g"}""", hostApproved = null,
                createdAt = now - 600_000, updatedAt = now - 600_000, terminalPaymentRequestId = "req-g",
            ),
        )
        elServidorDice("g", VeredictoDelServidor.RECORDED, requestId = "req-g", ganador = "pay-g")

        assertThat(ledger.reconocerCobroRegistrado(venue, "g", "Ana (stf-1)", now + 5_000)).isTrue()
        assertThat(dao.getById("g")!!.state).isEqualTo("REGISTRADO")
    }

    @Test fun `confirmar dos veces no re-fecha ni cambia a quien confirmo, y otro venue no puede confirmar`() = runTest {
        cobroLocalDescartado("d")
        elServidorDice("d", VeredictoDelServidor.RECORDED)

        assertThat(ledger.reconocerCobroRegistrado("otro-venue", "d", "Intruso", now + 1_000)).isFalse()
        assertThat(ledger.reconocerCobroRegistrado(venue, "d", "Ana (stf-1)", now + 5_000)).isTrue()
        assertThat(ledger.reconocerCobroRegistrado(venue, "d", "Beto (stf-2)", now + 9_000)).isFalse()
        val f = dao.getById("d")!!
        assertThat(f.acknowledgedBy).isEqualTo("Ana (stf-1)")
        assertThat(f.acknowledgedAt).isEqualTo(now + 5_000)
    }

    @Test fun `la barrera de la libreta nombra el Entendido cuando lo que aparta el APARATO es un cobro que si paso`() = runTest {
        cobroLocalDescartado("a")
        elServidorDice("a", VeredictoDelServidor.RECORDED)

        val texto = ledger.motivoDeLaBarrera(orderId = null, attemptIdPropio = "nuevo", ahoraMillis = now)

        assertThat(texto).contains("SÍ pasó")
        assertThat(texto).contains("Entendido")
    }

    @Test fun `la barrera nombra que ESA venta ya se cobro cuando la cerca un cobro que si paso`() = runTest {
        cobroLocalDescartado("a", orderId = "venta-1")
        elServidorDice("a", VeredictoDelServidor.RECORDED)

        val texto = ledger.motivoDeLaBarrera(orderId = "venta-1", attemptIdPropio = "nuevo", ahoraMillis = now)

        assertThat(texto).contains("ya se cobró")
    }

    @Test fun `un cobro que NO se puede confirmar no se anuncia como que si paso en la barrera`() = runTest {
        cobroLocalDescartado("m")
        elServidorDice("m", VeredictoDelServidor.RECORDED, amountCents = 4500)

        val texto = ledger.motivoDeLaBarrera(orderId = null, attemptIdPropio = "nuevo", ahoraMillis = now)

        assertThat(texto).doesNotContain("SÍ pasó")
    }

    @Test fun `el aviso de contradiccion no repite lo que ya ofrece el Entendido`() = runTest {
        cobroLocalDescartado("a")
        elServidorDice("a", VeredictoDelServidor.RECORDED)
        cobroLocalDescartado("m")
        elServidorDice("m", VeredictoDelServidor.RECORDED, amountCents = 4500)   // no confirmable: sigue en el aviso

        val aviso = db.remotePaymentRequestDao().observePendingObligations(venue).first()

        assertWithMessage("sólo la que NO se puede confirmar queda como contradicción en el aviso")
            .that(aviso.map { it.totalCentavos }).containsExactly(4000L)   // la de 4500 guarda 4000 locales; la «a» no sale
        assertThat(aviso.single().contradiccion).isEqualTo(1)
    }
}
