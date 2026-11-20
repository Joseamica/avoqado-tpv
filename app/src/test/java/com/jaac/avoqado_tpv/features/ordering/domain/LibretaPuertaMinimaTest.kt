package com.jaac.avoqado_tpv.features.ordering.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntity
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
 * Libreta de Órdenes · fase 1 MÍNIMA (encargo 3a34fd): lo que llega del SERVIDOR entra a Room por una sola puerta; con renglones sin
 * subir no se borra ni se esconde nada, y una lectura más vieja no pisa la libreta. Base real de Room (en memoria, con llaves foráneas
 * y CASCADE) y un SERVIDOR de mentiras. Promesa: docs/audit/2026-11-20-libreta-fase-1-minima/promesa.md (workspace).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LibretaPuertaMinimaTest {
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

    @Test fun `M1 releer con un producto agregado sin red no lo borra y el total lo suma`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        agregar("prod-cafe", "Café", "50.00")

        coordinator.cacheBackendOrder(delServidor(2, tacos)) // la lectura todavía no tiene el café (hz:nube0000:nacec621)

        val cafe = renglones().single { it.productId == "prod-cafe" }
        assertThat(cafe.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_PENDING)
        assertThat(loQueVeLaCajera().items.map { it.productId }).containsExactly("prod-tacos", "prod-cafe")
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("150.00")
        assertThat(cabecera().syncStatus).isNotEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
    }

    @Test fun `M2 releer con un retiro en cola no revive lo que la cajera quito`() = runTest {
        val cafe = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00")
        coordinator.cacheBackendOrder(delServidor(2, tacos, cafe))
        coordinator.removeItemFromLocalOrder(cuenta, "renglon-srv-cafe") // sin red: el retiro queda en cola

        coordinator.cacheBackendOrder(delServidor(2, tacos, cafe)) // el SERVIDOR todavía lo tiene

        assertThat(loQueVeLaCajera().items.map { it.productId }).containsExactly("prod-tacos")
        assertThat(renglones().single { it.productId == "prod-cafe" }.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_DELETED)
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("100.00")
    }

    @Test fun `M3 una lectura mas vieja que la libreta no la pisa`() = runTest {
        val cafe = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00")
        coordinator.cacheBackendOrder(delServidor(3, tacos, cafe))

        coordinator.cacheBackendOrder(delServidor(2, tacos)) // llegó tarde: es anterior al café

        assertThat(renglones().map { it.id }).containsExactly("renglon-srv-tacos", "renglon-srv-cafe")
        assertThat(cabecera().version).isEqualTo(3)
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("150.00")
    }

    @Test fun `M4 un acuse atrasado no baja la version que ya trajo una lectura mas nueva`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        val llave = agregar("prod-cafe", "Café", "50.00")
        val cafeSrv = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00", externalId = llave)
        val aguaDeOtro = renglon("renglon-srv-agua", "prod-agua", "Agua", "20.00")
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } coAnswers {
            // Mientras el alta viaja, otra caja agrega un agua y la terminal relee (cobro, socket): entra la versión 4.
            coordinator.cacheBackendOrder(delServidor(4, tacos, cafeSrv, aguaDeOtro))
            Result.success(delServidor(3, tacos, cafeSrv)) // el acuse del alta es la versión 3
        }
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(4, tacos, cafeSrv, aguaDeOtro))

        coordinator.syncOrderImmediately(cuenta)

        assertThat(cabecera().version).isEqualTo(4)
        assertThat(loQueVeLaCajera().items.map { it.productId }).containsExactly("prod-tacos", "prod-cafe", "prod-agua")
    }

    @Test fun `M5 un 409 con un alta en cola no la borra, entra lo de la otra caja y se reintenta`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        agregar("prod-cafe", "Café", "50.00")
        val aguaDeOtro = renglon("renglon-srv-agua", "prod-agua", "Agua", "20.00")
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), 2, any()) } returns Result.failure(ConflictException("3"))
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(3, tacos, aguaDeOtro))

        coordinator.syncOrderImmediately(cuenta) // lo que llama «Pagar» y «Anular»

        val cafe = renglones().single { it.productId == "prod-cafe" }
        assertThat(cafe.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_PENDING)
        assertThat(loQueVeLaCajera().items.map { it.productId }).containsExactly("prod-tacos", "prod-cafe", "prod-agua")
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("170.00")
        assertThat(cabecera().version).isEqualTo(3)
        assertThat(cabecera().syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_PENDING)
    }

    @Test fun `M6 sin cola la libreta queda igual al SERVIDOR y la marca de cocina sigue por la llave`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        val llave = agregar("prod-cafe", "Café", "50.00")
        coordinator.markItemsAsSentToKitchen(cuenta, listOf(llave), 1_000L)
        val cafeSrv = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00", externalId = llave)
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } returns Result.success(delServidor(3, tacos, cafeSrv))
        coordinator.syncOrderImmediately(cuenta)
        db.draftOrderDao().update(cabecera().copy(merchantAccountId = "ma-1", merchantAccountName = "Cuenta 1"))

        // Otra caja quitó los tacos y cobró $10: la terminal relee.
        coordinator.cacheBackendOrder(delServidor(4, cafeSrv, pagado = "10.00"))

        val r = renglones().single()
        assertThat(r.id).isEqualTo("renglon-srv-cafe")
        assertThat(r.sentToKitchenAt).isEqualTo(1_000L) // no vuelve a salir en la comanda
        assertThat(cabecera().syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
        assertThat(cabecera().merchantAccountId).isEqualTo("ma-1")
        assertThat(loQueVeLaCajera().remainingBalance).isEqualToIgnoringScale("40.00")
        assertThat(cabecera().version).isEqualTo(4)
    }

    @Test fun `M7 un rechazo permanente deja la libreta igual al SERVIDOR sin retiros colgados`() = runTest {
        val cafe = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00")
        coordinator.cacheBackendOrder(delServidor(2, tacos, cafe))
        coordinator.removeItemFromLocalOrder(cuenta, "renglon-srv-cafe")
        agregar("prod-agua", "Agua", "20.00")
        // Otra caja ya cobró la cuenta: el SERVIDOR rechaza para siempre.
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } returns
            Result.failure(PermanentSyncException(400, "Esta cuenta ya está pagada"))
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(3, tacos, cafe, pagado = "150.00"))

        coordinator.syncOrderImmediately(cuenta)

        assertThat(renglones().map { it.id }).containsExactly("renglon-srv-tacos", "renglon-srv-cafe")
        assertThat(renglones().all { it.syncStatus == DraftOrderItemEntity.SYNC_STATUS_SYNCED }).isTrue()
        assertThat(cabecera().syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
        assertThat(cabecera().version).isEqualTo(3)
    }

    @Test fun `M8 un 409 que no trae version nueva se suelta como rechazo y no deja Pagar trabado`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        coordinator.removeItemFromLocalOrder(cuenta, "renglon-srv-tacos")
        // El SERVIDOR rechaza el retiro con 409 aunque la versión es la correcta (p. ej. GIFT_CARD_ORDER_IMMUTABLE).
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-tacos", any()) } returns Result.failure(ConflictException("unknown"))
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(2, tacos))

        coordinator.syncOrderImmediately(cuenta)

        assertThat(renglones().map { it.id to it.syncStatus }).containsExactly("renglon-srv-tacos" to DraftOrderItemEntity.SYNC_STATUS_SYNCED)
        assertThat(cabecera().syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
        assertThat(coordinator.hayRenglonesSinSubir(cuenta)).isFalse()
    }

    @Test fun `M9 un mas sobre un cafe ya subido que otra caja quito no lo vuelve a dar de alta`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        val llave = agregar("prod-cafe", "Café", "50.00")
        val cafeSrv = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00", externalId = llave)
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), 2, any()) } returns Result.success(delServidor(3, tacos, cafeSrv))
        coordinator.syncOrderImmediately(cuenta)
        coordinator.updateItemQuantityInLocalOrder(cuenta, llave, 2, BigDecimal("50.00")) // «+» sin red
        // Otra caja quitó el café (versión 4): el «+» choca y la relectura ya no lo trae.
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), 3, any()) } returns Result.failure(ConflictException("4"))
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(4, tacos))

        coordinator.syncOrderImmediately(cuenta)

        assertThat(renglones().map { it.productId }).containsExactly("prod-tacos")
        assertThat(coordinator.hayRenglonesSinSubir(cuenta)).isFalse()
    }

    @Test fun `M10 un acuse que llega despues de una lectura mas nueva no deja visible lo que otra caja ya quito`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        val llave = agregar("prod-cafe", "Café", "50.00")
        val cafeSrv = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00", externalId = llave)
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } coAnswers {
            // El alta entra en la 3 y otra caja quita el café en la 4; la terminal lee la 4 antes de recibir el acuse.
            coordinator.cacheBackendOrder(delServidor(4, tacos))
            Result.success(delServidor(3, tacos, cafeSrv))
        }
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(4, tacos))

        coordinator.syncOrderImmediately(cuenta)

        assertThat(renglones().map { it.productId }).containsExactly("prod-tacos")
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("100.00")
        assertThat(cabecera().version).isEqualTo(4)
    }

    @Test fun `M11 un retiro que otra caja ya hizo se da por cumplido y no manda otro DELETE`() = runTest {
        val cafe = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00")
        coordinator.cacheBackendOrder(delServidor(2, tacos, cafe))
        coordinator.removeItemFromLocalOrder(cuenta, "renglon-srv-cafe")
        // B quitó el mismo café antes (versión 3): el DELETE de A choca y la relectura ya no lo trae.
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 2) } returns Result.failure(ConflictException("3"))
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(3, tacos))

        coordinator.syncOrderImmediately(cuenta)
        coordinator.syncOrderImmediately(cuenta)

        coVerify(exactly = 1) { repo.removeOrderItem(any(), any(), any(), any()) }
        assertThat(renglones().map { it.id }).containsExactly("renglon-srv-tacos")
        assertThat(coordinator.hayRenglonesSinSubir(cuenta)).isFalse()
    }

    @Test fun `M12 un mas sobre un cafe que confirmo la version anterior de la app y que otra caja quito no lo revive`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        // Lo que dejó la versión anterior: el alta confirmada con su id local y sin la marca de aceptado.
        val llave = DraftOrderItemEntity.generateLocalId()
        db.draftOrderItemDao().insert(renglon(llave, "prod-cafe", "Café", "50.00", externalId = llave).toEntity(DraftOrderItemEntity.SYNC_STATUS_SYNCED, false))
        coordinator.updateItemQuantityInLocalOrder(cuenta, llave, 2, BigDecimal("50.00")) // «+» sin red
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), 2, any()) } returns Result.failure(ConflictException("3"))
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(3, tacos)) // otra caja lo quitó

        coordinator.syncOrderImmediately(cuenta)

        assertThat(renglones().map { it.productId }).containsExactly("prod-tacos")
        coVerify(exactly = 1) { repo.addItemsToOrder(any(), any(), any(), any(), any()) }
    }

    @Test fun `M13 lo que se agrega mientras se recupera un rechazo no se borra aunque la relectura falle`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        agregar("prod-cafe", "Café", "50.00")
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } returns
            Result.failure(PermanentSyncException(400, "Ese producto ya no existe"))
        coEvery { repo.getOrder(venue, cuenta) } coAnswers {
            agregar("prod-pan", "Pan", "20.00") // la cajera agrega un pan mientras se relee
            Result.failure(Exception("sin red"))
        }

        coordinator.syncOrderImmediately(cuenta)

        assertThat(renglones().map { it.productId }).containsExactly("prod-tacos", "prod-pan")
        assertThat(renglones().single { it.productId == "prod-pan" }.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_PENDING)
        assertThat(cabecera().syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_PENDING)
    }

    @Test fun `M14 si falla la relectura tras un acuse atrasado la cuenta queda pendiente y la siguiente vuelta relee`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        val llave = agregar("prod-cafe", "Café", "50.00")
        val cafeSrv = renglon("renglon-srv-cafe", "prod-cafe", "Café", "50.00", externalId = llave)
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } coAnswers {
            coordinator.cacheBackendOrder(delServidor(4, tacos)) // otra caja ya lo quitó
            Result.success(delServidor(3, tacos, cafeSrv))
        }
        coEvery { repo.getOrder(venue, cuenta) } returnsMany listOf(Result.failure(Exception("sin red")), Result.success(delServidor(4, tacos)))

        coordinator.syncOrderImmediately(cuenta)
        assertThat(cabecera().syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_PENDING)

        coordinator.syncOrderImmediately(cuenta) // la reconexión o el siguiente cambio

        assertThat(renglones().map { it.productId }).containsExactly("prod-tacos")
        assertThat(cabecera().syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
    }

    @Test fun `M15 un 409 que no es de version usa su propia relectura aunque otra falle`() = runTest {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        coordinator.removeItemFromLocalOrder(cuenta, "renglon-srv-tacos")
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-tacos", any()) } returns Result.failure(ConflictException("unknown"))
        coEvery { repo.getOrder(venue, cuenta) } returnsMany listOf(Result.success(delServidor(2, tacos)), Result.failure(Exception("sin red")))

        coordinator.syncOrderImmediately(cuenta)

        assertThat(renglones().map { it.id to it.syncStatus }).containsExactly("renglon-srv-tacos" to DraftOrderItemEntity.SYNC_STATUS_SYNCED)
        assertThat(cabecera().syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
    }
}

