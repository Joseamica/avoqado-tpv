package com.jaac.avoqado_tpv.features.ordering.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant

/**
 * D23 (encargo bb2206): tras cobrar con tarjeta, la TPV anota en la cuenta qué comercio cobró (`updateOrderMerchant`).
 * Antes lo hacía con un INSERT … REPLACE, que en SQLite BORRA la fila y la vuelve a crear: la llave foránea de los renglones
 * (`ON DELETE CASCADE`) se llevaba los productos de la cuenta, también los que aún no subían al SERVIDOR.
 * Base real de Room (en memoria), porque el defecto vive en SQLite y no en el código.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ComercioSinBorrarRenglonesTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var coordinator: OrderSyncCoordinator

    private val venue = "venue-1"
    private val cuenta = "cuenta-srv-1"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        coordinator = OrderSyncCoordinator(db.draftOrderDao(), db.draftOrderItemDao(), mockk(relaxed = true))
    }

    @After fun tearDown() { coordinator.cleanup(); db.close() }

    private val tacos = OrderItem(
        id = "renglon-srv-tacos", orderId = cuenta, productId = "prod-tacos", productName = "Tacos", productSku = null,
        quantity = 1, unitPrice = BigDecimal("25.00"), totalPrice = BigDecimal("25.00"), notes = null,
        kitchenStatus = KitchenStatus.PENDING, createdAt = Instant.parse("2026-11-10T12:00:00Z"), sentToKitchenAt = null,
    )

    private fun cuentaDelServidor() = Order(
        id = cuenta, orderNumber = "ORD-1", venueId = venue, tableId = null, tableName = null, covers = 1, waiterId = null,
        waiterName = null, status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING, paymentStatus = PaymentStatus.PARTIAL,
        orderType = OrderType.TAKEOUT, items = listOf(tacos), subtotal = BigDecimal("25.00"), tax = BigDecimal.ZERO,
        total = BigDecimal("25.00"), remainingBalance = BigDecimal("25.00"), notes = null,
        createdAt = Instant.parse("2026-11-10T12:00:00Z"), updatedAt = Instant.parse("2026-11-10T12:00:00Z"), version = 4,
    )

    private suspend fun renglones() = db.draftOrderItemDao().getAllItemsByOrderId(cuenta)

    @Test fun `P1 anotar el comercio tras la tarjeta no borra los renglones de la cuenta, ni el que aún no sube`() = runTest {
        // TPV SOLA → Órdenes: la cuenta ya está en el SERVIDOR con unos tacos, y la cajera agrega un café sin red.
        coordinator.cacheBackendOrder(cuentaDelServidor())
        val cafe = coordinator.addItemToLocalOrder(cuenta, "prod-cafe", "Café", 1, "85.00", emptyList(), null)
        assertThat(renglones().map { it.id }).containsExactly("renglon-srv-tacos", cafe)

        // Abono con tarjeta: la pantalla de cobro anota el comercio en la cuenta.
        coordinator.updateOrderMerchant(cuenta, "cm-comercio-a", "Cuenta BBVA")

        val despues = renglones()
        assertThat(despues.map { it.id }).containsExactly("renglon-srv-tacos", cafe)
        assertThat(despues.single { it.id == cafe }.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_PENDING)

        val fila = db.draftOrderDao().getOrder(cuenta)!!
        assertThat(fila.merchantAccountId).isEqualTo("cm-comercio-a")
        assertThat(fila.merchantAccountName).isEqualTo("Cuenta BBVA")
        assertThat(fila.syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_PENDING)
        assertThat(fila.version).isEqualTo(4) // no toca lo demás de la cuenta
    }

    @Test fun `P2 sin la cuenta en el aparato no inventa una`() = runTest {
        coordinator.updateOrderMerchant("no-existe", "cm-comercio-a", "Cuenta BBVA")
        assertThat(db.draftOrderDao().getOrder("no-existe")).isNull()
    }
}
