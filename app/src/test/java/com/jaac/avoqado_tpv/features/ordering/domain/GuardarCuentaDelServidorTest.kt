package com.jaac.avoqado_tpv.features.ordering.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
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
 * D16 v4.2 · Regla A (hz:e82938e1:D16, Codex r5 #1 y #6): una lectura del SERVIDOR entra a Room sólo por una puerta
 * (`cacheBackendOrder` → `guardarCuentaDelServidor`), en una transacción:
 *  1. lectura vieja (versión menor que la de Room): no se toca nada;
 *  2. con renglones en cola (PENDING o DELETED): los renglones de Room no se tocan; del SERVIDOR se toman lo pagado y el descuento, y
 *     el total es el del SERVIDOR más lo que suman los renglones que el SERVIDOR todavía no tiene;
 *  3. sin cola: Room queda igual al SERVIDOR (como hoy).
 * Y un cambio local de productos mueve el total exactamente por lo que cambia el subtotal (antes lo igualaba y perdía el descuento).
 * Room real (en memoria): lo que se prueba es justo el borrado en cascada del `insert` REPLACE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class GuardarCuentaDelServidorTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var coordinator: OrderSyncCoordinator

    private val venue = "venue-1"
    private val cuenta = "cuenta-srv-1"
    private val tacos = renglon("renglon-srv-tacos", "prod-tacos", "Tacos", "100.00")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        coordinator = OrderSyncCoordinator(db.draftOrderDao(), db.draftOrderItemDao(), mockk(relaxed = true))
    }

    @After fun tearDown() { coordinator.cleanup(); db.close() }

    private fun renglon(id: String, productId: String, nombre: String, precio: String) = OrderItem(
        id = id, orderId = cuenta, productId = productId, productName = nombre, productSku = null, quantity = 1,
        unitPrice = BigDecimal(precio), totalPrice = BigDecimal(precio), notes = null, kitchenStatus = KitchenStatus.PENDING,
        createdAt = Instant.parse("2026-11-18T12:00:00Z"), sentToKitchenAt = null,
    )

    /** La cuenta del SERVIDOR: subtotal = suma de renglones, total = subtotal − descuento. */
    private fun delServidor(version: Int, descuento: String = "0.00", pagado: String = "0.00", vararg renglones: OrderItem): Order {
        val subtotal = renglones.fold(BigDecimal.ZERO) { suma, r -> suma + r.totalPrice }
        val total = subtotal - BigDecimal(descuento)
        return Order(
            id = cuenta, orderNumber = "ORD-1", venueId = venue, tableId = null, tableName = null, covers = 1, waiterId = null,
            waiterName = null, status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING,
            paymentStatus = if (BigDecimal(pagado).signum() > 0) PaymentStatus.PARTIAL else PaymentStatus.PENDING,
            orderType = OrderType.TAKEOUT, items = renglones.toList(), subtotal = subtotal, discountAmount = BigDecimal(descuento),
            tax = BigDecimal.ZERO, total = total, paidAmount = BigDecimal(pagado), remainingBalance = total - BigDecimal(pagado),
            notes = null, createdAt = Instant.parse("2026-11-18T12:00:00Z"), updatedAt = Instant.parse("2026-11-18T12:00:00Z"),
            version = version,
        )
    }

    private suspend fun agregarCafe(): String =
        coordinator.addItemToLocalOrder(cuenta, "prod-cafe", "Café", 1, "50.00", emptyList(), null)

    private suspend fun enLaPantalla() = coordinator.getLocalOrder(cuenta)!!

    @Test fun `con un producto sin subir, la lectura del SERVIDOR no lo borra y el total lo incluye`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, "0.00", "0.00", tacos))
        agregarCafe()  // $50, todavía en cola

        // Llega una lectura (otra caja puso $10 de descuento) que no conoce el café.
        coordinator.cacheBackendOrder(delServidor(3, "10.00", "0.00", tacos))

        val cuentaEnRoom = enLaPantalla()
        assertThat(cuentaEnRoom.items.map { it.productId }).containsExactly("prod-tacos", "prod-cafe")
        assertThat(cuentaEnRoom.discountAmount).isEqualToIgnoringScale("10.00")
        assertThat(cuentaEnRoom.total).isEqualToIgnoringScale("140.00")  // 90 del SERVIDOR + 50 del café
        assertThat(cuentaEnRoom.remainingBalance).isEqualToIgnoringScale("140.00")
        // El café sigue en cola: la cola lo sube después.
        assertThat(db.draftOrderItemDao().getPendingItemsByOrder(cuenta).map { it.productId }).containsExactly("prod-cafe")
    }

    @Test fun `con un retiro en cola, la lectura no revive ni borra el renglon y lo pagado si entra`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, "0.00", "0.00", tacos, renglon("renglon-srv-agua", "prod-agua", "Agua", "20.00")))
        coordinator.removeItemFromLocalOrder(cuenta, "renglon-srv-agua")  // DELETED, en cola

        coordinator.cacheBackendOrder(delServidor(3, "0.00", "30.00", tacos, renglon("renglon-srv-agua", "prod-agua", "Agua", "20.00")))

        val todos = db.draftOrderItemDao().getAllItemsByOrder(cuenta)
        assertThat(todos.single { it.productId == "prod-agua" }.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_DELETED)
        val cuentaEnRoom = enLaPantalla()
        assertThat(cuentaEnRoom.items.map { it.productId }).containsExactly("prod-tacos")
        assertThat(cuentaEnRoom.total).isEqualToIgnoringScale("100.00")  // 120 del SERVIDOR − 20 del agua que se quita
        assertThat(cuentaEnRoom.paidAmount).isEqualToIgnoringScale("30.00")
        assertThat(cuentaEnRoom.remainingBalance).isEqualToIgnoringScale("70.00")
    }

    @Test fun `una lectura mas vieja que Room no toca nada`() = runTest {
        coordinator.cacheBackendOrder(delServidor(5, "10.00", "0.00", tacos))

        coordinator.cacheBackendOrder(delServidor(4, "0.00", "0.00"))  // vieja y sin renglones

        val cuentaEnRoom = enLaPantalla()
        assertThat(cuentaEnRoom.items.map { it.productId }).containsExactly("prod-tacos")
        assertThat(cuentaEnRoom.discountAmount).isEqualToIgnoringScale("10.00")
        assertThat(cuentaEnRoom.total).isEqualToIgnoringScale("90.00")
    }

    @Test fun `sin cola, Room queda igual al SERVIDOR como hoy`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, "0.00", "0.00", tacos, renglon("renglon-srv-agua", "prod-agua", "Agua", "20.00")))

        coordinator.cacheBackendOrder(delServidor(3, "15.00", "0.00", tacos))  // otra caja quitó el agua y puso $15

        val cuentaEnRoom = enLaPantalla()
        assertThat(cuentaEnRoom.items.map { it.productId }).containsExactly("prod-tacos")
        assertThat(cuentaEnRoom.discountAmount).isEqualToIgnoringScale("15.00")
        assertThat(cuentaEnRoom.total).isEqualToIgnoringScale("85.00")
        assertThat(db.draftOrderDao().getOrder(cuenta)!!.version).isEqualTo(3)
    }

    @Test fun `agregar un producto a una cuenta con descuento no pierde el descuento del total`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, "10.00", "0.00", tacos))  // total 90

        agregarCafe()

        val cuentaEnRoom = enLaPantalla()
        assertThat(cuentaEnRoom.subtotal).isEqualToIgnoringScale("150.00")
        assertThat(cuentaEnRoom.total).isEqualToIgnoringScale("140.00")  // antes: 150 (se perdía el descuento)
        assertThat(cuentaEnRoom.remainingBalance).isEqualToIgnoringScale("140.00")
    }
}
