package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.checkout.data.RetiroDeVentaSinCobroCore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D36: la pregunta que decide si «atrás» retira una venta de Cobrar. CUALQUIER rastro de un intento de cobro de ESA venta en esta
 * terminal dice que sí se tocó: un intento de la libreta en cualquier estado (también PREPARANDO y DESCARTADA) o un cobro en la cola en
 * cualquier estado. Otra venta no cuenta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class RastroDeCobroDeLaVentaRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
    }

    @After fun cerrar() = db.close()

    private suspend fun hayRastro(orderId: String) =
        dao.hayRastroDeCobroDeLaVenta(orderId, RetiroDeVentaSinCobroCore.fragmentoDeLaVenta(orderId))

    private suspend fun intento(orderId: String, state: String) = dao.insert(
        PaymentAttemptEntity(
            attemptId = "a-$orderId-$state", venueId = "venue-1", processor = "BLUMON", state = state,
            amountCents = 4000, tipCents = 0, recordingRoute = "ORDER",
            paymentContextJson = """{"amount":40.00,"orderId":"$orderId","orderNumber":"ORD-1"}""",
            createdAt = 1L, updatedAt = 1L,
        ),
    )

    private suspend fun enLaCola(orderId: String, status: String) = db.pendingPaymentDao().insertRow(
        PendingPaymentEntity(
            referenceNumber = "ref-$orderId", venueId = "venue-1", staffId = "staff-a", amount = "40.00", tip = "0.00", rating = null,
            merchantAccountId = "", blumonSerialNumber = "", maskedPan = null, cardBrand = null, entryMode = "CASH",
            isInternational = false, authorizationNumber = null, createdAt = 100L, orderId = orderId, syncStatus = status,
        ),
    )

    @Test
    fun `una venta sin nada en la terminal no tiene rastro`() = runTest {
        intento("otra-venta", PaymentAttemptEntity.STATE_AUTORIZANDO)
        enLaCola("otra-venta", "PENDING")

        assertThat(hayRastro("order-1")).isFalse()
    }

    @Test
    fun `P1 un intento de la libreta en CUALQUIER estado es rastro - tambien PREPARANDO y DESCARTADA`() = runTest {
        for ((i, estado) in listOf(
            PaymentAttemptEntity.STATE_PREPARANDO,
            PaymentAttemptEntity.STATE_DESCARTADA,
            PaymentAttemptEntity.STATE_AUTORIZANDO,
            PaymentAttemptEntity.STATE_INDETERMINADO,
        ).withIndex()) {
            val orden = "order-$i"
            intento(orden, estado)
            assertThat(hayRastro(orden)).isTrue()
        }
    }

    @Test
    fun `P1 un cobro en la cola en cualquier estado es rastro - tambien uno ya subido`() = runTest {
        enLaCola("order-1", "PENDING")
        enLaCola("order-2", "SUCCESS")

        assertThat(hayRastro("order-1")).isTrue()
        assertThat(hayRastro("order-2")).isTrue()
    }

    @Test
    fun `un id que contiene a otro no se confunde`() = runTest {
        intento("order-10", PaymentAttemptEntity.STATE_AUTORIZANDO)

        assertThat(hayRastro("order-1")).isFalse()
    }
}
