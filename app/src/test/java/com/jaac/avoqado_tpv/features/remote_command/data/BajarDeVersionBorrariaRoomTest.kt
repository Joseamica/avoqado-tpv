package com.jaac.avoqado_tpv.features.remote_command.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entity.LocalCryptoOperationEntity
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.features.remote_command.domain.hayAlgoQueBajarDeVersionBorraria
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.jaac.avoqado_tpv.features.verification.data.local.PROOF_ROW_NUMBER_ONLY
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D4 (encargo dd4185): bajar de versión borra la base entera (`fallbackToDestructiveMigrationOnDowngrade`). Antes de un
 * retroceso se preguntan estas dos cuentas; aquí se prueba contra Room de verdad QUÉ filas cuentan: todo lo que el
 * servidor no tiene, de todas las sucursales (lo que espera subir y lo que espera a una persona), y nada de lo resuelto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class BajarDeVersionBorrariaRoomTest {
    private lateinit var db: AvoqadoDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun intent(id: String, venue: String, seq: Long, status: String) =
        SyncIntentEntity(id = id, venueId = venue, seq = seq, type = "PAY_CASH", payloadJson = "{}", status = status)

    private fun caja(id: String, venue: String, opening: String, close: String = "NONE", local: String = "CLOSED",
        closeOperation: String? = if (close == "NONE") null else "op-$id") =
        LocalShiftSessionEntity(localSessionId = id, venueId = venue, openingKind = "LOCAL", openingOperationId = id,
            openingStatus = opening, originalOpenedAt = "2026-11-16T10:00:00Z", startingCash = "50.00",
            openedByStaffId = "staff", localStatus = local, openSlot = if (local == "OPEN") 1 else null,
            closeStatus = close, closeOperationId = closeOperation, createdAt = 1, updatedAt = 1)

    @Test fun `ventas de mesas por subir o rechazadas cuentan en todas las sucursales y las resueltas no`() = runTest {
        val dao = db.syncIntentDao()
        assertThat(dao.contarQueBajarDeVersionBorraria()).isEqualTo(0)
        dao.insert(intent("a", "v1", 1, SyncIntentEntity.STATUS_ACKED))
        dao.insert(intent("b", "v1", 2, SyncIntentEntity.STATUS_DISMISSED))
        dao.insert(intent("c", "v1", 3, SyncIntentEntity.STATUS_DISCARDED))
        assertThat(dao.contarQueBajarDeVersionBorraria()).isEqualTo(0)
        dao.insert(intent("d", "v1", 4, SyncIntentEntity.STATUS_PENDING))
        dao.insert(intent("e", "v1", 5, SyncIntentEntity.STATUS_REJECTED))
        dao.insert(intent("f", "v2", 1, SyncIntentEntity.STATUS_PENDING))
        assertThat(dao.contarQueBajarDeVersionBorraria()).isEqualTo(3)
    }

    @Test fun `cajas que el servidor no tiene cuentan y las confirmadas o el cierre solo observado no`() = runTest {
        val dao = db.localShiftSessionDao()
        dao.insertar(caja("c1", "v1", opening = "CONFIRMED", close = "CONFIRMED"))
        dao.insertar(caja("c2", "v1", opening = "CONFIRMED", local = "OPEN"))
        // D65: el servidor cerró la caja y este aparato sólo lo observó (sin gesto propio).
        dao.insertar(caja("c3", "v2", opening = "CONFIRMED", close = "REVIEW", closeOperation = null))
        assertThat(dao.contarQueBajarDeVersionBorraria()).isEqualTo(0)
        dao.insertar(caja("c4", "v3", opening = "PENDING"))
        dao.insertar(caja("c5", "v4", opening = "SYNCING", local = "OPEN"))
        dao.insertar(caja("c6", "v5", opening = "REVIEW"))
        dao.insertar(caja("c7", "v6", opening = "REVIEW", close = "PENDING"))
        dao.insertar(caja("c8", "v7", opening = "CONFIRMED", close = "SYNCING"))
        dao.insertar(caja("c9", "v8", opening = "CONFIRMED", close = "REVIEW"))
        assertThat(dao.contarQueBajarDeVersionBorraria()).isEqualTo(6)
    }

    private fun prueba(key: String, venue: String, status: String) = ProofOfSaleQueueEntity(
        idempotencyKey = key, kind = PROOF_ROW_NUMBER_ONLY, venueId = venue, staffId = "promotor", paymentId = "pago-$key",
        verificationId = null, slot = "VINCULACION", localPath = null, linkingNumber = "5512345678", portedNumber = null,
        capturedAt = 1, captureSeq = 1, captureDeviceId = "AVQD-1", syncStatus = status, createdAt = 1)

    private fun cripto(key: String, venue: String, financial: String?, recorded: Boolean?) = LocalCryptoOperationEntity(
        idempotencyKey = key, venueId = venue, staffId = "staff", requestJson = "{}", financialStatus = financial,
        financialRecorded = recorded, createdAt = 1, updatedAt = 1)

    /**
     * hz:nube0000:naaa0db5: el candado sólo contaba Mesas y Caja. Un retroceso también borraba, sin aviso, las fotos y los números
     * de vinculación de PlayTelecom por subir o por corregir, y los cobros cripto que Caja todavía tiene pendientes.
     */
    @Test fun `fotos y numeros de vinculacion por subir y cobros cripto pendientes tambien frenan el retroceso`() = runTest {
        val fotos = db.proofOfSaleQueueDao()
        val criptos = db.localCryptoOperationDao()
        fotos.insertRow(prueba("subida", "v1", "SUCCESS"))
        criptos.insertar(cripto("acreditado", "v1", financial = "COMPLETED", recorded = true))
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isFalse()

        for (status in listOf("PENDING", "SYNCING", "FAILED", "NEEDS_ATTENTION")) {
            fotos.insertRow(prueba("p-$status", "v2", status))
            assertWithMessage(status).that(db.hayAlgoQueBajarDeVersionBorraria()).isTrue()
            fotos.delete("p-$status")
        }
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isFalse()

        // Codex r1 #6: un QR vencido (FAILED) ya tiene su desenlace en el SERVIDOR: no frena para siempre.
        criptos.insertar(cripto("vencido", "v3", financial = "FAILED", recorded = false))
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isFalse()
        // Sin desenlace del SERVIDOR todavía.
        criptos.insertar(cripto("sin-respuesta", "v3", financial = null, recorded = null))
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isTrue()
    }

    private fun intento(id: String, state: String, kind: String = "SALE") = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
        attemptId = id, venueId = "v1", processor = "ANGELPAY", state = state, kind = kind, amountCents = 1000, tipCents = 0,
        recordingRoute = "FAST", paymentContextJson = "{}", createdAt = 1, updatedAt = 1)

    /** Codex r1 #4: la libreta (cobros Y devoluciones) y la bandeja del POS también sólo existen en la terminal. */
    @Test fun `cobros o devoluciones con tarjeta sin resolver y solicitudes del POS en curso tambien frenan el retroceso`() = runTest {
        val libreta = db.paymentAttemptDao()
        libreta.insert(intento("registrado", "REGISTRADO"))
        libreta.insert(intento("descartado", "DESCARTADA"))
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isFalse()

        libreta.insert(intento("devolucion-en-duda", "INDETERMINADO", kind = "REFUND"))
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isTrue()
        db.openHelper.writableDatabase.execSQL("DELETE FROM payment_attempts WHERE attempt_id = 'devolucion-en-duda'")

        libreta.insert(intento("cerrado-con-dinero", "DESCARTADA").copy(serverProcessorEvidence = "APPROVED"))
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isTrue()
        db.openHelper.writableDatabase.execSQL("DELETE FROM payment_attempts WHERE attempt_id = 'cerrado-con-dinero'")
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isFalse()

        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO remote_payment_requests (request_id, venue_id, amount_cents, tip_cents, skip_review, source_timestamp, status, created_at, updated_at) " +
                "VALUES ('pos-1', 'v1', 1000, 0, 0, '2026-11-17T10:00:00Z', 'RESOLVED', 1, 1)")
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isFalse()
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO remote_payment_requests (request_id, venue_id, amount_cents, tip_cents, skip_review, source_timestamp, status, created_at, updated_at) " +
                "VALUES ('pos-2', 'v1', 1000, 0, 0, '2026-11-17T10:00:00Z', 'PROCESSING', 1, 1)")
        assertThat(db.hayAlgoQueBajarDeVersionBorraria()).isTrue()
    }
}
