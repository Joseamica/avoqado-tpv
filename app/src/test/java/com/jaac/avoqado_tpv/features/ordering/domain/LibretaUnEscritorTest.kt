package com.jaac.avoqado_tpv.features.ordering.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Libreta de Órdenes · fase 1 (encargo 95c69a): una lectura del SERVIDOR entra a Room por una sola puerta y, con renglones
 * sin subir, nadie reemplaza la cuenta ni borra esos renglones. Base real de Room (en memoria, con llaves foráneas y CASCADE)
 * y un SERVIDOR de mentiras. Promesa: docs/audit/2026-11-20-libreta-fase-1/promesa.md (workspace).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LibretaUnEscritorTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: OrderRepository
    private lateinit var coordinator: OrderSyncCoordinator

    private val venue = "venue-1"
    private val cuenta = "cuenta-srv-1"
    private val tacos = renglon("renglon-srv-tacos", "prod-tacos", "Tacos", "100.00")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = mockk(relaxed = true)
        coordinator = OrderSyncCoordinator(db.draftOrderDao(), db.draftOrderItemDao(), repo)
    }

    @After fun tearDown() { coordinator.cleanup(); db.close() }

    private fun renglon(id: String, productId: String, nombre: String, precio: String, externalId: String? = null) = OrderItem(
        id = id, orderId = cuenta, productId = productId, productName = nombre, productSku = null, quantity = 1,
        unitPrice = BigDecimal(precio), totalPrice = BigDecimal(precio), notes = null, kitchenStatus = KitchenStatus.PENDING,
        createdAt = Instant.parse("2026-11-20T12:00:00Z"), sentToKitchenAt = null, externalId = externalId,
    )

    /** La cuenta como la contesta el SERVIDOR: total = subtotal − descuento. */
    private fun delServidor(version: Int, vararg renglones: OrderItem, descuento: String = "0.00", pagado: String = "0.00"): Order {
        val subtotal = renglones.fold(BigDecimal.ZERO) { s, r -> s + r.totalPrice }
        val total = subtotal - BigDecimal(descuento)
        return Order(
            id = cuenta, orderNumber = "ORD-1", venueId = venue, tableId = null, tableName = null, covers = 1, waiterId = null,
            waiterName = null, status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING, paymentStatus = PaymentStatus.PENDING,
            orderType = OrderType.TAKEOUT, items = renglones.toList(), subtotal = subtotal, discountAmount = BigDecimal(descuento),
            tax = BigDecimal.ZERO, total = total, paidAmount = BigDecimal(pagado), remainingBalance = total - BigDecimal(pagado),
            notes = null, createdAt = Instant.parse("2026-11-20T12:00:00Z"), updatedAt = Instant.parse("2026-11-20T12:00:00Z"),
            version = version,
        )
    }

    private suspend fun agregar(producto: String, nombre: String, precio: String): String =
        coordinator.addItemToLocalOrder(cuenta, producto, nombre, 1, precio, emptyList(), null)

    private suspend fun renglones() = db.draftOrderItemDao().getAllItemsByOrderId(cuenta)
    private suspend fun cabecera() = db.draftOrderDao().getOrder(cuenta)!!
    private suspend fun loQueVeLaCajera() = coordinator.getLocalOrder(cuenta)!!

    @Test fun `T1 releer con un producto sin subir no lo borra y el total lo incluye`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        agregar("prod-cafe", "Café", "50.00")

        coordinator.cacheBackendOrder(delServidor(2, tacos)) // la lectura del SERVIDOR todavía no tiene el café

        val cafe = renglones().single { it.productId == "prod-cafe" }
        assertThat(cafe.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_PENDING)
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("150.00")
        assertThat(cabecera().syncStatus).isNotEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
    }

    @Test fun `T2 una lectura mas vieja que la libreta no la pisa`() = runTest {
        val cafe = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00")
        coordinator.cacheBackendOrder(delServidor(3, tacos, cafe))

        coordinator.cacheBackendOrder(delServidor(2, tacos)) // llegó tarde: es anterior al café

        assertThat(renglones().map { it.id }).containsExactly("renglon-srv-tacos", "renglon-srv-cafe")
        assertThat(cabecera().version).isEqualTo(3)
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("150.00")
    }

    @Test fun `T3 agregar con descuento conserva el descuento y una lectura cruzada no cuenta dos veces`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos, descuento = "10.00")) // 100 − 10 = 90
        val llave = agregar("prod-cafe", "Café", "50.00")
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("140.00")

        // La lectura llega con el café ya en el SERVIDOR mientras en Room sigue sin confirmarse.
        coordinator.cacheBackendOrder(
            delServidor(3, tacos, renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00", externalId = llave), descuento = "10.00")
        )
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("140.00")

        agregar("prod-agua", "Agua", "20.00")
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("160.00")
        assertThat(loQueVeLaCajera().subtotal).isEqualToIgnoringScale("170.00")
    }

    @Test fun `T4 un producto ya impreso sigue impreso tras subir y releer, y la marca con el id viejo no se pierde`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        val llaveCafe = agregar("prod-cafe", "Café", "50.00")
        coordinator.markItemsAsSentToKitchen(cuenta, listOf(llaveCafe), 1_000L)
        val llaveAgua = agregar("prod-agua", "Agua", "20.00")
        val cafeSrv = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00", externalId = llaveCafe)
        val aguaSrv = renglon("renglon-srv-agua", "prod-agua", "Agua", "20.00", externalId = llaveAgua)
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } returns Result.success(delServidor(3, tacos, cafeSrv, aguaSrv))
        coordinator.syncOrderImmediately(cuenta)

        coordinator.cacheBackendOrder(delServidor(3, tacos, cafeSrv, aguaSrv)) // relectura (cobro, descuento, apertura)
        // La impresora todavía tenía el id local del agua cuando la marcó.
        coordinator.markItemsAsSentToKitchen(cuenta, listOf(llaveAgua), 2_000L)

        val porProducto = loQueVeLaCajera().items.associateBy { it.productId }
        assertThat(porProducto.getValue("prod-cafe").sentToKitchenAt).isNotNull()
        assertThat(porProducto.getValue("prod-agua").sentToKitchenAt).isNotNull()
        assertThat(porProducto.getValue("prod-tacos").sentToKitchenAt).isNull()
    }

    @Test fun `T4b quitar con el id local despues de que la lectura puso el del SERVIDOR`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        val llave = agregar("prod-cafe", "Café", "50.00")
        val cafeSrv = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00", externalId = llave)
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } returns Result.success(delServidor(3, tacos, cafeSrv))
        coordinator.syncOrderImmediately(cuenta)
        coordinator.cacheBackendOrder(delServidor(3, tacos, cafeSrv))

        coordinator.removeItemFromLocalOrder(cuenta, llave) // la pantalla aún tenía el id local

        assertThat(loQueVeLaCajera().items.map { it.productId }).containsExactly("prod-tacos")
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("100.00")
    }

    @Test fun `T5 un 409 con un retiro en cola sube el retiro en la misma vuelta`() = runTest {
        val cafe = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00")
        val aguaDeOtro = renglon("renglon-srv-agua", "prod-agua", "Agua", "20.00")
        coordinator.cacheBackendOrder(delServidor(3, tacos, cafe))
        coordinator.removeItemFromLocalOrder(cuenta, "renglon-srv-cafe")
        // Otro aparato agregó un agua: la versión ya es 4.
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 3) } returns Result.failure(ConflictException("4"))
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(4, tacos, cafe, aguaDeOtro))
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 4) } returns Result.success(delServidor(5, tacos, aguaDeOtro))

        coordinator.syncOrderImmediately(cuenta) // lo que llama «Pagar» y «Anular»

        coVerify(exactly = 1) { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 4) }
        assertThat(renglones().map { it.productId }).doesNotContain("prod-cafe")
        assertThat(renglones().all { it.syncStatus == DraftOrderItemEntity.SYNC_STATUS_SYNCED }).isTrue()
        assertThat(cabecera().version).isEqualTo(5)
    }

    @Test fun `T6 un rechazo permanente no deja sin subir lo que se agrego durante la recuperacion`() = runTest {
        val cafe = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00")
        coordinator.cacheBackendOrder(delServidor(3, tacos, cafe))
        coordinator.removeItemFromLocalOrder(cuenta, "renglon-srv-cafe")
        // Otra caja ya lo había quitado: 404 permanente.
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", any()) } returns
            Result.failure(PermanentSyncException(404, "Ese producto ya no está en la cuenta"))
        var llaveAgua: String? = null
        coEvery { repo.getOrder(venue, cuenta) } coAnswers {
            if (llaveAgua == null) llaveAgua = agregar("prod-agua", "Agua", "20.00") // la cajera agrega mientras se relee
            Result.success(delServidor(4, tacos))
        }

        coordinator.syncOrderImmediately(cuenta)

        val agua = renglones().single { it.productId == "prod-agua" }
        assertThat(agua.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_PENDING)
        assertThat(renglones().none { it.productId == "prod-cafe" }).isTrue()
        assertThat(cabecera().syncStatus).isNotEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)

        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } returns
            Result.success(delServidor(5, tacos, renglon("renglon-srv-agua", "prod-agua", "Agua", "20.00", externalId = llaveAgua)))
        coordinator.syncOrderImmediately(cuenta)
        coVerify(exactly = 1) { repo.addItemsToOrder(venue, cuenta, match { it.single().externalId == llaveAgua }, any(), any()) }
    }

    @Test fun `T8 sin cola la libreta queda igual al SERVIDOR y conserva el comercio`() = runTest {
        val cafe = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00")
        coordinator.cacheBackendOrder(delServidor(3, tacos, cafe))
        db.draftOrderDao().update(cabecera().copy(merchantAccountId = "ma-1", merchantAccountName = "Cuenta 1"))

        coordinator.cacheBackendOrder(delServidor(4, tacos, pagado = "40.00")) // otro aparato quitó el café y cobró $40

        assertThat(renglones().map { it.id }).containsExactly("renglon-srv-tacos")
        assertThat(cabecera().syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
        assertThat(cabecera().merchantAccountId).isEqualTo("ma-1")
        assertThat(loQueVeLaCajera().remainingBalance).isEqualToIgnoringScale("60.00")
        assertThat(cabecera().version).isEqualTo(4)
    }
}
