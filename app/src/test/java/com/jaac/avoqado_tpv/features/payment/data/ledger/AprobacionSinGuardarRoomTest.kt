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
 * aprobación mientras el proceso vive (la pantalla no dice «Aprobado» a secas y ninguna salida «no se cobró» cierra ese intento),
 * la reintenta UNA vez en el acto, la suelta cuando el camino de siempre (AUTORIZADO, registro, cola, D5) la deja a salvo, y NO la
 * reescribe después desde otros caminos (rediseño tras la pasada exhaustiva de Codex r4).
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
        assertTrue("y la revisión de la pantalla lo confirma", libreta.revisarAprobacionPendiente("a1"))
        vuelveLaBase()
        assertEquals("la fila todavía no lo dice", PaymentAttemptEntity.STATE_AUTORIZANDO, dao.getById("a1")?.state)
    }

    @Test fun `P1 mientras no quede a salvo ninguna salida dice no se cobro sobre ese intento`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))

        assertFalse("un rechazo tardío no cierra el intento que el banco aprobó",
            libreta.markHostResponded("a1", false, null, null, null))
        assertEquals(PaymentAttemptEntity.STATE_AUTORIZANDO, dao.getById("a1")?.state)
        assertEquals("la mesa sigue cercada mientras el proceso vive", "a1", libreta.retencionDeLaVenta("mesa-200")?.attemptId)
        assertTrue(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P1 el camino de siempre la deja a salvo - AUTORIZADO cerca la mesa aunque el proceso muera despues`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        vuelveLaBase()

        libreta.markAuthorized("a1", "****4242", "VISA", "CHIP")

        assertFalse("AUTORIZADO (con su marca del banco) la deja a salvo: se suelta", libreta.aprobacionPendienteDeGuardar("a1"))
        // El proceso muere antes del registro y de la cola. Arranca otro.
        val nuevo = proceso()
        nuevo.cuarentenaDeHuerfanos()
        assertEquals("los $50 no se pierden: la mesa sigue cercada", "a1", nuevo.retencionDeLaVenta("mesa-200")?.attemptId)
        assertFalse("la mesa no se vuelve a cobrar",
            nuevo.openAttempt("otra-vez", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 20000, 0, "ORDER", mesa))
        assertEquals("y la recuperación la registra", listOf("a1"),
            dao.getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, System.currentTimeMillis()).map { it.attemptId })
    }

    @Test fun `P1 tambien la suelta el registro y la cola`() = runTest {
        for ((paso, ultimo) in listOf("REGISTRADO" to 2)) {
            val libreta = proceso()
            val id = "a-$paso"
            assertTrue(libreta.openAttempt(id, venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}"))
            assertTrue(libreta.markAuthorizing(id))
            fallaTodo()
            assertFalse(libreta.markHostResponded(id, true, "op", "ref", "auth"))
            libreta.markAuthorized(id, null, null, null)
            assertTrue("con la base rota AUTORIZADO tampoco entró", libreta.aprobacionPendienteDeGuardar(id))
            vuelveLaBase()
            libreta.markAuthorized(id, null, null, null)
            if (ultimo == 1) libreta.markRecordFailed(id, "sin red") else libreta.markRecorded(id)
            assertEquals(paso, dao.getById(id)?.state)
            assertFalse("[$paso] ya no queda pendiente", libreta.aprobacionPendienteDeGuardar(id))
        }
    }

    @Test fun `P1 un veredicto del servidor que la registra la suelta en la revision de la pantalla (Codex r4 H5)`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        vuelveLaBase()
        // Otro escritor (aquí, a mano: como el veredicto RECORDED del servidor) lleva la fila a REGISTRADO.
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state = 'REGISTRADO' WHERE attempt_id = 'a1'")

        assertFalse(libreta.revisarAprobacionPendiente("a1"))
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P1 nunca la reescribe despues desde otro camino - la siguiente reserva no toca la fila (Codex r4 H1 a H4)`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        libreta.marcarSdkRegreso("a1")
        vuelveLaBase()

        // La siguiente reserva y la pasada de la recuperación corren (la fila AUTORIZANDO de este proceso aparta el aparato como
        // siempre; lo que se comprueba es que ninguna de las dos reescriba la aprobación por su cuenta).
        libreta.openAttempt("otra", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}")
        assertEquals(0, libreta.reintentarEvidenciaSinGuardar())
        val fila = dao.getById("a1")!!
        assertEquals("nadie la reescribió", PaymentAttemptEntity.STATE_AUTORIZANDO, fila.state)
        assertNull(fila.hostApproved)
        assertTrue(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P1 el reintento inmediato salva una falla pasajera`() = runTest {
        // Falla sólo la PRIMERA escritura (como un SQLITE_BUSY). Room revierte su transacción entera, así que la falla de una
        // sola vez se simula con el DAO.
        val dudoso = mockk<PaymentAttemptDao>(relaxed = true)
        io.mockk.coEvery { dudoso.casHostResponded(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("database is locked") andThen 1
        val libreta = PaymentAttemptLedger(dudoso, settings)

        assertTrue(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))

        io.mockk.coVerify(exactly = 2) { dudoso.casHostResponded("a1", any(), PaymentAttemptEntity.STATE_HOST_RESPONDIO, any(), "op-1", "ref-1", "auth-1", true) }
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P2 repetir la aprobacion sobre una fila ya aprobada no deja nada pendiente (camino sin sesion, D3)`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        assertTrue(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))

        // Contrato de siempre: esta llamada no escribió nada (la fila ya la tenía) ⇒ false, y nada queda pendiente.
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P2 una duda del chip (D5) escrita la suelta al instante y no se vuelve a marcar como aprobada`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        assertEquals(PaymentAttemptLedger.ChipRechazo.EN_DUDA, libreta.markChipRechazoTrasAprobacion("a1"))

        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
        assertFalse("la pantalla la ve como duda, no como «hay evidencia de cobro»", libreta.tieneEvidenciaSinGuardar("a1"))
        vuelveLaBase()
        assertEquals(0, libreta.reintentarEvidenciaSinGuardar())
        val fila = dao.getById("a1")!!
        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, fila.state)
        assertEquals(PaymentAttemptEntity.MOTIVO_CHIP_RECHAZO_TRAS_APROBACION, fila.lastError)
        assertNull("no se sabe si hubo cargo: la duda no lleva host_approved", fila.hostApproved)
        assertEquals("y sigue cercando la mesa", "a1", libreta.retencionDeLaVenta("mesa-200")?.attemptId)
        assertTrue(dao.getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, System.currentTimeMillis()).isEmpty())
    }

    @Test fun `P1 Codex r2-2 con la duda del chip todavia sin escribir la pantalla no la lee como dinero confirmado`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaTodo()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        assertEquals(PaymentAttemptLedger.ChipRechazo.SIN_ESCRIBIR, libreta.markChipRechazoTrasAprobacion("a1"))

        assertFalse("es la duda de D5, no «hay evidencia de cobro»", libreta.tieneEvidenciaSinGuardar("a1"))
        vuelveLaBase()
        // Codex r3 #1 / r4 H1: ninguna pasada reescribe la aprobación antes que la duda.
        assertEquals(0, libreta.reintentarEvidenciaSinGuardar())
        libreta.reintentarChipRechazo("a1")
        val fila = dao.getById("a1")!!
        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, fila.state)
        assertNull(fila.hostApproved)
        assertTrue(dao.getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, System.currentTimeMillis()).isEmpty())
    }

    @Test fun `P1 Codex r2-1 la aprobacion que llega despues de que la fila se cerro como no se cobro deja la venta cercada`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state = 'DESCARTADA' WHERE attempt_id = 'a1'")
        assertNull("sin la aprobación la mesa estaría libre", libreta.retencionDeLaVenta("mesa-200"))

        assertTrue(libreta.markHostResponded("a1", true, null, "ref-1", "auth-1"))

        val fila = dao.getById("a1")!!
        assertEquals("no se reabre", PaymentAttemptEntity.STATE_DESCARTADA, fila.state)
        assertEquals(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED, fila.serverProcessorEvidence)
        assertEquals("la mesa queda cercada", "a1", libreta.retencionDeLaVenta("mesa-200")?.attemptId)
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `control un rechazo del banco que no se pudo escribir no se recuerda como dinero`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaTodo()
        assertFalse(libreta.markHostResponded("a1", false, null, null, null))
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
        assertFalse(libreta.tieneEvidenciaSinGuardar("a1"))
        vuelveLaBase()
    }

    @Test fun `control una devolucion ya cerrada que el banco aprueba tarde no se marca como venta con dinero ni traba la terminal`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("dev", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 5000, 0, "FAST", "{}",
            kind = PaymentAttemptEntity.KIND_REFUND))
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state = 'DESCARTADA' WHERE attempt_id = 'dev'")

        assertFalse(libreta.markHostResponded("dev", true, null, "ref-dev", null))

        assertNull(dao.getById("dev")?.serverProcessorEvidence)
        assertFalse(libreta.aprobacionPendienteDeGuardar("dev"))
        assertTrue(libreta.openAttempt("otra", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}"))
    }

    @Test fun `P2 con la base sana nada cambia - la aprobacion se escribe al instante`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)

        assertTrue(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))

        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
        assertEquals(PaymentAttemptEntity.STATE_HOST_RESPONDIO, dao.getById("a1")?.state)
        assertEquals(0, libreta.reintentarEvidenciaSinGuardar())
    }

    @Test fun `P1 Codex r5-2 la duda del chip que escribe la puerta de OTRO cobro tambien suelta la aprobacion`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaTodo()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        assertEquals(PaymentAttemptLedger.ChipRechazo.SIN_ESCRIBIR, libreta.markChipRechazoTrasAprobacion("a1"))
        vuelveLaBase()

        // Otra venta pasa por la puerta: ahí se escribe la duda (D5).
        libreta.openAttempt("otra", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}")

        assertEquals(PaymentAttemptEntity.STATE_INDETERMINADO, dao.getById("a1")?.state)
        assertFalse(libreta.chipRechazoPendienteDeEscribir("a1"))
        assertFalse("la aprobación ya no queda colgada", libreta.aprobacionPendienteDeGuardar("a1"))
        assertFalse("la pantalla ve la duda con su salida, no «hay dinero»", libreta.tieneEvidenciaSinGuardar("a1"))
    }

    @Test fun `P1 Codex r5-1 si la marca de dinero sobre la fila descartada falla, la puerta la reintenta y no deja reservar`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state = 'DESCARTADA' WHERE attempt_id = 'a1'")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER falla_marca BEFORE UPDATE ON payment_attempts WHEN NEW.server_processor_evidence IS 'APPROVED' " +
                "BEGIN SELECT RAISE(ABORT, 'disco lleno'); END")

        assertFalse(libreta.markHostResponded("a1", true, null, "ref-1", "auth-1"))

        assertTrue("es dinero sin escribir", libreta.tieneEvidenciaSinGuardar("a1"))
        assertFalse("la misma mesa no se vuelve a cobrar mientras falte",
            libreta.openAttempt("otra-vez", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 20000, 0, "ORDER", mesa))

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER falla_marca")
        libreta.openAttempt("otra", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 100, 0, "FAST", "{}")

        assertEquals(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED, dao.getById("a1")?.serverProcessorEvidence)
        assertEquals("la mesa queda cercada en la base", "a1", libreta.retencionDeLaVenta("mesa-200")?.attemptId)
        assertFalse(libreta.tieneEvidenciaSinGuardar("a1"))
    }

    @Test fun `P1 Codex r6-1 si ni la fila se puede leer, la puerta no deja reservar y al leerla marca la descartada`() = runTest {
        val dudoso = io.mockk.spyk(dao)
        io.mockk.coEvery { dudoso.getById("a1") } throws IllegalStateException("disco lleno") andThenAnswer { callOriginal() }
        val libreta = PaymentAttemptLedger(dudoso, settings)
        cobrandoCincuentaDeLaMesa(libreta)
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state = 'DESCARTADA' WHERE attempt_id = 'a1'")

        assertFalse(libreta.markHostResponded("a1", true, null, "ref-1", "auth-1"))

        // La siguiente decisión pasa por la puerta: ya puede leer la fila, ve la DESCARTADA y le pone su marca de dinero.
        assertFalse("la misma mesa no se vuelve a cobrar",
            libreta.openAttempt("otra-vez", venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 20000, 0, "ORDER", mesa))
        assertEquals(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED, dao.getById("a1")?.serverProcessorEvidence)
        assertEquals("a1", libreta.retencionDeLaVenta("mesa-200")?.attemptId)
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P1 Codex r13 un registro fallido sin la marca del banco NO quita el aviso - lo quita la cola`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        // El barrido ya la había puesto en duda por reloj (el cobro tardó), y fallan la aprobación y AUTORIZADO.
        assertEquals(1, dao.quarantineStaleAuthorizing(venue, Long.MAX_VALUE, System.currentTimeMillis()))
        fallaTodo()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        libreta.markAuthorized("a1", null, null, null)
        vuelveLaBase()
        libreta.markRecordFailed("a1", "sin red")

        assertEquals(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO, dao.getById("a1")?.state)
        assertTrue("sin host_approved la recuperación no lo toma: el aviso sigue", libreta.aprobacionPendienteDeGuardar("a1"))
        assertTrue(libreta.revisarAprobacionPendiente("a1"))
    }

    @Test fun `P1 Codex r16-1 AUTORIZADO sella la marca del banco y asi un registro fallido sigue siendo recuperable`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        // Falla sólo la PRIMERA vez que se escribe host_approved (las dos escrituras del host van juntas en la memoria de abajo).
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))
        vuelveLaBase()

        libreta.markAuthorized("a1", "****4242", "VISA", "CHIP")
        libreta.markRecordFailed("a1", "sin red")

        val fila = dao.getById("a1")!!
        assertEquals(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO, fila.state)
        assertEquals("AUTORIZADO selló la marca del banco", true, fila.hostApproved)
        assertEquals("ref-1", fila.referenceNumber)
        assertEquals("y la recuperación lo toma", listOf("a1"),
            dao.getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, System.currentTimeMillis()).map { it.attemptId })
        assertFalse(libreta.aprobacionPendienteDeGuardar("a1"))
    }

    @Test fun `P1 Codex r16-1 si AUTORIZADO entra pero el sello no, la proteccion y el aviso siguen`() = runTest {
        val libreta = proceso()
        cobrandoCincuentaDeLaMesa(libreta)
        fallaLaAprobacion()
        assertFalse(libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1"))

        libreta.markAuthorized("a1", "****4242", "VISA", "CHIP")

        assertEquals(PaymentAttemptEntity.STATE_AUTORIZADO, dao.getById("a1")?.state)
        assertNull(dao.getById("a1")?.hostApproved)
        assertTrue("sin la marca no se suelta", libreta.aprobacionPendienteDeGuardar("a1"))
        vuelveLaBase()
    }
}
