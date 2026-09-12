package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LedgerRecoveryLeaseRoomTest {
    private fun row(now: Long) = PaymentAttemptEntity(
        attemptId = "lease-attempt", venueId = "venue", processor = "BLUMON", state = "HOST_RESPONDIO",
        amountCents = 10000, tipCents = 0, recordingRoute = "FAST",
        paymentContextJson = Gson().toJson(PaymentContext.FastPayment(
            venueId = "venue", staffId = "original-staff", amount = BigDecimal("100.00"), tip = BigDecimal.ZERO,
            merchantAccountId = "merchant", deviceSerialNumber = "terminal", idempotencyKey = "lease-attempt")),
        hostApproved = true, authCode = "AUTH", referenceNumber = "REF", createdAt = now - 900000, updatedAt = now - 900000)

    @Test fun `review late acquisition cannot give reconnect worker an already expired lease`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            val now = System.currentTimeMillis()
            db.paymentAttemptDao().insert(row(now))
            var registrations = 0
            val fast = mockk<FastPaymentRecorder>()
            coEvery { fast.recordPayment(any(), any(), any(), any()) } coAnswers {
                registrations++
                entered.complete(Unit)
                finish.await()
                Result.failure<PaymentReceipt>(java.io.IOException("offline"))
            }
            val order = mockk<OrderPaymentRecorder>()
            // Models a sweep whose earlier candidates consumed more than the five-minute lease.
            val oldSweep = async { LedgerApprovalRecovery(db.paymentAttemptDao(), fast, order).recover("venue", now - 360000) }
            entered.await()
            val reconnect = async { LedgerApprovalRecovery(db.paymentAttemptDao(), fast, order).recover("venue", now) }
            // Release after reconnect has checked Room; both jobs always complete even on RED.
            kotlinx.coroutines.yield()
            finish.complete(Unit)
            oldSweep.await()
            reconnect.await()
            assertEquals("Only the current lease owner can start registration", 1, registrations)
        } finally { finish.complete(Unit); db.close() }
    }

    @Test fun `review delayed unknown sweep does not duplicate provider query on reconnect`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            val now = System.currentTimeMillis()
            val original = row(now)
            val json = com.google.gson.JsonParser.parseString(original.paymentContextJson).asJsonObject.apply {
                addProperty("processorAffiliation", "affiliation")
            }.toString()
            val dao = db.paymentAttemptDao()
            dao.insert(original.copy(processor = "ANGELPAY", state = "INDETERMINADO", hostApproved = null, paymentContextJson = json))
            val ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
            val verifier = mockk<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier>()
            var queries = 0
            coEvery { verifier.verificar(any(), any(), any(), any(), any(), any()) } coAnswers {
                queries++
                entered.complete(Unit)
                finish.await()
                com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro.NoSePudoVerificar("offline")
            }
            val first = async { LedgerUnknownRecovery(dao, ledger, verifier).recover("venue", now - 360000) }
            entered.await()
            val reconnect = async { LedgerUnknownRecovery(dao, ledger, verifier).recover("venue", now) }
            kotlinx.coroutines.yield()
            finish.complete(Unit)
            first.await()
            reconnect.await()
            assertEquals("A freshly acquired unknown-result lease cannot already be expired", 1, queries)
        } finally { finish.complete(Unit); db.close() }
    }

    @Test fun `review expired owner cannot overwrite state after another worker acquires lease`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            val now = System.currentTimeMillis()
            val dao = db.paymentAttemptDao()
            dao.insert(row(now))
            val fast = mockk<FastPaymentRecorder>()
            coEvery { fast.recordPayment(any(), any(), any(), any()) } coAnswers {
                entered.complete(Unit)
                finish.await()
                Result.failure<PaymentReceipt>(java.io.IOException("old request failed"))
            }
            // 🔴 Reloj REAL a propósito: `LedgerApprovalRecovery` envuelve el registro en un
            // `withTimeout(240_000)` de producción, y bajo `runTest` ese timeout usa el reloj
            // VIRTUAL — el scheduler salta los 4 minutos en cuanto el test se queda esperando y
            // lo dispara antes de que podamos completar `finish`. Correr el worker en
            // Dispatchers.Default deja el timeout en tiempo real (4 min de sobra) sin tocar
            // producción y sin debilitar lo que este test guarda: el CAS del lease.
            val oldWorker = async(kotlinx.coroutines.Dispatchers.Default) {
                LedgerApprovalRecovery(dao, fast, mockk()).recover("venue", now)
            }
            entered.await()
            val oldLease = requireNotNull(dao.getById("lease-attempt")?.leaseUntil)
            assertEquals(1, dao.claimRecovery("lease-attempt", "venue", oldLease + 1, oldLease + 600000))
            finish.complete(Unit)
            oldWorker.await()
            assertEquals("Stale owner's failure cannot modify successor's state", "HOST_RESPONDIO", dao.getById("lease-attempt")?.state)
        } finally { finish.complete(Unit); db.close() }
    }
}
