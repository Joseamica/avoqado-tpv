package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tienda híbrida (R78 a, R83 I2) con la base real: lo que la cola de ESTA terminal sabe del cobro de un folio. Juntas, las dos
 * consultas son el mismo predicado que frena el cierre de caja (`bloqueanCierre`), acotado a la cuenta del folio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CobroDelFolioEnColaRoomTest {
    private lateinit var db: AvoqadoDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private suspend fun fila(
        referencia: String,
        estado: String,
        orderId: String? = "ord-folio",
        venueId: String = "venue-1",
        revisada: Boolean = false,
        requiereRevision: Boolean = false,
    ) {
        db.pendingPaymentDao().insert(
            PendingPaymentEntity(
                referenceNumber = referencia, venueId = venueId, staffId = "staff-1", amount = "85.00", tip = "0.00", rating = null,
                merchantAccountId = "", blumonSerialNumber = "", maskedPan = null, cardBrand = null, entryMode = "OTHER",
                isInternational = false, authorizationNumber = "EFECTIVO", orderId = orderId, createdAt = 1_000L,
                syncStatus = estado, acknowledged = revisada, requiereRevision = requiereRevision,
            ),
        )
    }

    @Test
    fun `un cobro del folio que no ha subido se ve como subiendose`() = runTest {
        fila("CASH-1", PendingPaymentEntity.SYNC_STATUS_PENDING)
        fila("CASH-2", "SYNCING", orderId = "ord-otro")

        assertThat(db.pendingPaymentDao().hayCobroSubiendose("venue-1", "ord-folio")).isTrue()
        assertThat(db.pendingPaymentDao().hayCobroSinRegistrar("venue-1", "ord-folio")).isFalse()
    }

    @Test
    fun `R83 un cobro FAILED sin revisar del folio frena, revisado ya no`() = runTest {
        fila("CASH-1", "FAILED")
        assertThat(db.pendingPaymentDao().hayCobroSinRegistrar("venue-1", "ord-folio")).isTrue()
        assertThat(db.pendingPaymentDao().hayCobroSubiendose("venue-1", "ord-folio")).isFalse()

        val revisado = "ord-revisado"
        fila("CASH-2", "FAILED", orderId = revisado, revisada = true)
        assertThat(db.pendingPaymentDao().hayCobroSinRegistrar("venue-1", revisado)).isFalse()
    }

    @Test
    fun `R83 un cobro que pide revision sin revisar tambien frena, aunque haya subido`() = runTest {
        fila("CASH-1", "SUCCESS", requiereRevision = true)
        assertThat(db.pendingPaymentDao().hayCobroSinRegistrar("venue-1", "ord-folio")).isTrue()
    }

    @Test
    fun `lo que ya subio, lo de otra cuenta y lo de otro negocio no frenan`() = runTest {
        fila("CASH-1", "SUCCESS")
        fila("CASH-2", "FAILED", orderId = "ord-otro")
        fila("CASH-3", "PENDING", venueId = "venue-2")

        assertThat(db.pendingPaymentDao().hayCobroSubiendose("venue-1", "ord-folio")).isFalse()
        assertThat(db.pendingPaymentDao().hayCobroSinRegistrar("venue-1", "ord-folio")).isFalse()
    }

    // R89 (c): la libreta, con la base real. Es la MISMA cerca 2 de `reserveTerminal` (dinero en juego de la venta), por el
    // `"orderId":"…"` del contexto que guardan la PAX y la Nexgo.
    private suspend fun intento(id: String, estado: String, orderId: String = "ord-folio", processor: String = "BLUMON") {
        db.paymentAttemptDao().insert(
            com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
                attemptId = id, venueId = "venue-1", processor = processor, state = estado, amountCents = 8_500, tipCents = 0,
                recordingRoute = "ORDER", paymentContextJson = """{"venueId":"venue-1","orderId":"$orderId","amount":85.00}""",
                hostApproved = true, createdAt = 1_000L, updatedAt = 1_000L,
            ),
        )
    }

    private fun libreta() = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(db.paymentAttemptDao(), io.mockk.mockk(relaxed = true))

    @Test
    fun `R89 un cobro con tarjeta aprobado y sin registrar de la venta esta en la libreta, en PAX y en Nexgo`() = runTest {
        intento("a1", "REGISTRO_FALLIDO")
        intento("a2", "AUTORIZADO", orderId = "ord-nexgo", processor = "ANGELPAY")

        assertThat(libreta().hayDineroEnJuegoDeLaVenta("ord-folio")).isTrue()
        assertThat(libreta().hayDineroEnJuegoDeLaVenta("ord-nexgo")).isTrue()
    }

    @Test
    fun `R89 registrado, entregado a la cola u otra venta no cuentan en la libreta`() = runTest {
        intento("a1", "REGISTRADO")
        intento("a2", "ENTREGADA_A_COLA", orderId = "ord-cola") // lo cuenta la cola, no la libreta
        intento("a3", "REGISTRO_FALLIDO", orderId = "ord-otra")

        assertThat(libreta().hayDineroEnJuegoDeLaVenta("ord-folio")).isFalse()
        assertThat(libreta().hayDineroEnJuegoDeLaVenta("ord-cola")).isFalse()
    }
}
