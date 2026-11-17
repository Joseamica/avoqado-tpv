package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 D13 (encargo ff3278, tarjeta hz:72be07e6:D13): en la PAX el banco APRUEBA y la libreta no puede escribirlo (disco lleno,
 * error de E/S). Antes `markHostResponded` devolvía false, el ViewModel lo ignoraba y publicaba «Aprobado». Si el proceso moría
 * antes de AUTORIZADO / registro / cola, la fila quedaba AUTORIZANDO sin `host_approved`; al reiniciar, la cuarentena la pasaba a
 * INDETERMINADO/proceso_terminado y la cerca de la venta ya no la apartaba: la cuenta de $200 volvía a ofrecer el total aunque ya
 * hubieran salido $50 de la tarjeta.
 *
 * Esta suite fija, con Room real y una base que falla a propósito (un trigger, como un disco lleno), que la libreta RECUERDA esa
 * aprobación, la vuelve a escribir antes de cualquier decisión de dinero y, mientras no la escriba, no deja empezar otro cobro ni
 * decir «no se cobró» sobre ese intento.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class AprobacionSinGuardarRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private val settings = mockk<TpvSettingsRepository>(relaxed = true)
    private val venue = "venue"
    private val mesa = """{"orderId":"mesa-200"}"""

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
    }

    @After fun cerrar() = db.close()

    private fun proceso() = PaymentAttemptLedger(dao, settings)

    /** Room no puede escribir NADA en la libreta (disco lleno). */
    private fun fallaTodo() = db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER falla_todo BEFORE UPDATE ON payment_attempts BEGIN SELECT RAISE(ABORT, 'disco lleno'); END")

    /** Sólo falla escribir «el banco aprobó» (`host_approved = 1`); lo demás sí se escribe. */
    private fun fallaLaAprobacion() = db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER falla_aprobacion BEFORE UPDATE ON payment_attempts WHEN NEW.host_approved IS 1 " +
            "BEGIN SELECT RAISE(ABORT, 'error de E/S'); END")

    private fun vuelveLaBase() {
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS falla_todo")
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS falla_aprobacion")
    }

    /** El camino real hasta el defecto: la mesa de $200 cobra $50 con tarjeta; la fila queda AUTORIZANDO esperando al banco. */
    private suspend fun cobrandoCincuentaDeLaMesa(libreta: PaymentAttemptLedger, id: String = "a1") {
        assertTrue(libreta.openAttempt(id, venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 5000, 0, "ORDER", mesa))
        assertTrue(libreta.markAuthorizing(id))
    }

    @Test fun `P1 el banco aprueba y la base no escribe - la libreta lo recuerda y no lo da por guardado`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaTodo()

        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))

        assertTrue("la pantalla tiene que saber que no quedó guardada", libreta.aprobacionPendienteDeGuardar("a1"))
        assertTrue("es dinero de este intento sin escribir", libreta.tieneEvidenciaSinGuardar("a1"))
        vuelveLaBase()
        assertEquals("la fila todavía no lo dice", PaymentAttemptEntity.STATE_AUTORIZANDO, dao.getById("a1")?.state)
    }

    @Test fun `P1 mientras no se escriba no empieza otro cobro ni se dice no se cobro sobre ese intento`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))

        assertFalse("otra venta no reserva con dinero sin escribir",
            libreta.openAttempt("otra", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}"))
        assertFalse("un rechazo tardío no cierra el intento que el banco aprobó",
            libreta.markHostResponded("a1", false, null, null, null))
        assertEquals(PaymentAttemptEntity.STATE_AUTORIZANDO, dao.getById("a1")?.state)
        assertTrue(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P1 al volver la base la puerta de la siguiente venta la escribe y la terminal sigue cobrando`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        libreta.marcarSdkRegreso("a1")
        vuelveLaBase()

        assertTrue("la siguiente venta entra: la puerta escribió lo pendiente primero",
            libreta.openAttempt("otra", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}"))
        val fila = dao.getById("a1")!!
        assertEquals(PaymentAttemptEntity.STATE_HOST_RESPONDIO, fila.state)
        assertEquals(true, fila.hostApproved)
        assertEquals("op-1", fila.operationId)
        assertEquals("ref-1", fila.referenceNumber)
        assertEquals("auth-1", fila.authCode)
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P1 el recorrido del hallazgo - escrita en la puerta, tras morir el proceso la mesa sigue cercada`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        vuelveLaBase()
        // La recuperación (o cualquier decisión de dinero) pasa por la puerta: la aprobación queda escrita.
        assertEquals(0, libreta.reintentarEvidenciaSinGuardar())

        // El proceso muere antes de AUTORIZADO, del registro y de la cola. Arranca otro.
        val nuevo = proceso()
        nuevo.cuarentenaDeHuerfanos()

        assertEquals("los $50 no se pierden: la mesa sigue cercada", "a1", nuevo.retencionDeLaVenta("mesa-200")?.attemptId)
        assertFalse("la mesa no se vuelve a cobrar",
            nuevo.openAttempt("otra-vez", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 20000, 0, "ORDER", mesa))
        assertEquals("y la recuperación la registra", listOf("a1"),
            dao.getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, System.currentTimeMillis()).map { it.attemptId })
    }

    @Test fun `P1 si AUTORIZADO llega antes que la aprobacion se le sella host_approved sin cambiar el estado`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        vuelveLaBase()

        libreta.markAuthorized("a1", "****4242", "VISA", "CHIP")

        val fila = dao.getById("a1")!!
        assertEquals(PaymentAttemptEntity.STATE_AUTORIZADO, fila.state)
        assertEquals("la recuperación la sigue tomando aunque su registro falle", true, fila.hostApproved)
        assertEquals("ref-1", fila.referenceNumber)
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P1 tambien si el registro ya fallo (REGISTRO_FALLIDO) - se sella en la transicion siguiente`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        libreta.markAuthorized("a1", "****4242", "VISA", "CHIP")
        libreta.markRecordFailed("a1", "sin red")
        assertTrue("todavía no se pudo sellar", libreta.aprobacionPendienteDeGuardar("a1"))
        vuelveLaBase()

        libreta.markDeliveredToQueue("a1")

        val fila = dao.getById("a1")!!
        assertEquals(true, fila.hostApproved)
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P2 registrada en el servidor ya no queda pendiente aunque no se sellara`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        libreta.markAuthorized("a1", null, null, null)
        libreta.markRecorded("a1")

        assertEquals(PaymentAttemptEntity.STATE_REGISTRADO, dao.getById("a1")?.state)
        assertFalse("el servidor ya es dueño del dinero: la terminal no se traba",
            libreta.aprobacionPendienteDeGuardar("a1"))
        assertTrue(libreta.openAttempt("otra", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}"))
    }

    @Test fun `P2 repetir la aprobacion sobre una fila ya aprobada no deja nada pendiente (camino sin sesion, D3)`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        assertTrue(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))

        assertTrue("ya estaba escrita: quedó", libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P2 una duda del chip (D5) ya escrita gana - no se vuelve a marcar como aprobada`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        // El chip rechaza el cierre: la duda sí se escribe (desde AUTORIZANDO).
        assertEquals(PaymentAttemptLedger.ChipRechazo.EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))
        // Codex r2 #2: la duda escrita sustituye a la aprobación pendiente EN EL ACTO (sin esperar otra pasada): la pantalla la ve
        // como duda, con su salida, y no como «hay evidencia de cobro».
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
        assertFalse(libreta.tieneEvidenciaSinGuardar("a1"))
        vuelveLaBase()

        assertEquals(0, libreta.reintentarEvidenciaSinGuardar())
        val fila = dao.getById("a1")!!
        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, fila.state)
        assertEquals(PaymentAttemptEntity.MOTIVO_CHIP_RECHAZO_TRAS_APROBACION, fila.lastError)
        assertNull("no se sabe si hubo cargo: la duda no lleva host_approved", fila.hostApproved)
        assertEquals("y sigue cercando la mesa", "a1", libreta.retencionDeLaVenta("mesa-200")?.attemptId)
    }

    @Test fun `P2 con la base sana nada cambia - la aprobacion se escribe al instante`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)

        assertTrue(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))

        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
        assertEquals(PaymentAttemptEntity.STATE_HOST_RESPONDIO, dao.getById("a1")?.state)
        assertEquals(0, libreta.reintentarEvidenciaSinGuardar())
    }

    @Test fun `P1 Codex r2-2 con la duda del chip todavia sin escribir la pantalla no la lee como dinero confirmado`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaTodo()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        assertEquals(PaymentAttemptLedger.ChipRechazo.SIN_ESCRIBIR, libreta.markChipRechazoTrasAprobacion("a1"))

        assertTrue(libreta.chipRechazoPendienteDeEscribir("a1"))
        assertFalse("es la duda de D5, no «hay evidencia de cobro»", libreta.tieneEvidenciaSinGuardar("a1"))
        assertTrue("pero no se olvida: se sigue reintentando", libreta.aprobacionPendienteDeGuardar("a1"))
        vuelveLaBase()
    }

    @Test fun `P1 Codex r2-1 la aprobacion que llega despues de que la fila se cerro como no se cobro deja la venta cercada`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        // Una liberación ganó primero (p. ej. el servidor la dio por no cobrada) y DESPUÉS el historial confirma el cargo.
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state = 'DESCARTADA' WHERE attempt_id = 'a1'")
        assertNull("sin la aprobación la mesa estaría libre", libreta.retencionDeLaVenta("mesa-200"))

        assertTrue(libreta.markHostResponded("a1", true, null, "ref-1", "auth-1"))

        val fila = dao.getById("a1")!!
        assertEquals("no se reabre", PaymentAttemptEntity.STATE_DESCARTADA, fila.state)
        assertEquals(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED, fila.serverProcessorEvidence)
        assertEquals("la mesa queda cercada", "a1", libreta.retencionDeLaVenta("mesa-200")?.attemptId)
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }
}
