package com.jaac.avoqado_tpv.features.ordering.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
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
 * D23: en la cuenta de «Órdenes», un artículo agregado en el aparato y luego quitado también se quita del SERVIDOR.
 * Base real de Room (en memoria) y un SERVIDOR de mentiras que guarda la llave (`externalId`) de cada renglón.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class QuitarRecienAgregadoTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: OrderRepository
    private lateinit var coordinator: OrderSyncCoordinator

    private val venue = "venue-1"
    private val cuenta = "cuenta-srv-1"
    private val tacos = renglon(id = "renglon-srv-tacos", productId = "prod-tacos", nombre = "Tacos", precio = "25.00")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = mockk(relaxed = true)
        coordinator = OrderSyncCoordinator(db.draftOrderDao(), db.draftOrderItemDao(), repo)
    }

    @After fun tearDown() { coordinator.cleanup(); db.close() }

    private fun renglon(
        id: String, productId: String, nombre: String, precio: String, externalId: String? = null, notas: String? = null,
    ) = OrderItem(
        id = id, orderId = cuenta, productId = productId, productName = nombre, productSku = null, quantity = 1,
        unitPrice = BigDecimal(precio), totalPrice = BigDecimal(precio), notes = notas, kitchenStatus = KitchenStatus.PENDING,
        createdAt = Instant.parse("2026-11-10T12:00:00Z"), sentToKitchenAt = null, externalId = externalId,
    )

    private fun cafeDelServidor(llave: String) = renglon("renglon-srv-cafe", "prod-cafe", "Café", "85.00", externalId = llave)

    private fun cuentaDelServidor(version: Int, vararg renglones: OrderItem): Order {
        val total = renglones.fold(BigDecimal.ZERO) { suma, r -> suma + r.totalPrice }
        return Order(
            id = cuenta, orderNumber = "ORD-1", venueId = venue, tableId = null, tableName = null, covers = 1, waiterId = null,
            waiterName = null, status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING, paymentStatus = PaymentStatus.PENDING,
            orderType = OrderType.TAKEOUT, items = renglones.toList(), subtotal = total, tax = BigDecimal.ZERO, total = total,
            remainingBalance = total, notes = null, createdAt = Instant.parse("2026-11-10T12:00:00Z"),
            updatedAt = Instant.parse("2026-11-10T12:00:00Z"), version = version,
        )
    }

    /** La cuenta ya existe en el SERVIDOR con unos tacos de $25 (como cuando se abre desde la lista). */
    private suspend fun abrirCuentaConTacos() = coordinator.cacheBackendOrder(cuentaDelServidor(2, tacos))

    private suspend fun agregarCafe(): String =
        coordinator.addItemToLocalOrder(cuenta, "prod-cafe", "Café", 1, "85.00", emptyList(), null)

    /** El SERVIDOR guarda el alta con la llave que mandó la TPV y le da su propio id. */
    private fun servidorGuardaElAlta(llave: String, version: Int = 3) {
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } returns
            Result.success(cuentaDelServidor(version, tacos, cafeDelServidor(llave)))
    }

    /** Lo que contesta el SERVIDOR si la TPV relee la cuenta: los tacos y, si se da, el café con su llave. */
    private fun alReleerLaCuenta(cafeConLlave: String?, version: Int = 3) {
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(
            if (cafeConLlave == null) cuentaDelServidor(version, tacos) else cuentaDelServidor(version, tacos, cafeDelServidor(cafeConLlave))
        )
    }

    private fun servidorQuitaElCafe() {
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", any()) } answers
            { Result.success(cuentaDelServidor(arg<Int>(3) + 1, tacos)) }
    }

    private suspend fun renglonesEnElAparato() = db.draftOrderItemDao().getAllItemsByOrderId(cuenta)
    private suspend fun loQueVeLaCajera() = coordinator.getLocalOrder(cuenta)!!

    /** Lo que la pantalla ve y desliza: el renglón del café, con el id que tenga en ese momento. */
    private suspend fun quitarCafeDesdeLaPantalla() {
        val cafe = loQueVeLaCajera().items.single { it.productId == "prod-cafe" }
        coordinator.removeItemFromLocalOrder(cuenta, cafe.id)
    }

    /** Un café cuya alta ya salió sin saberse si llegó (o que la versión de hoy dejó así). */
    private suspend fun sembrarCafe(syncStatus: String, isServerCreated: Boolean): String {
        val llave = DraftOrderItemEntity.generateLocalId()
        db.draftOrderItemDao().insert(renglon(llave, "prod-cafe", "Café", "85.00").toEntity(syncStatus, isServerCreated))
        return llave
    }

    @Test fun `T1 agregar, sincronizar y quitar manda el DELETE con el id del SERVIDOR`() = runTest {
        abrirCuentaConTacos()
        val llave = agregarCafe()
        servidorGuardaElAlta(llave)
        alReleerLaCuenta(cafeConLlave = llave)
        servidorQuitaElCafe()

        coordinator.syncOrderImmediately(cuenta)
        quitarCafeDesdeLaPantalla()
        coordinator.syncOrderImmediately(cuenta)

        coVerify(exactly = 1) { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 3) }
        coVerify(exactly = 1) { repo.getOrder(venue, cuenta) } // una sola lectura para saber su id allá
        assertThat(renglonesEnElAparato().map { it.id }).containsExactly("renglon-srv-tacos")
        assertThat(loQueVeLaCajera().total).isEqualToIgnoringScale("25.00")
        assertThat(db.draftOrderDao().getOrder(cuenta)!!.syncStatus).isEqualTo("SYNCED")
    }

    @Test fun `T2 quitar sin red queda en el aparato y sale al volver la red`() = runTest {
        abrirCuentaConTacos()
        val llave = agregarCafe()
        servidorGuardaElAlta(llave)
        alReleerLaCuenta(cafeConLlave = llave)
        servidorQuitaElCafe()
        coordinator.syncOrderImmediately(cuenta)

        // Sin red: el retiro se guarda en Room antes de tocar la red; la cola no corre.
        quitarCafeDesdeLaPantalla()
        assertThat(renglonesEnElAparato().single { it.productId == "prod-cafe" }.syncStatus)
            .isEqualTo(DraftOrderItemEntity.SYNC_STATUS_DELETED)
        coVerify(exactly = 0) { repo.removeOrderItem(any(), any(), any(), any()) }

        // La app se reinicia (otro coordinador sobre la misma base) y vuelve la red.
        OrderSyncCoordinator(db.draftOrderDao(), db.draftOrderItemDao(), repo).syncPendingOrders(venue)

        coVerify(exactly = 1) { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 3) }
        assertThat(renglonesEnElAparato().map { it.id }).containsExactly("renglon-srv-tacos")
    }

    @Test fun `T3 quitar un renglon cuya alta nunca salio no manda ni el alta ni un DELETE`() = runTest {
        abrirCuentaConTacos()
        agregarCafe()
        quitarCafeDesdeLaPantalla()
        alReleerLaCuenta(cafeConLlave = null)

        coordinator.syncOrderImmediately(cuenta)

        coVerify(exactly = 0) { repo.addItemsToOrder(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repo.removeOrderItem(any(), any(), any(), any()) }
        assertThat(renglonesEnElAparato().map { it.id }).containsExactly("renglon-srv-tacos")
    }

    @Test fun `T4 quitar mientras el alta va en camino no lo revive y lo quita del SERVIDOR`() = runTest {
        abrirCuentaConTacos()
        val llave = agregarCafe()
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } coAnswers {
            coordinator.removeItemFromLocalOrder(cuenta, llave) // la cajera desliza mientras el POST va en camino
            Result.success(cuentaDelServidor(3, tacos, cafeDelServidor(llave)))
        }
        servidorQuitaElCafe()

        coordinator.syncOrderImmediately(cuenta)

        coVerify(exactly = 1) { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 3) }
        coVerify(exactly = 0) { repo.getOrder(any(), any()) } // la respuesta del alta ya trae su llave
        assertThat(renglonesEnElAparato().map { it.id }).containsExactly("renglon-srv-tacos")
        assertThat(loQueVeLaCajera().items.map { it.productId }).containsExactly("prod-tacos")
    }

    @Test fun `T5 un renglon que dejo la version de hoy (confirmado, con id local) se busca por su llave y se quita`() = runTest {
        abrirCuentaConTacos()
        val llave = sembrarCafe(DraftOrderItemEntity.SYNC_STATUS_SYNCED, isServerCreated = false)
        db.draftOrderDao().updateVersion(cuenta, 3)
        alReleerLaCuenta(cafeConLlave = llave)
        servidorQuitaElCafe()

        quitarCafeDesdeLaPantalla()
        coordinator.syncOrderImmediately(cuenta)

        coVerify(exactly = 0) { repo.addItemsToOrder(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 3) }
        assertThat(renglonesEnElAparato().map { it.id }).containsExactly("renglon-srv-tacos")
    }

    @Test fun `T6 si el SERVIDOR rechaza el DELETE porque otra terminal cobro, la cola lo suelta sin reintentar`() = runTest {
        abrirCuentaConTacos()
        val llave = agregarCafe()
        servidorGuardaElAlta(llave)
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", any()) } returns
            Result.failure(PermanentSyncException(httpCode = 400, message = "Esta cuenta ya está pagada"))
        alReleerLaCuenta(cafeConLlave = llave)
        coordinator.syncOrderImmediately(cuenta)

        quitarCafeDesdeLaPantalla()
        coordinator.syncOrderImmediately(cuenta)

        coVerify(exactly = 1) { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 3) }
        assertThat(db.draftOrderDao().getOrder(cuenta)!!.syncStatus).isEqualTo("SYNCED")
    }

    @Test fun `T7 respuesta perdida - lo encuentra por su llave, el 409 recarga la cuenta, vuelve a verse y al quitarlo otra vez sale`() = runTest {
        abrirCuentaConTacos() // la TPV cree que va en la versión 2; el alta perdida ya subió al SERVIDOR a la 3
        val llave = sembrarCafe(DraftOrderItemEntity.SYNC_STATUS_PENDING, isServerCreated = false)
        alReleerLaCuenta(cafeConLlave = llave)
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 2) } returns
            Result.failure(ConflictException(serverVersion = "unknown", message = "La orden fue modificada por otra terminal."))
        coEvery { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 3) } returns Result.success(cuentaDelServidor(4, tacos))

        quitarCafeDesdeLaPantalla()
        coordinator.syncOrderImmediately(cuenta)

        // Como hoy con cualquier artículo tras un 409: la cuenta se recarga del SERVIDOR y el café vuelve a verse.
        assertThat(loQueVeLaCajera().items.map { it.id }).contains("renglon-srv-cafe")

        quitarCafeDesdeLaPantalla()
        coordinator.syncOrderImmediately(cuenta)

        coVerify(exactly = 1) { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 3) }
        assertThat(renglonesEnElAparato().map { it.id }).containsExactly("renglon-srv-tacos")
    }

    @Test fun `T8 si el SERVIDOR no tiene el renglon, no se manda un DELETE de algo que no tiene`() = runTest {
        abrirCuentaConTacos()
        sembrarCafe(DraftOrderItemEntity.SYNC_STATUS_SYNCED, isServerCreated = false)
        alReleerLaCuenta(cafeConLlave = null) // otro aparato ya lo había quitado: no está

        quitarCafeDesdeLaPantalla()
        coordinator.syncOrderImmediately(cuenta)

        coVerify(exactly = 0) { repo.removeOrderItem(any(), any(), any(), any()) }
        assertThat(renglonesEnElAparato().map { it.id }).containsExactly("renglon-srv-tacos")
    }

    @Test fun `T9 un renglon de la version de hoy al que se le sumo otra unidad y se quito antes de mandar tambien sale`() = runTest {
        abrirCuentaConTacos()
        val llave = sembrarCafe(DraftOrderItemEntity.SYNC_STATUS_SYNCED, isServerCreated = false)
        agregarCafe() // se fusiona: el mismo renglón pasa a 2 unidades y queda pendiente
        assertThat(renglonesEnElAparato().single { it.productId == "prod-cafe" }.quantity).isEqualTo(2)
        alReleerLaCuenta(cafeConLlave = llave, version = 2)
        servidorQuitaElCafe()

        quitarCafeDesdeLaPantalla()
        coordinator.syncOrderImmediately(cuenta)

        coVerify(exactly = 0) { repo.addItemsToOrder(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 2) }
        assertThat(renglonesEnElAparato().map { it.id }).containsExactly("renglon-srv-tacos")
    }

    @Test fun `T10 un retiro guardado en una cuenta que quedo SYNCED (la app murio a media operacion) sale con el siguiente cambio`() = runTest {
        abrirCuentaConTacos()
        val llave = agregarCafe()
        servidorGuardaElAlta(llave)
        alReleerLaCuenta(cafeConLlave = llave)
        servidorQuitaElCafe()
        coordinator.syncOrderImmediately(cuenta)
        // Lo que queda si la app muere entre guardar el retiro y recalcular (residuo declarado: la cola de reconexión no lo ve).
        db.draftOrderItemDao().markAsDeleted(llave)

        // La app se reinicia; la cajera hace otro cambio en esa cuenta y la cola corre: el retiro sale con él.
        val otro = OrderSyncCoordinator(db.draftOrderDao(), db.draftOrderItemDao(), repo)
        otro.updateItemQuantityInLocalOrder(cuenta, "renglon-srv-tacos", 2, BigDecimal("25.00"))
        otro.syncOrderImmediately(cuenta)
        otro.cleanup()

        coVerify(exactly = 1) { repo.removeOrderItem(venue, cuenta, "renglon-srv-cafe", 3) }
        assertThat(renglonesEnElAparato().map { it.id }).containsExactly("renglon-srv-tacos")
    }
}
