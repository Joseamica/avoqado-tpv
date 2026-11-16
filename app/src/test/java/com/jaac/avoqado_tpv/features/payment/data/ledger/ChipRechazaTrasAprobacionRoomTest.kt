package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger.ChipRechazo.EN_DUDA
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger.ChipRechazo.HAY_EVIDENCIA
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger.ChipRechazo.SIN_ESCRIBIR
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 D5 (encargo 533d77, opción C): en la PAX el banco APROBÓ y después la tarjeta rechazó el cierre (`CompleteEmvTrans`, p. ej.
 * `FailureSecondGenerate -11`). No se sabe si Blumon TPV revierte el cargo, así que el intento NO es «cobrado» ni «no cobrado»:
 * es una DUDA con dinero en juego. Antes la fila se quedaba `HOST_RESPONDIO` + `host_approved = 1` y la recuperación la
 * registraba sola como venta cobrada, sin evidencia.
 *
 * Esta suite fija, con Room real, lo que la libreta hace con ese intento: pasa a INDETERMINADO con su motivo y sin
 * `host_approved`; la recuperación de aprobaciones ya no la toma; la venta queda cercada pero el aparato no; una persona puede
 * declarar «revisé y no se cobró» (sólo tras una respuesta del servidor); y si el servidor acredita el cobro, se registra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ChipRechazaTrasAprobacionRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private val settings = mockk<TpvSettingsRepository>(relaxed = true)
    private val venue = "venue"
    private val orden7 = """{"orderId":"orden-7"}"""

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
    }

    @After fun cerrar() = db.close()

    private fun proceso() = PaymentAttemptLedger(dao, settings)

    /** El camino real hasta el punto del defecto: reserva, autoriza y el banco aprueba (la fila queda HOST_RESPONDIO aprobada). */
    private suspend fun aprobadoPorElBanco(libreta: PaymentAttemptLedger, id: String = "a1", contexto: String = orden7, ruta: String = "ORDER") {
        assertTrue(libreta.openAttempt(id, venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 500, ruta, contexto))
        assertTrue(libreta.markAuthorizing(id))
        assertTrue(libreta.markHostResponded(id, true, "op-1", "ref-1", "auth-1"))
        assertEquals(PaymentAttemptEntity.STATE_HOST_RESPONDIO, dao.getById(id)?.state)
        assertEquals(true, dao.getById(id)?.hostApproved)
    }

    @Test fun `P1 el chip rechaza tras aprobar - la fila queda en duda con su motivo, sin host_approved y con la operacion del banco`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta)

        assertEquals(EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))

        val fila = dao.getById("a1")!!
        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, fila.state)
        assertNull("no se sabe si hubo cargo: no es «el banco aprobó = dinero»", fila.hostApproved)
        assertEquals(PaymentAttemptEntity.MOTIVO_CHIP_RECHAZO_TRAS_APROBACION, fila.lastError)
        // Lo que dijo el banco se conserva para conciliar con el portal.
        assertEquals("op-1", fila.operationId)
        assertEquals("ref-1", fila.referenceNumber)
        assertEquals("auth-1", fila.authCode)
    }

    @Test fun `P1 la recuperacion de aprobaciones ya no la registra sola como cobrada`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta)
        assertEquals("antes del cambio de estado, la recuperación la tomaría", listOf("a1"),
            dao.getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, System.currentTimeMillis()).map { it.attemptId })

        assertEquals(EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))

        assertTrue("la recuperación no la toma: sólo la evidencia del servidor o una persona la cierran",
            dao.getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, System.currentTimeMillis()).isEmpty())
        assertEquals("tampoco se puede reclamar para registrar", 0,
            dao.claimRecovery("a1", venue, System.currentTimeMillis(), Long.MAX_VALUE))
    }

    @Test fun `P1 la venta queda cercada pero el aparato cobra otras ventas`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta)
        assertEquals(EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))
        libreta.marcarSdkRegreso("a1")

        assertFalse("la MISMA venta no se vuelve a cobrar mientras siga en duda",
            libreta.openAttempt("otra-vez", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 500, "ORDER", orden7))
        assertEquals("a1", libreta.retencionDeLaVenta("orden-7")?.attemptId)
        assertNull("el SDK ya regresó: el aparato no queda apartado", libreta.retencionDelAparato())
        assertTrue("otra venta sí entra",
            libreta.openAttempt("orden-8", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "ORDER", """{"orderId":"orden-8"}"""))
    }

    @Test fun `P1 tras reiniciar la app la venta sigue cercada`() = runTest {
        aprobadoPorElBanco(proceso())
        assertEquals(EN_DUDA, proceso().markChipRechazoTrasAprobacion("a1"))

        val reiniciada = proceso()
        assertFalse(reiniciada.openAttempt("otra-vez", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 500, "ORDER", orden7))
        assertEquals("a1", reiniciada.retencionDeLaVenta("orden-7")?.attemptId)
    }

    @Test fun `P1 una persona declara revise y no se cobro - sólo despues de que el servidor contesto`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta, contexto = "{}", ruta = "FAST")
        assertEquals(EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))
        libreta.marcarSdkRegreso("a1")

        assertFalse("sin respuesta del servidor no se declara", libreta.declararSinCobroLocal("a1", venue, "Ana"))
        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, dao.getById("a1")?.state)

        dao.estamparRespuestaDelServidor("a1", System.currentTimeMillis())
        assertTrue("también sale en el banner verde para declararla",
            libreta.observarCobrosDeclarables(venue).first().any { it.attemptId == "a1" })
        assertTrue(libreta.declararSinCobroLocal("a1", venue, "Ana"))
        assertEquals(PaymentAttemptEntity.STATE_DESCARTADA, dao.getById("a1")?.state)
        assertEquals("declarado_sin_cobro:Ana", dao.getById("a1")?.lastError)
    }

    @Test fun `P1 al declararla deja de cercar su venta`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta)
        assertEquals(EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))
        libreta.marcarSdkRegreso("a1")
        dao.estamparRespuestaDelServidor("a1", System.currentTimeMillis())
        assertTrue(libreta.declararSinCobroLocal("a1", venue, "Ana"))

        assertNull(libreta.retencionDeLaVenta("orden-7"))
        assertTrue(libreta.openAttempt("otra-vez", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 500, "ORDER", orden7))
    }

    @Test fun `P1 si el servidor acredita el cobro con los mismos montos se registra`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta)
        assertEquals(EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))

        val veredicto = VeredictoDeIntento.desdeConsultaS6(
            venue, "a1",
            TerminalAttemptStatusResponse(
                success = true, attemptId = "a1",
                attempt = TerminalAttemptResultDto(
                    attemptId = "a1", outcome = "RECORDED", paymentId = "pay-1", paymentStatus = "COMPLETED",
                    recordedVia = "webhook", amountCents = 10000, tipCents = 500,
                ),
            ),
        )
        assertNotNull(veredicto)
        dao.aplicarVeredictoDelServidor(veredicto!!, System.currentTimeMillis())

        assertEquals(PaymentAttemptEntity.STATE_REGISTRADO, dao.getById("a1")?.state)
        assertNull("ya registrada, la venta deja de estar cercada", libreta.retencionDeLaVenta("orden-7"))
    }

    @Test fun `P1 con evidencia de cobro del servidor ya no se declara`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta, contexto = "{}", ruta = "FAST")
        assertEquals(EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))
        dao.estamparRespuestaDelServidor("a1", System.currentTimeMillis())
        assertTrue(libreta.marcarEvidenciaPositivaDelServidor(venue, "a1").getOrDefault(false))

        assertFalse(libreta.declararSinCobroLocal("a1", venue, "Ana"))
        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, dao.getById("a1")?.state)
    }

    // ── Dónde NO aplica la transición ────────────────────────────────────────────────────────────

    @Test fun `P1 si la aprobacion no quedo escrita (AUTORIZANDO) tambien queda en duda`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("a1", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("a1"))

        // Codex r1 #2: si «el banco aprobó» no se pudo escribir, la fila sigue en AUTORIZANDO y también pasa a la duda.
        assertEquals(EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))
        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, dao.getById("a1")?.state)
        assertEquals(PaymentAttemptEntity.MOTIVO_CHIP_RECHAZO_TRAS_APROBACION, dao.getById("a1")?.lastError)
    }

    @Test fun `P1 no aplica sobre un rechazo del banco`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("a1", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("a1"))
        assertTrue(libreta.markHostResponded("a1", false, null, null, null))

        assertEquals(SIN_ESCRIBIR, libreta.markChipRechazoTrasAprobacion("a1"))
        assertEquals(PaymentAttemptEntity.STATE_DESCARTADA, dao.getById("a1")?.state)
        assertEquals(false, dao.getById("a1")?.hostApproved)
    }

    @Test fun `P1 no aplica a una devolucion - el reembolso se queda como hoy`() = runTest {
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "r1", venueId = venue, processor = PaymentAttemptEntity.PROCESSOR_BLUMON,
                kind = PaymentAttemptEntity.KIND_REFUND, state = PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                amountCents = 100, tipCents = 0, recordingRoute = "REFUND", paymentContextJson = "{}",
                hostApproved = true, createdAt = 1L, updatedAt = 1L,
            ),
        )
        assertEquals(SIN_ESCRIBIR, proceso().markChipRechazoTrasAprobacion("r1"))
        assertEquals(PaymentAttemptEntity.STATE_HOST_RESPONDIO, dao.getById("r1")?.state)
        assertEquals(true, dao.getById("r1")?.hostApproved)
    }

    @Test fun `P1 no aplica si el servidor ya tiene evidencia o veto de ese intento`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta, contexto = "{}", ruta = "FAST")
        assertTrue(libreta.marcarEvidenciaPositivaDelServidor(venue, "a1").getOrDefault(false))

        assertEquals("con evidencia del servidor no se quita la aprobación", HAY_EVIDENCIA, libreta.markChipRechazoTrasAprobacion("a1"))
        assertEquals(true, dao.getById("a1")?.hostApproved)
    }

    @Test fun `P1 dos veces da lo mismo`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta)
        assertEquals(EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))
        assertEquals("ya estaba en duda: lo sigue estando", EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))
        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, dao.getById("a1")?.state)
    }

    // ── Codex r1: la recuperación con el SDK todavía dentro, y la duda que no se pudo escribir ──────────────────────────────

    @Test fun `P1 Codex r1-1 la recuperacion no reclama una venta de la PAX con el cierre EMV pendiente en este proceso`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta)   // HOST_RESPONDIO, sdk_returned_at NULL: CompleteEmvTrans sigue corriendo
        val ahora = System.currentTimeMillis()
        assertEquals("con el SDK dentro no se reclama", 0,
            dao.claimRecoveryConElSdkFuera("a1", venue, ahora, ahora + 300_000, libreta.tokenDelProceso()))

        libreta.marcarSdkRegreso("a1")   // camino con éxito: el SDK regresó y la fila sigue aprobada
        assertEquals("con el SDK fuera, la recuperación de siempre", 1,
            dao.claimRecoveryConElSdkFuera("a1", venue, ahora, ahora + 300_000, libreta.tokenDelProceso()))
    }

    @Test fun `P1 Codex r1-1 lo que dejo un proceso muerto si se reclama aunque no marcara el fin del SDK`() = runTest {
        aprobadoPorElBanco(proceso())
        val nuevo = proceso()
        val ahora = System.currentTimeMillis()
        assertEquals(1, dao.claimRecoveryConElSdkFuera("a1", venue, ahora, ahora + 300_000, nuevo.tokenDelProceso()))
    }

    @Test fun `P1 Codex r1-2 la duda que no se pudo escribir no se registra sola y se reintenta en cada pasada`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta)
        // La fila está en un estado que la transición no acepta (simula que la escritura no se pudo hacer): SIN_ESCRIBIR.
        assertEquals(1, dao.marcarSdkRegreso("a1", System.currentTimeMillis()))
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state = 'AUTORIZADO' WHERE attempt_id = 'a1'")
        assertEquals(SIN_ESCRIBIR, libreta.markChipRechazoTrasAprobacion("a1"))
        assertTrue("la recuperación la deja en paz", libreta.retenidaPorChipRechazo("a1"))

        // Cuando la escritura vuelve a ser posible, la pasada de la recuperación la escribe y la fila queda en duda.
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state = 'HOST_RESPONDIO' WHERE attempt_id = 'a1'")
        assertTrue(libreta.retenidaPorChipRechazo("a1"))
        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, dao.getById("a1")?.state)
        assertFalse("ya escrita, deja de estar en memoria", libreta.retenidaPorChipRechazo("a1"))
    }

    @Test fun `P1 Codex r1-2 una fila que no es de esta duda no queda retenida`() = runTest {
        val libreta = proceso()
        aprobadoPorElBanco(libreta)
        assertFalse(libreta.retenidaPorChipRechazo("a1"))
    }
}
