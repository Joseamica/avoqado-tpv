package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PaymentAttemptRoomTest {
    @Test fun `two ledger instances cannot reserve preparing kernel work concurrently across venues`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val first = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            val second = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            val start = kotlinx.coroutines.CompletableDeferred<Unit>()
            val one = async(kotlinx.coroutines.Dispatchers.IO) {
                start.await()
                first.openAttempt("one", "venue-a", "BLUMON", 100, 0, "FAST", "{}")
            }
            val two = async(kotlinx.coroutines.Dispatchers.IO) {
                start.await()
                second.openAttempt("two", "venue-b", "ANGELPAY", 100, 0, "FAST", "{}")
            }
            start.complete(Unit)
            val admitted = listOf(one.await(), two.await())
            assertEquals("A terminal has one durable pre-kernel reservation, independent of order/venue", 1, admitted.count { it })
            val winner = if (admitted[0]) "one" else "two"
            assertTrue(first.markDiscardedBeforeCharge(winner, "confirmed_pre_sdk_cancel"))
            assertTrue(second.openAttempt("after-safe-discard", "venue-b", "BLUMON", 100, 0, "FAST", "{}"))
        } finally { db.close() }
    }

    @Test fun `P1 una negativa explicita del kernel libera la terminal, una incierta la retiene`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)

            // La tarjeta se acerca: el kernel ya corre y NADIE puede descartar esa fila.
            assertTrue(ledger.openAttempt("rechazado", "venue", "BLUMON", 100, 0, "FAST", "{}"))
            assertTrue(ledger.markKernelEntered("rechazado"))
            assertEquals("KERNEL_ACTIVO", db.paymentAttemptDao().getById("rechazado")?.state)
            assertFalse("KERNEL_ACTIVO no es descartable: el kernel pudo aprobar solo",
                ledger.markDiscardedBeforeCharge("rechazado", "user_cancel"))
            assertFalse("y mientras corre, la terminal esta tomada",
                ledger.openAttempt("mientras-corre", "venue", "BLUMON", 100, 0, "FAST", "{}"))

            // El kernel dijo que NO antes de salir a autorizar: no hubo cobro, la caja sigue viva.
            assertTrue(ledger.markKernelRefused("rechazado", "DENIED_BY_KERNEL"))
            assertEquals("DESCARTADA", db.paymentAttemptDao().getById("rechazado")?.state)
            assertTrue("un rechazo del kernel no puede dejar la caja sin cobrar",
                ledger.openAttempt("siguiente-venta", "venue", "BLUMON", 100, 0, "FAST", "{}"))

            // En cambio un desenlace INCIERTO retiene: pudo haber cobro.
            assertTrue(ledger.markKernelEntered("siguiente-venta"))
            ledger.markIndeterminate("siguiente-venta", "TIMEOUT")
            assertEquals("INDETERMINADO", db.paymentAttemptDao().getById("siguiente-venta")?.state)
            assertFalse("un TIMEOUT del kernel NO libera la terminal",
                ledger.openAttempt("tercera", "venue", "BLUMON", 100, 0, "FAST", "{}"))
        } finally { db.close() }
    }

    @Test fun `review recreated ledger rejects orderless authorization while terminal has unknown`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val first = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            assertTrue(first.openAttempt("old", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
            assertTrue(first.markAuthorizing("old"))
            first.markIndeterminate("old", "process died")
            val recreated = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            val admitted = recreated.openAttempt("new", "venue", "BLUMON", 200, 0, "FAST", "{}") &&
                recreated.markAuthorizing("new")
            assertFalse("An orderless sale must retain the terminal execution hold across recreation", admitted)
            assertEquals("INDETERMINADO", db.paymentAttemptDao().getById("old")?.state)
        } finally { db.close() }
    }

    @Test fun `unknown survives new ledger reader and remains venue scoped`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.paymentAttemptDao()
            val row = PaymentAttemptEntity("attempt", "venue", "BLUMON", state = "INDETERMINADO",
                amountCents = 100, tipCents = 0, recordingRoute = "ORDER", paymentContextJson = """{"orderId":"order"}""",
                createdAt = 1, updatedAt = 1)
            dao.insert(row)
            dao.insert(row.copy(attemptId = "other", venueId = "other"))
            dao.insert(row.copy(attemptId = "done", state = "REGISTRADO"))
            assertEquals(1, dao.observeUnresolvedCount("venue").first())
            assertEquals("attempt", dao.findUnresolvedOrder("venue", "\"orderId\":\"order\"")?.attemptId)
            assertNull(dao.findUnresolvedOrder("other-venue", "\"orderId\":\"order\""))
            dao.casTransition("attempt", listOf("INDETERMINADO"), "REGISTRADO", 2)
            assertEquals(0, dao.observeUnresolvedCount("venue").first())
        } finally { db.close() }
    }
}
